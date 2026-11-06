package net.asksakis.massdroidv2.data.proximity

/**
 * Whether Follow Me must read the buffer once more before it suspends both scans.
 *
 * A suspended scan hears nothing until the next motion, so devices that arrived after the
 * last read would never be scored. On 2026-11-06 the loop suspended at the office with 11
 * devices in the buffer that no read had seen, and the phone then lay still for over an
 * hour. Devices the last read returned do not count, so a still phone whose anchors keep
 * reporting reaches the suspend after that one read.
 */
object ScanSuspendPolicy {
    fun readBeforeSuspend(bufferedDevices: Set<String>, lastReadDevices: Set<String>): Boolean =
        bufferedDevices.any { it !in lastReadDevices }
}
