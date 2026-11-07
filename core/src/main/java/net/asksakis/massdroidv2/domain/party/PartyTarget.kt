package net.asksakis.massdroidv2.domain.party

import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.model.Player

/** How the Party screen names a player. */
sealed interface PartyPlayerName {
    /** This phone's own Sendspin player. */
    data object ThisPhone : PartyPlayerName

    data class Named(val name: String) : PartyPlayerName

    /** A queue that belongs to no player the app knows. */
    data object Unknown : PartyPlayerName
}

/** Where the guests' songs go, as the Party screen describes it. */
sealed interface PartyTarget {
    /** The server has no player for the party, for example while nothing is playing. */
    data object NoPlayer : PartyTarget

    /** The silent disco: every guest listens on their own device. */
    data object GuestSession : PartyTarget

    /** The silent disco, but the server could not set up the player the guests listen to. */
    data object GuestSessionUnavailable : PartyTarget

    /** The player this app is controlling. */
    data class SelectedPlayer(val name: PartyPlayerName) : PartyTarget

    /**
     * Another player. In the car this is the case to warn about: with the plugin's automatic
     * choice, a home speaker that is playing takes the guests' songs.
     */
    data class OtherPlayer(val name: PartyPlayerName) : PartyTarget

    companion object {
        /**
         * Resolves the queue id from `party/player`. [selectedQueueId] is the queue the app
         * shows for the selected player, the same one the Queue screen uses, and
         * [thisPhonePlayerId] is this phone's Sendspin player.
         */
        fun resolve(
            targetQueueId: String?,
            mode: PartyMode,
            players: List<Player>,
            selectedQueueId: String?,
            thisPhonePlayerId: String?
        ): PartyTarget {
            // Remote mode first: there a missing queue means the silent disco could not start,
            // which playing something does not fix.
            if (mode == PartyMode.REMOTE) return if (targetQueueId == null) GuestSessionUnavailable else GuestSession
            if (targetQueueId == null) return NoPlayer
            val name = when {
                targetQueueId == thisPhonePlayerId -> PartyPlayerName.ThisPhone
                else -> players.firstOrNull { it.playerId == targetQueueId }
                    ?.let { PartyPlayerName.Named(it.displayName) }
                    ?: PartyPlayerName.Unknown
            }
            return if (targetQueueId == selectedQueueId) SelectedPlayer(name) else OtherPlayer(name)
        }
    }
}
