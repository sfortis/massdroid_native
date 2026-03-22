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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "ProximityScanner"
private const val SCAN_DURATION_MS = 5_000L
private const val RSSI_WINDOW_SIZE = 8
private const val EMIT_INTERVAL_MS = 2_000L
private const val DEVICE_RETAIN_MS = 15_000L

@Singleton
class ProximityScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class DeviceCategory { STATIONARY, MOBILE, UNKNOWN }

    data class ScannedDevice(
        val address: String,
        val name: String?,
        val rssi: Int,
        val category: DeviceCategory = DeviceCategory.UNKNOWN
    )

    // Persistent background scan: start once, read snapshot anytime
    private val persistentDevices = ConcurrentHashMap<String, ScannedDevice>()
    private val persistentLastSeen = ConcurrentHashMap<String, Long>()
    private var persistentCallback: ScanCallback? = null
    @Volatile private var persistentRunning = false

    // Health tracking
    @Volatile var lastPersistentCallbackMs = 0L
    @Volatile var lastBackgroundDeliveryMs = 0L
    @Volatile var zeroDeviceStreak = 0

    @SuppressLint("MissingPermission")
    fun startPersistentScan(lowPower: Boolean = true) {
        val scanner = getScanner() ?: return
        val mode = if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
        if (persistentRunning) {
            if (persistentCallback != null) return // Already running, skip mode switch to avoid throttle
        }
        persistentCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address ?: return
                val name = try { result.device.name } catch (_: SecurityException) { null }
                persistentDevices[addr] = ScannedDevice(addr, name, result.rssi, classifyDevice(result))
                persistentLastSeen[addr] = System.currentTimeMillis()
                lastPersistentCallbackMs = System.currentTimeMillis()
            }
        }
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        try {
            scanner.startScan(null, settings, persistentCallback)
            persistentRunning = true
            Log.d(TAG, "Persistent scan: ${if (lowPower) "LOW_POWER" else "LOW_LATENCY"}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Persistent scan permission denied: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopPersistentScan() {
        if (!persistentRunning) return
        val scanner = getScanner()
        persistentCallback?.let { cb -> try { scanner?.stopScan(cb) } catch (_: Exception) { } }
        persistentCallback = null
        persistentRunning = false
        persistentDevices.clear()
        persistentLastSeen.clear()
        Log.d(TAG, "Persistent scan stopped")
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
            Log.w(TAG, "Background scan failed: ${e.message}")
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
            val addr = result.device?.address ?: continue
            val name = try { result.device.name } catch (_: SecurityException) { null }
            persistentDevices[addr] = ScannedDevice(addr, name, result.rssi, classifyDevice(result))
            persistentLastSeen[addr] = System.currentTimeMillis()
        }
        lastBackgroundDeliveryMs = System.currentTimeMillis()
        Log.d(TAG, "Background scan: ${results.size} results, total=${persistentDevices.size}")
    }

    /** Read current snapshot from persistent scan (no start/stop) */
    fun readSnapshot(): List<ScannedDevice> {
        val now = System.currentTimeMillis()
        persistentLastSeen.entries.removeAll { now - it.value > DEVICE_RETAIN_MS }
        val stale = persistentDevices.keys - persistentLastSeen.keys
        stale.forEach { persistentDevices.remove(it) }
        val devices = persistentDevices.values.toList()
        if (devices.isEmpty()) zeroDeviceStreak++ else zeroDeviceStreak = 0
        return devices
    }

    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getScanner() != null

    @SuppressLint("MissingPermission")
    suspend fun scanOnce(lowPower: Boolean = true): List<ScannedDevice> {
        val scanner = getScanner() ?: return emptyList()
        val devices = ConcurrentHashMap<String, ScannedDevice>()

        return withTimeoutOrNull(SCAN_DURATION_MS + 1_000) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val addr = result.device.address ?: return
                        val name = try { result.device.name } catch (_: SecurityException) { null }
                        devices[addr] = ScannedDevice(addr, name, result.rssi, classifyDevice(result))
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "BLE scan failed: $errorCode")
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }

                val mode = if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
                val settings = ScanSettings.Builder().setScanMode(mode).build()

                try {
                    scanner.startScan(null, settings, callback)
                } catch (e: SecurityException) {
                    Log.w(TAG, "BLE scan permission denied: ${e.message}")
                    if (cont.isActive) cont.resume(emptyList())
                    return@suspendCancellableCoroutine
                }

                val timer = java.util.Timer()
                cont.invokeOnCancellation {
                    timer.cancel()
                    try { scanner.stopScan(callback) } catch (_: Exception) { }
                }

                timer.schedule(object : java.util.TimerTask() {
                    override fun run() {
                        try { scanner.stopScan(callback) } catch (_: Exception) { }
                        if (cont.isActive) cont.resume(devices.values.toList())
                    }
                }, SCAN_DURATION_MS)
            }
        } ?: devices.values.toList()
    }

    fun liveScan(): Flow<List<ScannedDevice>> = callbackFlow {
        val scanner = getScanner()
        if (scanner == null) {
            send(emptyList())
            close()
            return@callbackFlow
        }
        val rssiHistory = ConcurrentHashMap<String, MutableList<Int>>()
        val deviceNames = ConcurrentHashMap<String, String?>()
        val deviceCategories = ConcurrentHashMap<String, DeviceCategory>()
        val lastSeen = ConcurrentHashMap<String, Long>()

        @SuppressLint("MissingPermission")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address ?: return
                val name = try { result.device.name } catch (_: SecurityException) { null }
                if (name != null) deviceNames[addr] = name
                val cat = classifyDevice(result)
                if (cat != DeviceCategory.UNKNOWN) deviceCategories[addr] = cat
                lastSeen[addr] = System.currentTimeMillis()
                val history = rssiHistory.getOrPut(addr) { mutableListOf() }
                synchronized(history) {
                    history.add(result.rssi)
                    if (history.size > RSSI_WINDOW_SIZE) history.removeAt(0)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Live scan failed: $errorCode")
            }
        }

        val emitTimer = java.util.Timer()
        emitTimer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                val staleAddrs = lastSeen.filter { now - it.value > DEVICE_RETAIN_MS }.keys
                staleAddrs.forEach { addr ->
                    rssiHistory.remove(addr)
                    deviceNames.remove(addr)
                    deviceCategories.remove(addr)
                    lastSeen.remove(addr)
                }
                val smoothed = rssiHistory.map { (addr, history) ->
                    val avg = synchronized(history) { if (history.isNotEmpty()) history.average().toInt() else -100 }
                    ScannedDevice(addr, deviceNames[addr], avg, deviceCategories[addr] ?: DeviceCategory.UNKNOWN)
                }.sortedByDescending { it.rssi }
                trySend(smoothed)
            }
        }, EMIT_INTERVAL_MS, EMIT_INTERVAL_MS)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Live scan permission denied: ${e.message}")
            send(emptyList())
            close()
            return@callbackFlow
        }

        awaitClose {
            emitTimer.cancel()
            try { scanner.stopScan(callback) } catch (_: Exception) { }
        }
    }.flowOn(Dispatchers.IO)

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
        const val AUTO_FINGERPRINT_CYCLES = 10
    }
}
