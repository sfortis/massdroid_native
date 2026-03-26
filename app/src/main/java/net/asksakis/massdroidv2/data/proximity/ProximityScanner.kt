package net.asksakis.massdroidv2.data.proximity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import net.asksakis.massdroidv2.data.proximity.AnchorType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProximityScanner"
private const val SCAN_DURATION_MS = 5_000L
private const val DEVICE_RETAIN_MS = 15_000L
private const val MIN_VALID_RSSI = -126
private const val MAX_VALID_RSSI = 20
private const val MIN_CONNECTED_WIFI_RSSI = -90
private const val INVALID_WIFI_BSSID = "02:00:00:00:00:00"
private val MAC_ADDRESS_REGEX = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
private val LE_PREFIX_REGEX = Regex("^le[-_ ]+")
private val MULTISPACE_REGEX = Regex("\\s+")

@Singleton
class ProximityScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class DeviceCategory { STATIONARY, MOBILE, UNKNOWN }
    enum class AddressType { PUBLIC, RANDOM_STATIC, RPA, NRPA, AMBIGUOUS }

    data class ScannedDevice(
        val address: String,
        val name: String?,
        val rssi: Int,
        val category: DeviceCategory = DeviceCategory.UNKNOWN,
        val addressType: AddressType = AddressType.PUBLIC
    )

    data class SnapshotDebugState(
        val bufferSize: Int,
        val freshestAgeMs: Long?,
        val oldestAgeMs: Long?,
        val lastPersistentCallbackAgeMs: Long?,
        val lastBackgroundDeliveryAgeMs: Long?,
        val persistentRunning: Boolean
    )

    data class AnchorIdentity(
        val key: String,
        val type: AnchorType,
        val displayName: String
    )

    /**
     * Classify BLE address type. Uses BluetoothDevice.getAddressType() on API 34+
     * for reliable Public vs Random distinction. Falls back to top-2-bit heuristic.
     * The 0x00 bucket is ambiguous (PUBLIC or NRPA), so do not treat it as stable.
     */
    @SuppressLint("NewApi")
    fun classifyAddressType(address: String, result: ScanResult? = null): AddressType {
        val firstByte = address.split(":").firstOrNull()?.toIntOrNull(16) ?: return AddressType.PUBLIC

        // API 34+: system knows if address is Public or Random
        if (Build.VERSION.SDK_INT >= 34 && result != null) {
            val deviceType = result.device.addressType
            // ADDRESS_TYPE_RANDOM = 1
            if (deviceType == 1) {
                return when (firstByte and 0xC0) {
                    0xC0 -> AddressType.RANDOM_STATIC
                    0x40 -> AddressType.RPA
                    else -> AddressType.NRPA
                }
            }
            // ADDRESS_TYPE_PUBLIC = 0 or UNKNOWN
            return AddressType.PUBLIC
        }

        // Fallback: top 2 bits heuristic (can't distinguish Public from NRPA)
        return when (firstByte and 0xC0) {
            0xC0 -> AddressType.RANDOM_STATIC
            0x40 -> AddressType.RPA
            else -> AddressType.AMBIGUOUS
        }
    }

    /** Whether address is stable (usable for fingerprinting) */
    fun isStableAddress(addressType: AddressType): Boolean =
        addressType == AddressType.PUBLIC || addressType == AddressType.RANDOM_STATIC

    fun isPrivateAddress(addressType: AddressType): Boolean =
        addressType == AddressType.RPA || addressType == AddressType.NRPA

    fun isUnstableAddress(addressType: AddressType): Boolean =
        addressType == AddressType.RPA || addressType == AddressType.NRPA || addressType == AddressType.AMBIGUOUS

    fun isValidRssi(rssi: Int): Boolean = rssi in MIN_VALID_RSSI..MAX_VALID_RSSI

    fun normalizeAnchorName(name: String?): String? {
        val cleaned = name
            ?.trim()
            ?.takeIf { !looksLikeMacAddress(it) }
            ?.lowercase()
            ?.replace(LE_PREFIX_REGEX, "")
            ?.replace(MULTISPACE_REGEX, " ")
            ?.takeIf { it.length >= 4 }
            ?: return null
        return cleaned
    }

    fun nameAnchorKey(name: String?): String? = normalizeAnchorName(name)?.let { "name:$it" }

    fun hasMeaningfulAnchorName(name: String?): Boolean = normalizeAnchorName(name) != null

    private fun looksLikeMacAddress(value: String): Boolean {
        return MAC_ADDRESS_REGEX.matches(value.trim())
    }

    fun classifyAnchorIdentity(
        address: String,
        name: String?,
        category: DeviceCategory,
        addressType: AddressType,
        preferredNameAnchors: Set<String> = emptySet()
    ): AnchorIdentity {
        val normalizedName = normalizeAnchorName(name)
        val preferredNameKey = normalizedName?.let { "name:$it" }
        val shouldUseName = category != DeviceCategory.MOBILE &&
            preferredNameKey != null &&
            (
                preferredNameKey in preferredNameAnchors ||
                    !isStableAddress(addressType)
                )
        if (shouldUseName) {
            return AnchorIdentity(
                key = preferredNameKey!!,
                type = AnchorType.NAME,
                displayName = name!!.trim()
            )
        }

        return AnchorIdentity(
            key = address,
            type = AnchorType.MAC,
            displayName = name?.takeIf { it.isNotBlank() } ?: address
        )
    }

    fun classifyAnchorIdentity(
        device: ScannedDevice,
        preferredNameAnchors: Set<String> = emptySet()
    ): AnchorIdentity =
        classifyAnchorIdentity(
            device.address,
            device.name,
            device.category,
            device.addressType,
            preferredNameAnchors
        )

    fun buildAnchorSnapshot(
        devices: Collection<ScannedDevice>,
        preferredNameAnchors: Set<String> = emptySet()
    ): Map<String, Int> {
        val snapshot = mutableMapOf<String, Int>()
        for (device in devices) {
            val anchorKey = classifyAnchorIdentity(device, preferredNameAnchors).key
            val current = snapshot[anchorKey]
            if (current == null || device.rssi > current) {
                snapshot[anchorKey] = device.rssi
            }
        }
        return snapshot
    }

    @SuppressLint("MissingPermission")
    fun toScannedDevice(result: ScanResult): ScannedDevice? {
        val addr = result.device?.address ?: return null
        if (!isValidRssi(result.rssi)) return null
        val name = try { result.device.name } catch (_: Exception) { null }
        return ScannedDevice(
            address = addr,
            name = name,
            rssi = result.rssi,
            category = classifyDevice(result),
            addressType = classifyAddressType(addr, result)
        )
    }

    fun isUsableRoomAnchorAddress(
        category: DeviceCategory,
        addressType: AddressType,
        seenCount: Int,
        name: String?
    ): Boolean {
        if (category == DeviceCategory.MOBILE) return false
        if (!hasMeaningfulAnchorName(name)) return false
        if (isStableAddress(addressType)) return seenCount >= 2
        if (isUnstableAddress(addressType)) {
            return normalizeAnchorName(name) != null && seenCount >= 4
        }
        return false
    }

    // Persistent background scan: start once, read snapshot anytime
    private val persistentDevices = ConcurrentHashMap<String, ScannedDevice>()
    private val persistentLastSeen = ConcurrentHashMap<String, Long>()
    private val persistentSnapshotLock = Any()
    private var persistentCallback: ScanCallback? = null
    @Volatile private var persistentRunning = false
    @Volatile private var lastPersistentCallbackMs = 0L
    @Volatile private var lastBackgroundDeliveryMs = 0L

    @Volatile var zeroDeviceStreak = 0
    @Volatile var uiHighAccuracyRequested = false

    @SuppressLint("MissingPermission")
    fun startPersistentScan(lowPower: Boolean = true) {
        val scanner = getScanner() ?: return
        val mode = if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
        if (persistentRunning) {
            if (persistentCallback != null) return // Already running, skip mode switch to avoid throttle
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val device = toScannedDevice(result) ?: return
                    persistentDevices[device.address] = device
                    val now = System.currentTimeMillis()
                    persistentLastSeen[device.address] = now
                    lastPersistentCallbackMs = now
                } catch (e: Exception) { Log.w(TAG, "BLE callback error: ${e.javaClass.simpleName}") }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        try {
            scanner.startScan(null, settings, callback)
            persistentCallback = callback
            persistentRunning = true
            Log.d(
                TAG,
                "Persistent scan: ${if (lowPower) "LOW_POWER" else "LOW_LATENCY"} " +
                    "(buffer=${persistentDevices.size})"
            )
        } catch (e: Exception) {
            persistentCallback = null
            persistentRunning = false
            Log.w(TAG, "Persistent scan failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopPersistentScan(clearBuffers: Boolean = true) {
        if (!persistentRunning) {
            if (clearBuffers) {
                persistentDevices.clear()
                persistentLastSeen.clear()
            }
            return
        }
        val scanner = getScanner()
        val bufferBeforeStop = persistentDevices.size
        persistentCallback?.let { cb -> try { scanner?.stopScan(cb) } catch (_: Exception) { } }
        persistentCallback = null
        persistentRunning = false
        if (clearBuffers) {
            persistentDevices.clear()
            persistentLastSeen.clear()
        }
        Log.d(
            TAG,
            "Persistent scan stopped (bufferBefore=$bufferBeforeStop, clearBuffers=$clearBuffers, bufferAfter=${persistentDevices.size})"
        )
    }

    // --- PendingIntent-based background scan (works with screen off) ---

    private var backgroundScanPending: PendingIntent? = null

    @SuppressLint("MissingPermission")
    fun startBackgroundScan(beaconAddresses: Set<String>) {
        val scanner = getScanner() ?: return
        stopBackgroundScan()

        val intent = Intent(BLE_SCAN_ACTION).setPackage(context.packageName)
        backgroundScanPending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Filter for known beacon addresses only
        val filters = if (beaconAddresses.isNotEmpty()) {
            beaconAddresses.map { addr ->
                ScanFilter.Builder().setDeviceAddress(addr).build()
            }
        } else {
            listOf(ScanFilter.Builder().build())
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(3_000) // Batch results every 3s
            .build()

        try {
            scanner.startScan(filters, settings, backgroundScanPending!!)
            Log.d(TAG, "Background PendingIntent scan started for ${beaconAddresses.size} beacons")
        } catch (e: Exception) {
            Log.w(TAG, "Background scan failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBackgroundScan() {
        backgroundScanPending?.let { pi ->
            try { getScanner()?.stopScan(pi) } catch (_: Exception) { }
            backgroundScanPending = null
        }
    }

    /** Called from BroadcastReceiver when background scan results arrive */
    fun handleBackgroundScanResult(results: List<ScanResult>) {
        for (result in results) {
            try {
                val device = toScannedDevice(result) ?: continue
                persistentDevices[device.address] = device
                val now = System.currentTimeMillis()
                persistentLastSeen[device.address] = now
                lastBackgroundDeliveryMs = now
            } catch (e: Exception) { Log.w(TAG, "BLE callback error: ${e.javaClass.simpleName}") }
        }
        Log.d(TAG, "Background scan: ${results.size} results, total=${persistentDevices.size}")
    }

    /**
     * Read current snapshot from persistent scan (no start/stop).
     * This also prunes stale entries from the shared persistent buffer by design.
     */
    fun readSnapshot(
        retainMs: Long = DEVICE_RETAIN_MS,
        pruneMs: Long = retainMs
    ): List<ScannedDevice> {
        val devices = synchronized(persistentSnapshotLock) {
            val now = System.currentTimeMillis()
            persistentLastSeen.entries.removeAll { now - it.value > pruneMs }
            val stale = persistentDevices.keys - persistentLastSeen.keys
            stale.forEach { persistentDevices.remove(it) }
            persistentDevices.entries
                .filter { entry -> now - (persistentLastSeen[entry.key] ?: now) <= retainMs }
                .map { it.value }
        }
        if (devices.isEmpty()) zeroDeviceStreak++ else zeroDeviceStreak = 0
        return devices
    }

    fun snapshotDebugState(): SnapshotDebugState {
        val now = System.currentTimeMillis()
        val ages = persistentLastSeen.values.map { now - it }
        return SnapshotDebugState(
            bufferSize = persistentDevices.size,
            freshestAgeMs = ages.minOrNull(),
            oldestAgeMs = ages.maxOrNull(),
            lastPersistentCallbackAgeMs = lastPersistentCallbackMs.takeIf { it > 0L }?.let { now - it },
            lastBackgroundDeliveryAgeMs = lastBackgroundDeliveryMs.takeIf { it > 0L }?.let { now - it },
            persistentRunning = persistentRunning
        )
    }

    fun isAvailable(): Boolean = getScanner() != null

    fun isBluetoothEnabled(): Boolean = Companion.isBluetoothEnabled(context)

    fun observeBluetoothState(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        trySend(isBluetoothEnabled())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.ERROR)
                    trySend(state == android.bluetooth.BluetoothAdapter.STATE_ON)
                }
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        awaitClose { try { context.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }

    /** Get connected WiFi AP BSSID + RSSI. No scanning needed. */
    @SuppressLint("MissingPermission")
    fun readWifiSnapshot(): Map<String, Int> {
        val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return emptyMap()
        return try {
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            val bssid = info?.bssid
            val rssi = info?.rssi ?: -100
            if (bssid != null && bssid != INVALID_WIFI_BSSID && rssi > MIN_CONNECTED_WIFI_RSSI) {
                mapOf("wifi:$bssid" to rssi)
            } else {
                emptyMap()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "WiFi: permission denied: ${e.message}")
            emptyMap()
        }
    }


    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    suspend fun scanOnce(lowPower: Boolean = true): List<ScannedDevice> {
        val scanner = getScanner() ?: return emptyList()
        val devices = ConcurrentHashMap<String, ScannedDevice>()
        val scanFailed = CompletableDeferred<Unit>()

        return withTimeoutOrNull(SCAN_DURATION_MS + 1_000) {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    try {
                        val device = toScannedDevice(result) ?: return
                        devices[device.address] = device
                    } catch (e: Exception) {
                        Log.w(TAG, "BLE callback error: ${e.javaClass.simpleName}")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE scan failed: $errorCode")
                    scanFailed.complete(Unit)
                }
            }

            val mode = if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
            val settings = ScanSettings.Builder().setScanMode(mode).build()

            try {
                scanner.startScan(null, settings, callback)
            } catch (e: Exception) {
                Log.w(TAG, "BLE scan start failed: ${e.javaClass.simpleName}: ${e.message}", e)
                return@withTimeoutOrNull emptyList()
            }

            try {
                val failedEarly = select<Boolean> {
                    scanFailed.onAwait { true }
                    onTimeout(SCAN_DURATION_MS) { false }
                }
                if (failedEarly) emptyList() else devices.values.toList()
            } finally {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) { }
            }
        } ?: devices.values.toList()
    }

    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    suspend fun scanCalibrationSamples(
        lowPower: Boolean = false,
        sampleWindows: Int = CALIBRATION_SAMPLES_PER_SCAN_SESSION
    ): List<List<ScannedDevice>> {
        val scanner = getScanner() ?: return emptyList()
        val windows = mutableListOf<List<ScannedDevice>>()
        val windowDevices = ConcurrentHashMap<String, ScannedDevice>()
        val samples = sampleWindows.coerceAtLeast(1)
        val scanFailed = CompletableDeferred<Unit>()

        return withTimeoutOrNull(SCAN_DURATION_MS + 1_000) {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    try {
                        val device = toScannedDevice(result) ?: return
                        windowDevices[device.address] = device
                    } catch (e: Exception) {
                        Log.w(TAG, "BLE callback error: ${e.javaClass.simpleName}")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE calibration scan failed: $errorCode")
                    scanFailed.complete(Unit)
                }
            }

            val mode = if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
            val settings = ScanSettings.Builder().setScanMode(mode).build()

            try {
                scanner.startScan(null, settings, callback)
            } catch (e: Exception) {
                Log.w(TAG, "BLE calibration scan start failed: ${e.javaClass.simpleName}: ${e.message}", e)
                return@withTimeoutOrNull emptyList()
            }

            val intervalMs = (SCAN_DURATION_MS / samples).coerceAtLeast(1_000L)
            try {
                repeat(samples) {
                    val failedEarly = select<Boolean> {
                        scanFailed.onAwait { true }
                        onTimeout(intervalMs) { false }
                    }
                    windows += windowDevices.values.toList()
                    windowDevices.clear()
                    if (failedEarly) {
                        return@withTimeoutOrNull emptyList()
                    }
                }
                windows.filter { it.isNotEmpty() }
            } finally {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) { }
            }
        } ?: windows.filter { it.isNotEmpty() }
    }

    @SuppressLint("MissingPermission")
    fun classifyDevice(result: ScanResult): DeviceCategory {
        val record = result.scanRecord ?: return DeviceCategory.UNKNOWN

        val appleData = record.getManufacturerSpecificData(APPLE_COMPANY_ID)
        if (appleData != null && appleData.isNotEmpty()) {
            val subtype = appleData[0].toInt() and 0xFF
            if (subtype == 0x12 || subtype == 0x07) return DeviceCategory.MOBILE
            if (subtype == 0x10 || subtype == 0x0F) return DeviceCategory.MOBILE
            if (subtype == 0x09) return DeviceCategory.STATIONARY
        }

        if (record.serviceUuids?.contains(SMARTTAG_UUID) == true) return DeviceCategory.MOBILE
        if (record.getManufacturerSpecificData(TILE_COMPANY_ID) != null) return DeviceCategory.MOBILE

        val appearance = parseAppearance(record.bytes ?: byteArrayOf())
        if (appearance != null) {
            if (appearance in MOBILE_APPEARANCES) return DeviceCategory.MOBILE
            if (appearance in STATIONARY_APPEARANCES) return DeviceCategory.STATIONARY
        }

        return DeviceCategory.UNKNOWN
    }

    private fun parseAppearance(raw: ByteArray): Int? {
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0) break
            if (i + len >= raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            if (type == 0x19 && len >= 3) {
                val lo = raw[i + 2].toInt() and 0xFF
                val hi = raw[i + 3].toInt() and 0xFF
                return lo or (hi shl 8)
            }
            i += len + 1
        }
        return null
    }

    private fun getScanner() = try {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        btManager?.adapter?.bluetoothLeScanner
    } catch (_: Exception) {
        null
    }

    companion object {
        const val BLE_SCAN_ACTION = "net.asksakis.massdroidv2.BLE_SCAN_RESULT"
        const val AUTO_FINGERPRINT_CYCLES = 20
        const val CALIBRATION_SAMPLES_PER_SCAN_SESSION = 2
        fun isBluetoothEnabled(context: Context): Boolean {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return btManager?.adapter?.isEnabled == true
        }
        private const val APPLE_COMPANY_ID = 0x004C
        private const val TILE_COMPANY_ID = 0x00D7
        private val SMARTTAG_UUID = ParcelUuid.fromString("0000FD5A-0000-1000-8000-00805F9B34FB")
        private val MOBILE_APPEARANCES = setOf(
            0x0040, 0x00C0, 0x00C1, 0x0200, 0x0201, 0x0240,
            0x07C0, 0x07C1, 0x07C2, 0x07C3, 0x07C4
        )
        private val STATIONARY_APPEARANCES = setOf(
            0x0080, 0x0140, 0x0141, 0x0180, 0x0181, 0x0280,
            0x0840, 0x0841, 0x0842, 0x0843, 0x0844
        )
    }
}
