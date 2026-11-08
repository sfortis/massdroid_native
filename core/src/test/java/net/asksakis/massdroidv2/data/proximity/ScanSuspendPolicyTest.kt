package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Follow Me reads the buffer once more before it suspends both scans when the buffer holds
 * devices that no read has returned, and suspends straight away otherwise.
 */
class ScanSuspendPolicyTest {

    private val office = (1..11).map { "A0:0$it:B8:14:54:30" }.toSet()

    /**
     * The office arrival on 2026-11-06: the read at 12:01:49 returned nothing, and at
     * 12:02:49 the loop suspended over 11 buffered devices that no read had seen.
     */
    @Test
    fun `devices no read has seen are read before the suspend`() {
        assertThat(ScanSuspendPolicy.readBeforeSuspend(office, lastReadDevices = emptySet())).isTrue()
    }

    /** One device more than the last read returned is enough. */
    @Test
    fun `a device that arrived after the last read is read before the suspend`() {
        val lastRead = office - "A0:01:B8:14:54:30"
        assertThat(ScanSuspendPolicy.readBeforeSuspend(office, lastRead)).isTrue()
    }

    /** A still phone whose anchors keep reporting must reach the suspend after that one read. */
    @Test
    fun `devices the last read returned do not hold the suspend`() {
        assertThat(ScanSuspendPolicy.readBeforeSuspend(office, lastReadDevices = office)).isFalse()
    }

    @Test
    fun `devices that left the buffer since the last read do not hold the suspend`() {
        val buffered = office - "A0:01:B8:14:54:30"
        assertThat(ScanSuspendPolicy.readBeforeSuspend(buffered, lastReadDevices = office)).isFalse()
    }

    @Test
    fun `an empty buffer suspends straight away`() {
        assertThat(ScanSuspendPolicy.readBeforeSuspend(emptySet(), lastReadDevices = emptySet())).isFalse()
    }
}
