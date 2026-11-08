package net.asksakis.massdroidv2.domain.party

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The Party screen closes after a minute without a touch. */
@OptIn(ExperimentalCoroutinesApi::class)
class PartyIdleTimerTest {

    private fun TestScope.timer() = PartyIdleTimer(now = { testScheduler.currentTime })

    @Test
    fun `an untouched screen times out after a minute`() = runTest {
        val timer = timer()
        val idle = async { timer.awaitIdle() }

        advanceTimeBy(PartyIdleTimer.IDLE_DISMISS_MS - 1)
        runCurrent()
        assertThat(idle.isCompleted).isFalse()

        advanceTimeBy(1)
        runCurrent()
        assertThat(idle.isCompleted).isTrue()
    }

    @Test
    fun `every touch starts the minute again`() = runTest {
        val timer = timer()
        val idle = async { timer.awaitIdle() }

        advanceTimeBy(50_000)
        timer.touch()
        advanceTimeBy(50_000)
        timer.touch()
        advanceTimeBy(59_999)
        runCurrent()
        assertThat(idle.isCompleted).isFalse()

        advanceTimeBy(1)
        runCurrent()
        assertThat(idle.isCompleted).isTrue()
    }

    /** A screen that comes back to the front gets a full minute, however long it was away. */
    @Test
    fun `waiting again starts from the full minute`() = runTest {
        val timer = timer()
        advanceTimeBy(PartyIdleTimer.IDLE_DISMISS_MS * 5)
        assertThat(timer.remainingMs()).isEqualTo(0L)

        val idle = async { timer.awaitIdle() }
        runCurrent()
        assertThat(idle.isCompleted).isFalse()
        assertThat(timer.remainingMs()).isEqualTo(PartyIdleTimer.IDLE_DISMISS_MS)
    }
}
