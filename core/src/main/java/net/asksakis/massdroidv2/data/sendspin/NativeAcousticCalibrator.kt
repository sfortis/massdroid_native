package net.asksakis.massdroidv2.data.sendspin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Plays test signals through a native Oboe output stream opened the same way as
 * the Sendspin output (Shared, LowLatency, stereo, Media/Music), so they take the
 * mixer and effect path the music takes, and reports when the output presented
 * each signal's first frame according to its getTimestamp.
 */
class NativeAcousticCalibrator {

    companion object {
        init {
            System.loadLibrary("acoustic_calibrator")
        }

        // Slots of the LongArray nativePlaySignal fills (see acoustic_calibrator_jni.cpp).
        private const val SLOT_FRAME0_NANOS = 0
        private const val SLOT_SPREAD_NANOS = 1
        private const val SLOT_TIMESTAMP_SAMPLES = 2
        private const val SLOT_XRUNS = 3
        private const val SLOT_ROUTED_DEVICE_ID = 4
        private const val SLOT_SAMPLE_RATE = 5
        private const val SLOT_FRAME_OFFSET = 6
        private const val SLOT_COUNT = 7

        const val SAMPLE_RATE = 48_000
    }

    data class PlayResult(
        // CLOCK_MONOTONIC time at which the signal's first frame was presented,
        // as the output's getTimestamp places it (median over the run).
        val frame0Us: Long,
        // Largest distance of one timestamp sample from that median.
        val spreadUs: Long,
        val timestampSamples: Int,
        val xRuns: Int,
        val routedOutputDeviceId: Int,
        val sampleRate: Int,
        val frameOffset: Long,
    )

    /** An open output stream that plays silence between test signals. */
    inner class Session internal constructor(private val ptr: Long) {
        /**
         * Plays [signal] (mono, 48 kHz) and returns after it has been presented.
         * Null when the stream failed or reported no usable timestamp.
         */
        suspend fun play(signal: ShortArray): PlayResult? = withContext(Dispatchers.Default) {
            val out = LongArray(SLOT_COUNT)
            if (!nativePlaySignal(ptr, signal, out)) return@withContext null
            PlayResult(
                frame0Us = out[SLOT_FRAME0_NANOS] / 1000L,
                spreadUs = out[SLOT_SPREAD_NANOS] / 1000L,
                timestampSamples = out[SLOT_TIMESTAMP_SAMPLES].toInt(),
                xRuns = out[SLOT_XRUNS].toInt(),
                routedOutputDeviceId = out[SLOT_ROUTED_DEVICE_ID].toInt(),
                sampleRate = out[SLOT_SAMPLE_RATE].toInt(),
                frameOffset = out[SLOT_FRAME_OFFSET],
            )
        }
    }

    /**
     * Opens an output stream on [outputDeviceId], or on the current route when
     * it is 0, runs [block] with it, and closes it. The stream plays silence
     * whenever [block] is not playing a signal, so the output path keeps running
     * from one signal to the next. Returns null when the stream did not open.
     */
    suspend fun <T> session(outputDeviceId: Int, block: suspend (Session) -> T): T? {
        val ptr = nativeCreate()
        try {
            val opened = withContext(Dispatchers.Default) { nativeOpen(ptr, outputDeviceId) }
            if (!opened) return null
            return block(Session(ptr))
        } finally {
            withContext(NonCancellable + Dispatchers.Default) {
                nativeClose(ptr)
                nativeDestroy(ptr)
            }
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(enginePtr: Long)
    private external fun nativeOpen(enginePtr: Long, outputDeviceId: Int): Boolean
    private external fun nativeClose(enginePtr: Long)
    private external fun nativePlaySignal(enginePtr: Long, signal: ShortArray, resultOut: LongArray): Boolean
}
