package net.asksakis.massdroidv2.data.proximity

/**
 * What to do with a room selection that Follow Me put off because the room's player was not
 * available.
 *
 * A room is confirmed once. When its player is missing at that moment, which is what happens
 * while the Music Assistant connection is still down and the player list is empty, the
 * selection is skipped and the detector does not confirm the same room again. The room's
 * player then never got selected after the connection came up (2026-11-20: Living Room
 * confirmed at 07:01, the connection up at 07:07, the bedroom speaker still selected). The
 * selection put off is applied once the player is there, as long as the room has not changed.
 */
object DeferredRoomSelection {

    enum class Action {
        /** Nothing is put off, or the player is still missing: keep waiting. */
        WAIT,

        /** The room is still the current one and its player is now available: select it. */
        APPLY,

        /** The room has changed or been lost since: the selection put off no longer applies. */
        DROP
    }

    fun decide(deferredRoomId: String?, currentRoomId: String?, playerAvailable: Boolean): Action = when {
        deferredRoomId == null -> Action.WAIT
        currentRoomId != deferredRoomId -> Action.DROP
        playerAvailable -> Action.APPLY
        else -> Action.WAIT
    }
}
