package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

/**
 * The rc1 metadata role's scheduled updates: a future update waits for its time, at most one
 * waits, and a newer update replaces the waiting one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledMetadataTest {

    private val scope = TestScope()
    private val published = mutableListOf<String?>()
    private val scheduled = ScheduledMetadata(scope) { published += it.title }

    private fun titled(title: String) = ServerMetadataPayload(title = title)

    @Test
    fun `an update that is due is published at once`() {
        scheduled.offer(titled("now"), delayMs = 0)

        assertThat(published).containsExactly("now")
    }

    @Test
    fun `a future update is published at its time and not before`() {
        scheduled.offer(titled("next track"), delayMs = 2_000)

        scope.advanceTimeBy(1_999)
        scope.runCurrent()
        assertThat(published).isEmpty()

        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertThat(published).containsExactly("next track")
    }

    @Test
    fun `a newer update replaces the pending one`() {
        scheduled.offer(titled("stale"), delayMs = 2_000)
        scheduled.offer(titled("fresh"), delayMs = 3_000)

        scope.advanceTimeBy(5_000)
        scope.runCurrent()

        assertThat(published).containsExactly("fresh")
    }

    @Test
    fun `an update due now also drops the pending one`() {
        scheduled.offer(titled("later"), delayMs = 2_000)
        scheduled.offer(titled("now"), delayMs = 0)

        scope.advanceTimeBy(5_000)
        scope.runCurrent()

        assertThat(published).containsExactly("now")
    }

    @Test
    fun `a cancelled update is never published`() {
        scheduled.offer(titled("gone"), delayMs = 2_000)
        scheduled.cancel()

        scope.advanceTimeBy(5_000)
        scope.runCurrent()

        assertThat(published).isEmpty()
    }
}
