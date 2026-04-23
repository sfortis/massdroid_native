package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns DAC timestamp calibration, timeline tracking, and drift measurement.
 *
 * DAC calibration pairs map hardware DAC clock to system monotonic time via interpolation.
 * Drift detection: compares where the DAC IS (via calibrated timestamp) vs where it SHOULD be
 * (based on written frames and server timeline).
 *
 * Thread safety: fields annotated volatile are written by playback thread and read/reset by
 * main/WS thread during mode switches. The calibration ArrayDeque is synchronized.
 */
class DacDriftValidator(private val tag: String = "AudioStream") {

    private data class DacCalibration(val dacTimeUs: Long, val loopTimeUs: Long)

    private val calibrations = ArrayDeque<DacCalibration>()
    private val timestamp = android.media.AudioTimestamp()
    private var lastCalibrationTimeUs = 0L

    @Volatile var timestampsStable = false
        private set
    @Volatile var consecutiveValidTimestamps = 0
        private set

    // Server timeline tracking
    @Volatile var lastWrittenServerTimestampUs = 0L
    val totalFramesWritten = AtomicLong(0)
    var frameBaseline = 0L  // AudioTrack.playbackHeadPosition at last reset

    // Drift tracking
    @Volatile var prevRawErrorUs = Long.MIN_VALUE
        private set

    // Divergence state (read/written by engine's checkSync)
    @Volatile var divergenceEma = 0.0
    @Volatile var divergenceSameSignCount = 0
    @Volatile var divergenceLastSign = 0
    @Volatile var matureCount = 0
    var lastAnchorReseatMs = 0L

    /** Record a frame written to AudioTrack. Called from decode/write path. */
    fun onFrameWritten(endTimestampUs: Long, pcmFrameCount: Long) {
        totalFramesWritten.addAndGet(pcmFrameCount)
        lastWrittenServerTimestampUs = endTimestampUs
    }

    /** Collect a DAC-to-system-time calibration pair. Called after each track.write(). */
    fun collectCalibration(track: AudioTrack) {
        if (!track.getTimestamp(timestamp)) {
            consecutiveValidTimestamps = 0
            return
        }
        val dacTimeUs = timestamp.nanoTime / 1000
        val loopTimeUs = System.nanoTime() / 1000
        if (dacTimeUs <= 0) {
            consecutiveValidTimestamps = 0
            return
        }
        consecutiveValidTimestamps++
        if (consecutiveValidTimestamps >= 3) timestampsStable = true

        if (dacTimeUs - lastCalibrationTimeUs > 10_000) {
            lastCalibrationTimeUs = dacTimeUs
            synchronized(calibrations) {
                calibrations.addLast(DacCalibration(dacTimeUs, loopTimeUs))
                while (calibrations.size > 50) calibrations.removeFirst()
            }
        }
    }

    /** Convert DAC hardware timestamp to system monotonic time via calibration interpolation. */
    private fun dacTimeToLoopTimeUs(dacTimeUs: Long): Long {
        synchronized(calibrations) {
            if (calibrations.isEmpty()) return dacTimeUs
            if (calibrations.size == 1) {
                val c = calibrations.first()
                return dacTimeUs - c.dacTimeUs + c.loopTimeUs
            }
            val last = calibrations.last()
            if (dacTimeUs >= last.dacTimeUs) {
                return dacTimeUs - last.dacTimeUs + last.loopTimeUs
            }
            for (i in calibrations.size - 2 downTo 0) {
                val prev = calibrations[i]
                val next = calibrations[i + 1]
                if (dacTimeUs >= prev.dacTimeUs) {
                    val dacDelta = next.dacTimeUs - prev.dacTimeUs
                    if (dacDelta <= 0) continue
                    val t = (dacTimeUs - prev.dacTimeUs).toDouble() / dacDelta
                    return (prev.loopTimeUs + t * (next.loopTimeUs - prev.loopTimeUs)).toLong()
                }
            }
            val first = calibrations.first()
            return dacTimeUs - first.dacTimeUs + first.loopTimeUs
        }
    }

