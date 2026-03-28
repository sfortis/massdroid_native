package net.asksakis.massdroidv2.data.sendspin

import android.util.Log

class ClockSynchronizer {

    companion object {
        private const val TAG = "ClockSync"
        private const val INITIAL_SYNC_INTERVAL_MS = 300L
        private const val MAX_SYNC_INTERVAL_MS = 3000L
        private const val FAST_SYNC_SAMPLES = 20 // Fast sync for first N samples, then back off
    }

    // Kalman filter state
    private var estimatedOffset: Double = 0.0
    private var estimateError: Double = 1_000_000.0 // uncertainty in microseconds
    private val processNoise: Double = 5_000.0
    private val minimumMeasurementNoise: Double = 2_500.0

    private var synced = false
    private var sampleCount = 0

    var currentSyncIntervalMs: Long = INITIAL_SYNC_INTERVAL_MS
        private set

    fun nowMonotonicUs(): Long = System.nanoTime() / 1000

    /**
     * NTP-style 3-timestamp calculation.
     */
    @Synchronized
    fun processTimeResponse(
        clientTransmittedUs: Long,
        serverReceivedUs: Long,
        serverTransmittedUs: Long,
        clientReceivedUs: Long
    ) {
        val rtt = (clientReceivedUs - clientTransmittedUs) - (serverTransmittedUs - serverReceivedUs)
        val measuredOffset = ((serverReceivedUs - clientTransmittedUs) +
                (serverTransmittedUs - clientReceivedUs)) / 2.0
        val maxErrorUs = (rtt.coerceAtLeast(0L) / 2.0).coerceAtLeast(1.0)
        val measurementNoise = maxOf(minimumMeasurementNoise, maxErrorUs)

        if (!synced) {
            estimatedOffset = measuredOffset
            estimateError = measurementNoise
            sampleCount = 1
            synced = true
            currentSyncIntervalMs = INITIAL_SYNC_INTERVAL_MS
            Log.d(
                TAG,
                "Sync #$sampleCount seeded offset=${estimatedOffset.toLong()}us " +
                    "error=${estimateError.toLong()} rtt=${rtt}us interval=${currentSyncIntervalMs}ms"
            )
            return
        }

        // Kalman predict
        val predictedError = estimateError + processNoise

        // Kalman update
        val kalmanGain = predictedError / (predictedError + measurementNoise)
        estimatedOffset += kalmanGain * (measuredOffset - estimatedOffset)
        estimateError = (1.0 - kalmanGain) * predictedError

        sampleCount++

        // Adaptive sync interval: fast for initial samples, then back off
        currentSyncIntervalMs = if (sampleCount < FAST_SYNC_SAMPLES) {
            INITIAL_SYNC_INTERVAL_MS
        } else {
            // Gradually increase interval
            (currentSyncIntervalMs + 200).coerceAtMost(MAX_SYNC_INTERVAL_MS)
        }

        if (sampleCount <= 5 || sampleCount % 50 == 0) {
            Log.d(TAG, "Sync #$sampleCount offset=${estimatedOffset.toLong()}us " +
                    "error=${estimateError.toLong()} rtt=${rtt}us interval=${currentSyncIntervalMs}ms")
        }
    }

    @Synchronized
    fun serverToLocalUs(serverTimestampUs: Long): Long {
        return serverTimestampUs - estimatedOffset.toLong()
    }

    @Synchronized
    fun currentOffsetUs(): Long = estimatedOffset.toLong()

    @Synchronized
    fun isSynced(): Boolean = synced

    @Synchronized
    fun currentSampleCount(): Int = sampleCount

    @Synchronized
    fun reset() {
        estimatedOffset = 0.0
        estimateError = 1_000_000.0
        synced = false
        sampleCount = 0
        currentSyncIntervalMs = INITIAL_SYNC_INTERVAL_MS
    }
}
