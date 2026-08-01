package net.asksakis.massdroidv2.domain.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how much of a held volume key actually reaches the server.
 *
 * Worth pinning because the app receives the same presses through two entry
 * points and only one of them used to be paced. Measured on a device on
 * 2026-08-01, with the app in the background: 18 `group_volume` commands in
 * 700 ms, taking a sync group from 36 to 0 and leaving its only member muted.
 * The foreground path, holding its own copy of this logic, behaved correctly at
 * the same moment. Both now go through the pacer this test covers.
 */
class VolumeSendPacerTest {

    @Test
    fun `the first step is never held back`() {
        assertThat(VolumeSendPacer().tryAcquire(nowMs = 0)).isTrue()
    }

    @Test
    fun `a fresh press right after a send still goes out immediately`() {
        // A pacer is per-hold, not global: a new tap must feel instant.
        assertThat(VolumeSendPacer().tryAcquire(nowMs = 5_000)).isTrue()
    }

    @Test
    fun `steps inside the window are held back`() {
        val pacer = VolumeSendPacer(throttleMs = 120)
        assertThat(pacer.tryAcquire(nowMs = 0)).isTrue()
        assertThat(pacer.tryAcquire(nowMs = 50)).isFalse()
        assertThat(pacer.tryAcquire(nowMs = 100)).isFalse()
        assertThat(pacer.tryAcquire(nowMs = 120)).isTrue()
    }

    @Test
    fun `a flush that bypassed the throttle still restarts the window`() {
        val pacer = VolumeSendPacer(throttleMs = 120)
        pacer.markSent(nowMs = 1_000)
        assertThat(pacer.tryAcquire(nowMs = 1_050)).isFalse()
        assertThat(pacer.tryAcquire(nowMs = 1_120)).isTrue()
    }

    @Test
    fun `a three second hold sends far fewer commands than it receives`() {
        // Replays the measured repeat stream: 64 events, one every 50 ms.
        val pacer = VolumeSendPacer(throttleMs = 120)
        val received = 64
        var sent = 0
        for (repeat in 0 until received) {
            if (pacer.tryAcquire(nowMs = repeat * 50L)) sent++
        }
        assertThat(sent).isLessThan(received / 2)
        // Still responsive: roughly one command per throttle window.
        assertThat(sent).isAtLeast(20)
    }

    @Test
    fun `the burst that dropped a group from 36 to 0 would now be paced`() {
        // The exact measured burst: 18 steps arriving over 700 ms.
        val pacer = VolumeSendPacer(throttleMs = 120)
        val sent = (0 until 18).count { pacer.tryAcquire(nowMs = it * 50L) }
        assertThat(sent).isAtMost(8)
    }
}