    /**
     * Measure DAC drift: change in raw error since last measurement.
     * Raw error = (where DAC IS in server time) - (where DAC SHOULD be based on written frames).
     * Returns drift in microseconds, or null if not ready.
     */
    fun measureDriftUs(
        track: AudioTrack,
        clockSync: ClockSynchronizer,
        sampleRate: Int,
        decodedFrameCount: Int,
    ): Long? {
        if (!timestampsStable) return null
        if (!track.getTimestamp(timestamp)) return null
        val dacTimeUs = timestamp.nanoTime / 1000
        val dacFramePosition = timestamp.framePosition
        if (dacTimeUs <= 0 || dacFramePosition <= 0) return null

        val dacLoopTimeUs = dacTimeToLoopTimeUs(dacTimeUs)
        val dacServerTimeUs = clockSync.localToServerUs(dacLoopTimeUs)

        val written = totalFramesWritten.get()
        val adjustedDacPos = dacFramePosition - frameBaseline
        val pending = (written - adjustedDacPos).coerceAtLeast(0)
        val pendingDurationUs = pending * 1_000_000L / sampleRate.toLong()
        val expectedDacServerTimeUs = lastWrittenServerTimestampUs - pendingDurationUs

        if (expectedDacServerTimeUs <= 0L) return null

        val rawErrorUs = dacServerTimeUs - expectedDacServerTimeUs

        if (prevRawErrorUs == Long.MIN_VALUE) {
            prevRawErrorUs = rawErrorUs
            Log.d(tag, "DacSync: initial raw=${rawErrorUs / 1000}ms pending=$pending")
            return null
        }

        val driftUs = rawErrorUs - prevRawErrorUs
        prevRawErrorUs = rawErrorUs
        matureCount++

        if (decodedFrameCount < 500 || decodedFrameCount % 500 == 0) {
            Log.d(tag, "DacSync: raw=${rawErrorUs / 1000}ms drift=${driftUs / 1000}ms pending=$pending mature=$matureCount")
        }
        return driftUs
    }

    /**
     * Current absolute DAC error in ms. Returns null if not mature enough.
     * @param maturityThreshold minimum samples before trusting (default 20).
     */
    fun absoluteErrorMs(maturityThreshold: Int = 20): Double? {
        if (prevRawErrorUs == Long.MIN_VALUE) return null
        if (matureCount < maturityThreshold) return null
        return prevRawErrorUs.toDouble() / 1000.0
    }

    /** Reset divergence tracking state. */
    fun resetDivergence() {
        divergenceEma = 0.0
        divergenceSameSignCount = 0
        divergenceLastSign = 0
        matureCount = 0
    }

    /** Clear calibration pairs and reset stability. */
    fun clearCalibrations() {
        synchronized(calibrations) { calibrations.clear() }
        lastCalibrationTimeUs = 0L
        timestampsStable = false
        consecutiveValidTimestamps = 0
        resetDivergence()
    }

    /**
     * Reset timeline state after flush/seek. AudioTrack.playbackHeadPosition continues
     * across flush, so capture the current position as baseline.
     */
    fun resetTimeline(track: AudioTrack?) {
        frameBaseline = track?.playbackHeadPosition?.toLong() ?: 0L
        totalFramesWritten.set(0)
        lastWrittenServerTimestampUs = 0L
        prevRawErrorUs = Long.MIN_VALUE
        resetDivergence()
    }

    /**
     * Full reset for new AudioTrack (position starts from 0).
     */
    fun resetForNewTrack() {
        frameBaseline = 0L
        totalFramesWritten.set(0)
        lastWrittenServerTimestampUs = 0L
        prevRawErrorUs = Long.MIN_VALUE
        clearCalibrations()
    }

    /** Reset DAC stability for re-calibration (e.g. after mode switch, BT seek). */
    fun resetStability() {
        consecutiveValidTimestamps = 0
        timestampsStable = false
    }
}
