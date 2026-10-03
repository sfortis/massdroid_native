package net.asksakis.massdroidv2.data.sendspin

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A mono signal sampled at a uniform interval on the SERVER clock.
 *
 * [startServerUs] is the server time of the first sample. [sampleIntervalUs] is
 * the spacing in server microseconds, which differs from 1e6 / rate only by the
 * clock drift between the device that sampled it and the server.
 */
class SyncProbeSignal(
    val samples: FloatArray,
    val startServerUs: Long,
    val sampleIntervalUs: Double,
) {
    val endServerUs: Long get() = startServerUs + (sampleIntervalUs * (samples.size - 1)).toLong()
}

/** One audible copy of the stream: it plays [lagUs] after the server timestamp. */
data class SyncProbePeak(val lagUs: Long, val strength: Double)

data class SyncProbeAnalysis(
    val peaks: List<SyncProbePeak>,
    // Strongest peak against the median correlation level: a measure of how
    // clearly the recording matched the stream at all. Below about 5 the peaks
    // are noise.
    val clarity: Double,
    val overlapUs: Long,
)

/**
 * Finds where each speaker heard in a microphone recording plays, relative to
 * the Sendspin server timestamp.
 *
 * The reference is the decoded stream placed on the server clock by its chunk
 * timestamps. The recording is placed on the server clock through the clock
 * synchroniser. Both are band-limited and resampled onto one grid, and the
 * generalised cross-correlation with phase transform (GCC-PHAT) gives one sharp
 * peak per audible copy of the stream, at the lag by which that copy trails
 * the timestamp. The phase transform whitens the spectrum, so a peak stays
 * sharp through a speaker's colouration and a room's reverberation.
 *
 * Pure computation with no Android dependency, so it is unit tested.
 */
object SyncProbeAnalyzer {
    const val GRID_RATE_HZ = 8000
    /**
     * Lags searched when nothing better is known. The window is kept narrow on
     * purpose: loop-based music matches itself one beat and one bar away (a
     * bar at 124 BPM is 1.935 s), and a +-2 s window found those repeats
     * stronger than the true peak (2026-10-02). A caller that expects a speaker
     * elsewhere, from its current delay, passes a window centred there instead.
     */
    val DEFAULT_WINDOW_US: LongRange = -300_000L..700_000L

    private const val LOWPASS_CUTOFF_HZ = 3200.0
    private const val LOWPASS_TAPS = 63
    private const val BAND_LOW_HZ = 150.0
    private const val MIN_PEAK_FRACTION = 0.15
    private const val PEAK_SEPARATION_US = 3000L
    private const val MAX_PEAKS = 4
    private const val MIN_OVERLAP_US = 2_000_000L

    /**
     * Returns null when the two signals overlap by less than two seconds once
     * the lag window is allowed for.
     */
    fun analyze(
        reference: SyncProbeSignal,
        recording: SyncProbeSignal,
        windowUs: LongRange = DEFAULT_WINDOW_US,
    ): SyncProbeAnalysis? {
        val minLagUs = windowUs.first
        val maxLagUs = windowUs.last
        // The recording window must have reference audio up to maxLagUs before
        // it and minLagUs after it, so every lag in the window is compared
        // against real stream content rather than zeros.
        val micStart = max(recording.startServerUs, reference.startServerUs + maxLagUs)
        val micEnd = min(recording.endServerUs, reference.endServerUs + minLagUs)
        val overlapUs = micEnd - micStart
        if (overlapUs < MIN_OVERLAP_US) return null

        val gridUs = 1_000_000.0 / GRID_RATE_HZ
        val micLen = (overlapUs / gridUs).toInt()
        val refStart = micStart - maxLagUs
        val refLen = micLen + ((maxLagUs - minLagUs) / gridUs).toInt()

        val mic = resampleToGrid(recording, micStart, micLen, gridUs)
        val ref = resampleToGrid(reference, refStart, refLen, gridUs)

        var n = 1
        while (n < refLen + micLen) n = n shl 1
        val micRe = DoubleArray(n)
        val micIm = DoubleArray(n)
        val refRe = DoubleArray(n)
        val refIm = DoubleArray(n)
        for (i in 0 until micLen) micRe[i] = mic[i].toDouble()
        for (i in 0 until refLen) refRe[i] = ref[i].toDouble()
        fft(micRe, micIm, inverse = false)
        fft(refRe, refIm, inverse = false)

        // conj(MIC) * REF, whitened. Bins outside the pass band carry only the
        // filter's residue, and whitening them would amplify noise, so they
        // are zeroed.
        val lowBin = (BAND_LOW_HZ * n / GRID_RATE_HZ).toInt()
        val highBin = (LOWPASS_CUTOFF_HZ * n / GRID_RATE_HZ).toInt()
        val crossRe = DoubleArray(n)
        val crossIm = DoubleArray(n)
        for (k in 0..n / 2) {
            if (k < lowBin || k > highBin) continue
            val re = micRe[k] * refRe[k] + micIm[k] * refIm[k]
            val im = micRe[k] * refIm[k] - micIm[k] * refRe[k]
            val mag = hypot(re, im)
            if (mag <= 0.0) continue
            crossRe[k] = re / mag
            crossIm[k] = im / mag
            if (k != 0 && k != n / 2) {
                crossRe[n - k] = crossRe[k]
                crossIm[n - k] = -crossIm[k]
            }
        }
        fft(crossRe, crossIm, inverse = true)

        // c[k] = sum mic[i] * ref[i + k]. A copy that plays lag after the
        // timestamp matches ref at k = (maxLagUs - lag) / grid.
        val lagCount = refLen - micLen + 1
        val corr = DoubleArray(lagCount) { crossRe[it] }
        return SyncProbeAnalysis(
            peaks = findPeaks(corr, gridUs, maxLagUs),
            clarity = clarity(corr),
            overlapUs = overlapUs,
        )
    }

