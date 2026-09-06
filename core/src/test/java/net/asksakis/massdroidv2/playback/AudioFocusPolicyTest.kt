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

    /** The gain that ends an interruption starts what the interruption stopped. */
    @Test
    fun `a gain resumes the playback an interruption owed`() {
        val outcome = AudioFocusPolicy.onFocusGain(resumeOwed = true, localPlayerSelected = true)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.RESUME)
    }

    /**
     * Moving to another speaker during the interruption means the music is not
     * wanted back on this phone when it ends.
     */
    @Test
    fun `a gain does not bring the music back to a phone that is no longer selected`() {
        val outcome = AudioFocusPolicy.onFocusGain(resumeOwed = true, localPlayerSelected = false)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.IGNORE)
    }

    /** A gain with nothing owed must not start playback of its own accord. */
    @Test
    fun `a gain with nothing owed starts nothing`() {
        val outcome = AudioFocusPolicy.onFocusGain(resumeOwed = false, localPlayerSelected = true)
        assertThat(outcome).isEqualTo(AudioFocusPolicy.FocusGain.IGNORE)
    }
}
