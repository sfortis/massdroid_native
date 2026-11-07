package net.asksakis.massdroidv2.domain.party

import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player

/** How the Party screen names a player. */
sealed interface PartyPlayerName {
    /** This phone's own Sendspin player. */
    data object ThisPhone : PartyPlayerName

    data class Named(val name: String) : PartyPlayerName
}

/** Where the guests' songs go, as the Party screen describes it. */
sealed interface PartyTarget {
    /**
     * Nothing is playing, so guest songs have no real player yet. The plugin's automatic
     * choice then falls back to whichever queue it finds first, which can be a queue no
     * player the app knows owns, so naming it or warning about it would only confuse.
     */
    data object Idle : PartyTarget

    /** The silent disco: every guest listens on their own device. */
    data object GuestSession : PartyTarget

    /** The silent disco, but the server could not set up the player the guests listen to. */
    data object GuestSessionUnavailable : PartyTarget

    /** The player this app is controlling. */
    data class SelectedPlayer(val name: PartyPlayerName) : PartyTarget

    /**
     * Another player. In the car this is the case to warn about: with the plugin's automatic
     * choice, a home speaker that is playing takes the guests' songs. A [state] other than
     * playing or paused means a player fixed in the plugin's settings, which the first guest
     * song starts.
     */
    data class OtherPlayer(val name: PartyPlayerName, val state: PlaybackState) : PartyTarget

    companion object {
        /**
         * Resolves the queue id from `party/player`. [selectedQueueId] is the queue the app
         * shows for the selected player, the same one the Queue screen uses, and
         * [thisPhonePlayerId] is this phone's Sendspin player. A target no known player owns
         * is [Idle], and so is a target that is neither playing nor paused while no player is.
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
            val player = players.firstOrNull { it.playerId == targetQueueId } ?: return Idle
            // The automatic choice names an inactive queue only while no queue plays or is
            // paused. An inactive target while another player plays is therefore a player fixed
            // in the plugin's settings, and keeps its name and the warning. A fixed player while
            // nothing plays anywhere still shows as Idle: a non-admin user cannot read the
            // plugin's player setting, so the app cannot tell the two choices apart then.
            if (player.state !in ACTIVE_STATES && players.none { it.state in ACTIVE_STATES }) return Idle
            val name = if (player.playerId == thisPhonePlayerId) {
                PartyPlayerName.ThisPhone
            } else {
                PartyPlayerName.Named(player.displayName)
            }
            return if (targetQueueId == selectedQueueId) SelectedPlayer(name) else OtherPlayer(name, player.state)
        }

        private val ACTIVE_STATES = setOf(PlaybackState.PLAYING, PlaybackState.PAUSED)
    }
}
