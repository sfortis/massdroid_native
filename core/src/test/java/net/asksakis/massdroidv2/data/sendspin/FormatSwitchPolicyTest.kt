package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins when a mid-stream codec change keeps the decoded audio.
 *
 * Keeping it on a real timeline break would play stale audio; dropping it on a plain
 * format switch skips 2.5 s and leaves the stream with almost no cushion.
 */
class FormatSwitchPolicyTest {

    private fun keeps(
        continuation: Boolean = true,
        configured: Boolean = true,
        receivedAny: Boolean = true,
        sameOutputShape: Boolean = true,
    ) = FormatSwitchPolicy.keepsBuffer(continuation, configured, receivedAny, sameOutputShape)

    @Test
    fun `a codec change inside a running stream keeps the buffer`() {
        assertThat(keeps()).isTrue()
    }

    @Test
    fun `a new stream rebuilds`() {
        assertThat(keeps(continuation = false)).isFalse()
    }

    @Test
    fun `an engine that was never configured rebuilds`() {
        assertThat(keeps(configured = false)).isFalse()
    }

    @Test
    fun `nothing received yet leaves nothing to keep`() {
        assertThat(keeps(receivedAny = false)).isFalse()
    }

    @Test
    fun `another sample rate or channel count rebuilds the output`() {
        assertThat(keeps(sameOutputShape = false)).isFalse()
    }

    @Test
    fun `a new format starting where the old one ended continues the timeline`() {
        // Last Opus frame at 10.000 s, 20 ms long; FLAC resumes at 10.020 s.
        assertThat(FormatSwitchPolicy.continuesTimeline(10_020_000L, 10_020_000L)).isTrue()
        assertThat(FormatSwitchPolicy.continuesTimeline(10_020_000L, 10_100_000L)).isTrue()
    }

    @Test
    fun `a new format starting elsewhere is a new timeline`() {
        assertThat(FormatSwitchPolicy.continuesTimeline(10_020_000L, 12_500_000L)).isFalse()
        assertThat(FormatSwitchPolicy.continuesTimeline(10_020_000L, 9_000_000L)).isFalse()
    }
}
