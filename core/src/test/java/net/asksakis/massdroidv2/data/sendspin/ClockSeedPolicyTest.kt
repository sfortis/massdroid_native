package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the 2026-09-16 silent rejoin: a 74 minute old offset, 870 s stale because the
 * monotonic clock had stopped while the phone slept, was seeded at a 3 ms covariance.
 */
class ClockSeedPolicyTest {

    private val prior = 134_084_291_254L

    @Test
    fun `a quick unpaused rejoin keeps the converged offset tight`() {
        val seed = ClockSeedPolicy.seedForRejoin(prior, monotonicPauseUs = 200, sinceLastSampleUs = 4_000_000, 20, 900)
        assertThat(seed).isEqualTo(ClockSeed(prior + 200, ClockSeedPolicy.TIGHT_COVARIANCE))
    }

    @Test
    fun `time slept is added to the offset and the seed is no longer trusted`() {
        val slept = 870_000_000L
        val seed = ClockSeedPolicy.seedForRejoin(prior, slept, sinceLastSampleUs = 74L * 60 * 1_000_000, 20, 900)
        assertThat(seed).isEqualTo(ClockSeed(prior + slept, ClockSeedPolicy.WIDE_COVARIANCE))
    }

    @Test
    fun `an old prior is wide even without a pause`() {
        val seed = ClockSeedPolicy.seedForRejoin(prior, 0, sinceLastSampleUs = 10L * 60 * 1_000_000, 20, 900)
        assertThat(seed?.covariance).isEqualTo(ClockSeedPolicy.WIDE_COVARIANCE)
    }

    @Test
    fun `a prior that never converged is wide`() {
        val seed = ClockSeedPolicy.seedForRejoin(prior, 0, sinceLastSampleUs = 1_000_000, priorSamples = 3, priorErrorUs = 13_000)
        assertThat(seed?.covariance).isEqualTo(ClockSeedPolicy.WIDE_COVARIANCE)
    }

    @Test
    fun `no prior means no seed`() {
        assertThat(ClockSeedPolicy.seedForRejoin(0, 0, 0, 0, 0)).isNull()
    }

    @Test
    fun `a first sample far from the seed rejects it`() {
        assertThat(ClockSeedPolicy.seedRejected(samplesSinceSeedIncluded = 3, residualUs = 870_000_000)).isTrue()
        assertThat(ClockSeedPolicy.seedRejected(samplesSinceSeedIncluded = 3, residualUs = -600_000)).isTrue()
    }

    @Test
    fun `ordinary residuals and later samples never reject`() {
        assertThat(ClockSeedPolicy.seedRejected(3, 40_000)).isFalse()
        assertThat(ClockSeedPolicy.seedRejected(50, 870_000_000)).isFalse()
        assertThat(ClockSeedPolicy.seedRejected(1, 870_000_000)).isFalse()
    }
}
