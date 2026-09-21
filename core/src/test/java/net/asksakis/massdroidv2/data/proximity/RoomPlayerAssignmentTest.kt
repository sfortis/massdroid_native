package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks what assigning a player to a room from the player settings dialog does to the config.
 *
 * The dialog writes the assignment straight away, so the rules have to hold without a review
 * step: only the named room changes, its calibration survives the write, the room the player
 * already served keeps it, and an id that no longer exists changes nothing.
 */
class RoomPlayerAssignmentTest {

    private fun room(id: String, playerId: String) = RoomConfig(
        id = id,
        name = "Room $id",
        playerId = playerId,
        playerName = "Player $playerId",
        fingerprints = listOf(
            RoomFingerprint(id = "fp-$id", label = "spot", samples = mapOf("aa" to -60), capturedAtMs = 1L)
        ),
        calibrationQuality = CalibrationQuality.GOOD
    )

    private val config = ProximityConfig(
        enabled = true,
        rooms = listOf(room("living", "jbl"), room("bedroom", "sonos"))
    )

    @Test
    fun `assignment replaces the player of the named room only`() {
        val updated = config.withRoomPlayer("living", playerId = "sonos", playerName = "Bedroom Sonos")

        val living = updated.rooms.first { it.id == "living" }
        assertThat(living.playerId).isEqualTo("sonos")
        assertThat(living.playerName).isEqualTo("Bedroom Sonos")
        assertThat(updated.rooms.first { it.id == "bedroom" }).isEqualTo(config.rooms.first { it.id == "bedroom" })
    }

    @Test
    fun `assignment keeps the calibration of the room it writes`() {
        val updated = config.withRoomPlayer("living", playerId = "sonos", playerName = "Bedroom Sonos")

        val living = updated.rooms.first { it.id == "living" }
        assertThat(living.fingerprints).isEqualTo(config.rooms.first { it.id == "living" }.fingerprints)
        assertThat(living.calibrationQuality).isEqualTo(CalibrationQuality.GOOD)
    }

    @Test
    fun `a player can serve two rooms, so the room it already had is not cleared`() {
        val updated = config.withRoomPlayer("living", playerId = "sonos", playerName = "Bedroom Sonos")

        assertThat(updated.rooms.filter { it.playerId == "sonos" }.map { it.id })
            .containsExactly("living", "bedroom")
    }

    @Test
    fun `an unknown room id leaves the config alone`() {
        val updated = config.withRoomPlayer("kitchen", playerId = "sonos", playerName = "Bedroom Sonos")

        assertThat(updated).isEqualTo(config)
    }
}
