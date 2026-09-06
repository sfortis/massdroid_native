package net.asksakis.massdroidv2.data.proximity

/**
 * The one record of every BLE scan start this app makes.
 *
 * Android refuses a scan start once an app has started five scans within thirty seconds,
 * and it refuses silently: the scanner registers with status 6, no result is ever
 * delivered, and `onScanFailed` is not called ("If scanning too frequently, don't report
 * anything to the app" in BluetoothLeScanner). On 2026-09-06 a room calibration started
 * ten scans in fifty seconds; the persistent scan that restarted right after it was
 * accepted and dead for eighteen seconds, until the zero-device recovery restarted it.
 *
 * Every start path reserves its slot through [tryAcquire], so no path can walk into that
 * refusal on its own. The limit is one below the platform's, because
 * the platform also counts a start that we then fail to track (an exception after the
 * registration went through).
 *
 * Timestamps are whatever monotonic clock the caller uses; the class only compares them.
 */
internal class ScanStartBudget(
    private val maxStarts: Int = MAX_STARTS,
    private val windowMs: Long = WINDOW_MS,
) {
    private val starts = ArrayDeque<Long>()

    /** Zero when a start may happen now, otherwise how long to wait for the oldest start to age out. */
    @Synchronized
    fun msUntilAllowed(nowMs: Long): Long {
        prune(nowMs)
        if (starts.size < maxStarts) return 0L
        return (starts.first() + windowMs - nowMs).coerceAtLeast(0L)
    }

    /**
     * Reserve a slot for a start that happens right now. Atomic: two callers racing for the
     * last slot get one true and one false, which is what makes waiting on [msUntilAllowed]
     * safe; a caller re-tries this after every wait instead of assuming the slot is still free.
     * Reserve BEFORE calling the platform, and keep the slot even if the platform then throws:
     * that is the conservative side of the limit.
     */
    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        prune(nowMs)
        if (starts.size >= maxStarts) return false
        starts.addLast(nowMs)
        return true
    }

    private fun prune(nowMs: Long) {
        while (starts.isNotEmpty() && nowMs - starts.first() >= windowMs) starts.removeFirst()
    }

    companion object {
        /** One below Android's `NUM_SCAN_DURATIONS_KEPT` of 5. */
        const val MAX_STARTS = 4
        /** Android's `EXCESSIVE_SCANNING_PERIOD_MS`. */
        const val WINDOW_MS = 30_000L
    }
}
