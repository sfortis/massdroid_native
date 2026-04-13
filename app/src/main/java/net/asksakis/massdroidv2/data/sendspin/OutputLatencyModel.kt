package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log

/**
 * Owns output latency measurement and acoustic correction state.
 *
 * Latency measurement:
 * - Two-phase: startup median (5 samples) then EMA steady-state.
 * - Measured via AudioTrack.getTimestamp(): framesWritten - framesAtDAC = pipeline frames.
 *
 * Acoustic correction:
 * - routeAcousticExtraUs = btRoundTrip - phoneBaseline (from calibration chirp).
 * - BT's measuredOutputLatencyUs already includes A2DP HAL pipeline overlap,
 *   so acousticExtraUs = max(0, routeAcousticExtraUs - measuredOutputLatencyUs)
 *   to avoid double-counting. Total compensation = measured + acousticExtra = max(measured, calibration).
 *
 * Thread safety: measuredOutputLatencyUs and routeAcousticExtraUs are volatile.
 * All other fields are accessed only from the playback thread.
 */
class OutputLatencyModel(private val tag: String = "AudioStream") {

    @Volatile var measuredOutputLatencyUs: Long = 0L
        private set
    @Volatile var routeAcousticExtraUs: Long = 0L

    private val startupLatencySamples = mutableListOf<Long>()
    var latencyPhaseStartup = true
        private set
    private var outputLatencyMeasureCount = 0
    private val outputLatencyTimestamp = AudioTimestamp()

    // Latency drift correction: one-shot per startup cycle.
    // steadyStateSampleCount tracks EMA updates since last boundary reset,
    // replacing external decodedFrameCount which doesn't reset on same-codec paths.
    var startupAlignLatencyUs = 0L
    var latencyDriftCorrected = false
    var outputRouteChangedAtMs = 0L
    private var steadyStateSampleCount = 0
    private var totalMeasureCalls = 0  // total calls to measure() since last reset

    /**
     * Total latency compensation for startup alignment.
     * Acoustic calibration is ground truth when available (full one-way output delay:
     * DAC + transport + speaker + air). Measured pipeline is fallback for uncalibrated routes.
     */
    fun totalCompensationUs(): Long {
        if (routeAcousticExtraUs > 0) return routeAcousticExtraUs
        // Fallback: pipeline measurement, only for high-latency outputs (BT without calibration)
        return if (measuredOutputLatencyUs > 50_000L) measuredOutputLatencyUs else 0L
    }

    /** Diagnostic: how much of the compensation is beyond what AudioTrack measures. */
    fun acousticExtraUs(): Long =
        if (routeAcousticExtraUs > measuredOutputLatencyUs)
            routeAcousticExtraUs - measuredOutputLatencyUs
        else 0L

    fun hasValidMeasurement(): Boolean = measuredOutputLatencyUs > 0L

    /** Reset for timing boundaries (seek, new stream). Preserves route/acoustic state. */
    fun resetForBoundary() {
        latencyDriftCorrected = false
        startupAlignLatencyUs = 0L
        steadyStateSampleCount = 0
        totalMeasureCalls = 0
    }

    /**
     * Full measurement reset for high-latency outputs (BT) after seek/flush.
     * BT pipeline latency can shift after pause+flush (codec rebuffer, HAL restart).
     * Clears measured value so pre-cal and startup median re-measure from scratch.
     */
    fun resetMeasurementForSeek() {
        measuredOutputLatencyUs = 0L
        outputLatencyMeasureCount = 0
        latencyPhaseStartup = true
        startupLatencySamples.clear()
        resetForBoundary()
    }

    /** Atomic route change: set new correction, reset measurement state. */
    fun onRouteChanged() {
        measuredOutputLatencyUs = 0L
        outputLatencyMeasureCount = 0
        latencyPhaseStartup = true
        startupLatencySamples.clear()
        outputRouteChangedAtMs = System.currentTimeMillis()
        latencyDriftCorrected = false
        startupAlignLatencyUs = 0L
        steadyStateSampleCount = 0
        totalMeasureCalls = 0
    }

    /** Seed measurement from a pre-calibration phase (e.g. silence write). */
    fun seedFromPreCal(latencyUs: Long) {
        if (measuredOutputLatencyUs > 0L) {
            // Already have a measurement (e.g. from previous stream), just warm DAC
            Log.d(tag, "Pre-cal: DAC warm (keeping EMA=${measuredOutputLatencyUs / 1000}ms)")
        } else {
            measuredOutputLatencyUs = latencyUs
            latencyPhaseStartup = false
            Log.d(tag, "Pre-cal: output latency=${latencyUs / 1000}ms")
        }
        startupLatencySamples.clear()
    }

