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

    private const val MIN_CONVERGED_SAMPLES = 8
    private const val MAX_CONVERGED_ERROR_US = 2_000L
}
