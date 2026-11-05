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
     * Whether the transport carries audio to the output: it streams, or the stream is held
     * through a dropped connection while the buffered audio plays.
     */
    fun flowing(transportStreaming: Boolean, streamActive: Boolean): Boolean =
        transportStreaming || streamActive
}