    /**
     * Observe AudioTrack timestamp and update latency measurement.
     * Called from playback thread after each track.write().
     *
     * @return MeasureResult indicating what the engine should do.
     */
    fun measure(
        track: AudioTrack,
        sampleRate: Int,
    ): MeasureResult {
        totalMeasureCalls++
        outputLatencyMeasureCount++
        val minFrames = if (latencyPhaseStartup) 40 else 150
        val interval = if (latencyPhaseStartup) 20 else 50
        if (totalMeasureCalls < minFrames) return MeasureResult.NoChange
        if (outputLatencyMeasureCount < interval) return MeasureResult.NoChange
        outputLatencyMeasureCount = 0

        if (!track.getTimestamp(outputLatencyTimestamp)) return MeasureResult.NoChange
        val framesAtDac = outputLatencyTimestamp.framePosition
        if (framesAtDac <= 0) return MeasureResult.NoChange

        val framesConsumed = track.playbackHeadPosition.toLong()
        val framesInPipeline = framesConsumed - framesAtDac
        if (framesInPipeline < 0) return MeasureResult.NoChange

        val tsAgeUs = (System.nanoTime() - outputLatencyTimestamp.nanoTime) / 1000L
        val pipelineUs = framesInPipeline * 1_000_000L / sampleRate.toLong()
        val latencyUs = tsAgeUs + pipelineUs
        if (latencyUs <= 0 || latencyUs > 500_000) return MeasureResult.NoChange
        if (tsAgeUs > 50_000) return MeasureResult.NoChange

        // Outlier rejection
        val recentRouteChange = outputRouteChangedAtMs > 0 &&
            System.currentTimeMillis() - outputRouteChangedAtMs < 5000
        if (!latencyPhaseStartup && !recentRouteChange && measuredOutputLatencyUs > 0L) {
            val deltaUs = kotlin.math.abs(latencyUs - measuredOutputLatencyUs)
            if (deltaUs > 30_000) return MeasureResult.NoChange
        }

        if (latencyPhaseStartup) {
            startupLatencySamples.add(latencyUs)
            if (startupLatencySamples.size >= 5) {
                val sorted = startupLatencySamples.sorted()
                // Capture total compensation BEFORE updating pipeline measurement
                val oldTotalComp = totalCompensationUs()
                measuredOutputLatencyUs = sorted[sorted.size / 2]
                latencyPhaseStartup = false
                startupLatencySamples.clear()
                Log.d(tag, "OutputLatency: startup median=${measuredOutputLatencyUs / 1000}ms (from ${sorted.size} samples)")
                // Only re-align if totalCompensation actually changed by >10ms.
                // With acoustic calibration active, pipeline + acousticExtra = calibration,
                // so the total stays the same when pipeline measurement completes.
                val newTotalComp = totalCompensationUs()
                val totalCompDelta = kotlin.math.abs(newTotalComp - oldTotalComp)
                if (outputRouteChangedAtMs > 0
                    && System.currentTimeMillis() - outputRouteChangedAtMs < 10_000
                    && totalCompDelta > 10_000
                ) {
                    Log.d(tag, "Route-change latency correction: pipeline=${measuredOutputLatencyUs / 1000}ms totalDelta=${totalCompDelta / 1000}ms, re-aligning")
                    return MeasureResult.NeedsRealign
                }
            }
        } else {
            val alpha = if (recentRouteChange) 0.3 else 0.05
            measuredOutputLatencyUs = (alpha * latencyUs + (1.0 - alpha) * measuredOutputLatencyUs).toLong()
            steadyStateSampleCount++
        }

        if (totalMeasureCalls < 500 || totalMeasureCalls % 500 == 0) {
            Log.d(tag, "OutputLatency: raw=${latencyUs / 1000}ms ema=${measuredOutputLatencyUs / 1000}ms " +
                "tsAge=${tsAgeUs / 1000}ms pipeline=${pipelineUs / 1000}ms phase=${if (latencyPhaseStartup) "startup" else "steady"}")
        }

        return MeasureResult.NoChange
    }

    /**
     * Check if latency has drifted enough from startup to warrant anchor shift.
     * One-shot per startup cycle. Uses internal steadyStateSampleCount (resets on boundary)
     * instead of external decodedFrameCount which doesn't reset on same-codec paths.
     * 10 steady-state EMA updates ~= 10s at 50-frame interval.
     */
    fun checkDriftCorrection(
        isSyncMode: Boolean,
        anchorLocalUs: Long,
    ): Long? {
        // When acoustic calibration is active, totalCompensation is fixed (= calibration value)
        // regardless of measuredOutputLatencyUs changes. Pipeline EMA drift is irrelevant
        // because it doesn't affect the actual compensation used for alignment.
        if (routeAcousticExtraUs > 0) return null
        if (!isSyncMode || latencyDriftCorrected || startupAlignLatencyUs <= 0
            || steadyStateSampleCount < 10 || anchorLocalUs <= 0
        ) return null

        val driftUs = measuredOutputLatencyUs - startupAlignLatencyUs
        val driftMs = driftUs / 1000.0
        if (kotlin.math.abs(driftMs) <= 5.0) return null

        startupAlignLatencyUs = measuredOutputLatencyUs
        latencyDriftCorrected = true
        Log.d(tag, "Latency drift correction: shift=${"%.1f".format(driftMs)}ms, new EMA=${measuredOutputLatencyUs / 1000}ms")
        return driftUs
    }

    sealed class MeasureResult {
        data object NoChange : MeasureResult()
        data object NeedsRealign : MeasureResult()
    }
}
