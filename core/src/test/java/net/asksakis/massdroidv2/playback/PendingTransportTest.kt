package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.PlaybackState
import org.junit.Test

/**
 * The play or pause sent from this phone that MA has not answered yet decides which
 * PLAYING reports are adopted and which pauses are owed when the MA connection is lost.
 */
class PendingTransportTest {

    private val policy = PendingTransportPolicy

    private fun adopts(pending: PendingTransport) =
        AudioFocusPolicy.adoptRemotePlaying(localIntent = false, pauseAwaitingConfirmation = pending == PendingTransport.Pause)

    /**
     * A pause while the phone is already paused and the server says so (a car or a watch
     * sends one on disconnect) has nothing to confirm. Left pending, it refused a later play
     * started elsewhere and owed a pause at the next drop that stopped it.
     */
    @Test
    fun `a pause while already paused is not pending, so a remote play is adopted and a drop owes nothing`() {
        val afterPause = policy.afterLocalPause(PendingTransport.None, PlaybackState.PAUSED, nowMs = 1_000L)
        assertThat(afterPause).isEqualTo(PendingTransport.None)

        val afterRemotePlay = policy.afterServerReport(afterPause, PlaybackState.PLAYING)
        assertThat(adopts(afterRemotePlay)).isTrue()
        assertThat(policy.pauseOwedAtConnectionLoss(afterRemotePlay, PlaybackState.PLAYING)).isFalse()
    }

    /**
     * A car sends play and then pause within one round trip. The external-control handlers
     * clear the listener's intent before the pause reaches this point, so the play has to
     * be known from the pending command, not from the intent.
     */
    @Test
    fun `the car path, play then pause before the server answered, keeps the pause pending`() {
        val afterPlay = policy.afterLocalPlay(nowMs = 1_000L)
        val afterPause = policy.afterLocalPause(afterPlay, PlaybackState.PAUSED, nowMs = 1_300L)
        assertThat(afterPause).isEqualTo(PendingTransport.Pause)
    }

    /** The play's PLAYING report arrives after the pause and is older than it. */
    @Test
    fun `the PLAYING report of a play followed by a quick pause is not adopted`() {
        val afterPause = policy.afterLocalPause(policy.afterLocalPlay(nowMs = 1_000L), PlaybackState.PAUSED, nowMs = 1_300L)
        val afterStalePlaying = policy.afterServerReport(afterPause, PlaybackState.PLAYING)
        assertThat(afterStalePlaying).isEqualTo(PendingTransport.Pause)
        assertThat(adopts(afterStalePlaying)).isFalse()
        assertThat(policy.afterServerReport(afterStalePlaying, PlaybackState.PAUSED)).isEqualTo(PendingTransport.None)
    }

    /**
     * A play the server never confirmed (it rejected it) leaves nothing in flight once the
     * window is over, so a pause on the already paused server is not left pending.
     */
    @Test
    fun `a pause long after a play that was never confirmed is not pending on a paused server`() {
        val afterPlay = policy.afterLocalPlay(nowMs = 1_000L)
        val later = 1_000L + PendingTransportPolicy.PLAY_IN_FLIGHT_MS
        assertThat(policy.afterLocalPause(afterPlay, PlaybackState.PAUSED, nowMs = later)).isEqualTo(PendingTransport.None)
    }

    @Test
    fun `a second pause while one is pending stays pending`() {
        assertThat(policy.afterLocalPause(PendingTransport.Pause, PlaybackState.PAUSED, nowMs = 1_000L))
            .isEqualTo(PendingTransport.Pause)
    }

    @Test
    fun `a pause of a playing player or with no report is pending`() {
        assertThat(policy.afterLocalPause(PendingTransport.None, PlaybackState.PLAYING, nowMs = 1_000L))
            .isEqualTo(PendingTransport.Pause)
        assertThat(policy.afterLocalPause(PendingTransport.None, null, nowMs = 1_000L)).isEqualTo(PendingTransport.Pause)
    }

