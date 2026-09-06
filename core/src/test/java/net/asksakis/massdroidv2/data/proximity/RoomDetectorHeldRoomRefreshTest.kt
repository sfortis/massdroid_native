package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A config save that changes the speaker or the name of the room we are in must re-bind the
 * held room without a reset. Later reads of the same room id are "no change" and never refresh
 * the stored copy, so the old speaker would otherwise be re-selected for as long as we stayed.
 */
class RoomDetectorHeldRoomRefreshTest {

    private val living = DetectedRoom("living", "Living Room", "p-old", "Old speaker")

    @Test
    fun `re-binding the held room keeps detection state and swaps the speaker`() {
        val detector = RoomDetector()
        detector.seedRoom(living)

        detector.refreshHeldRoom(DetectedRoom("living", "Living Room", "p-new", "New speaker"))

        assertThat(detector.currentRoom.value?.playerId).isEqualTo("p-new")
        assertThat(detector.currentRoom.value?.roomId).isEqualTo("living")
    }

    @Test
    fun `another room's details do not touch the held room`() {
        val detector = RoomDetector()
        detector.seedRoom(living)

        detector.refreshHeldRoom(DetectedRoom("kitchen", "Kitchen", "p-k", "Kitchen speaker"))

        assertThat(detector.currentRoom.value).isEqualTo(living)
    }

    @Test
    fun `nothing held means nothing to refresh`() {
        val detector = RoomDetector()

        detector.refreshHeldRoom(living)

        assertThat(detector.currentRoom.value).isNull()
    }
}
