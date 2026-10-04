package net.asksakis.massdroidv2.data.sendspin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class OutputLatencyMeasurementTest {

    private val rate = OutputLatencyMeasurement.RATE
    private val frame0Us = 9_000_000_000L

    /**
     * What the microphone records when the output presents the signal at
     * [frame0Us] and the sound arrives [latencyUs] later, plus each extra
     * (delay after the direct sound, gain) reflection and noise. The recording
     * starts [startBeforeUs] before frame 0 and lasts [seconds].
     */
    private fun recording(
        signal: ShortArray,
        latencyUs: Long,
        reflections: List<Pair<Long, Float>> = emptyList(),
        noise: Float = 0.02f,
        startBeforeUs: Long = 200_000L,
        seconds: Int = 6,
        gain: Float = 0.3f,
        rampUsPerS: Long = 0L,
    ): Pair<FloatArray, Long> {
        val random = Random(7)
        val originUs = frame0Us - startBeforeUs
        val copies = listOf(latencyUs to gain) + reflections.map { (d, g) -> latencyUs + d to g }
        val samples = FloatArray(rate * seconds) { i ->
            val tUs = originUs + i * 1_000_000L / rate
            var v = (random.nextFloat() * 2f - 1f) * noise
            // A path still settling: the delay grows by rampUsPerS every second.
            val ramp = (tUs - frame0Us) * rampUsPerS / 1_000_000L
            for ((lag, g) in copies) {
                val index = ((tUs - lag - ramp - frame0Us) * rate / 1_000_000L).toInt()
                if (index in signal.indices) v += g * signal[index] / 32768f
            }
            v
        }
        return samples to originUs
    }

    private fun measured(result: OutputLatencyResult): Long {
        assertTrue("expected a measurement, got $result", result is OutputLatencyResult.Measured)
        return (result as OutputLatencyResult.Measured).latencyUs
    }

    @Test
    fun `signal is the same every time`() {
        assertArrayEquals(OutputLatencyMeasurement.signal(), OutputLatencyMeasurement.signal())
    }

    @Test
    fun `signal starts with silence and then sounds`() {
        val signal = OutputLatencyMeasurement.signal()
        val lead = rate * OutputLatencyMeasurement.LEAD_SILENCE_MS / 1000
        assertTrue((0 until lead).all { signal[it].toInt() == 0 })
        assertTrue(signal.drop(lead).any { abs(it.toInt()) > 10_000 })
    }

    @Test
    fun `finds a speaker 21 ms late`() {
        val signal = OutputLatencyMeasurement.signal()
        val (mic, origin) = recording(signal, latencyUs = 21_000L)
        val lag = measured(OutputLatencyMeasurement.analyze(signal, frame0Us, mic, origin))
        assertEquals(21_000.0, lag.toDouble(), 500.0)
    }

    @Test
    fun `finds a Bluetooth speaker 350 ms late through reflections`() {
        val signal = OutputLatencyMeasurement.signal()
        val (mic, origin) = recording(
            signal,
            latencyUs = 350_000L,
            reflections = listOf(4_000L to 0.15f, 9_000L to 0.1f),
            noise = 0.05f,
        )
        val lag = measured(OutputLatencyMeasurement.analyze(signal, frame0Us, mic, origin))
        assertEquals(350_000.0, lag.toDouble(), 500.0)
    }

    @Test
    fun `a timestamp slightly behind the sound gives a small negative latency`() {
        val signal = OutputLatencyMeasurement.signal()
        val (mic, origin) = recording(signal, latencyUs = -3_000L)
        val lag = measured(OutputLatencyMeasurement.analyze(signal, frame0Us, mic, origin))
        assertEquals(-3_000.0, lag.toDouble(), 500.0)
    }

    @Test
    fun `rejects a recording that never heard the signal`() {
        val signal = OutputLatencyMeasurement.signal()
        val (mic, origin) = recording(signal, latencyUs = 21_000L, gain = 0f, noise = 0.1f)
        val result = OutputLatencyMeasurement.analyze(signal, frame0Us, mic, origin)
        assertTrue("expected a rejection, got $result", result is OutputLatencyResult.Rejected)
    }

    @Test
    fun `rejects a recording that does not cover the signal`() {
        val signal = OutputLatencyMeasurement.signal()
        val (mic, origin) = recording(signal, latencyUs = 21_000L, seconds = 2)
        val result = OutputLatencyMeasurement.analyze(signal, frame0Us, mic, origin)
        assertTrue("expected a rejection, got $result", result is OutputLatencyResult.Rejected)
    }

    @Test
    fun `a steady path is settled`() {
        val signal = OutputLatencyMeasurement.signal()
        val (samples, origin) = recording(signal, latencyUs = 165_000L)
        val result = OutputLatencyMeasurement.analyze(signal, frame0Us, samples, origin)
        assertTrue("expected a measurement, got $result", result is OutputLatencyResult.Measured)
        result as OutputLatencyResult.Measured
        assertTrue("settling ${result.settlingUsPerS}", result.settled)
    }

    @Test
    fun `a path that grows its delay is not settled`() {
        // The CREATIVE MUVO 2 grew its delay by about 1.4 ms per second after audio started.
        val signal = OutputLatencyMeasurement.signal()
        val (samples, origin) = recording(signal, latencyUs = 30_000L, rampUsPerS = 1_400L)
        val result = OutputLatencyMeasurement.analyze(signal, frame0Us, samples, origin)
        assertTrue("expected a measurement, got $result", result is OutputLatencyResult.Measured)
        result as OutputLatencyResult.Measured
        // The estimate is coarse (the chirps are not spread evenly over the two
        // halves it compares); what matters is that it is far above the limit.
        val settling = result.settlingUsPerS
        assertTrue("settling $settling", settling != null && settling in 1_000.0..2_500.0)
        assertTrue(!result.settled)
    }
}
