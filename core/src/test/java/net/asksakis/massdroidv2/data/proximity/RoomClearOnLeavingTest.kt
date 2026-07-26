package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression lock for "the room did not clear after I left the house".
 *
 * The scanner-warmup grace used to stamp `lastScanActivityMs = now` and zero the no-match
 * streak every time it excused an empty read. That made it re-arm itself: with ~12 s
 * detection cycles and a 30 s grace, every third cycle was excused and wiped the streak,
 * so it never reached NO_MATCH_CLEAR_THRESHOLD (observed on device: max streak 3, zero
 * "left all rooms" in a full day) and the last known room stayed pinned forever.
 *
 * It only became visible once BLE scans were filtered. An unfiltered scan returned foreign
 * devices anywhere in the world, so reads were never empty, lastScanActivityMs was
 * refreshed every cycle, and this branch never ran.
 *
 * The grace must therefore be granted at most ONCE per scanner gap.
 */
class RoomClearOnLeavingTest {

    private companion object {
        const val GRACE_MS = 30_000L
        const val CYCLE_MS = 12_000L
        const val T_SCAN = 1_000_000L
    }

    @Test
    fun `first empty read after a long gap is excused`() {
        val excused = shouldExcuseAsWarmup(
            nowMs = T_SCAN + GRACE_MS + 1,
            lastScanActivityMs = T_SCAN,
            lastWarmupGraceAtMs = 0L,
            graceMs = GRACE_MS
        )
        assertThat(excused).isTrue()
    }

    @Test
    fun `grace is not granted twice for the same gap`() {
        val firstAt = T_SCAN + GRACE_MS + 1
        assertThat(
            shouldExcuseAsWarmup(firstAt, T_SCAN, 0L, GRACE_MS)
        ).isTrue()

        // Same gap, much later: must NOT be excused again, otherwise the streak never grows.
        assertThat(
            shouldExcuseAsWarmup(firstAt + 10 * GRACE_MS, T_SCAN, firstAt, GRACE_MS)
        ).isFalse()
    }

    @Test
    fun `walking away keeps counting so the streak can reach the clear threshold`() {
        // Simulate the real device cadence: one real read, then nothing but empty reads.
        var lastScanActivity = T_SCAN
        var lastGraceAt = 0L
        var streak = 0
        var excusedCount = 0

        var now = T_SCAN
        repeat(40) {
            now += CYCLE_MS
            if (shouldExcuseAsWarmup(now, lastScanActivity, lastGraceAt, GRACE_MS)) {
                lastGraceAt = now
                excusedCount++
            } else {
                streak++
            }
        }

        // Exactly one cycle excused; every other empty read counted.
        assertThat(excusedCount).isEqualTo(1)
        assertThat(streak).isEqualTo(39)
        // The old behaviour capped the streak at 3, well under the threshold of 10.
        assertThat(streak).isAtLeast(10)
        assertThat(lastScanActivity).isEqualTo(T_SCAN)
    }

    @Test
    fun `a real read re-arms the grace for the next gap`() {
        val firstGraceAt = T_SCAN + GRACE_MS + 1
        assertThat(shouldExcuseAsWarmup(firstGraceAt, T_SCAN, firstGraceAt, GRACE_MS)).isFalse()

        // Scanner delivers devices again (e.g. back home), which moves lastScanActivityMs
        // past the recorded grace: a later gap gets its own single excuse.
        val backHomeAt = firstGraceAt + 60_000
        assertThat(
            shouldExcuseAsWarmup(backHomeAt + GRACE_MS + 1, backHomeAt, firstGraceAt, GRACE_MS)
        ).isTrue()
    }

    @Test
    fun `short gaps are never excused`() {
        assertThat(
            shouldExcuseAsWarmup(T_SCAN + GRACE_MS - 1, T_SCAN, 0L, GRACE_MS)
        ).isFalse()
    }

    @Test
    fun `no excuse before the scanner has ever delivered`() {
        // lastScanActivityMs == 0: a cold start has no gap to excuse.
        assertThat(shouldExcuseAsWarmup(T_SCAN, 0L, 0L, GRACE_MS)).isFalse()
    }
}
