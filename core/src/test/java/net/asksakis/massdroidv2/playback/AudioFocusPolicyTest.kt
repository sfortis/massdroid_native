package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.PlaybackIntentCause
import net.asksakis.massdroidv2.playback.AudioFocusPolicy.OwedResume
import org.junit.Test

/**
 * Pins the table three shipped bugs lived in.
 *
 * All three left the phone silent while the server was streaming, and all three
 * were invisible: the transport was healthy, the buffer filled, and nothing in a
 * log said the output was being held down. They were also untestable where they
 * sat, which is why the answers moved into [AudioFocusPolicy].
 */
class AudioFocusPolicyTest {

    private fun never(): FocusRequestResult =
        error("focus must not be requested again while it is already held")

    /**
     * The first bug. configure() leaves the output paused until focus is settled,
     * so a grant has to say PLAY or a playback the server started, a cold start or
     * the queue rolling on, stays silent with nothing to lift it.
     */
    @Test
    fun `a stream that gets focus plays`() {
        val outcome = AudioFocusPolicy.onStreamStart(holdsFocus = false) {
            FocusRequestResult.GRANTED
        }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.PLAY)
    }

    /**
     * The second bug, the same silence reached another way: after a permanent
     * focus loss the transport never leaves STREAMING, so the restart arrives with
     * focus already gone and has to ask for it again rather than assume it.
     */
    @Test
    fun `a stream that starts without focus asks for it rather than assuming`() {
        var asked = 0
        AudioFocusPolicy.onStreamStart(holdsFocus = false) {
            asked++
            FocusRequestResult.GRANTED
        }
        assertThat(asked).isEqualTo(1)
    }

    /** A refusal must not produce audio on top of whatever owns the output. */
    @Test
    fun `a refused stream stays silent`() {
        val outcome = AudioFocusPolicy.onStreamStart(holdsFocus = false) {
            FocusRequestResult.FAILED
        }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.STAY_SILENT)
    }

    /**
     * A delayed grant is an accepted request queued behind whatever holds the
     * path, which Samsung returns while a Bluetooth route settles. Treating it as
     * a refusal loses the playback; treating it as a grant plays over the holder.
     */
    @Test
    fun `a delayed grant waits for the gain instead of playing or giving up`() {
        val outcome = AudioFocusPolicy.onStreamStart(holdsFocus = false) {
            FocusRequestResult.DELAYED
        }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.WAIT_FOR_GAIN)
    }

    /**
     * A track change is a stream end followed by a stream start, so this runs on
     * every track. Asking the platform again each time would be churn.
     */
    @Test
    fun `a stream started while focus is already held does not ask again`() {
        val outcome = AudioFocusPolicy.onStreamStart(holdsFocus = true) { never() }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.PLAY)
    }

    /**
     * The counter is a StateFlow and the manager never resets it, so a controller
     * that starts again is handed the last stream's number at once. Acting on it
     * asked for focus with nothing to play, which could silence another app.
     */
    @Test
    fun `an old stream number with no stream running does not ask for focus`() {
        val needsFocus = AudioFocusPolicy.streamNeedsFocus(
            generation = 5L,
            handled = null,
            streamActive = false
        )
        assertThat(needsFocus).isFalse()
    }

    /**
     * The opposite case, and the reason the replayed value cannot simply be
     * dropped: a controller can subscribe while a stream is genuinely playing, and
     * that stream does need focus.
     */
    @Test
    fun `a stream already live when we subscribe is adopted`() {
        val needsFocus = AudioFocusPolicy.streamNeedsFocus(
            generation = 5L,
            handled = null,
            streamActive = true
        )
        assertThat(needsFocus).isTrue()
    }

    /** An ordinary new start, which is what the collector exists for. */
    @Test
    fun `a new stream number with a live stream asks for focus`() {
        val needsFocus = AudioFocusPolicy.streamNeedsFocus(
            generation = 6L,
            handled = 5L,
            streamActive = true
        )
        assertThat(needsFocus).isTrue()
    }

    /** The same number twice is one start, not two. */
    @Test
    fun `the same stream number is not treated as a second start`() {
        val needsFocus = AudioFocusPolicy.streamNeedsFocus(
            generation = 6L,
            handled = 6L,
            streamActive = true
        )
        assertThat(needsFocus).isFalse()
    }

    /** Nothing has ever played, so there is nothing to give focus to. */
    @Test
    fun `a stream number of zero is not a start`() {
        val needsFocus = AudioFocusPolicy.streamNeedsFocus(
            generation = 0L,
            handled = null,
            streamActive = true
        )
        assertThat(needsFocus).isFalse()
    }

    /**
     * A start whose stream has already ended by the time the collector runs has
     * nothing to give focus to either.
     */
    @Test
    fun `a start whose stream already ended asks for nothing`() {
        val needsFocus = AudioFocusPolicy.streamNeedsFocus(
            generation = 6L,
            handled = 5L,
            streamActive = false
        )
        assertThat(needsFocus).isFalse()
    }

    /**
     * Local play and pause write the intent before the server answers, so a
     * PLAYING report can describe the play that came BEFORE a later pause.
     * Adopting it put the intent back behind the pause and a reconnect could then
     * resume music the listener had stopped.
     */
    @Test
    fun `a PLAYING report older than the pause just sent is not adopted`() {
        val adopt = AudioFocusPolicy.adoptRemotePlaying(
            localIntent = false,
            pauseAwaitingConfirmation = true
        )
        assertThat(adopt).isFalse()
    }

    /** Once the pause is confirmed, a PLAYING report is genuinely later. */
    @Test
    fun `a PLAYING report after the pause is confirmed is adopted`() {
        val adopt = AudioFocusPolicy.adoptRemotePlaying(
            localIntent = false,
            pauseAwaitingConfirmation = false
        )
        assertThat(adopt).isTrue()
    }

    /** Nothing to adopt when the listener is already playing from this phone. */
    @Test
    fun `a PLAYING report is not adopted when playback is already intended`() {
        val adopt = AudioFocusPolicy.adoptRemotePlaying(
            localIntent = true,
            pauseAwaitingConfirmation = false
        )
        assertThat(adopt).isFalse()
    }

    /**
     * A notification or an alarm carries a usage that says so, which is the case
     * the filter always handled.
     */
    @Test
    fun `a sound with an interrupting usage is audible`() {
        val audible = AudioFocusPolicy.interrupterAudible(
            interrupterUsagePresent = true,
            mediaPlayerCount = 1
        )
        assertThat(audible).isTrue()
    }

    /**
     * Our own output is the one media player present in every duck measured, so
     * seeing it alone must not read as another app talking over us. Widening the
     * usage list to include media would have made this true forever and the duck
     * would never have released.
     */
    @Test
    fun `our own output alone is not an interrupting sound`() {
        val audible = AudioFocusPolicy.interrupterAudible(
            interrupterUsagePresent = false,
            mediaPlayerCount = 1
        )
        assertThat(audible).isFalse()
    }

    /**
     * The case this was written for: an app that asks to duck us and plays as
     * plain media, which no usage in the list describes. It is a second media
     * player beside ours, and that is what gives it away.
     */
    @Test
    fun `a second media player is another app talking over us`() {
        val audible = AudioFocusPolicy.interrupterAudible(
            interrupterUsagePresent = false,
            mediaPlayerCount = 2
        )
        assertThat(audible).isTrue()
    }

    /** Nothing playing at all, which is 13 of the 122 ducks measured. */
    @Test
    fun `silence is not an interrupting sound`() {
        val audible = AudioFocusPolicy.interrupterAudible(
            interrupterUsagePresent = false,
            mediaPlayerCount = 0
        )
        assertThat(audible).isFalse()
    }

    /** The gain that ends an interruption starts what the interruption stopped. */
    @Test
    fun `a gain resumes the playback an interruption owed`() {
        val outcome = AudioFocusPolicy.onFocusGain(OwedResume.INTERRUPTION, OutputRoute.BT, externalSinkConnected = true)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.RESUME)
    }

    /**
     * The regression this replaced. Every earlier version asked a second question
     * at the moment of the gain, and the last one asked whether the stream was
     * still up. Pausing is what ends the stream, measured at 0.8 s after our own
     * pause, so that question was already answered no at every gain that followed
     * an interruption: three owed resumes out of three were lost, and two deferred
     * plays went with them. Nothing but the owed flag may gate this.
     */
    @Test
    fun `a gain resumes even though the pause has already ended the stream`() {
        // The phone's own speaker is a route like any other for a plain interruption.
        val outcome = AudioFocusPolicy.onFocusGain(OwedResume.INTERRUPTION, OutputRoute.SPEAKER, externalSinkConnected = false)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.RESUME)
    }

    /**
     * The fault the owed-flag gate exposed: a transient loss while the phone was
     * PAUSED still owed a resume, so the gain that followed started music the
     * listener had stopped (2026-09-12 20:08, 20:18, 20:21, 20:23, each undone by
     * hand). Nothing playing means nothing to pause and nothing owed.
     */
    @Test
    fun `a transient loss while paused owes nothing`() {
        val outcome = AudioFocusPolicy.onTransientLoss(listenerWantsPlayback = false, localStreamActive = true)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.TransientLoss.NOTHING_PLAYING)
    }

    /** The interruption case itself must keep working: playing, so pause and owe. */
    @Test
    fun `a transient loss while playing pauses and owes a resume`() {
        val outcome = AudioFocusPolicy.onTransientLoss(listenerWantsPlayback = true, localStreamActive = true)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.TransientLoss.PAUSE_AND_OWE)
    }

    /**
     * The intent can outlive the stream: a pause sent to the sync group left it set,
     * and a keyboard click six minutes later restarted the group. With no local
     * stream the loss must owe nothing, whatever the intent says.
     */
    @Test
    fun `a transient loss with no local stream owes nothing even if the intent is set`() {
        val outcome = AudioFocusPolicy.onTransientLoss(listenerWantsPlayback = true, localStreamActive = false)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.TransientLoss.NOTHING_PLAYING)
    }

    /** A gain with nothing owed must not start playback of its own accord. */
    @Test
    fun `a gain with nothing owed starts nothing`() {
        val outcome = AudioFocusPolicy.onFocusGain(owed = null, route = OutputRoute.BT, externalSinkConnected = true)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.IGNORE)
    }

    /**
     * Issue #68 on a Pixel in Android Auto: a message read aloud takes the focus and
     * keeps the output off the car for longer than the route-loss settle window. The
     * deadline used to pause again and drop the resume, so the music stayed stopped.
     */
    @Test
    fun `a hold deadline during an interruption keeps the resume for the gain`() {
        assertThat(AudioFocusPolicy.onSinkLossConfirmed(OwedResume.INTERRUPTION))
            .isEqualTo(AudioFocusPolicy.SinkLoss.KEEP_INTERRUPTION_RESUME)
        assertThat(AudioFocusPolicy.onFocusGain(OwedResume.INTERRUPTION_SINK_LOST, OutputRoute.BT, externalSinkConnected = true))
            .isEqualTo(AudioFocusPolicy.FocusGain.RESUME)
    }

    /** A sink lost with no interruption in progress pauses and drops what was owed, as it always did. */
    @Test
    fun `a hold deadline with no interruption pending pauses`() {
        assertThat(AudioFocusPolicy.onSinkLossConfirmed(owed = null)).isEqualTo(AudioFocusPolicy.SinkLoss.PAUSE)
        assertThat(AudioFocusPolicy.onSinkLossConfirmed(OwedResume.DEFERRED_PLAY))
            .isEqualTo(AudioFocusPolicy.SinkLoss.PAUSE)
    }

    /**
     * The race the first version of the fix lost: the output is still bound to the speaker
     * when the focus comes back, a few hundred milliseconds before the reopen onto the car.
     * With the car still connected, the gain waits instead of dropping the resume.
     */
    @Test
    fun `a gain that reads the speaker while the car is connected waits for the sink`() {
        assertThat(AudioFocusPolicy.onFocusGain(OwedResume.INTERRUPTION_SINK_LOST, OutputRoute.SPEAKER, externalSinkConnected = true))
            .isEqualTo(AudioFocusPolicy.FocusGain.AWAIT_SINK_RETURN)
    }

    /** The phone left the car during the interruption: no music from the phone speaker. */
    @Test
    fun `a gain on the speaker with no external sink connected stays paused`() {
        assertThat(AudioFocusPolicy.onFocusGain(OwedResume.INTERRUPTION_SINK_LOST, OutputRoute.SPEAKER, externalSinkConnected = false))
            .isEqualTo(AudioFocusPolicy.FocusGain.IGNORE)
    }

    /**
     * The one case the old conditions existed for, answered where the choice is
     * made: moving to another speaker sends no pause to the player being left, so
     * without this the gain would start this phone beside the chosen one.
     */
    @Test
    fun `choosing another player cancels the resume owed to this phone`() {
        val cancels = AudioFocusPolicy.selectionCancelsOwedResume(
            newSelectedPlayerId = "kitchen",
            localPlayerId = "phone"
        )
        assertThat(cancels).isTrue()
    }

    /** Choosing this phone again is not a cancellation. */
    @Test
    fun `choosing this phone does not cancel its own owed resume`() {
        val cancels = AudioFocusPolicy.selectionCancelsOwedResume(
            newSelectedPlayerId = "phone",
            localPlayerId = "phone"
        )
        assertThat(cancels).isFalse()
    }

    /**
     * And an unknown selection is not a decision. It reads as null on every
     * reconnect, before the player list arrives, which is exactly how an earlier
     * version threw away 16 resumes out of 41.
     */
    @Test
    fun `an unknown selection does not cancel an owed resume`() {
        val cancels = AudioFocusPolicy.selectionCancelsOwedResume(
            newSelectedPlayerId = null,
            localPlayerId = "phone"
        )
        assertThat(cancels).isFalse()
    }

    /**
     * A solo phone pauses even for a call it recognises: a freeze would let the MA
     * player run on through the call and bring the phone back to stale audio.
     */
    @Test
    fun `a solo phone pauses for a call`() {
        val hold = AudioFocusPolicy.interruptionHold(grouped = false, callModeSet = true)
        assertThat(hold).isEqualTo(AudioFocusPolicy.InterruptionHold.PAUSE)
    }

    /** A grouped phone freezes alone for a call, so the pause does not stop the group. */
    @Test
    fun `a grouped phone freezes for a call`() {
        val hold = AudioFocusPolicy.interruptionHold(grouped = true, callModeSet = true)
        assertThat(hold).isEqualTo(AudioFocusPolicy.InterruptionHold.FREEZE)
    }

    /**
     * Telecom requests the focus before it sets the call mode, so a grouped loss with
     * the mode still normal must freeze and check again, not pause the group at once.
     */
    @Test
    fun `a grouped loss before the call mode is set freezes and checks`() {
        val hold = AudioFocusPolicy.interruptionHold(grouped = true, callModeSet = false)
        assertThat(hold).isEqualTo(AudioFocusPolicy.InterruptionHold.FREEZE_AND_CHECK_FOR_CALL)
    }

    /**
     * The server never heard a pause made during a dropped connection and starts the
     * stream again when the phone rejoins. Focus that is still held, as after a pause
     * from a watch, must not let that stream play.
     */
    @Test
    fun `a stream that restarts before the server heard the pause stays paused`() {
        val outcome = AudioFocusPolicy.onStreamStart(holdsFocus = true, pauseOwedToServer = true) { never() }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.HOLD_FOR_SERVER_PAUSE)
    }

    /** Without focus the owed pause also wins, so a call is not asked to give focus back. */
    @Test
    fun `a stream held for an owed pause does not ask for focus`() {
        val outcome = AudioFocusPolicy.onStreamStart(holdsFocus = false, pauseOwedToServer = true) { never() }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.HOLD_FOR_SERVER_PAUSE)
    }

    @Test
    fun `a pause made while MA is down is owed`() {
        val owed = AudioFocusPolicy.pauseOwedToServer(
            maConnected = false,
            streamHeldThroughDrop = false,
            serverState = PlaybackState.PLAYING,
        )
        assertThat(owed).isTrue()
    }

    /**
     * The player list is emptied while MA is down, so there is no report at all
     * exactly when the pause is most likely lost.
     */
    @Test
    fun `a pause with no server report is owed while MA is down`() {
        val owed = AudioFocusPolicy.pauseOwedToServer(
            maConnected = false,
            streamHeldThroughDrop = false,
            serverState = null,
        )
        assertThat(owed).isTrue()
    }

    /**
     * A socket that died without a close still reads as connected, and a command
     * written into it is dropped without an error. The held stream is the sign.
     */
    @Test
    fun `a pause made while the stream is held through a drop is owed even with MA connected`() {
        val owed = AudioFocusPolicy.pauseOwedToServer(
            maConnected = true,
            streamHeldThroughDrop = true,
            serverState = PlaybackState.PLAYING,
        )
        assertThat(owed).isTrue()
    }

    @Test
    fun `a pause the server can hear at once is not owed`() {
        val owed = AudioFocusPolicy.pauseOwedToServer(
            maConnected = true,
            streamHeldThroughDrop = false,
            serverState = PlaybackState.PLAYING,
        )
        assertThat(owed).isFalse()
    }

    /** A server that already reports the player stopped has nothing left to pause. */
    @Test
    fun `a pause for a player the server reports stopped is not owed`() {
        val owed = AudioFocusPolicy.pauseOwedToServer(
            maConnected = false,
            streamHeldThroughDrop = true,
            serverState = PlaybackState.PAUSED,
        )
        assertThat(owed).isFalse()
    }

    /**
     * The same field test: Sendspin rejoined 0.95 s before MA, and the next track, which
     * the server had moved on to during the drop, played on the speaker until MA was back.
     */
    @Test
    fun `a rejoin while MA is down and the listener has paused stays silent`() {
        val outcome = AudioFocusPolicy.onStreamStart(holdsFocus = true, listenerPausedWithMaDown = true) { never() }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.HOLD_UNTIL_MA_RETURNS)
    }

    /**
     * Run 2 of the S25 field test: the phone played its buffer through the drop, the
     * listener still wanted playback, and the rejoin spliced onto it. MA being down must
     * not hold that stream.
     */
    @Test
    fun `a rejoin while MA is down and the listener wants playback plays`() {
        val outcome = AudioFocusPolicy.onStreamStart(holdsFocus = true, listenerPausedWithMaDown = false) { never() }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.PLAY)
    }

    /** The owed pause still wins, so the hold that resends it is the one taken. */
    @Test
    fun `an owed pause wins over the hold until MA returns`() {
        val outcome = AudioFocusPolicy.onStreamStart(
            holdsFocus = true,
            pauseOwedToServer = true,
            listenerPausedWithMaDown = true,
        ) { never() }
        assertThat(outcome).isEqualTo(AudioFocusPolicy.StreamStart.HOLD_FOR_SERVER_PAUSE)
    }

    /**
     * The MINI case of 2026-11-11. The assistant took the focus, the phone paused and owed a
     * resume, and AAIdrive sent its own pause 360 ms later. That pause must not cancel the
     * resume, because AAIdrive never sent play again.
     */
    @Test
    fun `a car pause during an interruption keeps the owed resume`() {
        val decides = AudioFocusPolicy.pauseIsListenerDecision(
            cause = PlaybackIntentCause.EXTERNAL_CONTROL,
            owed = OwedResume.INTERRUPTION,
            holdsFocus = false,
        )
        assertThat(decides).isFalse()
    }

    /** The listener's own pause during the same interruption still means "stay stopped". */
    @Test
    fun `a listener pause during an interruption cancels the owed resume`() {
        val decides = AudioFocusPolicy.pauseIsListenerDecision(
            cause = PlaybackIntentCause.LISTENER,
            owed = OwedResume.INTERRUPTION,
            holdsFocus = false,
        )
        assertThat(decides).isTrue()
    }

    /** With the focus back, a car pause is a decision again, for example while the sink is awaited. */
    @Test
    fun `a car pause after the focus returned is a decision`() {
        val decides = AudioFocusPolicy.pauseIsListenerDecision(
            cause = PlaybackIntentCause.EXTERNAL_CONTROL,
            owed = OwedResume.INTERRUPTION,
            holdsFocus = true,
        )
        assertThat(decides).isTrue()
    }

    /** Without an interruption, or with a delayed play pending, a car pause stops playback as before. */
    @Test
    fun `a car pause with no interruption resume owed is a decision`() {
        listOf(null, OwedResume.DEFERRED_PLAY, OwedResume.INTERRUPTION_SINK_LOST).forEach { owed ->
            val decides = AudioFocusPolicy.pauseIsListenerDecision(
                cause = PlaybackIntentCause.EXTERNAL_CONTROL,
                owed = owed,
                holdsFocus = false,
            )
            assertThat(decides).isTrue()
        }
    }

    /** The phone's own focus pause and a repeated pause never cancel the resume. */
    @Test
    fun `the focus pause and a repeat are not decisions`() {
        listOf(PlaybackIntentCause.AUDIO_FOCUS, PlaybackIntentCause.REPEAT).forEach { cause ->
            val decides = AudioFocusPolicy.pauseIsListenerDecision(
                cause = cause,
                owed = OwedResume.INTERRUPTION,
                holdsFocus = false,
            )
            assertThat(decides).isFalse()
        }
    }
}
