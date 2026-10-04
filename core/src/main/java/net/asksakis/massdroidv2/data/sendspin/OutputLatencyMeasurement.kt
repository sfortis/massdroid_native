package net.asksakis.massdroidv2.data.sendspin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/** The output path's latency beyond its getTimestamp, measured from one recording. */
sealed interface OutputLatencyResult {
    /**
     * [settlingUsPerS] is how fast the latency changed while the signal played,
     * when it could be told; see [OutputLatencyMeasurement.MAX_SETTLING_US_PER_S].
     */
    data class Measured(
        val latencyUs: Long,
        val analysis: SyncProbeAnalysis,
        val settlingUsPerS: Double? = null,
    ) : OutputLatencyResult {
        val settled: Boolean
            get() = settlingUsPerS == null || abs(settlingUsPerS) <= OutputLatencyMeasurement.MAX_SETTLING_US_PER_S
    }
    data class Rejected(val reason: String, val analysis: SyncProbeAnalysis?) : OutputLatencyResult
}

/**
 * The pure part of the output calibration: the test signal and the analysis.
 *
 * The signal is a sequence of short chirps at irregular intervals, the same
 * shape as the sync probe's announcement clip (`assets/sync-probe/make_chirps.py`),
 * so it does not resemble itself at any lag and the correlation has one peak.
 * The analysis places the played signal at the time the output's getTimestamp
 * says it was presented, places the recording at the time the input's
 * timestamp says it was captured, and finds the lag between them with the
 * sync probe's GCC-PHAT. That lag is the part of the output path the timestamp
 * does not cover, which is what the engine must add to the latency it aligns
 * against.
 */
object OutputLatencyMeasurement {
    const val RATE = 48_000
    // Silence before the chirps, so the recorder is running before the first one.
    const val LEAD_SILENCE_MS = 500
    const val CHIRP_SECONDS = 4.0
    // Lags searched: a little below zero for timestamp noise, up to what an
    // uncompensated Bluetooth speaker could add.
    val WINDOW_US: LongRange = -100_000L..800_000L
    // Below this the recording did not clearly contain the signal.
    const val MIN_CLARITY = 8.0
    // A latency that moves faster than this while the signal plays has not
    // settled, and the calibration keeps measuring.
    // A Bluetooth speaker can grow its own buffer for minutes after audio starts:
    // the CREATIVE MUVO 2 went from about 25 ms to 165 ms beyond the timestamp
    // at 1.3 to 1.5 ms per second (2026-10-03), so a recording taken early
    // stored a value 140 ms short. A settled path moves well under this.
    const val MAX_SETTLING_US_PER_S = 500.0
    // The stretch of chirps compared at the start and at the end of the signal,
    // and the lags searched around the overall result for each.
    private const val SETTLING_SEGMENT_S = 1.5
    private const val SETTLING_WINDOW_US = 25_000L
    private const val SETTLING_MIN_OVERLAP_US = 1_000_000L
    // A second peak this strong, away from the first, means the match is ambiguous.
    const val MAX_SECOND_PEAK = 0.5
    // Peaks this close to the strongest are the same sound: a reflection, or a
    // second driver in the same box.
    const val SAME_SOUND_US = 10_000L

    private const val SEED = 20261003
    private const val F_LOW = 300.0
    private const val F_HIGH = 6000.0
    private const val PEAK_LEVEL = 0.5

    /** The test signal: [LEAD_SILENCE_MS] of silence, then [CHIRP_SECONDS] of chirps, mono 16 bit. */
    fun signal(): ShortArray {
        val lead = RATE * LEAD_SILENCE_MS / 1000
        val body = DoubleArray((RATE * CHIRP_SECONDS).toInt())
        val random = Random(SEED)
        var t = 0.05
        while (t < CHIRP_SECONDS - 0.15) {
            val dur = random.nextDouble(0.04, 0.12)
            val up = random.nextBoolean()
            val f0 = if (up) F_LOW else F_HIGH
            val f1 = if (up) F_HIGH else F_LOW
            val n = (dur * RATE).toInt()
            val k = ln(f1 / f0) / dur
            val gain = random.nextDouble(0.6, 1.0)
            val start = (t * RATE).toInt()
            for (i in 0 until n) {
                val tt = i.toDouble() / RATE
                val phase = 2 * PI * f0 * (exp(k * tt) - 1) / k
                val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                body[start + i] += sin(phase) * window * gain
            }
            t += dur + random.nextDouble(0.12, 0.45)
        }
        val peak = body.maxOf { abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(lead + body.size) { i ->
            if (i < lead) 0 else (body[i - lead] / peak * PEAK_LEVEL * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /**
     * [frame0Us] is when the output presented [signal]'s first frame; the
     * recording starts at [recordingOriginUs]; both on the same clock.
     */
    fun analyze(
        signal: ShortArray,
        frame0Us: Long,
        recording: FloatArray,
        recordingOriginUs: Long,
    ): OutputLatencyResult {
        val interval = 1_000_000.0 / RATE
        val reference = SyncProbeSignal(FloatArray(signal.size) { signal[it] / 32768f }, frame0Us, interval)
        val mic = SyncProbeSignal(recording, recordingOriginUs, interval)
        val analysis = SyncProbeAnalyzer.analyze(reference, mic, WINDOW_US)
            ?: return OutputLatencyResult.Rejected(
                "Couldn't measure. The recording did not cover the test sound.", null
            )
        val strongest = analysis.peaks.firstOrNull()
        val second = analysis.peaks.drop(1)
            .filter { strongest != null && abs(it.lagUs - strongest.lagUs) > SAME_SOUND_US }
            .maxOfOrNull { it.strength } ?: 0.0
        return when {
            strongest == null || analysis.clarity < MIN_CLARITY -> OutputLatencyResult.Rejected(
                "Couldn't measure. The microphone did not hear the test sound clearly.", analysis
            )
            second >= MAX_SECOND_PEAK -> OutputLatencyResult.Rejected(
                "Couldn't measure. The test sound was heard more than once; keep the room quiet.", analysis
            )
            else -> OutputLatencyResult.Measured(
                strongest.lagUs, analysis, settlingUsPerS(signal, reference, mic, strongest.lagUs)
            )
        }
    }

    /**
     * How fast the latency moved while the signal played, in microseconds per
     * second: the lag of the first [SETTLING_SEGMENT_S] of chirps against the
     * lag of the last, each searched close to [lagUs]. Null when either part
     * cannot be matched on its own, in which case nothing is concluded.
     */
    private fun settlingUsPerS(
        signal: ShortArray,
        reference: SyncProbeSignal,
        mic: SyncProbeSignal,
        lagUs: Long,
    ): Double? {
        val segment = (SETTLING_SEGMENT_S * RATE).toInt()
        val firstStart = RATE * LEAD_SILENCE_MS / 1000
        val lastStart = signal.size - segment
        if (lastStart <= firstStart) return null
        val window = (lagUs - SETTLING_WINDOW_US)..(lagUs + SETTLING_WINDOW_US)
        fun lagOf(start: Int): Long? {
            val part = SyncProbeSignal(
                reference.samples.copyOfRange(start, start + segment),
                reference.startServerUs + (start * reference.sampleIntervalUs).toLong(),
                reference.sampleIntervalUs,
            )
            return SyncProbeAnalyzer.analyze(part, mic, window, SETTLING_MIN_OVERLAP_US)
                ?.peaks?.firstOrNull()?.lagUs
        }
        val first = lagOf(firstStart) ?: return null
        val last = lagOf(lastStart) ?: return null
        return (last - first) * RATE.toDouble() / (lastStart - firstStart)
    }
}
