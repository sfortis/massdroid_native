package net.asksakis.massdroidv2.playback

/**
 * What the app's own play/pause button shows and may do while the MA connection is down,
 * separated from the screens so the phone's mini player and Now Playing share one rule and
 * the rule can be tested.
 *
 * With MA down the selected player's state is the last one MA reported, usually PLAYING,
 * so the button kept showing Pause after a local pause, and the connection guard refused
 * every tap with "Not connected to Music Assistant", the pause included, while the phone
 * went on playing its buffered audio for up to about 30 s. When the selected player is
 * this phone's own, the phone itself knows whether it is playing: its stream is active
 * and its output is not idle. A pause of that playback needs no server to take effect
 * here, and the controller sends it to MA once the connection is back.
 */
object OfflinePlayPausePolicy {

    /**
     * [shownPlaying] is what the button shows. [pausableOffline] is whether a tap on it may
     * go through although MA is not connected; only a pause of the phone's own playback may.
     */
    data class Button(val shownPlaying: Boolean, val pausableOffline: Boolean)

    fun button(
        maConnected: Boolean,
        serverPlaying: Boolean,
        localPlayerId: String?,
        selectedPlayerId: String?,
        streamActive: Boolean,
        outputIdle: Boolean,
    ): Button {
        val selectedIsLocal = localPlayerId != null && selectedPlayerId == localPlayerId
        if (maConnected || !selectedIsLocal) return Button(shownPlaying = serverPlaying, pausableOffline = false)
        val playingLocally = streamActive && !outputIdle
        return Button(shownPlaying = playingLocally, pausableOffline = playingLocally)
    }
}
