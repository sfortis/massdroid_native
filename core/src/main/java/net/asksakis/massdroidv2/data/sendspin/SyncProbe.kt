package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SyncProbeResult(
    val analysis: SyncProbeAnalysis,
    val model: SyncProbeModel,
    val micSource: String,
    // Spread of the microphone timestamps around their median, a bound on how
    // well the recording is placed in time.
    val micTimestampSpreadUs: Long,
)

sealed interface SyncProbeOutcome {
    data class Success(val result: SyncProbeResult) : SyncProbeOutcome
    data class Failure(val reason: String) : SyncProbeOutcome
}

/**
 * Measures where each audible speaker plays relative to the Sendspin server
 * timestamp, by recording the room and matching the recording against the
 * decoded stream (see [SyncProbeAnalyzer]).
 *
 * A [Session] keeps the stream tap armed across several measurements. The
 * decoded stream reaches the tap about 2.5 s before it plays, so the first
 * measurement of a session records [FIRST_RECORD_SECONDS] and uses only the
 * part where the recording and the reference overlap; later ones already have
 * the reference and record [NEXT_RECORD_SECONDS].
 *
 * Diagnostic only: the result is reported, nothing in playback is changed.
 */
class SyncProbe(private val clock: ClockSynchronizer) {

    companion object {
        private const val TAG = "SyncProbe"
        const val FIRST_RECORD_SECONDS = 10
        const val NEXT_RECORD_SECONDS = 5
        private const val TAP_SECONDS = 20
        private const val REFERENCE_MARGIN_US = 1_000_000L
    }

    /** One armed tap; [measure] records and analyses once. */
    inner class Session internal constructor(
        private val engine: SendspinPlaybackEngine,
        private val tap: SyncProbeTap,
    ) {
        private var measured = 0

        /**
         * [windowUs] is where to look for speakers, in microseconds after the
         * timestamp; keep it narrow (see [SyncProbeAnalyzer.DEFAULT_WINDOW_US]).
         */
        suspend fun measure(windowUs: LongRange = SyncProbeAnalyzer.DEFAULT_WINDOW_US): SyncProbeOutcome {
            val seconds = if (measured++ == 0) FIRST_RECORD_SECONDS else NEXT_RECORD_SECONDS
            val mic = record(seconds)
                ?: return SyncProbeOutcome.Failure(
                    "Couldn't record. The microphone did not open; check the microphone permission."
                )
            val model = engine.syncProbeModel()
            val reference = tap.snapshot(
                mic.signal.startServerUs - windowUs.last - REFERENCE_MARGIN_US,
                mic.signal.endServerUs - windowUs.first + REFERENCE_MARGIN_US,
            ) ?: return SyncProbeOutcome.Failure(
                "Couldn't measure. No audio reached the phone while it recorded."
            )
            val analysis = withContext(Dispatchers.Default) {
                SyncProbeAnalyzer.analyze(reference, mic.signal, windowUs)
            } ?: return SyncProbeOutcome.Failure(
                "Couldn't measure. The recording and the stream overlapped by less than two seconds."
            )
            val result = SyncProbeResult(analysis, model, mic.source, mic.timestampSpreadUs)
            logResult(result)
            return SyncProbeOutcome.Success(result)
        }
    }

    /** Arms the tap on [engine] for the duration of [block]. */
    suspend fun <T> session(engine: SendspinPlaybackEngine, block: suspend (Session) -> T): T {
        val tap = SyncProbeTap(engine.streamSampleRate, TAP_SECONDS)
        engine.syncProbeTap = tap
        return try {
            block(Session(engine, tap))
        } finally {
            engine.syncProbeTap = null
        }
    }

    private class Recording(val signal: SyncProbeSignal, val source: String, val timestampSpreadUs: Long)

    /** Records [seconds] of the room and places the recording on the server clock. */
    private suspend fun record(seconds: Int): Recording? {
        val mic = TimestampedMicRecorder.record(seconds * 1000L) ?: return null
        val startServerUs = clock.localToServerUs(mic.originUs)
        val endServerUs = clock.localToServerUs(mic.originUs + mic.durationUs)
        val interval = (endServerUs - startServerUs).toDouble() / (mic.samples.size - 1)
        return Recording(SyncProbeSignal(mic.samples, startServerUs, interval), mic.source, mic.timestampSpreadUs)
    }

    private fun logResult(result: SyncProbeResult) {
        val m = result.model
        val peaks = result.analysis.peaks.joinToString { "${it.lagUs / 1000.0}ms@${"%.2f".format(it.strength)}" }
        Log.i(
            TAG,
            "peaks=[$peaks] clarity=${"%.1f".format(result.analysis.clarity)} " +
                "overlap=${result.analysis.overlapUs / 1000}ms expected=${m.expectedLagUs / 1000.0}ms " +
                "headroom=${m.headroomUs / 1000}ms syncDelay=${m.syncDelayUs / 1000}ms " +
                "acoustic=${m.acousticCorrectionUs / 1000}ms reportedLat=${m.reportedOutputLatencyUs / 1000}ms " +
                "fullLat=${m.fullOutputLatencyUs / 1000}ms drift=${m.nativeDriftUs}us " +
                "codec=${m.codec}/${m.sampleRate}/${m.channels} mic=${result.micSource} " +
                "micTsSpread=${result.micTimestampSpreadUs}us clockErr=${clock.errorUs()}us"
        )
    }
}
