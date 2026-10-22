package net.asksakis.massdroidv2.playback

/**
 * Where a group join may seek to relock the phone, separated from the controller that
 * sends the seek so the rule can be tested.
 *
 * MA's queue position can run past the end of the track at a track change: in flow mode
 * with a crossfade the server has moved on to the next track while the queue still names
 * the old one, and after a reconnect it reported 462 s on a 383 s track (2026-10-22). A
 * relock seek there lands in the last second of the old track, and MA then held the
 * player's playback lock for minutes without streaming, so the phone stayed silent and
 * every later command, Break sync included, waited behind it.
 */
internal object GroupJoinRelockPolicy {

    /** A seek this close to the end of the track is treated as past it. */
    const val TRACK_END_MARGIN_SEC = 5.0

    /** How long a join waits for MA to report a position inside the track. */
    const val VALID_POSITION_WAIT_MS = 15_000L

    /**
     * The position to seek to, or null when it cannot be trusted yet. A track with no
     * known length (a radio stream) keeps the position as it is, as before.
     */
    fun seekPosition(positionSec: Double, durationSec: Double): Double? {
        val position = positionSec.coerceAtLeast(0.0)
        if (durationSec <= 0.0) return position
        return position.takeIf { it <= durationSec - TRACK_END_MARGIN_SEC }
    }
}
