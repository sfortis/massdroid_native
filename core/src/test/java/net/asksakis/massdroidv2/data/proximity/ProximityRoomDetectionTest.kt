package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavioural tests for the room classifier ([VectorRoomScorer]) and the joint separability quality
 * ([RoomSeparability]). Fixtures are SYNTHETIC but mirror the real-home overlap that motivated the
 * rework: a set of whole-house anchors (jbl/shield/lg) visible everywhere at DIFFERENT relative
 * strength per room, plus room-unique anchors (watch in the bedroom, govee in the mancave). No real
 * MAC/BSSID data is committed.
 *
 * Key relative patterns:
 *   - Living: jbl LOUDER than shield (jbl -58 > shield -68)
 *   - Kitchen: shield LOUDER than jbl (shield -61 > jbl -67)
 * The absolute levels overlap; only the pattern (and the unique anchors) separates the rooms - which
 * is exactly what per-scan mean-centering preserves under global attenuation.
 */
class ProximityRoomDetectionTest {

    private fun profile(key: String, mean: Int, weight: Double, disc: Double): BeaconProfile =
        BeaconProfile(
            address = key,
            name = key,
            meanRssi = mean,
            variance = 4.0,
            visibilityRate = 1.0,
            discriminationScore = disc,
            weight = weight,
            anchorKey = key,
            anchorType = AnchorType.MAC
        )

    private fun room(id: String, means: Map<String, Int>, profiles: List<BeaconProfile>): RoomConfig {
        // Five fingerprints with small deterministic jitter so k-NN has spread and leave-one-out works.
        val fingerprints = (0 until 5).map { i ->
            val jitter = (i % 3) - 1 // -1, 0, 1, -1, 0
            RoomFingerprint(
                id = "$id-fp$i",
                label = "s$i",
                samples = means.mapValues { it.value + jitter },
                capturedAtMs = 0L
            )
        }
        return RoomConfig(
            id = id,
            name = id,
            playerId = "p-$id",
            playerName = "P $id",
            fingerprints = fingerprints,
            beaconProfiles = profiles,
            calibrationQuality = CalibrationQuality.GOOD,
            detectionPolicy = DetectionPolicy.STRICT
        )
    }

    private val living = room(
        "living",
        mapOf("jbl" to -58, "shield" to -68, "lg" to -86),
        listOf(
            profile("jbl", -58, weight = 0.9, disc = 3.0),
            profile("shield", -68, weight = 0.5, disc = 0.0),
            profile("lg", -86, weight = 0.4, disc = -4.0)
        )
    )

    private val kitchen = room(
        "kitchen",
        mapOf("jbl" to -67, "shield" to -61, "lg" to -82),
        listOf(
            profile("shield", -61, weight = 0.8, disc = 6.0),
            profile("jbl", -67, weight = 0.7, disc = -9.0),
            profile("lg", -82, weight = 0.5, disc = 3.0)
        )
    )

    private val bedroom = room(
        "bedroom",
        mapOf("jbl" to -82, "shield" to -85, "lg" to -90, "watch" to -66),
        listOf(
            profile("watch", -66, weight = 1.4, disc = 21.0),
            profile("jbl", -82, weight = 0.4, disc = -20.0),
            profile("shield", -85, weight = 0.3, disc = -18.0)
        )
    )

    private val mancave = room(
        "mancave",
        mapOf("govee" to -31, "jbl" to -94, "mjht" to -82),
        listOf(
            profile("govee", -31, weight = 2.5, disc = 49.0),
            profile("mjht", -82, weight = 0.4, disc = -12.0),
            profile("jbl", -94, weight = 0.25, disc = -31.0)
        )
    )

    private val allRooms = listOf(living, kitchen, bedroom, mancave)

    private fun classify(scan: Map<String, Int>): String =
        allRooms.maxByOrNull { r ->
            val primary = rankBeaconProfilesForDetection(r.beaconProfiles).take(8).map { it.anchorKey }.toSet()
            VectorRoomScorer.score(r.id, scan, r.fingerprints, r.beaconProfiles, primary).score
        }!!.id

    @Test
    fun `living room scan classifies to living, not kitchen`() {
        // Standing by the JBL: jbl loud, shield mid. The Living relative pattern.
        assertThat(classify(mapOf("jbl" to -56, "shield" to -69, "lg" to -85))).isEqualTo("living")
    }

    @Test
    fun `kitchen scan classifies to kitchen, not living`() {
        // shield louder than jbl = the Kitchen pattern, even though both rooms see both anchors.
        assertThat(classify(mapOf("jbl" to -69, "shield" to -60, "lg" to -83))).isEqualTo("kitchen")
    }

    @Test
    fun `globally attenuated living scan still classifies to living (centering invariance)`() {
        // Phone in a pocket: every co-observed anchor drops ~15 dB uniformly. The relative pattern
        // (jbl louder than shield) is intact, so mean-centering must keep this in Living.
        assertThat(classify(mapOf("jbl" to -73, "shield" to -83))).isEqualTo("living")
    }

    @Test
    fun `mancave with one strong unique anchor classifies to mancave`() {
        assertThat(classify(mapOf("govee" to -33, "jbl" to -92))).isEqualTo("mancave")
    }

    @Test
    fun `separability marks the unique-anchor room GOOD`() {
        val report = RoomSeparability.analyze(allRooms)
        // Mancave (unique strong Govee) and Bedroom (unique Watch) are cleanly separable.
        assertThat(report.getValue("mancave").quality).isEqualTo(CalibrationQuality.GOOD)
        assertThat(report.getValue("mancave").selfRecognition).isAtLeast(0.8)
        assertThat(report.getValue("bedroom").quality).isEqualTo(CalibrationQuality.GOOD)
    }

    @Test
    fun `separability reports kitchen and living as mutual confusers when overlapping`() {
        // Build a deliberately overlapping pair: same anchors AND the same means -> indistinguishable,
        // so each room's fingerprints classify into the other as often as itself.
        val means = mapOf("jbl" to -60, "shield" to -64, "lg" to -84)
        val profiles = listOf(profile("jbl", -60, 0.7, 1.0), profile("shield", -64, 0.7, 1.0), profile("lg", -84, 0.4, -2.0))
        val a = room("a", means, profiles)
        val b = room("b", means, profiles)
        val report = RoomSeparability.analyze(listOf(a, b))
        // At least one of the overlapping pair must be flagged WEAK and name the other as confuser.
        val weakOnes = report.values.filter { it.quality == CalibrationQuality.WEAK }
        assertThat(weakOnes).isNotEmpty()
        assertThat(weakOnes.any { it.topConfuserId in setOf("a", "b") }).isTrue()
    }
}
