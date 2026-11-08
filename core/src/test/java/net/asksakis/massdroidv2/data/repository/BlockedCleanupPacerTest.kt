package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The blocked-artist cleanup runs at most once per cooldown for a queue, and a request inside
 * the cooldown runs once when it ends instead of being dropped. Dropping it let a blocked track
 * right after a freshly started album play, because the album's queue events all landed inside
 * the cooldown of the forced run that came before the play.
 */
class BlockedCleanupPacerTest {

    private val cooldown = 2_000L
    private val runs = mutableListOf<Pair<String, String>>()

    private fun TestScope.pacer() = BlockedCleanupPacer(
        scope = backgroundScope,
        cooldownMs = cooldown,
        now = { testScheduler.currentTime },
        run = { queueId, reason -> runs += queueId to reason }
    )

    @Test
    fun `the first request runs at once`() = runTest {
        pacer().request("q", "queue_update")

        assertThat(runs).containsExactly("q" to "queue_update")
    }

    @Test
    fun `a request inside the cooldown runs once the cooldown ends`() = runTest {
        val pacer = pacer()
        pacer.request("q", "filter_mode", force = true)
        advanceTimeBy(300)

        pacer.request("q", "queue_items_updated")
        runCurrent()
        assertThat(runs).hasSize(1)

        advanceTimeBy(cooldown - 300)
        runCurrent()
        assertThat(runs).containsExactly(
            "q" to "filter_mode",
            "q" to "queue_items_updated/trailing"
        ).inOrder()
    }

    @Test
    fun `several requests inside one cooldown make one trailing run`() = runTest {
        val pacer = pacer()
        pacer.request("q", "filter_mode", force = true)
        advanceTimeBy(100)
        pacer.request("q", "queue_items_updated")
        advanceTimeBy(100)
        pacer.request("q", "queue_update")
        // advanceUntilIdle does not run work in backgroundScope, so time is moved on by hand.
        advanceTimeBy(cooldown)
        runCurrent()

        assertThat(runs).hasSize(2)
    }

    @Test
    fun `a forced request runs at once inside the cooldown`() = runTest {
        val pacer = pacer()
        pacer.request("q", "queue_update")
        advanceTimeBy(100)

        pacer.request("q", "blocked_update", force = true)

        assertThat(runs.map { it.second }).containsExactly("queue_update", "blocked_update").inOrder()
    }

    @Test
    fun `each queue has its own cooldown`() = runTest {
        val pacer = pacer()
        pacer.request("a", "queue_update")

        pacer.request("b", "queue_update")

        assertThat(runs.map { it.first }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a reset drops a run that is still waiting`() = runTest {
        val pacer = pacer()
        pacer.request("q", "filter_mode", force = true)
        pacer.request("q", "queue_items_updated")

        pacer.reset()
        advanceTimeBy(cooldown)
        runCurrent()

        assertThat(runs).hasSize(1)
    }
}
