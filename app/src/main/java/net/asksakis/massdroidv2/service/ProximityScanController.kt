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
    }

    private var persistentScanLowPower: Boolean? = null

    fun ensurePersistentScan(lowPower: Boolean, config: ProximityConfig) {
        if (persistentScanLowPower == lowPower) return
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
    }

    fun stopPersistentScan(clearBuffers: Boolean = true) {
        proximityScanner.stopPersistentScan(clearBuffers = clearBuffers)
        persistentScanLowPower = null
    }

    fun recoverScannerIfNeeded(highAccuracy: Boolean, config: ProximityConfig, roomDetector: RoomDetector) {
        if (proximityScanner.zeroDeviceStreak < 5) return
        Log.w(TAG, "Scanner recovery: ${proximityScanner.zeroDeviceStreak} zero-device reads")
        stopPersistentScan()
        ensurePersistentScan(lowPower = !highAccuracy, config = config)
        proximityScanner.zeroDeviceStreak = 0
        roomDetector.resetNoMatchStreak()
    }

    fun startBackgroundScanForConfig(config: ProximityConfig) {
        val hasNameAnchors = config.rooms.any { room ->
            room.beaconProfiles.any { it.anchorType == AnchorType.NAME }
        }
        val macAddresses = config.rooms
            .flatMap { room ->
                room.beaconProfiles
                    .filter { it.anchorType == AnchorType.MAC }
                    .map { it.address }
            }
            .filter { !it.startsWith("wifi:") }
            .toSet()

        if (hasNameAnchors) {
            proximityScanner.startBackgroundScan(emptySet())
        } else if (macAddresses.isNotEmpty()) {
            proximityScanner.startBackgroundScan(macAddresses)
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
