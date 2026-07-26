package net.asksakis.massdroidv2.service

import android.util.Log
import kotlinx.coroutines.delay
import net.asksakis.massdroidv2.data.proximity.AnchorType
import net.asksakis.massdroidv2.data.proximity.ProximityConfig
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomDetector

class ProximityScanController(
    private val proximityScanner: ProximityScanner,
) {
    companion object {
        private const val TAG = "ProximityScan"
        private const val HIGH_ACCURACY_SNAPSHOT_RETAIN_MS = 30_000L
        private const val HIGH_ACCURACY_FRESH_SNAPSHOT_MS = 8_000L
        private const val BLE_DEBUG_DEVICE_LIMIT = 8

        /**
         * Floor between BLE scan restarts. Android throttles (and the OS flags battery drain on)
         * any app that starts a scan more than 5 times per 30s. A mode switch or a scanner recovery
         * is a stop+start, so we coalesce rapid flips: at most ~1 restart per this interval keeps us
         * safely under the limit (~3/30s). The device buffer stays warm in the meantime.
         */
        private const val MIN_SCAN_RESTART_INTERVAL_MS = 10_000L
    }

    private var persistentScanLowPower: Boolean? = null
    private var lastScanRestartMs = 0L

    fun ensurePersistentScan(lowPower: Boolean, config: ProximityConfig) {
        if (persistentScanLowPower == lowPower) return
        // Defer mode flips that arrive too soon after the last restart. A scan is already running
        // (persistentScanLowPower != null) so we lose nothing but the higher/lower duty cycle for a
        // few seconds; the caller (main loop) re-requests and the switch applies once the floor
        // elapses. This is what keeps us under Android's BLE scan-restart throttle.
        val now = System.currentTimeMillis()
        if (persistentScanLowPower != null && now - lastScanRestartMs < MIN_SCAN_RESTART_INTERVAL_MS) {
            return
        }
        if (persistentScanLowPower != null) {
            Log.d(
                TAG,
                "Persistent mode switch: " +
                    "${if (persistentScanLowPower == true) "LOW_POWER" else "LOW_LATENCY"} -> " +
                    if (lowPower) "LOW_POWER" else "LOW_LATENCY"
            )
        }
        stopPersistentScan(clearBuffers = false)
        val macAnchors = config.rooms
            .filter { it.wifiMatchMode == null }
            .flatMap { room ->
                room.beaconProfiles
                    .filter { it.anchorType == AnchorType.MAC }
                    .map { it.address }
            }
            .filter { !it.startsWith("wifi:") }
            .toSet()
        val nameAnchors = config.rooms
            .filter { it.wifiMatchMode == null }
            .flatMap { room ->
                room.beaconProfiles
                    .filter { it.anchorType == AnchorType.NAME }
                    .map { it.name }
            }
            .filter { it.isNotBlank() }
            .toSet()
        proximityScanner.startPersistentScan(
            lowPower = lowPower,
            anchorAddresses = macAnchors,
            anchorNames = nameAnchors
        )
        persistentScanLowPower = lowPower
        lastScanRestartMs = now
    }

    fun stopPersistentScan(clearBuffers: Boolean = true) {
        proximityScanner.stopPersistentScan(clearBuffers = clearBuffers)
        persistentScanLowPower = null
    }

    fun recoverScannerIfNeeded(highAccuracy: Boolean, config: ProximityConfig, roomDetector: RoomDetector) {
        if (proximityScanner.zeroDeviceStreak < 5) return
        // Recovery is a stop+start; rate-limit it like any restart. Without this, a streak of
        // zero-device reads (no beacons in range) thrashes the scanner every cycle and trips the
        // OS scan-restart throttle. Leave the streak intact so we retry once the floor elapses.
        if (System.currentTimeMillis() - lastScanRestartMs < MIN_SCAN_RESTART_INTERVAL_MS) return
        Log.w(TAG, "Scanner recovery: ${proximityScanner.zeroDeviceStreak} zero-device reads")
        stopPersistentScan()
        ensurePersistentScan(lowPower = !highAccuracy, config = config)
        proximityScanner.zeroDeviceStreak = 0
        roomDetector.resetNoMatchStreak()
    }

    /**
     * (Re)start the PendingIntent batch scan, which only ever carries MAC filters.
     *
     * Batch scan filters are offloaded to the BLE controller, and that hardware can
     * only match addresses/UUIDs, not advertised names. This used to fall back to a
     * match-all filter whenever ANY room had a NAME anchor, which made the app an
     * always-on unoptimized scanner: it woke the process for every advertisement in
     * range (233k results / 34h on-battery, flagged by the OS as unoptimized) purely
     * to catch the handful of name anchors. Those name anchors are already covered by
     * the regular persistent scan, which carries real name filters and therefore also
     * survives screen-off (see ProximityScanner.startPersistentScan). Both scans feed
     * the same device buffer, so dropping the match-all fallback loses no coverage.
     */
    fun startBackgroundScanForConfig(config: ProximityConfig) {
        val macAddresses = config.rooms
            .flatMap { room ->
                room.beaconProfiles
                    .filter { it.anchorType == AnchorType.MAC }
                    .map { it.address }
            }
            .filter { !it.startsWith("wifi:") }
            .toSet()

        if (macAddresses.isNotEmpty()) {
            proximityScanner.startBackgroundScan(macAddresses)
        } else {
            proximityScanner.stopBackgroundScan()
        }
    }

    suspend fun readFastPathSnapshotWithWarmRetry(
        logPrefix: String,
        detailPrefix: String
    ): List<ProximityScanner.ScannedDevice> {
        var devices = readDetectionSnapshot(preferFresh = true)
        if (devices.isNotEmpty()) return devices

        val before = proximityScanner.snapshotDebugState()
        Log.d(
            TAG,
            "$logPrefix ($detailPrefix): empty snapshot, warming " +
                "(buffer=${before.bufferSize}, freshest=${before.freshestAgeMs}ms, " +
                "oldest=${before.oldestAgeMs}ms, lastPersistent=${before.lastPersistentCallbackAgeMs}ms, " +
                "lastBackground=${before.lastBackgroundDeliveryAgeMs}ms, running=${before.persistentRunning})"
        )

        delay(750)
        devices = readDetectionSnapshot(preferFresh = true)
        if (devices.isEmpty()) {
            val after = proximityScanner.snapshotDebugState()
            Log.d(
                TAG,
                "$logPrefix ($detailPrefix): still empty after warm retry " +
                    "(buffer=${after.bufferSize}, freshest=${after.freshestAgeMs}ms, " +
                    "oldest=${after.oldestAgeMs}ms, lastPersistent=${after.lastPersistentCallbackAgeMs}ms, " +
                    "lastBackground=${after.lastBackgroundDeliveryAgeMs}ms, running=${after.persistentRunning})"
            )
        }
        return devices
    }

    fun readDetectionSnapshot(preferFresh: Boolean): List<ProximityScanner.ScannedDevice> {
        if (!preferFresh) return proximityScanner.readSnapshot()

        val freshDevices = proximityScanner.readSnapshot(
            retainMs = HIGH_ACCURACY_FRESH_SNAPSHOT_MS,
            pruneMs = HIGH_ACCURACY_SNAPSHOT_RETAIN_MS
        )
        if (freshDevices.size >= 3) return freshDevices

        return proximityScanner.readSnapshot(
            retainMs = HIGH_ACCURACY_SNAPSHOT_RETAIN_MS,
            pruneMs = HIGH_ACCURACY_SNAPSHOT_RETAIN_MS
        )
    }

    fun buildDetectionAnchorSnapshot(
        devices: Collection<ProximityScanner.ScannedDevice>,
        config: ProximityConfig
    ): Map<String, Int> = proximityScanner.buildAnchorSnapshot(
        devices,
        preferredNameAnchors(config)
    )

    fun logBleDevices(
        trigger: String,
        devices: List<ProximityScanner.ScannedDevice>,
        config: ProximityConfig
    ) {
        if (devices.isEmpty()) return
        val summary = devices
            .sortedByDescending { it.rssi }
            .take(BLE_DEBUG_DEVICE_LIMIT)
            .joinToString("; ") { device ->
                val name = device.name?.takeIf { it.isNotBlank() } ?: "Unknown"
                "$name | ${device.address} | ${device.rssi}dBm | ${device.addressType} | ${device.category}"
            }
        Log.d(TAG, "BLE devices ($trigger): $summary")
        val preferredNameAnchors = preferredNameAnchors(config)
        val anchorSummary = devices
            .groupBy { proximityScanner.classifyAnchorIdentity(it, preferredNameAnchors).key }
            .values
            .map { grouped ->
                val strongest = grouped.maxBy { it.rssi }
                val identity = proximityScanner.classifyAnchorIdentity(strongest, preferredNameAnchors)
                "${identity.displayName} => ${identity.key} | ${strongest.rssi}dBm | ${identity.type}"
            }
            .take(BLE_DEBUG_DEVICE_LIMIT)
            .joinToString("; ")
        Log.d(TAG, "BLE anchors ($trigger): $anchorSummary")
    }

    private fun preferredNameAnchors(config: ProximityConfig): Set<String> = config.rooms
        .flatMap { room ->
            room.beaconProfiles
                .filter { it.anchorType == AnchorType.NAME }
                .map { it.anchorKey }
        }
        .toSet()
}
