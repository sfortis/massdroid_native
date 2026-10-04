package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks which anchors the persistent scan filters on. The set has to fit the controller's
 * hardware filter slots, so faint anchors at the calibration floor are dropped, but never in a
 * way that leaves a room with nothing to hear.
 */
class ScanAnchorsTest {

    private fun mac(address: String, weight: Double) = BeaconProfile(
        address = address,
        name = "",
        meanRssi = -80,
        variance = 1.0,
        visibilityRate = 1.0,
        discriminationScore = 1.0,
        weight = weight
    )

    private fun name(name: String, weight: Double) = mac("name:$name", weight)
        .copy(name = name, anchorKey = "name:$name", anchorType = AnchorType.NAME)

    private fun room(id: String, profiles: List<BeaconProfile>, wifiMode: WifiMatchMode? = null) = RoomConfig(
        id = id,
        name = "Room $id",
        playerId = "p-$id",
        playerName = "Player $id",
        beaconProfiles = profiles,
        wifiMatchMode = wifiMode
    )

    private fun anchors(vararg rooms: RoomConfig) = ScanAnchors.from(ProximityConfig(enabled = true, rooms = rooms.toList()))

    @Test
    fun `an anchor at the floor in every room is left out`() {
        val result = anchors(
            room("a", listOf(mac("AA", 1.2), mac("FAINT", BEACON_WEIGHT_FLOOR))),
            room("b", listOf(mac("BB", 0.8), mac("FAINT", BEACON_WEIGHT_FLOOR)))
        )

        assertThat(result.macs).containsExactly("AA", "BB")
    }

    @Test
    fun `an anchor above the floor in any room is kept for every room`() {
        val result = anchors(
            room("a", listOf(mac("AA", 1.2), mac("SHARED", BEACON_WEIGHT_FLOOR))),
            room("b", listOf(mac("BB", 0.8), mac("SHARED", 0.6)))
        )

        assertThat(result.macs).containsExactly("AA", "BB", "SHARED")
    }

    @Test
    fun `a room with only floor anchors keeps all of them`() {
        val result = anchors(
            room("a", listOf(mac("AA", 1.2))),
            room("weak", listOf(mac("W1", BEACON_WEIGHT_FLOOR), mac("W2", BEACON_WEIGHT_FLOOR)))
        )

        assertThat(result.macs).containsExactly("AA", "W1", "W2")
    }

    @Test
    fun `name anchors follow the same rule and stay names`() {
        val result = anchors(
            room("a", listOf(name("SHIELD", 0.4), name("Faint TV", BEACON_WEIGHT_FLOOR), mac("AA", 1.0)))
        )

        assertThat(result.names).containsExactly("SHIELD")
        assertThat(result.macs).containsExactly("AA")
    }

    @Test
    fun `beacons of wifi rooms are never filtered on`() {
        val result = anchors(
            room("a", listOf(mac("AA", 1.2))),
            room("office", listOf(mac("OFFICE", 1.5)), wifiMode = WifiMatchMode.entries.first())
        )

        assertThat(result.macs).containsExactly("AA")
    }

    @Test
    fun `a weight a hair above the floor still counts as the floor`() {
        val result = anchors(room("a", listOf(mac("AA", 1.2), mac("FAINT", BEACON_WEIGHT_FLOOR + 0.000001))))

        assertThat(result.macs).containsExactly("AA")
    }
}
