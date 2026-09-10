package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
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
        val outcome = AudioFocusPolicy.onFocusGain(
            resumeOwed = true,
            localOutputStillStreaming = true
        )
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.RESUME)
    }

    /**
     * A phone that has stopped streaming has had the music moved elsewhere, or
     * stopped altogether, so there is nothing here to bring back.
     */
    @Test
    fun `a gain does not resume a phone that has stopped streaming`() {
        val outcome = AudioFocusPolicy.onFocusGain(
            resumeOwed = true,
            localOutputStillStreaming = false
        )
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.IGNORE)
    }

    /**
     * The condition is the stream, deliberately NOT whether this phone is the
     * selected player. Asking about selection refused 16 of 41 real resumes, every
     * one of them mid-reconnect with the selection momentarily unknown, which left
     * a notification able to pause the music for good.
     */
    @Test
    fun `a streaming phone resumes even while the selected player is unknown`() {
        val outcome = AudioFocusPolicy.onFocusGain(
            resumeOwed = true,
            localOutputStillStreaming = true
        )
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.RESUME)
    }

    /**
     * And deliberately NOT the connection state either, which is the other way to
     * get this wrong. The protocol client stays connected as an available player
     * after the music moves to another speaker, so a connection-based answer
     * resumes a phone that is no longer playing and two speakers play at once.
     * Selecting another player does not pause the one being left, so nothing else
     * catches it.
     */
    @Test
    fun `a phone that has handed the music to another speaker is not resumed`() {
        val outcome = AudioFocusPolicy.onFocusGain(
            resumeOwed = true,
            localOutputStillStreaming = false
        )
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.IGNORE)
    }

    /** A gain with nothing owed must not start playback of its own accord. */
    @Test
    fun `a gain with nothing owed starts nothing`() {
        val outcome = AudioFocusPolicy.onFocusGain(
            resumeOwed = false,
            localOutputStillStreaming = true
        )
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.IGNORE)
    }
}
