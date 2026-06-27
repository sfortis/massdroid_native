package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins the softened loved-track injection quota (no floor, Discovery-tapered). */
class SeedTrackInjectCountTest {

    @Test
    fun `high discovery tapers injection to zero`() {
        assertThat(seedTrackInjectCount(discovery = 1.0, target = 40)).isEqualTo(0)
    }

    @Test
    fun `zero discovery reserves up to the max fraction`() {
        // (1 - 0) * 0.4 * 40 = 16
        assertThat(seedTrackInjectCount(discovery = 0.0, target = 40)).isEqualTo(16)
    }

    @Test
    fun `mid discovery reserves a proportional slice`() {
        // (1 - 0.5) * 0.4 * 40 = 8
        assertThat(seedTrackInjectCount(discovery = 0.5, target = 40)).isEqualTo(8)
        // (1 - 0.875) * 0.4 * 40 = 2 (the value observed in the 5-run test)
        assertThat(seedTrackInjectCount(discovery = 0.875, target = 40)).isEqualTo(2)
    }

    @Test
    fun `out-of-range discovery never yields a negative count`() {
        assertThat(seedTrackInjectCount(discovery = 1.5, target = 40)).isEqualTo(0)
    }
}
