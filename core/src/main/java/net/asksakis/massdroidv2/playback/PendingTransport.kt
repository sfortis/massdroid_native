package net.asksakis.massdroidv2.playback

import net.asksakis.massdroidv2.domain.model.PlaybackState

/**
 * The play or pause sent from this phone for its own Sendspin player that MA has not
 * answered yet. One value, so the latest command replaces the previous one and no two
 * flags can disagree about which of them is still in flight.
 *
 * While a pause is pending, a PLAYING report is older than that pause and is not adopted
 * as the listener's wish to play ([AudioFocusPolicy.adoptRemotePlaying]), and a lost MA
 * connection makes the pause owed ([PendingTransportPolicy.pauseOwedAtConnectionLoss]).
 */
sealed interface PendingTransport {
    /** Nothing sent from here is waiting for an answer. */
    data object None : PendingTransport

    /** A play sent at [sentAtMs] on the elapsed clock, until MA reports the player. */
    data class Play(val sentAtMs: Long) : PendingTransport

    /** A pause, until MA reports the player not playing. */
    data object Pause : PendingTransport
}

object PendingTransportPolicy {

    /**
     * How long a play counts as on its way to the server. A PLAYING report normally follows
     * within one round trip. A play the server rejected (no more tracks, for example) never
     * produces one, and only the time since it was sent tells it apart from a play still in
     * flight: both leave a play pending while the server reports paused.
     */
    const val PLAY_IN_FLIGHT_MS = 10_000L

    fun afterLocalPlay(nowMs: Long): PendingTransport = PendingTransport.Play(nowMs)

    /**
     * A pause sent now is pending unless there is nothing to confirm: no play is in flight,
     * no pause is already pending, and the server already reports the player as not
     * playing. A pause there changes nothing on the server and no report would ever confirm
     * it, so it stayed pending: a later play started elsewhere was refused, and the next MA
     * drop owed a pause that stopped it.
     *
     * A play still in flight keeps the pause pending even though the server says paused,
     * because that play's PLAYING report is still to come and is older than the pause.
     */
    fun afterLocalPause(pending: PendingTransport, serverState: PlaybackState?, nowMs: Long): PendingTransport {
        val playInFlight = pending is PendingTransport.Play && nowMs - pending.sentAtMs < PLAY_IN_FLIGHT_MS
        val nothingToConfirm = !playInFlight && pending != PendingTransport.Pause &&
            serverState != null && serverState != PlaybackState.PLAYING
        return if (nothingToConfirm) PendingTransport.None else PendingTransport.Pause
    }

    /**
     * A report from MA for the player. No report at all (the list is emptied while the MA
     * connection is down) changes nothing. A pending play ends only on PLAYING: a report of
     * anything else can be older than the play, and ending the play there let a quick pause
     * land with nothing pending and the play's PLAYING be adopted behind it. A play that is
     * never confirmed expires by its age instead ([PLAY_IN_FLIGHT_MS]). A pause ends only
     * when the server reports the player not playing; a PLAYING report then is the one the
     * pause is older than.
     */
    fun afterServerReport(pending: PendingTransport, state: PlaybackState?): PendingTransport = when {
        state == null -> pending
        pending is PendingTransport.Play && state == PlaybackState.PLAYING -> PendingTransport.None
        pending == PendingTransport.Pause && state != PlaybackState.PLAYING -> PendingTransport.None
        else -> pending
    }

    /**
     * Whether the pending command becomes a pause owed to the server when the MA connection
     * is lost.
     *
     * A pause sent while the connection still counts as up is written into a socket that
     * may already be dead: on a Wi-Fi to mobile handover the old socket stays "connected"
     * until the handover probe fails five seconds later, and a pause pressed in that window
     * was lost while the server played on (2026-11-06). Sent is not delivered, so a pending
     * pause is owed when the connection goes, and the reconnect sends it again.
     * [lastServerState] is MA's last report for the player, kept across the drop; a server
     * that last reported anything but PLAYING has nothing left to pause.
     */
    fun pauseOwedAtConnectionLoss(pending: PendingTransport, lastServerState: PlaybackState?): Boolean =
        pending == PendingTransport.Pause &&
            AudioFocusPolicy.pauseOwedToServer(
                maConnected = false,
                streamHeldThroughDrop = false,
                serverState = lastServerState,
            )
}