    /** The player list is emptied while MA is down; that is no answer to anything. */
    @Test
    fun `no report keeps the pending command`() {
        assertThat(policy.afterServerReport(PendingTransport.Pause, null)).isEqualTo(PendingTransport.Pause)
        assertThat(policy.afterServerReport(PendingTransport.Play(1_000L), null)).isEqualTo(PendingTransport.Play(1_000L))
    }

    @Test
    fun `a PLAYING report ends a pending play`() {
        assertThat(policy.afterServerReport(PendingTransport.Play(1_000L), PlaybackState.PLAYING)).isEqualTo(PendingTransport.None)
    }

    /**
     * MA pushes player updates for volume, metadata and group changes too, so a PAUSED
     * report can arrive after a local play and before MA has processed it. It must not end
     * the play, or a quick pause lands with nothing pending and the play's PLAYING is adopted.
     */
    @Test
    fun `a local play, a stale PAUSED report, then a quick pause leaves the pause pending`() {
        val afterPlay = policy.afterLocalPlay(nowMs = 1_000L)
        val afterStalePaused = policy.afterServerReport(afterPlay, PlaybackState.PAUSED)
        assertThat(afterStalePaused).isEqualTo(afterPlay)
        assertThat(policy.afterLocalPause(afterStalePaused, PlaybackState.PAUSED, nowMs = 1_300L))
            .isEqualTo(PendingTransport.Pause)
    }

    /**
     * The S25 field test (2026-11-06): a pause pressed 4.7 s into a Wi-Fi to mobile
     * handover went into the old socket, which still counted as connected, so it was not
     * owed. The probe then found the socket dead and the server played on.
     */
    @Test
    fun `a pending pause is owed when the MA connection is lost`() {
        assertThat(policy.pauseOwedAtConnectionLoss(PendingTransport.Pause, PlaybackState.PLAYING)).isTrue()
        assertThat(policy.pauseOwedAtConnectionLoss(PendingTransport.Pause, null)).isTrue()
    }

    @Test
    fun `a pending play or nothing pending is not owed when the connection is lost`() {
        assertThat(policy.pauseOwedAtConnectionLoss(PendingTransport.Play(1_000L), PlaybackState.PLAYING)).isFalse()
        assertThat(policy.pauseOwedAtConnectionLoss(PendingTransport.None, PlaybackState.PLAYING)).isFalse()
    }

    @Test
    fun `a pending pause for a player MA last reported paused is not owed`() {
        assertThat(policy.pauseOwedAtConnectionLoss(PendingTransport.Pause, PlaybackState.PAUSED)).isFalse()
    }

    /**
     * Emulator test of cdb8a38a: for 2.3 s between a pause and the dead socket being
     * found, the session showed BUFFERING with playWhenReady while the output was silent,
     * because only an owed pause showed paused. A pause sent and not yet confirmed is
     * already silent.
     */
    @Test
    fun `a pause sent and not yet confirmed shows the phone's player paused`() {
        val pending = policy.afterLocalPause(PendingTransport.None, PlaybackState.PLAYING, nowMs = 1_000L)
        val shown = LocalPlayingPolicy.shownPlaying(
            serverPlaying = true,
            localPlayerSelected = true,
            localPausePending = policy.pauseNotYetHeard(pending),
        )
        assertThat(shown).isFalse()
        val shownOffline = LocalPlayingPolicy.shownPlayingDisconnected(
            serverPlaying = true,
            localPlayerSelected = true,
            localPausePending = policy.pauseNotYetHeard(pending),
            localStreamActive = true,
        )
        assertThat(shownOffline).isFalse()
    }

    @Test
    fun `a confirmed pause, a pending play or nothing pending is not a pause not yet heard`() {
        assertThat(policy.pauseNotYetHeard(policy.afterServerReport(PendingTransport.Pause, PlaybackState.PAUSED))).isFalse()
        assertThat(policy.pauseNotYetHeard(PendingTransport.Play(1_000L))).isFalse()
        assertThat(policy.pauseNotYetHeard(PendingTransport.None)).isFalse()
    }
}
