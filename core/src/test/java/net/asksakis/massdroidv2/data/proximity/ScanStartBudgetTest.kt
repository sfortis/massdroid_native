package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the scan-start budget to the platform rule it mirrors: Android refuses, silently,
 * the sixth scan start within thirty seconds. We stop one earlier.
 */
class ScanStartBudgetTest {

    @Test
    fun `the first four starts in a window are granted at once`() {
        val budget = ScanStartBudget()
        repeat(4) { i -> assertThat(budget.tryAcquire(nowMs = 1_000L * i)).isTrue() }
        assertThat(budget.tryAcquire(nowMs = 4_000L)).isFalse()
    }

    @Test
    fun `the fifth start waits until the oldest one is thirty seconds old`() {
        val budget = ScanStartBudget()
        listOf(0L, 5_000L, 10_000L, 15_000L).forEach { assertThat(budget.tryAcquire(it)).isTrue() }

        assertThat(budget.msUntilAllowed(nowMs = 20_000L)).isEqualTo(10_000L)
        assertThat(budget.msUntilAllowed(nowMs = 29_999L)).isEqualTo(1L)
        assertThat(budget.msUntilAllowed(nowMs = 30_000L)).isEqualTo(0L)
    }

    @Test
    fun `starts that aged out no longer count`() {
        val budget = ScanStartBudget()
        listOf(0L, 1_000L, 2_000L, 3_000L).forEach { assertThat(budget.tryAcquire(it)).isTrue() }

        // At 32 s the first three have aged out; only the 3 s start remains.
        assertThat(budget.msUntilAllowed(nowMs = 32_000L)).isEqualTo(0L)
        repeat(3) { assertThat(budget.tryAcquire(32_000L)).isTrue() }
        // Four again: the 3 s start plus three new ones. The 3 s start ages out at 33 s.
        assertThat(budget.msUntilAllowed(nowMs = 32_500L)).isEqualTo(500L)
        assertThat(budget.tryAcquire(nowMs = 32_500L)).isFalse()
        assertThat(budget.tryAcquire(nowMs = 33_000L)).isTrue()
    }

    @Test
    fun `two callers racing for the last freed slot get one yes and one no`() {
        val budget = ScanStartBudget()
        listOf(0L, 1_000L, 2_000L, 3_000L).forEach { assertThat(budget.tryAcquire(it)).isTrue() }
        // Both waited on msUntilAllowed and wake at the same instant, when exactly one slot frees.
        assertThat(budget.msUntilAllowed(30_000L)).isEqualTo(0L)
        val first = budget.tryAcquire(30_000L)
        val second = budget.tryAcquire(30_000L)
        assertThat(listOf(first, second)).containsExactly(true, false)
    }

    @Test
    fun `a calibration of ten sessions five seconds apart is refused from the fifth until the first ages out`() {
        val budget = ScanStartBudget()
        val granted = (0 until 10).map { session -> budget.tryAcquire(session * 5_000L) }
        // Sessions at 0, 5, 10, 15 s are granted; 20 and 25 s are refused; at 30 s the 0 s start
        // has aged out and one slot frees per 5 s from then on.
        assertThat(granted).containsExactly(true, true, true, true, false, false, true, true, true, true).inOrder()
    }
}
