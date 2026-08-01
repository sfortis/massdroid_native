package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins what counts as having listened to a track.
 *
 * The rule guards the positive half of the learning signal, and getting it
 * wrong is invisible: a mistake here does not crash or look wrong on screen, it
 * quietly teaches the recommendation engine that the listener enjoyed something
 * they walked away from.
 *
 * It became load-bearing when `previous` stopped filing a skip. Pressing back
 * used to record the harshest negative signal the engine has against a track
 * that had only just started, so that was removed - but the move must still be
 * marked, because a track abandoned after a minute would otherwise be recorded
 * as a genuine play.
 */
class PlayHistoryRecordingRuleTest {

    private fun recorded(
        listenedMs: Long,
        manualSkip: Boolean = false,
        queueReplacement: Boolean = false,
    ) = shouldRecordPlay(listenedMs, manualSkip, queueReplacement)

    @Test
    fun `a track heard past half a minute counts`() {
        assertThat(recorded(45_000)).isTrue()
    }

    @Test
    fun `anything shorter was passed through, not listened to`() {
        assertThat(recorded(29_000)).isFalse()
        assertThat(recorded(0)).isFalse()
    }

    @Test
    fun `the threshold is exclusive`() {
        assertThat(recorded(30_000)).isFalse()
        assertThat(recorded(30_001)).isTrue()
    }

    @Test
    fun `leaving by hand never counts, however long it played`() {
        // This is what `previous` relies on. It files no opinion about the
        // track, but it does mark the move - and without that mark, going back
        // after two minutes would be recorded as enjoying it.
        assertThat(recorded(120_000, manualSkip = true)).isFalse()
    }

    @Test
    fun `a queue replaced under the listener never counts`() {
        // Starting a Smart Mix swaps the queue out; whatever was playing was
        // not chosen away from, so it is neither a play nor a rejection.
        assertThat(recorded(120_000, queueReplacement = true)).isFalse()
    }

    @Test
    fun `a long listen that was also skipped still does not count`() {
        assertThat(recorded(300_000, manualSkip = true, queueReplacement = true)).isFalse()
    }
}
