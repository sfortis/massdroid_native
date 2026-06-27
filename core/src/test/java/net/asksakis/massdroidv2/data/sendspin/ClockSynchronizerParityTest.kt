package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * Parity vectors ported from the sendspin-js reference tests
 * (tests/unit/time-filter.test.ts). Our [ClockSynchronizer] is a port of
 * src/core/time-filter.ts and MUST match it byte-for-byte (a /dt vs /dt^2 slip
 * once cost ~34 ms); these pin it to the reference's own expected values.
 *
 * The reference calls update(measurement, maxError, time) directly. Our
 * processTimeResponse derives those from four NTP timestamps, so [feed] inverts
 * a symmetric path: with serverRecv == serverTx the filter sees exactly
 * measurement=m, maxError=e (e >= 1), dt from t — algebraically identical.
 */
class ClockSynchronizerParityTest {

    private fun ClockSynchronizer.feed(measurement: Long, maxError: Long, timeUs: Long) {
        val pivot = measurement + timeUs - maxError
        processTimeResponse(
            clientTransmittedUs = timeUs - 2 * maxError,
            serverReceivedUs = pivot,
            serverTransmittedUs = pivot,
            clientReceivedUs = timeUs,
        )
    }

    @Test
    fun `becomes synchronized after the first measurement`() {
        val f = ClockSynchronizer()
        f.feed(1000, 500, 100)
        assertThat(f.isSynced()).isTrue()
        assertThat(f.currentSampleCount()).isEqualTo(1)
    }

    @Test
    fun `seeds offset to the first measurement value`() {
        val f = ClockSynchronizer()
        f.feed(5000, 500, 100)
        assertThat(f.rawOffsetUs()).isEqualTo(5000.0)
    }

    @Test
    fun `refines offset with consistent measurements`() {
        val f = ClockSynchronizer()
        for (t in longArrayOf(100_000, 200_000, 300_000, 400_000, 500_000)) f.feed(10_000, 1000, t)
        assertThat(abs(f.rawOffsetUs() - 10_000.0)).isLessThan(500.0)
    }

    @Test
    fun `error decreases with more measurements`() {
        val f = ClockSynchronizer()
        val errors = mutableListOf<Long>()
        for (i in 0 until 10) {
            f.feed(10_000, 1000, (i + 1) * 100_000L)
            errors += f.errorUs()
        }
        assertThat(errors.last()).isLessThan(errors.first())
    }

    @Test
    fun `computeServerTime applies the offset to client time`() {
        val f = ClockSynchronizer()
        f.feed(10_000, 500, 100_000)
        // 200000 + 10000
        assertThat(f.localToServerUs(200_000)).isEqualTo(210_000)
    }

    @Test
    fun `computeClientTime inverts computeServerTime`() {
        val f = ClockSynchronizer()
        f.feed(10_000, 500, 100_000)
        val client = 200_000L
        assertThat(f.serverToLocalUs(f.localToServerUs(client))).isEqualTo(client)
    }

    @Test
    fun `reset clears all state`() {
        val f = ClockSynchronizer()
        f.feed(10_000, 500, 100_000)
        f.feed(10_500, 500, 200_000)
        assertThat(f.isSynced()).isTrue()
        f.reset()
        assertThat(f.isSynced()).isFalse()
        assertThat(f.currentSampleCount()).isEqualTo(0)
        assertThat(f.rawOffsetUs()).isEqualTo(0.0)
        assertThat(f.rawDrift()).isEqualTo(0.0)
    }

    @Test
    fun `estimates drift from two measurements`() {
        val f = ClockSynchronizer()
        f.feed(10_000, 500, 100_000) // offset 10ms at t=100ms
        f.feed(10_100, 500, 200_000) // offset 10.1ms at t=200ms
        assertThat(f.currentSampleCount()).isEqualTo(2)
        // (10100 - 10000) / (200000 - 100000) = 0.001
        assertThat(f.rawDrift()).isWithin(5e-5).of(0.001)
    }

    @Test
    fun `applies the drift term once drift is significant`() {
        val f = ClockSynchronizer()
        // exact linear offset(t) = 10000 + 0.01*t over 30 samples
        for (i in 1..30) f.feed(10_000 + 1000L * i, 200, i * 100_000L)
        val off1 = f.localToServerUs(4_000_000) - 4_000_000
        val off2 = f.localToServerUs(5_000_000) - 5_000_000
        assertThat(off2).isGreaterThan(off1)
        assertThat(abs(f.serverToLocalUs(f.localToServerUs(4_000_000)) - 4_000_000)).isAtMost(5)
    }

    @Test
    fun `recovers toward a large offset jump after sufficient history`() {
        val f = ClockSynchronizer()
        for (i in 1..110) f.feed(10_000, 500, i * 100_000L)
        val before = f.rawOffsetUs()
        f.feed(50_000, 500, 111 * 100_000L)
        assertThat(abs(f.rawOffsetUs() - 50_000.0)).isLessThan(abs(before - 50_000.0))
    }

    @Test
    fun `error reflects the first measurement variance`() {
        val f = ClockSynchronizer()
        f.feed(5000, 400, 100)
        // covariance = maxError^2 = 160000 -> error = sqrt = 400
        assertThat(f.errorUs()).isEqualTo(400)
    }
}
