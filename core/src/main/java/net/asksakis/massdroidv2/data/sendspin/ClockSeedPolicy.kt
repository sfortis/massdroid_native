package net.asksakis.massdroidv2.data.sendspin

/** How the clock filter is seeded when a synced session starts with an earlier offset in hand. */
data class ClockSeed(val offsetUs: Long, val covariance: Double)

/**
 * Decide how much to trust the offset left over from an earlier synced session.
 *
 * The offset is `server clock minus System.nanoTime()`, and `System.nanoTime()` is
 * CLOCK_MONOTONIC, which stops while the phone is suspended. An offset measured before
 * a nap is therefore wrong by exactly the time slept: on 2026-09-16 a phone rejoined a
 * group 74 minutes after its last sample, the seed was 870 s stale, it was trusted at a
 * 3 ms covariance, and the output sat silent for the whole track because every frame
 * was scheduled fourteen minutes into the future while the filter crawled toward the
 * truth. CLOCK_BOOTTIME keeps counting through suspend, so the difference between the
 * two clocks measured at the last sample and now IS the slept time, and the seed can be
 * corrected by it exactly. Trust it tightly only when it is both recent and unpaused.
 */
object ClockSeedPolicy {
    /** About 3 ms: lets the playback-start gate pass at once on a genuine quick rejoin. */
    const val TIGHT_COVARIANCE = 9_000_000.0

    /** About 31 ms: a hint that fresh samples override within three or four exchanges. */
    const val WIDE_COVARIANCE = 1_000_000_000.0

    /** A prior older than this has drifted too far (tens of ppm over minutes) to be tight. */
    const val RECENT_PRIOR_US = 60_000_000L

    /** Below this the two clocks merely jittered; above it the phone actually slept. */
    const val PAUSE_TOLERANCE_US = 5_000L

    fun seedForRejoin(
        previousOffsetUs: Long,
        monotonicPauseUs: Long,
        sinceLastSampleUs: Long,
        priorSamples: Int,
        priorErrorUs: Long
    ): ClockSeed? {
        if (previousOffsetUs == 0L) return null
        val corrected = previousOffsetUs + monotonicPauseUs
        val priorConverged = priorSamples >= MIN_CONVERGED_SAMPLES && priorErrorUs in 1L..MAX_CONVERGED_ERROR_US
        val recent = sinceLastSampleUs in 0L..RECENT_PRIOR_US
        val unpaused = kotlin.math.abs(monotonicPauseUs) <= PAUSE_TOLERANCE_US
        val covariance = if (priorConverged && recent && unpaused) TIGHT_COVARIANCE else WIDE_COVARIANCE
        return ClockSeed(corrected, covariance)
    }

    /**
     * Whether the first samples after a seed contradict it so badly that the seed must go.
     *
     * The filter has no outlier recovery until its hundredth sample (a port detail that
     * stays as it is), and even a WIDE seed only averages a wrong prior down: 870 s went
     * to 17 s on the first sample and then halved per sample, minutes of silence either
     * way. A seed that is off by more than [SEED_REJECT_US] on any of its first samples is
     * not a slightly stale prior, it is a different clock (a server restart, a nap the
     * pause measurement missed), so the filter restarts from that sample instead.
     */
    fun seedRejected(samplesSinceSeedIncluded: Int, residualUs: Long): Boolean =
        samplesSinceSeedIncluded in FIRST_SEEDED_SAMPLE..LAST_CHECKED_SAMPLE &&
            kotlin.math.abs(residualUs) > SEED_REJECT_US

    /** Half a second: far beyond any drift a real prior can accumulate, well inside any nap. */
    const val SEED_REJECT_US = 500_000L

    private const val MIN_CONVERGED_SAMPLES = 8
    private const val MAX_CONVERGED_ERROR_US = 2_000L

    /** softReset seeds the count at 2, so the first real sample after a seed is the third. */
    private const val FIRST_SEEDED_SAMPLE = 3
    private const val LAST_CHECKED_SAMPLE = 8
}
