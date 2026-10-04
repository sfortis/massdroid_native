package net.asksakis.massdroidv2.service

import android.util.Log
import kotlinx.coroutines.delay
import net.asksakis.massdroidv2.data.proximity.AnchorType
import net.asksakis.massdroidv2.data.proximity.ProximityConfig
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.ScanStartResult

class ProximityScanController(
    private val proximityScanner: ProximityScanner,
) {
    companion object {
        private const val TAG = "ProximityScan"
        private const val HIGH_ACCURACY_SNAPSHOT_RETAIN_MS = 30_000L
        private const val HIGH_ACCURACY_FRESH_SNAPSHOT_MS = 8_000L
        private const val BLE_DEBUG_DEVICE_LIMIT = 8

        /**
         * Floor between restarts of a RUNNING persistent scan (a mode switch or a filter
         * refresh). The scan-start budget is what keeps us under Android's limit; this floor
         * additionally coalesces rapid flips so the settings screen toggling "live monitoring"
         * cannot spend the whole budget on mode changes. The device buffer stays warm meanwhile.
         */
        private const val MIN_SCAN_RESTART_INTERVAL_MS = 10_000L

        /** Retry spacing after a start the platform refused, doubling up to the cap. */
        private const val START_BACKOFF_MIN_MS = 5_000L
        private const val START_BACKOFF_MAX_MS = 60_000L

        /** Zero-device reads before the scan is presumed dead and restarted. */
        private const val RECOVERY_ZERO_READS = 5

        /**
         * Spacing of dead-scan recoveries, doubling up to the cap while reads stay empty. Away
         * from every anchor the filtered scan legitimately hears nothing, and a fixed 10 s
         * spacing restarted it three times a minute for as long as the phone was out.
         */
        private const val RECOVERY_BACKOFF_MAX_MS = 5 * 60_000L
    }

    private data class AnchorFilters(val macs: Set<String>, val names: Set<String>)

    private var persistentScanLowPower: Boolean? = null
    private var persistentAnchors: AnchorFilters? = null
    private var lastScanRestartMs = 0L
    private var startBackoffMs = 0L
    private var nextStartAttemptMs = 0L
    private var recoveryBackoffMs = MIN_SCAN_RESTART_INTERVAL_MS
    private var lastRecoveryMs = 0L

    /**
     * Reconcile what is wanted with what runs: the persistent scan in the requested mode with
     * the config's current anchor filters, and a restart when it has died or gone silent. Called on every main-loop cycle and on
     * every motion path, which is what turns a deferred or failed start into a retry, and what
     * picks up a calibration save (new anchors) without restarting the engine.
     */
    fun ensureScans(lowPower: Boolean, config: ProximityConfig) {
        ensurePersistentScan(lowPower, config)
        recoverScannerIfNeeded(highAccuracy = !lowPower, config = config)
    }

    private fun ensurePersistentScan(lowPower: Boolean, config: ProximityConfig) {
        val now = System.currentTimeMillis()
        val wanted = anchorFilters(config)
        val running = proximityScanner.isPersistentScanRunning
        if (running && persistentScanLowPower == lowPower && persistentAnchors == wanted) return
        if (!running && persistentScanLowPower != null) {
            // Accepted, then reported failed (onScanFailed). Retrying on every cycle would spend
            // the budget on a stack that is refusing us; retry on the backoff instead.
            persistentScanLowPower = null
            persistentAnchors = null
            scheduleStartBackoff(now)
            Log.w(TAG, "Persistent scan died; restart in ${startBackoffMs}ms")
            return
        }
        if (now < nextStartAttemptMs) return
        if (running && now - lastScanRestartMs < MIN_SCAN_RESTART_INTERVAL_MS) return
        if (running && persistentScanLowPower != lowPower) {
            Log.d(
                TAG,
                "Persistent mode switch: " +
                    "${if (persistentScanLowPower == true) "LOW_POWER" else "LOW_LATENCY"} -> " +
                    if (lowPower) "LOW_POWER" else "LOW_LATENCY"
            )
        }
        if (running && persistentAnchors != wanted) {
            Log.d(TAG, "Persistent scan filters refreshed: ${wanted.macs.size} MAC + ${wanted.names.size} name anchors")
        }
        when (proximityScanner.startPersistentScan(lowPower, wanted.macs, wanted.names)) {
            ScanStartResult.STARTED -> {
                persistentScanLowPower = lowPower
                persistentAnchors = wanted
                lastScanRestartMs = now
                startBackoffMs = 0L
            }
            ScanStartResult.DEFERRED -> Unit // budget; the next cycle asks again
            ScanStartResult.FAILED -> scheduleStartBackoff(now)
        }
    }

    private fun anchorFilters(config: ProximityConfig): AnchorFilters {
        val bleRooms = config.rooms.filter { it.wifiMatchMode == null }
        val macs = bleRooms
            .flatMap { room -> room.beaconProfiles.filter { it.anchorType == AnchorType.MAC }.map { it.address } }
            .filter { !it.startsWith("wifi:") }
            .toSet()
        val names = bleRooms
            .flatMap { room -> room.beaconProfiles.filter { it.anchorType == AnchorType.NAME }.map { it.name } }
            .filter { it.isNotBlank() }
            .toSet()
        return AnchorFilters(macs, names)
    }

    private fun scheduleStartBackoff(now: Long) {
        startBackoffMs = (startBackoffMs * 2).coerceIn(START_BACKOFF_MIN_MS, START_BACKOFF_MAX_MS)
        nextStartAttemptMs = now + startBackoffMs
    }

    fun stopPersistentScan(clearBuffers: Boolean = true) {
        proximityScanner.stopPersistentScan(clearBuffers = clearBuffers)
        persistentScanLowPower = null
        persistentAnchors = null
    }

    /**
     * Restart a persistent scan that reports running but has delivered nothing for
     * [RECOVERY_ZERO_READS] reads. Android's silent refusal ("scanning too frequently")
     * looks exactly like this from the inside, and so does a stack that stopped delivering.
     * Runs from [ensureScans], so the screen-off paths recover too; they used to depend on
     * the screen-on loop for it.
     */
    private fun recoverScannerIfNeeded(highAccuracy: Boolean, config: ProximityConfig) {
        val streak = proximityScanner.zeroDeviceStreak
        if (streak == 0) {
            recoveryBackoffMs = MIN_SCAN_RESTART_INTERVAL_MS
            return
        }
        if (streak < RECOVERY_ZERO_READS) return
        val now = System.currentTimeMillis()
        if (now - lastScanRestartMs < MIN_SCAN_RESTART_INTERVAL_MS) return
        if (now - lastRecoveryMs < recoveryBackoffMs) return
        Log.w(TAG, "Scanner recovery: $streak zero-device reads (next no sooner than ${recoveryBackoffMs * 2}ms)")
        lastRecoveryMs = now
        recoveryBackoffMs = (recoveryBackoffMs * 2).coerceAtMost(RECOVERY_BACKOFF_MAX_MS)
        stopPersistentScan()
        ensurePersistentScan(lowPower = !highAccuracy, config = config)
        proximityScanner.zeroDeviceStreak = 0
        // Deliberately NOT resetting the detector's no-match streak. Restarting the scanner
        // says nothing about whether the user is still near a known room, and an empty
        // buffer is the NORMAL state away from home now that scans are filtered. Zeroing it
        // here was a second reason "left all rooms" never fired: between this and the
        // warmup grace the streak could not survive long enough to reach its threshold.
    }

    /** Cancel the batch scan an earlier build left registered; see [ProximityScanner.stopBackgroundScan]. */
    fun stopBackgroundScan() {
        proximityScanner.stopBackgroundScan()
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
                "running=${before.persistentRunning})"
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
                    "running=${after.persistentRunning})"
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
