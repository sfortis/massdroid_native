package net.asksakis.massdroidv2.playback

/**
 * Whether the phone's own output counts as playing, which is what every media session
 * and the play/pause toggle show. Separated from the controller's flow combine so the
 * rule can be tested.
 *
 * The phone plays on from its buffer through a dropped connection, and the stream stays
 * active while that audio plays. Requiring the transport to be STREAMING showed that
 * time as paused: the TV's remote card read paused for the whole drop, and a media
 * button, which toggles by what the session shows, sent play instead of pause.
 */
object LocalPlayingPolicy {

    /**
     * [selectedPlayerId] is null when no selection has been observed. While the stream is
     * held through a drop that counts as unchanged, because the player list empties when
     * the MA connection drops with it, and "not observed" is not a choice of another
     * player. A real selection of another player, or the end of the hold, applies the
     * normal rule again.
     */
    fun playing(
        intent: Boolean,
        localPlayerId: String?,
        selectedPlayerId: String?,
        transportStreaming: Boolean,
        streamActive: Boolean,
        outputSynced: Boolean,
        serverSpeedPositive: Boolean,
    ): Boolean {
        if (localPlayerId == null) return false
        val heldThroughDrop = streamActive && !transportStreaming
        val selected = selectedPlayerId == localPlayerId || (selectedPlayerId == null && heldThroughDrop)
        return intent && selected && flowing(transportStreaming, streamActive) && outputSynced && serverSpeedPositive
    }

    /**
     * Whether the media session and the home screen widget show the selected player as
     * playing. The server's state is the base, and through a dropped connection its last
     * report is kept, so the phone's own player shows playing while its buffer plays. A
     * pause made on this phone that MA has not heard yet wins for the phone's own player:
     * the server still says PLAYING, but the phone is paused.
     */
    fun shownPlaying(serverPlaying: Boolean, localPlayerSelected: Boolean, localPauseOwed: Boolean): Boolean =
        serverPlaying && !(localPlayerSelected && localPauseOwed)

    /**
     * [shownPlaying] while MA is disconnected, for a view that has no buffering state to
     * fall back on, such as the home screen widget. The selected player keeps its last
     * server state through the drop, so only the phone's own player can be known to
     * play, and only while its buffered stream still plays ([localStreamActive]).
     */
    fun shownPlayingDisconnected(
        serverPlaying: Boolean,
        localPlayerSelected: Boolean,
        localPauseOwed: Boolean,
        localStreamActive: Boolean,
    ): Boolean = localPlayerSelected && localStreamActive && shownPlaying(serverPlaying, true, localPauseOwed)

    /**
     * Whether the transport carries audio to the output: it streams, or the stream is held
     * through a dropped connection while the buffered audio plays.
     */
    fun flowing(transportStreaming: Boolean, streamActive: Boolean): Boolean =
        transportStreaming || streamActive
}
