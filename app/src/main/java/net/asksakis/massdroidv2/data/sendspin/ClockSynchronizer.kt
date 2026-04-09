package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Two-dimensional Kalman filter for NTP-style time synchronization.
 * Port of the reference sendspin-js TypeScript implementation (time-filter.ts).
 *
 * Tracks both clock offset and drift rate between client and server.
 * Adaptive forgetting helps recover quickly from network disruptions.
 * Drift compensation is gated on statistical significance to avoid
 * amplifying noise when drift is too small to measure reliably.
 */
class ClockSynchronizer {

    companion object {
        private const val TAG = "ClockSync"
        private const val INITIAL_SYNC_INTERVAL_MS = 300L
        private const val MAX_SYNC_INTERVAL_MS = 3000L
        private const val FAST_SYNC_SAMPLES = 50

        // Match JS reference: sendspin-js/src/time-filter.ts
        private const val ADAPTIVE_FORGETTING_CUTOFF = 2.0
        private const val DEFAULT_OFFSET_PROCESS_STD_DEV = 0.01
        private const val DEFAULT_FORGET_FACTOR = 1.1
        private const val DRIFT_SIGNIFICANCE_THRESHOLD = 2.0
    }

    // Filter state
    private var offset = 0.0
    private var drift = 0.0
    private var offsetCovariance = Double.MAX_VALUE
    private var offsetDriftCovariance = 0.0
    private var driftCovariance = 0.0
    private var lastUpdateUs = 0L
    private var count = 0

    // Drift significance gating (from JS reference)
    private var useDrift = false
    private val driftSignificanceThresholdSquared =
        DRIFT_SIGNIFICANCE_THRESHOLD * DRIFT_SIGNIFICANCE_THRESHOLD

    // Noise parameters
    private val offsetProcessVariance =
        DEFAULT_OFFSET_PROCESS_STD_DEV * DEFAULT_OFFSET_PROCESS_STD_DEV
    private val forgetVarianceFactor = DEFAULT_FORGET_FACTOR * DEFAULT_FORGET_FACTOR

    // Snapshot for lock-free reads
    @Volatile private var currentOffset = 0.0
    @Volatile private var currentDrift = 0.0
    @Volatile private var currentUseDrift = false
    @Volatile private var currentLastUpdate = 0L

    var currentSyncIntervalMs: Long = INITIAL_SYNC_INTERVAL_MS
        private set

    fun nowMonotonicUs(): Long = System.nanoTime() / 1000

    @Synchronized
    fun processTimeResponse(
        clientTransmittedUs: Long,
        serverReceivedUs: Long,
        serverTransmittedUs: Long,
        clientReceivedUs: Long
    ) {
        val rtt = (clientReceivedUs - clientTransmittedUs) -
            (serverTransmittedUs - serverReceivedUs)
        val measurement = ((serverReceivedUs - clientTransmittedUs) +
            (serverTransmittedUs - clientReceivedUs)) / 2.0
        val maxError = (rtt.coerceAtLeast(0L) / 2.0).coerceAtLeast(1.0)
        val measurementVariance = maxError * maxError

        if (clientReceivedUs == lastUpdateUs) return

        val dt = (clientReceivedUs - lastUpdateUs).toDouble()
        lastUpdateUs = clientReceivedUs

        // First measurement: seed offset
        if (count <= 0) {
            count = 1
            offset = measurement
            offsetCovariance = measurementVariance
            drift = 0.0
            useDrift = false
            publishState()
            currentSyncIntervalMs = INITIAL_SYNC_INTERVAL_MS
            Log.d(TAG, "Sync #$count seeded offset=${offset.toLong()}us rtt=${rtt}us")
            return
        }

        // Second measurement: initial drift estimate
        if (count == 1) {
            count = 2
            drift = (measurement - offset) / dt
            offset = measurement
            driftCovariance = (offsetCovariance + measurementVariance) / dt
            offsetCovariance = measurementVariance
            useDrift = false
            publishState()
            Log.d(TAG, "Sync #$count drift=${"%.6f".format(drift)} offset=${offset.toLong()}us rtt=${rtt}us")
            return
        }

        // Kalman Prediction
        val predictedOffset = offset + drift * dt
        val dtSquared = dt * dt

        var newOffsetCovariance = offsetCovariance +
            2 * offsetDriftCovariance * dt +
            driftCovariance * dtSquared +
            dt * offsetProcessVariance
        var newOffsetDriftCovariance = offsetDriftCovariance + driftCovariance * dt
        var newDriftCovariance = driftCovariance

        // Adaptive forgetting for large residuals (matches JS reference)
        val residual = measurement - predictedOffset
        val maxResidualCutoff = maxError * ADAPTIVE_FORGETTING_CUTOFF

        if (count < 100) {
            count++
        } else if (kotlin.math.abs(residual) > maxResidualCutoff) {
            // Large prediction error: apply forgetting to ALL covariances
            newDriftCovariance *= forgetVarianceFactor
            newOffsetDriftCovariance *= forgetVarianceFactor
            newOffsetCovariance *= forgetVarianceFactor
        }

        // Kalman Update
        val uncertainty = 1.0 / (newOffsetCovariance + measurementVariance)
        val offsetGain = newOffsetCovariance * uncertainty
        val driftGain = newOffsetDriftCovariance * uncertainty

        offset = predictedOffset + offsetGain * residual
        drift += driftGain * residual

        driftCovariance = newDriftCovariance - driftGain * newOffsetDriftCovariance
        offsetDriftCovariance = newOffsetDriftCovariance - driftGain * newOffsetCovariance
        offsetCovariance = newOffsetCovariance - offsetGain * newOffsetCovariance

        // Drift significance gating (from JS reference):
        // Only use drift when it's statistically significant relative to its uncertainty
        val driftSquared = drift * drift
        useDrift = driftSquared > driftSignificanceThresholdSquared * driftCovariance

        if (count >= 100) count++  // only increment past 100 (2..99 handled above)
        publishState()

        // Adaptive sync interval
        currentSyncIntervalMs = if (count < FAST_SYNC_SAMPLES) {
            INITIAL_SYNC_INTERVAL_MS
        } else {
            (currentSyncIntervalMs + 200).coerceAtMost(MAX_SYNC_INTERVAL_MS)
        }

        if (count <= 5 || count % 50 == 0) {
            Log.d(TAG, "Sync #$count offset=${offset.toLong()}us drift=${"%.6f".format(drift)} " +
                "useDrift=$useDrift error=${errorUs()}us rtt=${rtt}us interval=${currentSyncIntervalMs}ms")
        }
    }

