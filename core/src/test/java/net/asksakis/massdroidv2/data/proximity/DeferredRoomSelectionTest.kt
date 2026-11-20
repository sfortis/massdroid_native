package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.proximity.DeferredRoomSelection.Action
import org.junit.Test

/** A room selection put off for a missing player is applied once the player is there. */
class DeferredRoomSelectionTest {

    @Test
    fun `the room's player coming back selects the room still confirmed`() {
        assertThat(DeferredRoomSelection.decide("living", "living", playerAvailable = true, selectionChanged = false))
            .isEqualTo(Action.APPLY)
    }

    @Test
    fun `while the player is still missing it keeps waiting`() {
        assertThat(DeferredRoomSelection.decide("living", "living", playerAvailable = false, selectionChanged = false))
            .isEqualTo(Action.WAIT)
    }

    /** A selection put off for one room must not pull the player of a room the listener left. */
    @Test
    fun `a room that changed or was lost drops the selection put off`() {
        assertThat(DeferredRoomSelection.decide("living", "kitchen", playerAvailable = true, selectionChanged = false))
            .isEqualTo(Action.DROP)
        assertThat(DeferredRoomSelection.decide("living", null, playerAvailable = true, selectionChanged = false))
            .isEqualTo(Action.DROP)
    }

    /** A player picked by hand while the room waited is never overridden when the speaker returns. */
    @Test
    fun `a selection made while it waited drops the selection put off`() {
        assertThat(
            DeferredRoomSelection.decide("living", "living", playerAvailable = true, selectionChanged = true)
        ).isEqualTo(Action.DROP)
        assertThat(
            DeferredRoomSelection.decide("living", "living", playerAvailable = false, selectionChanged = true)
        ).isEqualTo(Action.DROP)
    }

    /** Only a selection that was really put off is applied, never a player list update alone. */
    @Test
    fun `nothing put off does nothing`() {
        assertThat(DeferredRoomSelection.decide(null, "living", playerAvailable = true, selectionChanged = false))
            .isEqualTo(Action.WAIT)
    }
}
