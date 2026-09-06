package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the rule that `Confirmed` means the room was STORED, and what the BLE commit gate
 * does and does not cover.
 *
 * `detectDetailed` used to return `Confirmed` even when the caller had passed
 * `commitRoomChange = false`. Nothing was written, so every later read looked like a change
 * again, and three call sites acted on the answer: the player switched, "Room confirmed"
 * logged every 20 to 40 s, and the scan never settled. A passing BLE read that may not commit
 * now comes back as `Borderline("commit-withheld")`.
 *
 * The gate exists because a half-filled BLE buffer is not yet a picture. The Wi-Fi override
 * does not depend on that buffer, so it commits on its own evidence: gating it too left
 * Wi-Fi-only rooms unconfirmed on every read whose BLE side was cold or too small.
 */
class RoomDetectorCommitContractTest {

    private fun profile(key: String, mean: Int): BeaconProfile = BeaconProfile(
        address = key,
        name = key,
        meanRssi = mean,
        variance = 4.0,
        visibilityRate = 1.0,
        discriminationScore = 1.0,
        weight = 1.0,
        anchorKey = key,
        anchorType = AnchorType.MAC
    )

    private val bathroomMeans = mapOf("washer" to -40, "mirror" to -55, "scale" to -60, "hub" to -70)

    private val bathroom = RoomConfig(
        id = "bath",
        name = "Bathroom",
        playerId = "p-bath",
        playerName = "Bath speaker",
        fingerprints = (0 until 5).map { i ->
            val jitter = (i % 3) - 1
            RoomFingerprint(id = "bath-fp$i", label = "s$i", samples = bathroomMeans.mapValues { it.value + jitter }, capturedAtMs = 0L)
        },
        beaconProfiles = bathroomMeans.map { (key, mean) -> profile(key, mean) },
        calibrationQuality = CalibrationQuality.GOOD,
        detectionPolicy = DetectionPolicy.STRICT
    )

    private val living = RoomConfig(
        id = "living",
        name = "Living Room",
        playerId = "p1",
        playerName = "LyraT",
        connectedSsid = "home",
        wifiMatchMode = WifiMatchMode.SSID
    )

    private val bleConfig = ProximityConfig(enabled = true, rooms = listOf(bathroom))
    private val wifiConfig = ProximityConfig(enabled = true, rooms = listOf(living))
    private val onHomeWifi = RoomDetector.WifiMatchContext(ssid = "home")

    /**
     * A STRICT room at startup needs two consecutive wins before its evidence passes; the first
     * read is always `Borderline("needs-2-wins")`. This spends that read so the read under test
     * is the one whose commit is being decided.
     */
    private fun RoomDetector.firstWin() {
        val first = detectDetailed(bathroomMeans, bleConfig, motionActive = false, commitRoomChange = false)
        assertThat((first as DetectResult.Borderline).reason).isEqualTo("needs-2-wins")
    }

    @Test
    fun `a passing BLE read that may not commit is withheld, and nothing is stored`() {
        val detector = RoomDetector().apply { firstWin() }

        val result = detector.detectDetailed(bathroomMeans, bleConfig, motionActive = false, commitRoomChange = false)

        assertThat(result).isInstanceOf(DetectResult.Borderline::class.java)
        assertThat((result as DetectResult.Borderline).reason).isEqualTo("commit-withheld")
        assertThat(detector.currentRoom.value).isNull()
    }

    @Test
    fun `a passing BLE read that may commit is confirmed exactly once`() {
        val detector = RoomDetector().apply { firstWin() }

        val first = detector.detectDetailed(bathroomMeans, bleConfig, motionActive = false)
        val second = detector.detectDetailed(bathroomMeans, bleConfig, motionActive = false)

        assertThat(first).isInstanceOf(DetectResult.Confirmed::class.java)
        assertThat(detector.currentRoom.value?.roomId).isEqualTo("bath")
        // The same room again is not a change, and must not re-fire the confirmation.
        assertThat(second).isEqualTo(DetectResult.NoDecision)
    }

    @Test
    fun `a withheld BLE read still records what it saw, so settling can be judged on it`() {
        val detector = RoomDetector().apply { firstWin() }

        detector.detectDetailed(bathroomMeans, bleConfig, motionActive = false, commitRoomChange = false)

        assertThat(detector.lastDetection.value?.roomId).isEqualTo("bath")
        assertThat(detector.lastDetectionAtMs).isGreaterThan(0L)
        assertThat(detector.currentRoom.value).isNull()
    }

    @Test
    fun `a Wi-Fi match is stored even when the BLE commit is withheld`() {
        val detector = RoomDetector()

        val result = detector.detectDetailed(emptyMap(), wifiConfig, motionActive = false, wifi = onHomeWifi, commitRoomChange = false)

        assertThat(result).isInstanceOf(DetectResult.Confirmed::class.java)
        assertThat(detector.currentRoom.value?.roomId).isEqualTo("living")
    }

    @Test
    fun `a Wi-Fi-only evaluation confirms the room and then holds it quietly`() {
        val detector = RoomDetector()

        val first = detector.detectWifiOnly(wifiConfig, onHomeWifi)
        val second = detector.detectWifiOnly(wifiConfig, onHomeWifi)

        assertThat(first).isInstanceOf(DetectResult.Confirmed::class.java)
        assertThat(detector.currentRoom.value?.roomId).isEqualTo("living")
        assertThat(second).isEqualTo(DetectResult.NoDecision)
    }

    @Test
    fun `re-confirming the held Wi-Fi room refreshes the confirmation stamp`() {
        val detector = RoomDetector()
        detector.detectWifiOnly(wifiConfig, onHomeWifi)
        val stampAfterFirst = detector.lastConfirmedAtMs
        detector.lastConfirmedAtMs = stampAfterFirst - 60_000L // pretend a minute passed

        detector.detectWifiOnly(wifiConfig, onHomeWifi)

        // This stamp is the grace that stops a passing no-coverage read from clearing a room
        // you are sitting in; losing the refresh on re-confirmation re-armed "left all rooms".
        assertThat(detector.lastConfirmedAtMs).isAtLeast(stampAfterFirst)
    }
}
