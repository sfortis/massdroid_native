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

    // Per-AudioTrack DAC-absolute epoch calibration.
    // The raw DAC error carries a device-specific CONSTANT bias because it mixes two
    // AudioTrack frame counters whose epochs are not guaranteed to align across HALs
    // (AudioTimestamp.framePosition vs getPlaybackHeadPosition / 0). Observed on a clean,
    // acoustically-aligned startup (absOffset=0, same 25ms pipeline): +18ms on one device,
    // -62ms on another, -132ms on a third (issue #45). DRIFT cancels this bias; ABSOLUTE
    // does not. We calibrate it out by capturing the raw error a few samples into the first
    // aligned stream and subtracting it from every absolute reading thereafter — making the
    // absolute device-independent (≈0 at the aligned start everywhere) while still measuring
    // real drift and per-seek cold-start variance. Captured ONCE per AudioTrack (kept across
    // seeks, since the epoch is a property of the track instance); cleared on resetForNewTrack.
    @Volatile var epochBaselineUs = Long.MIN_VALUE
        private set
    // Raw samples collected to derive the epoch baseline (median, so a transient clock-storm
    // outlier such as a multi-second raw error can't poison the calibration).
    // Guarded by [epochLock]: added/medianed on the playback thread (measureDriftUs) and
    // cleared on the WS/main thread (resetForNewTrack); the release join is bounded (1s), so
    // the two can briefly overlap and an unsynchronized ArrayList would risk a torn read /
    // IndexOutOfBounds during sorted()[size/2].
    private val epochLock = Any()
    private val epochSamples = ArrayList<Long>(EPOCH_CALIBRATION_SAMPLES)

    private companion object {
        const val EPOCH_CALIBRATION_SAMPLES = 9
        const val EPOCH_CALIBRATION_SANITY_US = 5_000_000L  // reject |raw| > 5s (clock storm)
    }

    // Divergence state (read/written by engine's checkSync)
    @Volatile var divergenceEma = 0.0
    @Volatile var divergenceSameSignCount = 0
    @Volatile var divergenceLastSign = 0
    @Volatile var matureCount = 0

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

        // Epoch calibration window (device-bias removal, once per AudioTrack): collect sane
        // raw samples and take their median as the absolute baseline. Subtracting it makes the
        // DAC absolute device-independent (≈0 at the aligned start on every device).
        if (epochBaselineUs == Long.MIN_VALUE &&
            kotlin.math.abs(rawErrorUs) < EPOCH_CALIBRATION_SANITY_US
        ) {
            synchronized(epochLock) {
                if (epochBaselineUs == Long.MIN_VALUE) {
                    epochSamples.add(rawErrorUs)
                    if (epochSamples.size >= EPOCH_CALIBRATION_SAMPLES) {
                        epochBaselineUs = epochSamples.sorted()[epochSamples.size / 2]
                        Log.d(tag, "DacSync: epoch baseline=${epochBaselineUs / 1000}ms " +
                            "(device bias removed, median of n=${epochSamples.size})")
                    }
                }
            }
        }

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
        // Not yet epoch-calibrated: withhold the absolute (the closed loop falls back to the
        // anchor) rather than feed the raw, device-biased value into the correction signal.
        if (epochBaselineUs == Long.MIN_VALUE) return null
        return (prevRawErrorUs - epochBaselineUs).toDouble() / 1000.0
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
        // New AudioTrack instance = new framePosition epoch, so the device-bias calibration
        // must be re-derived. (resetTimeline/seek keeps it: same track, same epoch.)
        synchronized(epochLock) {
            epochBaselineUs = Long.MIN_VALUE
            epochSamples.clear()
        }
        clearCalibrations()
    }

    /** Reset DAC stability for re-calibration (e.g. after mode switch, BT seek). */
    fun resetStability() {
        consecutiveValidTimestamps = 0
        timestampsStable = false
    }
}
