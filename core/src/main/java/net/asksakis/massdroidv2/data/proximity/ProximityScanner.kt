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
import android.os.SystemClock
import android.os.ParcelUuid
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import net.asksakis.massdroidv2.data.proximity.AnchorType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProximityScanner"

/** Outcome of a synchronous scan start; see [ProximityScanner.startPersistentScan]. */
enum class ScanStartResult {
    /** Running as requested (started now, or already so). */
    STARTED,
    /** No slot in the [ScanStartBudget]; nothing changed, ask again on the next cycle. */
    DEFERRED,
    /** The platform refused or threw. */
    FAILED,
}
private const val SCAN_DURATION_MS = 5_000L
private const val DEVICE_RETAIN_MS = 30_000L

/** One calibration sample window. Twenty of them make one fingerprint set (see [ProximityScanner.calibrationWindows]). */
private const val CALIBRATION_WINDOW_MS = SCAN_DURATION_MS / 2

/**
 * Above this, a scan result's own timestamp is treated as unusable rather than as
 * a very old reading. Real batched results are at most one report interval old;
 * anything beyond a minute means the controller reported something meaningless.
 */
private const val MAX_TRUSTED_RESULT_AGE_MS = 60_000L

/** RSSI jitter on a still phone is a couple of dB; below this a change is noise, not news. */
private const val RSSI_CHANGE_DB = 4

/** Floor on a budget wait, so a wake at the exact boundary cannot spin. */
private const val MIN_BUDGET_WAIT_MS = 50L

/**
 * How often the offloaded batch scan hands its results to us.
 *
 * This is the one scan that stays registered while the phone is still, so its
 * interval is what wakes the application processor through an idle night. It used to
 * be three seconds, which the controller rounded to about five and a half, and that
 * was the single largest source of wakeups in a measured day.
 *
 * The ceiling is [DEVICE_RETAIN_MS]: a reading is dropped from the buffer once it is
 * that old, so a batch interval near it would deliver readings already at the edge of
 * being discarded and leave the buffer empty between deliveries. Half the retain
 * window leaves every reading a further half window of useful life. Detection while
 * MOVING does not depend on this value at all, because the persistent scan runs then
 * and delivers continuously.
 *
 * Measured caveat: once the device is properly idle the platform coalesces delivery to
 * about 22 s on its own, and a control build requesting 3 s was delivered on exactly the
 * same 22 s cadence. So this value does not govern the deepest idle state. It governs the
 * lighter screen-off state, which is where a full day of measurement found the batch
 * being delivered every 5.5 s against a requested 3 s.
 */
private const val BACKGROUND_BATCH_INTERVAL_MS = DEVICE_RETAIN_MS / 2
private const val MIN_VALID_RSSI = -126
private const val MAX_VALID_RSSI = 20
private const val INVALID_WIFI_BSSID = "02:00:00:00:00:00"
private val MAC_ADDRESS_REGEX = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
private val LE_PREFIX_REGEX = Regex("^le[-_ ]+")
private val MULTISPACE_REGEX = Regex("\\s+")

