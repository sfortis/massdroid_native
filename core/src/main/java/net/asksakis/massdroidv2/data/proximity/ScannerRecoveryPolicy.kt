package net.asksakis.massdroidv2.data.proximity

/** Zero-device reads before a running scan is presumed dead and restarted. */
private const val RECOVERY_ZERO_READS = 5

/**
 * Whether a persistent scan that reports running has gone silent. Android's silent refusal
 * ("scanning too frequently") and a stack that stopped delivering both look like a run of
 * empty reads, and a restart is the cure; the caller still spaces restarts out.
 *
 * A buffer that holds devices proves the scan delivers, whatever the streak of past reads
 * says. On 2026-11-06 a restart on a streak of 7 cleared 4 devices that had just arrived at
 * the office, and the read right after it found nothing.
 */
object ScannerRecoveryPolicy {
    enum class Health {
        /** Devices are arriving; any recovery backoff starts over. */
        DELIVERING,

        /** Empty reads, but too few to call the scan dead. */
        QUIET,

        /** Enough empty reads and an empty buffer: restart the scan. */
        SILENT
    }

    fun health(zeroDeviceStreak: Int, bufferedDevices: Int): Health = when {
        zeroDeviceStreak == 0 || bufferedDevices > 0 -> Health.DELIVERING
        zeroDeviceStreak < RECOVERY_ZERO_READS -> Health.QUIET
        else -> Health.SILENT
    }
}