    private fun findPeaks(corr: DoubleArray, gridUs: Double, maxLagUs: Long): List<SyncProbePeak> {
        val maxValue = corr.maxOrNull() ?: return emptyList()
        if (maxValue <= 0.0) return emptyList()
        val candidates = (1 until corr.size - 1)
            .filter { corr[it] >= corr[it - 1] && corr[it] > corr[it + 1] }
            .filter { corr[it] >= maxValue * MIN_PEAK_FRACTION }
            .sortedByDescending { corr[it] }
        val separation = (PEAK_SEPARATION_US / gridUs).roundToInt()
        val chosen = mutableListOf<Int>()
        for (index in candidates) {
            if (chosen.none { abs(it - index) < separation }) chosen += index
            if (chosen.size == MAX_PEAKS) break
        }
        return chosen.map { index ->
            // Parabolic interpolation through the peak and its neighbours gives
            // a position finer than one grid step.
            val y0 = corr[index - 1]
            val y1 = corr[index]
            val y2 = corr[index + 1]
            val denom = y0 - 2 * y1 + y2
            val offset = if (denom != 0.0) 0.5 * (y0 - y2) / denom else 0.0
            val k = index + offset
            SyncProbePeak(
                lagUs = (maxLagUs - k * gridUs).roundToLong(),
                strength = y1 / maxValue,
            )
        }
    }

    private fun clarity(corr: DoubleArray): Double {
        val sorted = corr.map { abs(it) }.sorted()
        val median = sorted[sorted.size / 2]
        val peak = sorted.last()
        return if (median > 0.0) peak / median else 0.0
    }

    /**
     * Low-pass filters [signal] below the grid's Nyquist rate and samples it by
     * linear interpolation at [count] points [gridUs] apart from [startUs].
     * Points outside the signal read as silence.
     */
    private fun resampleToGrid(signal: SyncProbeSignal, startUs: Long, count: Int, gridUs: Double): FloatArray {
        val sourceRate = 1_000_000.0 / signal.sampleIntervalUs
        val filtered = lowpass(signal.samples, LOWPASS_CUTOFF_HZ / sourceRate)
        val out = FloatArray(count)
        for (i in 0 until count) {
            val position = (startUs + i * gridUs - signal.startServerUs) / signal.sampleIntervalUs
            val i0 = floor(position).toInt()
            if (i0 < 0 || i0 + 1 >= filtered.size) continue
            val frac = (position - i0).toFloat()
            out[i] = filtered[i0] + (filtered[i0 + 1] - filtered[i0]) * frac
        }
        return out
    }

    /** Windowed-sinc FIR low-pass; [cutoff] is a fraction of the sample rate. */
    private fun lowpass(input: FloatArray, cutoff: Double): FloatArray {
        val half = LOWPASS_TAPS / 2
        val taps = DoubleArray(LOWPASS_TAPS) { i ->
            val m = i - half
            val sinc = if (m == 0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
            val blackman = 0.42 - 0.5 * cos(2 * PI * i / (LOWPASS_TAPS - 1)) +
                0.08 * cos(4 * PI * i / (LOWPASS_TAPS - 1))
            sinc * blackman
        }
        val out = FloatArray(input.size)
        for (i in input.indices) {
            var acc = 0.0
            val from = max(0, i - half)
            val to = min(input.size - 1, i + half)
            for (j in from..to) acc += input[j] * taps[j - i + half]
            out[i] = acc.toFloat()
        }
        return out
    }

    /** In-place iterative radix-2 FFT; the size must be a power of two. */
    internal fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        val sign = if (inverse) 1.0 else -1.0
        while (len <= n) {
            val angle = sign * 2 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
}