@Singleton
class ProximityScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class ConnectedWifiInfo(
        val bssid: String,
        val ssid: String?,
        val rssi: Int
    )
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
        // Key by name for anything whose MAC is NOT permanent (RANDOM_STATIC regenerates on a
        // power-cycle/reset, RPA/NRPA rotate continuously) as long as it carries a stable, meaningful
        // name - so a fixed sensor like the Govee (RANDOM_STATIC) or a watch anchor survives a reboot
        // instead of silently dropping out until the next recalibration. PUBLIC addresses keep their
        // permanent, globally-unique MAC as the key (more reliable than a possibly-shared name, e.g.
        // the two heat-pump controllers both advertising as "net").
        val shouldUseName = category != DeviceCategory.MOBILE &&
            preferredNameKey != null &&
            (
                preferredNameKey in preferredNameAnchors ||
                    addressType != AddressType.PUBLIC
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
        // Stable addresses (PUBLIC / RANDOM_STATIC) are keyed by their permanent MAC, so they are
        // valid anchors on their own - a human-readable name is NOT required. This keeps fixed
        // infrastructure with short or blank names (e.g. routers / heat-pump controllers advertising
        // as "net", 3 chars) that the old name-length gate wrongly discarded, even when perfectly
        // stationary and strongly discriminative.
        if (isStableAddress(addressType)) return seenCount >= 2
        // Unstable addresses (RPA / NRPA / AMBIGUOUS) rotate their MAC, so the name is the only stable
        // identity - require a meaningful one and a higher seen bar.
        if (isUnstableAddress(addressType)) {
            return normalizeAnchorName(name) != null && seenCount >= 4
        }
        return false
    }

    // WiFi identity: callback-based cache with freshness validation on read
    private data class CachedWifi(val info: ConnectedWifiInfo, val network: android.net.Network)

    @Volatile private var cachedWifi: CachedWifi? = null
    @Volatile private var wifiCallbackRegistered = false
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private fun wifiCallbackOnCapabilities(network: android.net.Network, caps: NetworkCapabilities) {
        val wi = caps.transportInfo as? WifiInfo ?: return
        val bssid = wi.bssid
        if (bssid == null || bssid == INVALID_WIFI_BSSID) return
        val ssid = wi.ssid
            ?.takeUnless { it == android.net.wifi.WifiManager.UNKNOWN_SSID }
            ?.trim('"')
        cachedWifi = CachedWifi(ConnectedWifiInfo(bssid = bssid, ssid = ssid, rssi = wi.rssi), network)
    }

    private fun wifiCallbackOnLost(network: android.net.Network) {
        if (cachedWifi?.network == network) cachedWifi = null
    }

    @SuppressLint("MissingPermission")
    fun startWifiMonitor() {
        if (wifiCallbackRegistered) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d(TAG, "WiFi monitor skipped (API ${Build.VERSION.SDK_INT} < 31, using WifiManager fallback)")
            return
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @SuppressLint("NewApi")
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) { wifiCallbackOnCapabilities(network, caps) }
                override fun onLost(network: android.net.Network) { wifiCallbackOnLost(network) }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) { wifiCallbackOnCapabilities(network, caps) }
                override fun onLost(network: android.net.Network) { wifiCallbackOnLost(network) }
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            wifiNetworkCallback = callback
            wifiCallbackRegistered = true
            Log.d(TAG, "WiFi monitor started")
        } catch (e: Exception) {
            Log.w(TAG, "WiFi monitor start failed: ${e.message}")
        }
    }

    fun stopWifiMonitor() {
        if (!wifiCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        wifiNetworkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
        }
        wifiNetworkCallback = null
        wifiCallbackRegistered = false
        cachedWifi = null
    }

    // Persistent background scan: start once, read snapshot anytime
    private val persistentDevices = ConcurrentHashMap<String, ScannedDevice>()
    private val persistentLastSeen = ConcurrentHashMap<String, Long>()
    private val persistentSnapshotLock = Any()
    private var persistentCallback: ScanCallback? = null
    @Volatile private var persistentRunning = false
    @Volatile private var lastPersistentCallbackMs = 0L
    // Buffer warmth. A room decision taken while devices are still arriving judges a
    // half-drawn picture: at a cold start the buffer here went 5 -> 7 -> 8 devices over
    // twenty seconds, and the room was committed at 7, before the two loudest anchors
    // had spoken. The controller reads these to hold commits until the set of devices
    // seen since the scan started has stopped growing. The set is what is tracked, not
    // the pruned buffer, so a marginal beacon flapping in and out does not count as new.
    /**
     * Fires when the buffer changed in a way a room decision could care about: a device
     * seen for the first time since the scan started, or an anchor whose RSSI moved by at
     * least [RSSI_CHANGE_DB]. NOT on every advertisement; those arrive several times a
     * second and would make "the buffer went quiet" unreachable. Consumers wait on this
     * instead of polling on a timer.
     */
    private val _bufferChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val bufferChanged: kotlinx.coroutines.flow.SharedFlow<Unit> = _bufferChanged

    /**
     * Warmth and change are tracked per ANCHOR IDENTITY, not per address. The Shield
     * advertises with a resolvable private address and showed up under four addresses in
     * one session; keyed by address, every rotation looked like a new device, reset the
     * warm gate, and flipped the scan to LOW_LATENCY (19 mode switches in 20 minutes on a
     * still phone). The identity key is the same one the detector scores on.
     */
    private val lastRssiByIdentity = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Current address per identity, so a rotated anchor does not linger under its old one. */
    private val addressByIdentity = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun noteObservation(device: ScannedDevice, now: Long) {
        val key = classifyAnchorIdentity(device).key
        // One entry per identity in the buffer. Without this the Shield's previous address
        // stayed for the 30 s retain window next to its new one, inflating the device count
        // and letting a stale, stronger reading outscore the live one.
        val previousAddress = addressByIdentity.put(key, device.address)
        if (previousAddress != null && previousAddress != device.address) {
            persistentDevices.remove(previousAddress)
            persistentLastSeen.remove(previousAddress)
        }
        if (seenSinceScanStart.add(key)) lastNewDeviceMs = now
        val previousRssi = lastRssiByIdentity.put(key, device.rssi)
        if (previousRssi == null || kotlin.math.abs(previousRssi - device.rssi) >= RSSI_CHANGE_DB) {
            _bufferChanged.tryEmit(Unit)
        }
    }

    @Volatile private var persistentStartedMs = 0L
    @Volatile private var lastScanFailureMs = 0L
    @Volatile private var lastNewDeviceMs = 0L
    private val seenSinceScanStart = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastBackgroundDeliveryMs = 0L

    @Volatile var zeroDeviceStreak = 0

    /** Every scan start goes through this; see [ScanStartBudget] for the platform rule it enforces. */
    private val startBudget = ScanStartBudget()

    /** Suspends until a slot in [startBudget] is reserved for the caller's start. */
    private suspend fun acquireScanStart(what: String) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (startBudget.tryAcquire(now)) return
            val waitMs = startBudget.msUntilAllowed(now).coerceAtLeast(MIN_BUDGET_WAIT_MS)
            Log.d(TAG, "$what waits ${waitMs}ms for the scan-start budget")
            delay(waitMs)
        }
    }

    val isPersistentScanRunning: Boolean get() = persistentRunning
    val isBackgroundScanRunning: Boolean get() = backgroundScanPending != null
    private var persistentAnchorAddresses: Set<String> = emptySet()
    private var persistentAnchorNames: Set<String> = emptySet()
    private var backgroundAddresses: Set<String>? = null
    @Volatile var uiHighAccuracyRequested = false

    @SuppressLint("MissingPermission")
    private var persistentLowPower: Boolean? = null

    /**
     * Start the filtered persistent scan, switch its mode, or refresh its anchor filters
     * while it runs.
     *
     * A start while the scan is running (a new mode or a new anchor set) is a restart of the
     * radio, not of what we know: the buffer and its warmth survive it. Returns
     * [ScanStartResult.DEFERRED] when the [ScanStartBudget] has no slot, in which case a
     * running scan keeps running as it was, and [ScanStartResult.FAILED] when the platform
     * refused. Callers re-ask on their next cycle rather than waiting here, because this
     * runs on the main loop.
     */
    fun startPersistentScan(
        lowPower: Boolean = true,
        anchorAddresses: Set<String> = emptySet(),
        anchorNames: Set<String> = emptySet()
    ): ScanStartResult {
        val scanner = getScanner() ?: return ScanStartResult.FAILED
        val mode = if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY
        val running = persistentRunning && persistentCallback != null
        if (running && persistentLowPower == lowPower &&
            anchorAddresses == persistentAnchorAddresses && anchorNames == persistentAnchorNames
        ) {
            return ScanStartResult.STARTED // already exactly as requested
        }
        // Reserved before the running scan is torn down, so a deferral costs nothing.
        val now = SystemClock.elapsedRealtime()
        if (!startBudget.tryAcquire(now)) {
            Log.d(
                TAG,
                "Persistent scan ${if (running) "restart" else "start"} deferred " +
                    "${startBudget.msUntilAllowed(now)}ms: scan-start budget"
            )
            return ScanStartResult.DEFERRED
        }
        if (running) stopPersistentScan(clearBuffers = false)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val device = toScannedDevice(result) ?: return
                    persistentDevices[device.address] = device
                    val now = System.currentTimeMillis()
                    persistentLastSeen[device.address] = now
                    lastPersistentCallbackMs = now
                    noteObservation(device, now)
                } catch (e: Exception) { Log.w(TAG, "BLE callback error: ${e.javaClass.simpleName}") }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Persistent scan failed: errorCode=$errorCode")
                persistentRunning = false
                lastScanFailureMs = System.currentTimeMillis()
            }
        }
        // Use ScanFilters so Android delivers results even with screen off.
        // Unfiltered callback scans are silently stopped when screen turns off.
        val macFilters = anchorAddresses.mapNotNull { addr ->
            if (MAC_ADDRESS_REGEX.matches(addr)) ScanFilter.Builder().setDeviceAddress(addr).build() else null
        }
        val nameFilters = anchorNames.map { name ->
            ScanFilter.Builder().setDeviceName(name).build()
        }
        val filters = (macFilters + nameFilters).ifEmpty {
            listOf(ScanFilter.Builder().build())
        }
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        try {
            if (!running) {
                // Real (cold) start: what was seen before no longer says anything about now.
                // A restart of a running scan (mode or filter change) keeps the warmth: resetting
                // it here made LOW_POWER<->LOW_LATENCY oscillate every 10 to 13 s (a cold buffer
                // asks for LOW_LATENCY, the restart makes it cold again) and kept commits gated.
                val startedAt = System.currentTimeMillis()
                persistentStartedMs = startedAt
                lastNewDeviceMs = startedAt
                seenSinceScanStart.clear()
                lastRssiByIdentity.clear()
                addressByIdentity.clear()
            }
            scanner.startScan(filters, settings, callback)
            lastScanFailureMs = 0L
            persistentCallback = callback
            persistentRunning = true
            persistentLowPower = lowPower
            persistentAnchorAddresses = anchorAddresses
            persistentAnchorNames = anchorNames
            Log.d(
                TAG,
                "Persistent scan: ${if (lowPower) "LOW_POWER" else "LOW_LATENCY"} " +
                    "(buffer=${persistentDevices.size}, filters=${macFilters.size} MAC + ${nameFilters.size} name)"
            )
            return ScanStartResult.STARTED
        } catch (e: Exception) {
            persistentCallback = null
            persistentRunning = false
            Log.w(TAG, "Persistent scan failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return ScanStartResult.FAILED
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
        persistentLowPower = null
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

    /**
     * Batch scan for known beacon addresses, delivered via PendingIntent.
     *
     * Requires at least one address: a match-all batch scan reports every
     * advertisement in range, which the OS counts as an unoptimized scan and which
     * wakes the process continuously for devices we can never anchor on. Callers
     * with only name anchors rely on [startPersistentScan] instead, which can carry
     * real name filters.
     *
     * A start with the address set already running is a no-op. The PendingIntent
     * variant of `startScan` reports most refusals as a RETURN CODE, not an exception,
     * so the code is checked; a later error arrives as `EXTRA_ERROR_CODE` on the
     * broadcast and is reported through [onBackgroundScanError].
     */
    @SuppressLint("MissingPermission")
    fun startBackgroundScan(beaconAddresses: Set<String>): ScanStartResult {
        val scanner = getScanner() ?: return ScanStartResult.FAILED
        if (beaconAddresses.isEmpty()) {
            stopBackgroundScan()
            Log.d(TAG, "Background scan skipped: no MAC anchors to filter on")
            return ScanStartResult.STARTED
        }
        if (backgroundScanPending != null && beaconAddresses == backgroundAddresses) return ScanStartResult.STARTED
        // Reserved before the running scan is torn down, so a deferral costs nothing.
        val now = SystemClock.elapsedRealtime()
        if (!startBudget.tryAcquire(now)) {
            Log.d(TAG, "Background scan start deferred ${startBudget.msUntilAllowed(now)}ms: scan-start budget")
            return ScanStartResult.DEFERRED
        }
        stopBackgroundScan()

        val intent = Intent(BLE_SCAN_ACTION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val filters = beaconAddresses.map { addr ->
            ScanFilter.Builder().setDeviceAddress(addr).build()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(BACKGROUND_BATCH_INTERVAL_MS)
            .build()

        return try {
            val code = scanner.startScan(filters, settings, pending)
            if (code != 0) {
                Log.w(TAG, "Background scan refused: code=$code")
                ScanStartResult.FAILED
            } else {
                backgroundScanPending = pending
                backgroundAddresses = beaconAddresses
                Log.d(TAG, "Background PendingIntent scan started for ${beaconAddresses.size} beacons")
                ScanStartResult.STARTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Background scan failed: ${e.javaClass.simpleName}: ${e.message}", e)
            ScanStartResult.FAILED
        }
    }

    /** The platform reported an error on the PendingIntent scan. Nothing is delivered after it. */
    fun onBackgroundScanError(errorCode: Int) {
        Log.w(TAG, "Background scan error: code=$errorCode")
        backgroundScanPending = null
        backgroundAddresses = null
    }

    @SuppressLint("MissingPermission")
    fun stopBackgroundScan() {
        backgroundScanPending?.let { pi ->
            try { getScanner()?.stopScan(pi) } catch (_: Exception) { }
            backgroundScanPending = null
        }
        backgroundAddresses = null
    }

    /**
     * When [result] was actually heard, in wall-clock time.
     *
     * A batched result can be a whole report interval old by the time the broadcast
     * arrives, so stamping it with the delivery time makes a stale reading look
     * fresh, and the detector reads freshness to decide which room wins. The error
     * was bounded by the report interval and therefore invisible while that interval
     * was three seconds; it stops being invisible as the interval grows.
     *
     * `timestampNanos` is on the elapsed-realtime clock, so it is converted rather
     * than used directly. A controller that reports nothing usable (0, or a value in
     * the future) falls back to [now] rather than inventing an age.
     */
    private fun observedAtMs(result: ScanResult, now: Long): Long {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000
        if (ageMs < 0 || ageMs > MAX_TRUSTED_RESULT_AGE_MS) return now
        return now - ageMs
    }

    /** Called from BroadcastReceiver when background scan results arrive */
    fun handleBackgroundScanResult(results: List<ScanResult>) {
        val now = System.currentTimeMillis()
        for (result in results) {
            try {
                val device = toScannedDevice(result) ?: continue
                val observedAt = observedAtMs(result, now)
                // One batch routinely carries several readings for the same address.
                // Without the real timestamps the last one in the list won, which was
                // not necessarily the most recent one.
                val previous = persistentLastSeen[device.address]
                if (previous != null && previous > observedAt) continue
                persistentDevices[device.address] = device
                persistentLastSeen[device.address] = observedAt
                noteObservation(device, now)
            } catch (e: Exception) { Log.w(TAG, "BLE callback error: ${e.javaClass.simpleName}") }
        }
        lastBackgroundDeliveryMs = now
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

    /**
     * When the persistent scan started and when a device was last seen for the FIRST
     * time since then. Zero when no scan has started. Both wall-clock millis.
     */
    data class BufferWarmth(
        val scanStartedMs: Long,
        val lastNewDeviceMs: Long,
        val scanRunning: Boolean,
        /** The radio reported a failure since the last start; an empty read then means nothing. */
        val scanFailed: Boolean
    )

    fun bufferWarmth(): BufferWarmth =
        BufferWarmth(persistentStartedMs, lastNewDeviceMs, persistentRunning, lastScanFailureMs > persistentStartedMs)

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

    /** Get connected WiFi AP BSSID/SSID + RSSI. Callback-cached, validated against activeNetwork. */
    @SuppressLint("MissingPermission")
    fun readConnectedWifiInfo(): ConnectedWifiInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val isWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        if (!isWifi) {
            cachedWifi = null
            return null
        }

        val cached = cachedWifi
        if (cached != null && cached.network == active) return cached.info

        // Callback hasn't delivered yet; bootstrap from WifiManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return null
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo ?: return null
        val bssid = info.bssid ?: return null
        if (bssid == INVALID_WIFI_BSSID) return null
        val ssid = info.ssid
            ?.takeUnless { it == android.net.wifi.WifiManager.UNKNOWN_SSID }
            ?.trim('"')
        val result = ConnectedWifiInfo(bssid = bssid, ssid = ssid, rssi = info.rssi)
        cachedWifi = CachedWifi(result, active!!)
        return result
    }

    /** Get connected WiFi AP BSSID + RSSI. No scanning needed. */
    fun readWifiSnapshot(): Map<String, Int> =
        readConnectedWifiInfo()?.let { mapOf("wifi:${it.bssid}" to it.rssi) } ?: emptyMap()


    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    suspend fun scanOnce(lowPower: Boolean = true): List<ScannedDevice> {
        val scanner = getScanner() ?: return emptyList()
        val devices = ConcurrentHashMap<String, ScannedDevice>()
        val scanFailed = CompletableDeferred<Unit>()
        acquireScanStart("One-shot scan")

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

    /**
     * The calibration scan: ONE scan start, sampled in [windows] windows of [windowMs].
     *
     * It used to be ten short scans of two windows each, twice the number of starts
     * Android accepts in thirty seconds. The later sessions were accepted and delivered
     * nothing (14 windows out of 20 on 2026-09-06), and the persistent scan that restarted
     * after the save was refused the same silent way. Each non-empty window is emitted as
     * it completes so the UI can show progress. The flow ends early on a scan failure.
     */
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    fun calibrationWindows(
        lowPower: Boolean = false,
        windows: Int = AUTO_FINGERPRINT_CYCLES,
        windowMs: Long = CALIBRATION_WINDOW_MS
    ): Flow<List<ScannedDevice>> = flow {
        val scanner = getScanner() ?: return@flow
        val windowDevices = ConcurrentHashMap<String, ScannedDevice>()
        val scanFailed = CompletableDeferred<Unit>()
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

        acquireScanStart("Calibration scan")
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: Exception) {
            Log.w(TAG, "BLE calibration scan start failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return@flow
        }
        try {
            repeat(windows.coerceAtLeast(1)) {
                val failedEarly = select<Boolean> {
                    scanFailed.onAwait { true }
                    onTimeout(windowMs) { false }
                }
                if (failedEarly) return@flow
                val window = windowDevices.values.toList()
                windowDevices.clear()
                if (window.isNotEmpty()) emit(window)
            }
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) { }
        }
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
