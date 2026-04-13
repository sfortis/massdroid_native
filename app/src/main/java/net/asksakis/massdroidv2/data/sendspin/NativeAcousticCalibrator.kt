package net.asksakis.massdroidv2.data.sendspin

import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Oboe-based acoustic round-trip latency calibrator.
 *
 * Plays 1kHz tone bursts through the speaker and detects them via the
 * microphone using native Oboe streams for deterministic, low-jitter timing.
 * All audio I/O and DSP runs in C++ on SCHED_FIFO audio threads.
 *
 * Drop-in replacement for AcousticLatencyCalibrator (Java MVP).
 */
class NativeAcousticCalibrator {

    companion object {
        private const val MAX_DELAY_MS_DEFAULT = 500

        init {
            System.loadLibrary("acoustic_calibrator")
        }
    }

    data class CalibrationResult(
        val roundTripUs: Long,
        val detectedTones: Int,
        val varianceMs: Double,
        val snrDb: Float,
        val quality: Quality
    )

    enum class Quality { GOOD, MARGINAL, FAILED }

    var onProgress: ((toneIndex: Int, total: Int) -> Unit)? = null

    suspend fun measureRoundTrip(
        maxDelayMs: Int = MAX_DELAY_MS_DEFAULT
    ): CalibrationResult = withContext(Dispatchers.Default) {
        val ptr = nativeCreate()
        if (ptr == 0L) {
            return@withContext CalibrationResult(0, 0, 0.0, 0f, Quality.FAILED)
        }
        try {
            nativeMeasure(ptr, maxDelayMs)
        } finally {
            nativeDestroy(ptr)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Called from native audio thread via JNI. Post to main thread for Compose state safety. */
    @Keep
    @Suppress("unused")
    private fun onNativeProgress(toneIndex: Int, total: Int) {
        val callback = onProgress ?: return
        mainHandler.post { callback(toneIndex, total) }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(enginePtr: Long)
    private external fun nativeMeasure(enginePtr: Long, maxDelayMs: Int): CalibrationResult
}
