package net.asksakis.massdroidv2.data.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SyncProbeAnalyzerTest {

    private val serverStartUs = 5_000_000_000L

    /** Band-limited noise standing in for music, at [rate] Hz on the server clock. */
    private fun stream(rate: Int, seconds: Int, seed: Int): SyncProbeSignal {
        val random = Random(seed)
        val samples = FloatArray(rate * seconds)
        var smooth = 0f
        for (i in samples.indices) {
            smooth = smooth * 0.6f + (random.nextFloat() * 2f - 1f) * 0.4f
            samples[i] = smooth
        }
        return SyncProbeSignal(samples, serverStartUs, 1_000_000.0 / rate)
    }

    /** Reads [source] at server time [us] by linear interpolation, 0 outside it. */
    private fun sampleAt(source: SyncProbeSignal, us: Double): Float {
        val position = (us - source.startServerUs) / source.sampleIntervalUs
        val i0 = position.toInt()
        if (position < 0 || i0 + 1 >= source.samples.size) return 0f
        val frac = (position - i0).toFloat()
        return source.samples[i0] + (source.samples[i0 + 1] - source.samples[i0]) * frac
    }

    /**
     * A recording that starts [startOffsetUs] into the stream and hears each
     * (lag, gain) copy, plus noise, sampled at [rate] Hz.
     */
    private fun recording(
        source: SyncProbeSignal,
        copies: List<Pair<Long, Float>>,
        rate: Int,
        startOffsetUs: Long,
        seconds: Int,
        noise: Float = 0.05f,
    ): SyncProbeSignal {
        val random = Random(99)
        val interval = 1_000_000.0 / rate
        val start = source.startServerUs + startOffsetUs
        val samples = FloatArray(rate * seconds) { i ->
            val t = start + i * interval
            var v = (random.nextFloat() * 2f - 1f) * noise
            for ((lag, gain) in copies) v += gain * sampleAt(source, t - lag)
            v
        }
        return SyncProbeSignal(samples, start, interval)
    }

    private fun assertPeakNear(analysis: SyncProbeAnalysis, expectedLagUs: Long) {
        val nearest = analysis.peaks.minByOrNull { abs(it.lagUs - expectedLagUs) }
        assertNotNull("no peaks at all", nearest)
        assertTrue(
            "no peak within 300 us of $expectedLagUs, peaks=${analysis.peaks}",
            abs(nearest!!.lagUs - expectedLagUs) <= 300,
        )
    }

    @Test
    fun `finds a single copy that plays 200 ms after the timestamp`() {
        val source = stream(48_000, 12, seed = 1)
        val mic = recording(source, listOf(200_000L to 1f), 48_000, startOffsetUs = 3_000_000, seconds = 6)

        val analysis = SyncProbeAnalyzer.analyze(source, mic)

        assertNotNull(analysis)
        assertEquals(200_000.0, analysis!!.peaks.first().lagUs.toDouble(), 300.0)
        assertTrue("clarity ${analysis.clarity}", analysis.clarity > 10)
    }

    @Test
    fun `separates two speakers and ranks the louder first`() {
        val source = stream(48_000, 12, seed = 2)
        val mic = recording(
            source,
            listOf(203_000L to 1f, 4_000L to 0.4f),
            48_000,
            startOffsetUs = 3_000_000,
            seconds = 6,
        )

        val analysis = SyncProbeAnalyzer.analyze(source, mic)!!

        assertEquals(203_000.0, analysis.peaks[0].lagUs.toDouble(), 300.0)
        assertPeakNear(analysis, 4_000L)
    }

    @Test
    fun `measures a speaker that plays before the timestamp`() {
        val source = stream(48_000, 12, seed = 3)
        val mic = recording(source, listOf(-62_000L to 1f), 48_000, startOffsetUs = 3_000_000, seconds = 6)

        val analysis = SyncProbeAnalyzer.analyze(source, mic)!!

        assertEquals(-62_000.0, analysis.peaks.first().lagUs.toDouble(), 300.0)
    }

    @Test
    fun `finds a speaker whose delay puts it more than a second early`() {
        // A static delay dragged to 1595 ms put the Pi about 1.3 s early.
        val source = stream(48_000, 14, seed = 8)
        val mic = recording(source, listOf(-1_300_000L to 1f), 48_000, startOffsetUs = 4_000_000, seconds = 6)

        // The window a static delay of 1595 ms centres on: -1.6 s, -300..+700 ms around it.
        val analysis = SyncProbeAnalyzer.analyze(source, mic, windowUs = -1_895_000L..-895_000L)!!

        assertEquals(-1_300_000.0, analysis.peaks.first().lagUs.toDouble(), 300.0)
    }

    @Test
    fun `loop-based music does not pull the peak a bar away in the default window`() {
        // A one-bar loop at 124 BPM (1.935 s) repeated: every lag a whole bar
        // away matches as well as the true one, so only the window can tell.
        val bar = stream(48_000, 2, seed = 9).samples.copyOf(92_880)
        val samples = FloatArray(48_000 * 14) { bar[it % bar.size] }
        val source = SyncProbeSignal(samples, serverStartUs, 1_000_000.0 / 48_000)
        val mic = recording(source, listOf(20_000L to 1f), 48_000, startOffsetUs = 4_000_000, seconds = 6)

        val analysis = SyncProbeAnalyzer.analyze(source, mic)!!

        assertEquals(20_000.0, analysis.peaks.first().lagUs.toDouble(), 300.0)
    }

    @Test
    fun `works when the stream rate differs from the microphone rate`() {
        val source = stream(44_100, 12, seed = 4)
        val mic = recording(source, listOf(150_000L to 1f), 48_000, startOffsetUs = 3_000_000, seconds = 6)

        val analysis = SyncProbeAnalyzer.analyze(source, mic)!!

        assertEquals(150_000.0, analysis.peaks.first().lagUs.toDouble(), 300.0)
    }

    @Test
    fun `an unrelated recording has no clear peak`() {
        val source = stream(48_000, 12, seed = 5)
        val other = stream(48_000, 12, seed = 6)
        val mic = recording(other, listOf(100_000L to 1f), 48_000, startOffsetUs = 3_000_000, seconds = 6)

        val analysis = SyncProbeAnalyzer.analyze(source, mic)!!

        assertTrue("clarity ${analysis.clarity}", analysis.clarity < 8)
    }

    @Test
    fun `returns null when the recording does not overlap the stream`() {
        val source = stream(48_000, 4, seed = 7)
        val mic = recording(source, listOf(0L to 1f), 48_000, startOffsetUs = 10_000_000, seconds = 3)

        assertNull(SyncProbeAnalyzer.analyze(source, mic))
    }
}
