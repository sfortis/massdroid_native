package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

/**
 * A stream stays active through a dropped connection while the audio held at the drop plays,
 * and ends when that audio has played out unless a stream came back first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamDropHoldTest {

    private val scope = TestScope()
    private var playedOut = 0
    private val hold = StreamDropHold(scope) { playedOut++ }

    private fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test
    fun `the stream stays active while the buffered audio plays`() {
        hold.onLoss(playableMs = 28_000L)

        advance(28_000L + StreamDropHold.PLAYED_OUT_GRACE_MS - 1L)

        assertThat(playedOut).isEqualTo(0)
    }

    @Test
    fun `the stream ends once the buffered audio has played out`() {
        hold.onLoss(playableMs = 28_000L)

        advance(28_000L + StreamDropHold.PLAYED_OUT_GRACE_MS + 1L)

        assertThat(playedOut).isEqualTo(1)
    }

    @Test
    fun `a stream that came back cancels the hold`() {
        hold.onLoss(playableMs = 28_000L)
        advance(10_000L)

        hold.cancel()
        advance(60_000L)

        assertThat(playedOut).isEqualTo(0)
    }

    @Test
    fun `the retries of one drop keep the first hold's time`() {
        hold.onLoss(playableMs = 28_000L)
        advance(20_000L)
        // A failed reconnect reports the loss again; a restarted hold would end at 23 s.
        hold.onLoss(playableMs = 2_000L)

        advance(3_100L)
        assertThat(playedOut).isEqualTo(0)

        // The first hold ends at 28 s plus the grace.
        advance(6_000L)
        assertThat(playedOut).isEqualTo(1)
        advance(60_000L)
        assertThat(playedOut).isEqualTo(1)
    }

    @Test
    fun `an empty buffer ends the stream after the grace alone`() {
        hold.onLoss(playableMs = -5L)

        advance(StreamDropHold.PLAYED_OUT_GRACE_MS + 1L)

        assertThat(playedOut).isEqualTo(1)
    }

    @Test
    fun `a later drop after one has played out holds again`() {
        hold.onLoss(playableMs = 1_000L)
        advance(5_000L)
        hold.onLoss(playableMs = 1_000L)
        advance(5_000L)

        assertThat(playedOut).isEqualTo(2)
    }
}
