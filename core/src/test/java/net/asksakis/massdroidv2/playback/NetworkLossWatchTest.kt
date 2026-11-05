package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

/**
 * A lost default network counts only when nothing has replaced it after the grace, so a
 * handover that reports the old network lost first does not cut a socket that survives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkLossWatchTest {

    private val scope = TestScope()
    private var defaultNetwork = false
    private var confirmed = 0
    private val watch = NetworkLossWatch(scope, hasDefaultNetwork = { defaultNetwork }, onLossConfirmed = { confirmed++ })

    private fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test
    fun `a loss nothing replaces is confirmed after the grace`() {
        watch.onLost()

        advance(NetworkLossWatch.GRACE_MS + 1L)

        assertThat(confirmed).isEqualTo(1)
    }

    @Test
    fun `a loss is not confirmed before the grace is over`() {
        watch.onLost()

        advance(NetworkLossWatch.GRACE_MS - 1L)

        assertThat(confirmed).isEqualTo(0)
    }

    /** The handover case: the next network arrives within the grace. */
    @Test
    fun `a network arriving within the grace cancels the loss`() {
        watch.onLost()
        advance(500L)

        watch.cancel()
        advance(NetworkLossWatch.GRACE_MS * 2)

        assertThat(confirmed).isEqualTo(0)
    }

    /** A default network present when the grace ends, even if its callback was missed. */
    @Test
    fun `a default network present at the end of the grace is not a loss`() {
        watch.onLost()
        defaultNetwork = true

        advance(NetworkLossWatch.GRACE_MS + 1L)

        assertThat(confirmed).isEqualTo(0)
    }

    @Test
    fun `a second loss within the grace starts it again and confirms once`() {
        watch.onLost()
        advance(1_500L)
        watch.onLost()

        advance(1_000L)
        assertThat(confirmed).isEqualTo(0)

        advance(NetworkLossWatch.GRACE_MS)
        assertThat(confirmed).isEqualTo(1)
    }
}
