package net.asksakis.massdroidv2.data.proximity

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.proximity.ScannerRecoveryPolicy.Health
import org.junit.Test

/**
 * A running scan is restarted only after a run of empty reads with nothing in its buffer.
 */
class ScannerRecoveryPolicyTest {

    /**
     * The office arrival on 2026-11-06: at 12:01:49 the streak stood at 7 empty reads, but
     * 4 devices had just arrived, and the restart cleared them.
     */
    @Test
    fun `a scan whose buffer holds devices is not restarted`() {
        assertThat(ScannerRecoveryPolicy.health(zeroDeviceStreak = 7, bufferedDevices = 4))
            .isEqualTo(Health.DELIVERING)
    }

    /** The drive to the office: empty reads and an empty buffer still restart the scan. */
    @Test
    fun `a run of empty reads with an empty buffer restarts the scan`() {
        assertThat(ScannerRecoveryPolicy.health(zeroDeviceStreak = 7, bufferedDevices = 0))
            .isEqualTo(Health.SILENT)
    }

    @Test
    fun `the restart waits for enough empty reads`() {
        assertThat(ScannerRecoveryPolicy.health(zeroDeviceStreak = 4, bufferedDevices = 0))
            .isEqualTo(Health.QUIET)
        assertThat(ScannerRecoveryPolicy.health(zeroDeviceStreak = 5, bufferedDevices = 0))
            .isEqualTo(Health.SILENT)
    }

    @Test
    fun `no empty reads means the scan delivers`() {
        assertThat(ScannerRecoveryPolicy.health(zeroDeviceStreak = 0, bufferedDevices = 0))
            .isEqualTo(Health.DELIVERING)
    }
}