    private fun publishState() {
        currentOffset = offset
        currentDrift = drift
        currentUseDrift = useDrift
        currentLastUpdate = lastUpdateUs
    }

    /**
     * Convert server timestamp to local client time.
     * Algebraic inverse matching JS reference computeClientTime():
     *   T_client = (T_server - offset + drift * last_update) / (1 + drift)
     */
    fun serverToLocalUs(serverTimestampUs: Long): Long {
        val effectiveDrift = if (currentUseDrift) currentDrift else 0.0
        return round(
            (serverTimestampUs - currentOffset +
                effectiveDrift * currentLastUpdate) / (1.0 + effectiveDrift)
        ).toLong()
    }

    /** Convert local client time to server timestamp. Inverse of serverToLocalUs. */
    fun localToServerUs(localTimestampUs: Long): Long {
        val effectiveDrift = if (currentUseDrift) currentDrift else 0.0
        return round(
            localTimestampUs * (1.0 + effectiveDrift) + currentOffset -
                effectiveDrift * currentLastUpdate
        ).toLong()
    }

    /** Current offset including drift extrapolation. */
    fun currentOffsetUs(): Long {
        val effectiveDrift = if (currentUseDrift) currentDrift else 0.0
        val dt = (nowMonotonicUs() - currentLastUpdate).toDouble()
        return round(currentOffset + effectiveDrift * dt).toLong()
    }

    /** Estimation error (standard deviation) in microseconds. */
    fun errorUs(): Long = round(sqrt(offsetCovariance.coerceAtLeast(0.0))).toLong()

    /** Math validity: at least 1 measurement processed. */
    @Synchronized
    fun isSynced(): Boolean = count >= 1 && offsetCovariance < Double.MAX_VALUE

    /**
     * Safe to start speakers in sync: filter has converged enough.
     * Requires multiple low-RTT samples with reasonable error.
     */
    @Synchronized
    fun isReadyForPlaybackStart(): Boolean =
        count >= 8 && errorUs() <= 5_000

    @Synchronized
    fun currentSampleCount(): Int = count

    @Synchronized
    fun softReset(previousOffsetUs: Long, preserveDrift: Boolean = true) {
        val prevDrift = drift
        val prevUseDrift = useDrift
        reset()
        if (previousOffsetUs != 0L) {
            offset = previousOffsetUs.toDouble()
            if (preserveDrift) {
                drift = prevDrift
                useDrift = prevUseDrift
            }
            offsetCovariance = 50_000.0  // moderate uncertainty, not infinite
            count = 2  // skip initialization phase
            publishState()
            Log.d(TAG, "Soft reset: seeded offset=${previousOffsetUs}us " +
                "drift=${"%.6f".format(drift)} useDrift=$useDrift preserveDrift=$preserveDrift")
        }
    }

    @Synchronized
    fun reset() {
        offset = 0.0
        drift = 0.0
        offsetCovariance = Double.MAX_VALUE
        offsetDriftCovariance = 0.0
        driftCovariance = 0.0
        lastUpdateUs = 0L
        count = 0
        useDrift = false
        currentOffset = 0.0
        currentDrift = 0.0
        currentUseDrift = false
        currentLastUpdate = 0L
        currentSyncIntervalMs = INITIAL_SYNC_INTERVAL_MS
    }
}
