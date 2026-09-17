package net.asksakis.massdroidv2.domain.widget

import kotlinx.serialization.Serializable
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player

/**
 * What the home screen widget shows, frozen to a file so it can be drawn after the
 * process has died. Built only from the selected player and the connection state,
 * the same two things the mini player and the notification are built from.
 */
@Serializable
data class NowPlayingWidgetSnapshot(
    val playerId: String? = null,
    val playerName: String = "",
    val title: String = "",
    val artist: String = "",
    val imageUrl: String? = null,
    val isPlaying: Boolean = false,
    val connected: Boolean = false
) {
    val hasTrack: Boolean get() = title.isNotBlank()

    companion object {
        val Empty = NowPlayingWidgetSnapshot()

        /**
         * The snapshot to publish after a change, or null when nothing should be published.
         *
         * The process is often started just to draw the widget and has no connection, and
         * right after connecting the selected player is still being restored. Publishing
         * "nothing" in either window replaced a good snapshot with an empty card. So while
         * disconnected the last known track is kept and only marked offline and stopped,
         * and a connected process with no selection yet publishes nothing and waits.
         */
        fun next(previous: NowPlayingWidgetSnapshot, player: Player?, connected: Boolean): NowPlayingWidgetSnapshot? =
            when {
                !connected -> previous.copy(connected = false, isPlaying = false)
                player == null -> null
                else -> from(player, connected = true)
            }

        fun from(player: Player?, connected: Boolean): NowPlayingWidgetSnapshot {
            if (player == null) return NowPlayingWidgetSnapshot(connected = connected)
            val media = player.currentMedia
            return NowPlayingWidgetSnapshot(
                playerId = player.playerId,
                playerName = player.displayName,
                title = media?.title.orEmpty(),
                artist = media?.artist.orEmpty(),
                imageUrl = media?.imageUrl,
                isPlaying = player.state == PlaybackState.PLAYING,
                connected = connected
            )
        }
    }
}
