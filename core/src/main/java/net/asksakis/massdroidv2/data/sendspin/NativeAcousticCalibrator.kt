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
        val quality: Quality,
        // Microphone input-path latency reported by Oboe. Reports only the
        // AAudio HAL buffer occupancy — DSP/processing latency on the mic
        // path is NOT included. Use [outputHALUs] from a phone-speaker
        // reference pass plus that pass' [roundTripUs] to derive the true
        // full mic_path instead.
        val inputLatencyUs: Long,
        // Output pipeline latency extrapolated from Oboe getTimestamp during
        // chirp playback. Meaningful for in-phone outputs (built-in speaker,
        // wired, USB). For BT this reflects only the time frames left the
        // host SDK boundary, NOT the BT speaker's DAC time; treat it as an
        // emission timestamp rather than true output HAL when the routed
        // device is BT.
        val outputHALUs: Long,
        // AAudio device ids that the streams actually opened on. When a
        // calibration call requested a specific output device, the caller
        // verifies via this field that the routing override took effect.
        val routedOutputDeviceId: Int,
        val routedInputDeviceId: Int
    )

    enum class Quality { GOOD, MARGINAL, FAILED }

    var onProgress: ((toneIndex: Int, total: Int) -> Unit)? = null

    /**
     * Run a single calibration pass.
     *
     * @param maxDelayMs Maximum acceptable round-trip delay; tones beyond are
     *   rejected as outliers.
     * @param outputDeviceId Optional AAudio device id to pin the output route
     *   to (e.g. [android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER] id from
     *   AudioManager.getDevices). Default 0 lets the framework pick the
     *   current default route. Pass an explicit id to force a phone-speaker
     *   reference pass while BT is the system default. The framework MAY
     *   silently ignore the request — callers must verify success via the
     *   returned [CalibrationResult.routedOutputDeviceId].
     */
    suspend fun measureRoundTrip(
        maxDelayMs: Int = MAX_DELAY_MS_DEFAULT,
        outputDeviceId: Int = 0
    ): CalibrationResult = withContext(Dispatchers.Default) {
        val ptr = nativeCreate()
        if (ptr == 0L) {
            return@withContext CalibrationResult(
                roundTripUs = 0,
                detectedTones = 0,
                varianceMs = 0.0,
                snrDb = 0f,
                quality = Quality.FAILED,
                inputLatencyUs = 0L,
                outputHALUs = 0L,
                routedOutputDeviceId = 0,
                routedInputDeviceId = 0
            )
        }
        try {
            nativeMeasure(ptr, maxDelayMs, outputDeviceId)
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
    private external fun nativeMeasure(
        enginePtr: Long,
        maxDelayMs: Int,
        outputDeviceId: Int
    ): CalibrationResult
}
