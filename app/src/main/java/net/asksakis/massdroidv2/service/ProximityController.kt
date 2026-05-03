package net.asksakis.massdroidv2.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.proximity.DetectResult
import net.asksakis.massdroidv2.data.proximity.DetectedRoom
import net.asksakis.massdroidv2.data.proximity.MotionGate
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.data.proximity.RoomDetector.WifiMatchContext
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository

class ProximityController(
    private val service: PlaybackService,
    private val scope: CoroutineScope,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val proximityConfigStore: ProximityConfigStore,
    private val proximityScanner: ProximityScanner,
    private val roomDetector: RoomDetector,
    private val motionGate: MotionGate,
    private val shouldBlockProximitySelectionForBt: () -> Boolean,
    private val sendVolumeCommand: (playerId: String, volume: Int) -> Unit,
) {
    companion object {
        private const val TAG = "ProximityCtrl"
        private const val PROXIMITY_CHANNEL_ID = "massdroid_proximity_v2"
        private const val PROXIMITY_NOTIFICATION_ID = 4
        private const val BURST_SCAN_INTERVAL_MS = 4_000L
        private const val MOTION_SCAN_INTERVAL_MS = 2_000L
        private const val QUICK_RETRY_DELAY_MS = 1_500L
        private const val COOLDOWN_AFTER_SWITCH_MS = 15_000L
        private const val HIGH_ACCURACY_WINDOW_MS = 30_000L
        private const val AWAY_MODE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val AWAY_MODE_SCAN_INTERVAL_MS = 60_000L
        private const val SCREEN_OFF_IDLE_SCAN_INTERVAL_MS = 2 * 60 * 1000L
        private const val HIGH_ACCURACY_MAX_MS = 60_000L
        private const val BG_CONFIRM_MIN_DEVICES = 4
        private const val MOTION_BOOST_DEBOUNCE_MS = 1_000L
        private const val STARTUP_WARMUP_SNAPSHOTS = 3
        private const val STARTUP_WARMUP_INTERVAL_MS = 1_200L
        private const val WIFI_ROOM_GRACE_MS = 10_000L
    }

    private val playbackController = ProximityPlaybackController(
        service = service,
        scope = scope,
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        proximityConfigStore = proximityConfigStore,
        sendVolumeCommand = sendVolumeCommand,
        onReevaluate = { reevaluate("intent") },
    )
    private val scanController = ProximityScanController(proximityScanner)
    private val noRoomStopController = ProximityNoRoomStopController(
        scope = scope,
        playerRepository = playerRepository,
        proximityConfigStore = proximityConfigStore,
        roomDetector = roomDetector,
        shouldRunProximity = { config -> shouldRunProximity(config) },
        isWithinSchedule = { isWithinSchedule() },
    )

    private var proximityJob: Job? = null
    private var proximityQuickRetryJob: Job? = null
    private var lastRoomSwitchMs = 0L
    private var highAccuracyUntilMs = 0L
    private var highAccuracyStartedAtMs = 0L
    private var lastMotionBoostMs = 0L
    private var suppressNextProximityRoomAction = false
    private var lastConfirmedWifiRoomId: String? = null
    private var lastConfirmedWifiBssid: String? = null
    private var lastConfirmedWifiSsid: String? = null
    private var lastConfirmedWifiAtMs = 0L

    fun start() {
        createNotificationChannel()
        androidx.core.content.ContextCompat.registerReceiver(
            service,
            bleScanReceiver,
            android.content.IntentFilter(ProximityScanner.BLE_SCAN_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        observeProximityConfig()
        observeBluetoothState()
        noRoomStopController.start()
    }

    fun stop() {
        stopEngine()
        noRoomStopController.stop()
        try { service.unregisterReceiver(bleScanReceiver) } catch (_: Exception) { }
    }

    fun handleStartCommand(intent: Intent?): Boolean {
        return playbackController.handleStartCommand(intent)
    }

    private fun <T> getSystemService(serviceClass: Class<T>): T? = service.getSystemService(serviceClass)
    private fun getSystemService(name: String): Any? = service.getSystemService(name)
    private fun checkSelfPermission(permission: String): Int = service.checkSelfPermission(permission)

    private val bleScanReceiver = object : android.content.BroadcastReceiver() {
        @android.annotation.SuppressLint("InlinedApi")
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val results = intent?.getParcelableArrayListExtra<android.bluetooth.le.ScanResult>(
                android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
            ) ?: return
            proximityScanner.handleBackgroundScanResult(results)

            // Only detect from receiver when screen is OFF (main loop handles screen-on)
            val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val screenOn = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
            if (screenOn) return

            val config = proximityConfigStore.config.value
            if (!config.enabled || !isWithinSchedule()) return
            if (System.currentTimeMillis() - lastRoomSwitchMs < COOLDOWN_AFTER_SWITCH_MS) return
            val backgroundDevices = results.mapNotNull { result ->
                proximityScanner.toScannedDevice(result)
            }
            scanController.logBleDevices("bg-receiver", backgroundDevices, config)
            val currentWifi = currentConnectedWifi()
            if (shouldHoldWifiOnlyRoom(config, currentWifi)) {
                Log.d(TAG, "Wi-Fi-only room hold (bg-receiver): keeping ${roomDetector.currentRoom.value?.roomName}")
                return
            }
            val rssiMap = scanController.buildDetectionAnchorSnapshot(backgroundDevices, config)
            val allowCommit = rssiMap.size >= BG_CONFIRM_MIN_DEVICES
            val hadCurrentRoomBeforeDetection = roomDetector.currentRoom.value != null
            when (val result = roomDetector.detectDetailed(
                rssiMap,
                config,
                motionActive = motionGate.isMoving.value,
                wifi = currentWifi.toWifiMatchContext(),
                commitRoomChange = allowCommit
            )) {
                is DetectResult.Confirmed -> {
                    if (!allowCommit) {
                        Log.d(
                            TAG,
                            "Background confirm suppressed: ${result.room.roomName} from tiny batch " +
                                "(${rssiMap.size} unique devices)"
                        )
                        scheduleQuickProximityRetry("bg-receiver:tiny-batch")
                        return
                    }
                    Log.d(TAG, "Background room change: ${result.room.roomName}")
                    handleConfirmedRoom(result.room, config, hadCurrentRoomBeforeDetection)
                }
                is DetectResult.Borderline -> {
                    if (shouldQuickRetryBorderline(result.winner.roomId, motionGate.isMoving.value)) {
                        scheduleQuickProximityRetry("bg-receiver:${result.reason}")
                    }
                }
                else -> Unit
            }
        }
    }

    // region Proximity Playback

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            PROXIMITY_CHANNEL_ID, "Follow Me", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Room detection and playback transfer"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun shouldQuickRetryBorderline(winnerRoomId: String, motionActive: Boolean): Boolean {
        val currentRoomId = roomDetector.currentRoom.value?.roomId
        return motionActive || currentRoomId == null || winnerRoomId != currentRoomId
    }

    private fun hasBleScanPermission(): Boolean {
        val granted = { perm: String -> checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            granted(android.Manifest.permission.BLUETOOTH_SCAN) && granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasFollowMePermissions(): Boolean {
        val granted = { perm: String -> checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val bleOk = hasBleScanPermission()
        val activityOk = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            granted(android.Manifest.permission.ACTIVITY_RECOGNITION)
        return bleOk && activityOk
    }

    private fun observeProximityConfig() {
        scope.launch {
            proximityConfigStore.load()
            var wasEnabled = false
            var roomCount = 0
            var roomHash = 0
            proximityConfigStore.config.collect { config ->
                val shouldRun = shouldRunProximity(config)
                val currentHash = config.rooms.sumOf { it.beaconProfiles.size + it.id.hashCode() }
                val structureChanged = config.enabled != wasEnabled || config.rooms.size != roomCount || currentHash != roomHash
                wasEnabled = config.enabled
                roomCount = config.rooms.size
                roomHash = currentHash
                if (shouldRun && (structureChanged || proximityJob?.isActive != true)) {
                    startProximityEngine()
                } else if (!shouldRun) {
                    stopEngine()
                }
            }
        }
    }

    private fun observeBluetoothState() {
        scope.launch {
            try {
                proximityScanner.observeBluetoothState()
                    .distinctUntilChanged()
                    .collect { enabled ->
                        if (enabled) return@collect

                        val config = proximityConfigStore.config.value
                        if (!config.enabled) return@collect

                        Log.d(TAG, "Bluetooth turned off: disabling Follow Me")
                        proximityConfigStore.update { it.copy(enabled = false) }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth state observer failed: ${e.message}")
            }
        }
    }

    private fun shouldRunProximity(config: net.asksakis.massdroidv2.data.proximity.ProximityConfig): Boolean {
        return config.enabled && config.rooms.isNotEmpty() && hasFollowMePermissions()
    }

    private fun isDeviceInDoze(): Boolean {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isDeviceIdleMode
    }

    /**
     * Motion-gated proximity engine:
     * 1. MotionGate (sensor hub) detects movement -> opens 30s window
     * 2. During window: use higher-accuracy BLE scanning with faster detect cadence
     * 3. RoomDetector classifies using anchored beacons + confidence
     * 4. On confirmed room change: notification or auto-transfer
     * 5. No selectPlayer until user action (notification tap)
     */
    private fun startProximityEngine() {
        stopEngine()
        Log.d(TAG, "Starting proximity engine")
        proximityScanner.startWifiMonitor()

        // Skip full radio startup if outside schedule
        if (isWithinSchedule()) {
            motionGate.start()
            ensurePersistentScan(lowPower = true)
            scanController.startBackgroundScanForConfig(proximityConfigStore.config.value)
        }

        proximityJob = scope.launch {
            var scheduleSuspended = !isWithinSchedule()
            // Startup warmup: use short high-accuracy snapshots instead of 4s burst spacing.
            if (isWithinSchedule()) {
                runStartupWarmup()
                val config = proximityConfigStore.config.value
                val hasWifiOnlyRooms = config.rooms.any { room ->
                    room.wifiMatchMode != null &&
                        (!room.connectedBssid.isNullOrBlank() || !room.connectedSsid.isNullOrBlank())
                }
                if (roomDetector.currentRoom.value == null && hasWifiOnlyRooms) {
                    suppressNextProximityRoomAction = true
                    Log.d(TAG, "Proximity startup: suppressing first room action until Wi-Fi startup sync settles")
                }
                syncSelectedPlayerToCurrentRoom("engine-start")
                Log.d(TAG, "Proximity warmup: ${roomDetector.currentRoom.value?.roomName ?: "no room"}")
            }

            launch {
                var wasMoving = motionGate.isMoving.value
                motionGate.isMoving.collect { moving ->
                    val risingEdge = moving && !wasMoving
                    wasMoving = moving
                    if (!risingEdge) return@collect
                    performMotionDetection("Motion boost: immediate high-accuracy escalation", "motion-open", "immediate")
                }
            }

            launch {
                motionGate.motionEvents.collect {
                    performMotionDetection("Motion boost: significant-motion refresh", "motion-refresh", "significant-motion")
                }
            }

            /*
             * Main detection loop.
             *
             * Three operating modes based on device state:
             *   SCREEN_ON  – persistent BLE scan + periodic burst reads
             *   SCREEN_OFF + MOTION – wake-lock fast-path burst reads
             *   SCREEN_OFF + IDLE  – low-power persistent scan, no burst
             *
             * Within SCREEN_ON:
             *   Motion active → LOW_LATENCY scan, burst every 2 s
             *   No motion     → LOW_POWER scan, burst every 12 s
             *   Away mode     → no room for 5 min → burst every 60 s
             *
             * Room changes require motion, so it is safe to scan less
             * aggressively when idle. The scanner always runs (even idle)
             * to keep the device buffer warm for instant detection on the
             * next motion event.
             */
            val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            Log.d(TAG, "Proximity main loop starting, enabled=${proximityConfigStore.config.value.enabled}")
            while (proximityConfigStore.config.value.enabled) {

                // ── Gate: schedule ──
                if (!isWithinSchedule()) {
                    if (!scheduleSuspended) { suspendProximityForSchedule(); scheduleSuspended = true }
                    kotlinx.coroutines.delay(60_000); continue
                }
                if (scheduleSuspended) { resumeProximityAfterSchedule(); scheduleSuspended = false }

                // ── Gate: doze ──
                if (isDeviceInDoze()) {
                    scanController.stopPersistentScan(); proximityScanner.stopBackgroundScan(); motionGate.stop()
                    highAccuracyUntilMs = 0L; highAccuracyStartedAtMs = 0L
                    kotlinx.coroutines.delay(30_000)
                    if (!isDeviceInDoze() && isWithinSchedule()) {
                        motionGate.start(); scanController.startBackgroundScanForConfig(proximityConfigStore.config.value)
                        ensurePersistentScan(lowPower = true)
                    }
                    continue
                }

                // ── Evaluate state ──
                val isMoving = motionGate.isMoving.value
                val highAccuracy = updateHighAccuracyWindow(isMoving) || proximityScanner.uiHighAccuracyRequested
                val screenOn = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
                val cooledDown = System.currentTimeMillis() - lastRoomSwitchMs >= COOLDOWN_AFTER_SWITCH_MS

                when {
                    // ── Screen ON ──
                    screenOn && cooledDown -> {
                        // Away mode: no room matched for 5 min, conserve battery
                        if (isInAwayMode()) {
                            ensurePersistentScan(lowPower = true)
                            scanController.recoverScannerIfNeeded(false, proximityConfigStore.config.value, roomDetector)
                            burstScan("away")
                            kotlinx.coroutines.delay(AWAY_MODE_SCAN_INTERVAL_MS)
                            continue
                        }
                        // Normal: scan aggressiveness follows motion state
                        ensurePersistentScan(lowPower = !highAccuracy)
                        scanController.recoverScannerIfNeeded(highAccuracy, proximityConfigStore.config.value, roomDetector)
                        burstScan(if (highAccuracy) "motion" else "screen")
                        kotlinx.coroutines.delay(if (highAccuracy) MOTION_SCAN_INTERVAL_MS else BURST_SCAN_INTERVAL_MS * 3)
                    }

                    // ── Screen OFF + motion ──
                    !screenOn && highAccuracy && cooledDown -> {
                        screenOffMotionBurst()
                        kotlinx.coroutines.delay(MOTION_SCAN_INTERVAL_MS)
                    }

                    // ── Screen OFF + idle ──
                    // PendingIntent background scan handles detection via bg-receiver.
                    // No burst scan needed here; just keep persistent scan warm.
                    else -> {
                        ensurePersistentScan(lowPower = true)
                        kotlinx.coroutines.delay(2_000)
                    }
                }
            }
        }
    }

    private suspend fun runStartupWarmup() {
        ensurePersistentScan(lowPower = false)
        repeat(STARTUP_WARMUP_SNAPSHOTS) { index ->
            if (index > 0) {
                kotlinx.coroutines.delay(STARTUP_WARMUP_INTERVAL_MS)
            }
            val devices = scanController.readDetectionSnapshot(preferFresh = true)
            if (devices.isEmpty()) return@repeat
            val rssi = scanController.buildDetectionAnchorSnapshot(devices, proximityConfigStore.config.value)
            roomDetector.detectDetailed(
                scanResults = rssi,
                config = proximityConfigStore.config.value,
                motionActive = false,
                wifi = currentConnectedWifi().toWifiMatchContext()
            )
            if (roomDetector.currentRoom.value != null) return
        }
        if (!updateHighAccuracyWindow(motionGate.isMoving.value) && !proximityScanner.uiHighAccuracyRequested) {
            ensurePersistentScan(lowPower = true)
        }
    }

    private fun suspendProximityForSchedule() {
        Log.d(TAG, "Proximity schedule inactive: suspending Follow Me")
        proximityQuickRetryJob?.cancel()
        proximityQuickRetryJob = null
        scanController.stopPersistentScan()
        proximityScanner.stopBackgroundScan()
        motionGate.stop()
        roomDetector.reset()
        noRoomStopController.cancel("outside-schedule")
        playbackController.clearPending()
        highAccuracyUntilMs = 0L
        highAccuracyStartedAtMs = 0L
        lastMotionBoostMs = 0L
        getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
    }

    private suspend fun resumeProximityAfterSchedule() {
        Log.d(TAG, "Proximity schedule active: resuming Follow Me")
        suppressNextProximityRoomAction = true
        motionGate.start()
        scanController.startBackgroundScanForConfig(proximityConfigStore.config.value)
        ensurePersistentScan(lowPower = true)
        runStartupWarmup()
        syncSelectedPlayerToCurrentRoom("schedule-resume")
        Log.d(TAG, "Proximity warmup: ${roomDetector.currentRoom.value?.roomName ?: "no room"}")
    }

    private fun syncSelectedPlayerToCurrentRoom(reason: String) {
        val room = roomDetector.currentRoom.value ?: return
        if (shouldBlockProximitySelectionForBt.invoke()) {
            Log.d(TAG, "Proximity selected player skipped ($reason): BT A2DP sendspin active")
            return
        }
        if (!playbackController.isPlayerAvailable(room.playerId)) return
        if (playerRepository.selectedPlayer.value?.playerId == room.playerId) return
        playerRepository.selectPlayer(room.playerId)
        Log.d(TAG, "Proximity selected player ($reason): ${room.playerName}")
    }

    private fun currentWifiOnlyRoomConfig(
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig
    ): net.asksakis.massdroidv2.data.proximity.RoomConfig? {
        val currentRoomId = roomDetector.currentRoom.value?.roomId ?: return null
        return config.rooms.find { it.id == currentRoomId && it.wifiMatchMode != null }
    }

    private fun shouldHoldWifiOnlyRoom(
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig,
        wifi: ProximityScanner.ConnectedWifiInfo?
    ): Boolean {
        val currentRoom = currentWifiOnlyRoomConfig(config) ?: return false
        val mode = currentRoom.wifiMatchMode ?: return false

        val matches = when (mode) {
            net.asksakis.massdroidv2.data.proximity.WifiMatchMode.BSSID ->
                !currentRoom.connectedBssid.isNullOrBlank() && wifi?.bssid != null &&
                    currentRoom.connectedBssid.equals(wifi.bssid, ignoreCase = true)
            net.asksakis.massdroidv2.data.proximity.WifiMatchMode.SSID ->
                !currentRoom.connectedSsid.isNullOrBlank() && wifi?.ssid != null &&
                    currentRoom.connectedSsid.equals(wifi.ssid, ignoreCase = true)
        }

        if (matches) {
            lastConfirmedWifiRoomId = currentRoom.id
            lastConfirmedWifiBssid = wifi?.bssid ?: currentRoom.connectedBssid
            lastConfirmedWifiSsid = wifi?.ssid ?: currentRoom.connectedSsid
            lastConfirmedWifiAtMs = System.currentTimeMillis()
            return false
        }

        if (wifi != null) {
            if (roomDetector.currentRoom.value?.roomId == currentRoom.id) {
                Log.d(TAG, "Wi-Fi-only room ${currentRoom.name} no longer matches connected WiFi, clearing")
                roomDetector.reset()
            }
            return false
        }

        val now = System.currentTimeMillis()
        return lastConfirmedWifiRoomId == currentRoom.id &&
            now - lastConfirmedWifiAtMs <= WIFI_ROOM_GRACE_MS
    }

    private fun handleConfirmedRoom(
        detected: DetectedRoom,
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig,
        hadCurrentRoomBeforeDetection: Boolean
    ) {
        val roomConfig = config.rooms.find { it.id == detected.roomId }
        val currentWifi = currentConnectedWifi()
        if (roomConfig?.wifiMatchMode != null &&
            (!roomConfig.connectedBssid.isNullOrBlank() || !roomConfig.connectedSsid.isNullOrBlank())
        ) {
            lastConfirmedWifiRoomId = roomConfig.id
            lastConfirmedWifiBssid = currentWifi?.bssid ?: roomConfig.connectedBssid
            lastConfirmedWifiSsid = currentWifi?.ssid ?: roomConfig.connectedSsid
            lastConfirmedWifiAtMs = System.currentTimeMillis()
            if ((!currentWifi?.ssid.isNullOrBlank() && roomConfig.connectedSsid != currentWifi?.ssid) ||
                (!currentWifi?.bssid.isNullOrBlank() && roomConfig.connectedBssid != currentWifi?.bssid)
            ) {
                scope.launch {
                    proximityConfigStore.update { cfg ->
                        cfg.copy(
                            rooms = cfg.rooms.map { room ->
                                if (room.id == roomConfig.id) {
                                    room.copy(
                                        connectedBssid = currentWifi?.bssid ?: room.connectedBssid,
                                        connectedSsid = currentWifi?.ssid ?: room.connectedSsid
                                    )
                                } else {
                                    room
                                }
                            }
                        )
                    }
                }
            }
        }
        if (!hadCurrentRoomBeforeDetection && roomConfig?.wifiMatchMode != null) {
            suppressNextProximityRoomAction = true
            Log.d(TAG, "Proximity startup-sync: suppressing first Wi-Fi room action for ${detected.roomName}")
        }
        handleRoomChange(detected, config)
    }

    private fun updateHighAccuracyWindow(isMoving: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (!isMoving) {
            if (highAccuracyUntilMs <= now) {
                highAccuracyUntilMs = 0L
                highAccuracyStartedAtMs = 0L
            }
            return highAccuracyUntilMs > now
        }

        if (highAccuracyStartedAtMs == 0L || highAccuracyUntilMs <= now) {
            highAccuracyStartedAtMs = now
        }
        val capUntil = highAccuracyStartedAtMs + HIGH_ACCURACY_MAX_MS
        val proposedUntil = now + HIGH_ACCURACY_WINDOW_MS
        val newUntil = minOf(capUntil, proposedUntil)
        if (newUntil > highAccuracyUntilMs) {
            highAccuracyUntilMs = newUntil
        }
        return highAccuracyUntilMs > now
    }

    private fun ensurePersistentScan(lowPower: Boolean) {
        scanController.ensurePersistentScan(lowPower = lowPower, config = proximityConfigStore.config.value)
    }

    /** Reset away mode so the next scan cycle runs at full speed. */
    fun resetAwayMode(reason: String) {
        if (!isInAwayMode()) return
        Log.d(TAG, "Away mode reset ($reason)")
        roomDetector.resetNoMatchStreak()
        highAccuracyStartedAtMs = System.currentTimeMillis()
    }

    /** No room detected for 5+ minutes: likely not at home. */
    private fun isInAwayMode(): Boolean {
        if (roomDetector.currentRoom.value != null) return false
        if (roomDetector.noMatchStreak == 0) return false
        val ref = roomDetector.lastConfirmedAtMs.takeIf { it > 0 } ?: highAccuracyStartedAtMs
        return System.currentTimeMillis() - ref > AWAY_MODE_TIMEOUT_MS
    }

    /** Screen-off motion burst: wake lock + 2 fast-path snapshots. */
    private suspend fun screenOffMotionBurst() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "massdroid:proximity")
        wl.acquire(15_000)
        try {
            ensurePersistentScan(lowPower = false)
            val wifi = currentConnectedWifi()
            val cfg = proximityConfigStore.config.value
            for (burst in 1..2) {
                val devices = scanController.readFastPathSnapshotWithWarmRetry(
                    logPrefix = "BLE fast-path", detailPrefix = "motion $burst/2"
                )
                if (devices.isNotEmpty()) {
                    if (shouldHoldWifiOnlyRoom(cfg, wifi)) {
                        Log.d(TAG, "Wi-Fi-only room hold (fast-path:motion-$burst): keeping ${roomDetector.currentRoom.value?.roomName}")
                        break
                    }
                    Log.d(TAG, "BLE fast-path (motion $burst/2): ${devices.size} devices")
                    scanController.logBleDevices("fast-path:motion-$burst", devices, cfg)
                    val rssiMap = scanController.buildDetectionAnchorSnapshot(devices, cfg)
                    val hadRoom = roomDetector.currentRoom.value != null
                    when (val result = roomDetector.detectDetailed(rssiMap, cfg, motionActive = true, wifi = wifi.toWifiMatchContext())) {
                        is DetectResult.Confirmed -> { handleConfirmedRoom(result.room, cfg, hadRoom); break }
                        else -> Unit
                    }
                }
                if (burst < 2) kotlinx.coroutines.delay(1_500)
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE fast-path failed: ${e.message}")
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    private suspend fun performMotionDetection(
        logMessage: String,
        burstTrigger: String,
        detailPrefix: String
    ) {
        if (!isWithinSchedule() || isDeviceInDoze()) return

        val now = System.currentTimeMillis()
        if (now - lastMotionBoostMs < MOTION_BOOST_DEBOUNCE_MS) return
        lastMotionBoostMs = now

        updateHighAccuracyWindow(true)
        ensurePersistentScan(lowPower = false)
        Log.d(TAG, logMessage)

        if (System.currentTimeMillis() - lastRoomSwitchMs < COOLDOWN_AFTER_SWITCH_MS) return

        try {
            val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val screenOn = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
            if (screenOn) {
                burstScan(burstTrigger)
            } else {
                val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "massdroid:$detailPrefix")
                wl.acquire(10_000)
                try {
                    val devices = scanController.readFastPathSnapshotWithWarmRetry(
                        logPrefix = "Motion boost",
                        detailPrefix = detailPrefix
                    )
                    if (devices.isNotEmpty()) {
                        val cfg = proximityConfigStore.config.value
                        val currentWifi = currentConnectedWifi()
                        if (shouldHoldWifiOnlyRoom(cfg, currentWifi)) {
                            Log.d(TAG, "Wi-Fi-only room hold ($detailPrefix): keeping ${roomDetector.currentRoom.value?.roomName}")
                            return
                        }
                        scanController.logBleDevices("motion-boost:$detailPrefix", devices, cfg)
                        val rssiMap = scanController.buildDetectionAnchorSnapshot(devices, cfg)
                        val hadCurrentRoomBeforeDetection = roomDetector.currentRoom.value != null
                        when (val result = roomDetector.detectDetailed(
                            rssiMap,
                            cfg,
                            motionActive = true,
                            wifi = currentWifi.toWifiMatchContext()
                        )) {
                            is DetectResult.Confirmed -> handleConfirmedRoom(result.room, cfg, hadCurrentRoomBeforeDetection)
                            else -> Unit
                        }
                    }
                } finally {
                    if (wl.isHeld) wl.release()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Motion detection failed ($detailPrefix): ${e.message}")
        }
    }

    /** Read BLE snapshot from persistent scan and detect room */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun burstScan(trigger: String) {
        val config = proximityConfigStore.config.value
        if (!config.enabled) return

        try {
            val motionActive = motionGate.isMoving.value
            val devices = if (motionActive) {
                scanController.readDetectionSnapshot(preferFresh = true)
            } else {
                proximityScanner.readSnapshot()
            }
            val currentWifi = currentConnectedWifi()
            if (shouldHoldWifiOnlyRoom(config, currentWifi)) {
                Log.d(TAG, "Wi-Fi-only room hold (burst:$trigger): keeping ${roomDetector.currentRoom.value?.roomName}")
                return
            }
            val rssiMap = scanController.buildDetectionAnchorSnapshot(devices, config)
            Log.d(TAG, "BLE snapshot ($trigger): ${devices.size} devices")
            scanController.logBleDevices("snapshot:$trigger", devices, config)

            val hadCurrentRoomBeforeDetection = roomDetector.currentRoom.value != null
            when (val result = roomDetector.detectDetailed(rssiMap, config, motionActive, currentWifi.toWifiMatchContext())) {
                is DetectResult.Confirmed -> handleConfirmedRoom(result.room, config, hadCurrentRoomBeforeDetection)
                is DetectResult.Borderline -> {
                    if (shouldQuickRetryBorderline(result.winner.roomId, motionActive)) {
                        scheduleQuickProximityRetry("burst-$trigger:${result.reason}")
                    }
                }
                else -> Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "Burst scan failed: ${e.message}")
        }
    }

    private fun scheduleQuickProximityRetry(reason: String) {
        if (proximityQuickRetryJob?.isActive == true) return
        proximityQuickRetryJob = scope.launch {
            kotlinx.coroutines.delay(QUICK_RETRY_DELAY_MS)
            val config = proximityConfigStore.config.value
            if (!config.enabled || !isWithinSchedule()) return@launch
            if (System.currentTimeMillis() - lastRoomSwitchMs < COOLDOWN_AFTER_SWITCH_MS) return@launch

            val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val screenOn = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON

            try {
                if (!screenOn && motionGate.isMoving.value) {
                    ensurePersistentScan(lowPower = false)
                }
                val devices = if (!screenOn && motionGate.isMoving.value) {
                    scanController.readFastPathSnapshotWithWarmRetry(
                        logPrefix = "Quick retry",
                        detailPrefix = reason
                    )
                } else {
                    scanController.readDetectionSnapshot(preferFresh = motionGate.isMoving.value)
                }
                if (devices.isEmpty()) {
                    val snapshot = proximityScanner.snapshotDebugState()
                    Log.d(
                        TAG,
                        "Quick retry ($reason): 0 devices " +
                            "(buffer=${snapshot.bufferSize}, freshest=${snapshot.freshestAgeMs}ms, " +
                            "oldest=${snapshot.oldestAgeMs}ms, lastPersistent=${snapshot.lastPersistentCallbackAgeMs}ms, " +
                            "lastBackground=${snapshot.lastBackgroundDeliveryAgeMs}ms, running=${snapshot.persistentRunning})"
                    )
                    return@launch
                }
                scanController.logBleDevices("quick-retry:$reason", devices, config)
                val currentWifi = currentConnectedWifi()
                if (shouldHoldWifiOnlyRoom(config, currentWifi)) {
                    Log.d(TAG, "Wi-Fi-only room hold (quick-retry:$reason): keeping ${roomDetector.currentRoom.value?.roomName}")
                    return@launch
                }

                val hadCurrentRoomBeforeDetection = roomDetector.currentRoom.value != null
                when (val result = roomDetector.detectDetailed(
                    scanController.buildDetectionAnchorSnapshot(devices, config),
                    config,
                    motionGate.isMoving.value,
                    currentWifi.toWifiMatchContext()
                )) {
                    is DetectResult.Confirmed -> {
                        Log.d(TAG, "Quick retry ($reason): confirmed ${result.room.roomName}")
                        handleConfirmedRoom(result.room, config, hadCurrentRoomBeforeDetection)
                    }
                    is DetectResult.Borderline -> {
                        Log.d(TAG, "Quick retry ($reason): borderline ${result.winner.roomName} (${result.reason}) from ${devices.size} devices")
                    }
                    DetectResult.NoCoverage -> {
                        Log.d(TAG, "Quick retry ($reason): no coverage from ${devices.size} devices")
                    }
                    DetectResult.NoDecision -> {
                        Log.d(TAG, "Quick retry ($reason): no decision from ${devices.size} devices")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Quick retry ($reason) failed: ${e.message}")
            }
        }
    }

    fun cancelNoRoomStopTimer(reason: String) {
        noRoomStopController.cancel(reason)
    }

    private fun handleRoomChange(detected: DetectedRoom, config: net.asksakis.massdroidv2.data.proximity.ProximityConfig) {
        proximityQuickRetryJob?.cancel()
        proximityQuickRetryJob = null
        noRoomStopController.cancel("room-confirmed")
        if (!isWithinSchedule()) return
        val roomConfig = config.rooms.find { it.id == detected.roomId }
        val playbackContext = playbackController.resolvePlaybackContext(detected.playerId)
        val sourcePlayerId = playbackContext.transferSourcePlayer?.playerId
        val targetIsPlaying = playbackContext.targetPlayer?.state == PlaybackState.PLAYING
        if (playbackController.isPendingRoom(detected.roomId)) return
        lastRoomSwitchMs = System.currentTimeMillis()
        Log.d(TAG, "Room confirmed: ${detected.roomName} -> ${detected.playerName}")
        if (suppressNextProximityRoomAction) {
            suppressNextProximityRoomAction = false
            if (
                !shouldBlockProximitySelectionForBt.invoke() &&
                playbackController.isPlayerAvailable(detected.playerId) &&
                playerRepository.selectedPlayer.value?.playerId != detected.playerId
            ) {
                playerRepository.selectPlayer(detected.playerId)
                Log.d(TAG, "Proximity selected player (resume-sync): ${detected.playerName}")
            }
            Log.d(TAG, "Proximity room action suppressed after schedule resume: ${detected.roomName}")
            return
        }
        if (playbackContext.ambiguousTransferPlayers.isNotEmpty()) {
            val names = playbackContext.ambiguousTransferPlayers.joinToString { it.displayName }
            Log.w(TAG, "Proximity transfer source ambiguous for ${detected.roomName}: [$names]")
        }

        playbackController.showRoomDetectedNotification(detected)

        if (targetIsPlaying) {
            playbackController.applyRoomVolume(detected)
            if (roomConfig?.playbackConfig?.playlistUri != null) {
                Log.d(
                    TAG,
                    "Proximity room playback skipped: ${detected.playerName} is already playing; " +
                        "keeping current queue for ${detected.roomName}"
                )
            }
            return
        }

        if (config.autoTransfer && sourcePlayerId != null && sourcePlayerId != detected.playerId) {
            playbackController.applyRoomVolume(detected)
            playbackController.performProximityTransfer(sourcePlayerId, detected)
        } else {
            playbackController.showActionNotification(
                room = detected,
                canTransfer = sourcePlayerId != null && sourcePlayerId != detected.playerId,
                sourcePlayerId = sourcePlayerId
            )
        }
    }

    private fun stopEngine() {
        proximityJob?.cancel()
        proximityJob = null
        motionGate.stop()
        scanController.stopPersistentScan()
        proximityScanner.stopBackgroundScan()
        proximityScanner.stopWifiMonitor()
        roomDetector.reset()
        proximityQuickRetryJob?.cancel()
        proximityQuickRetryJob = null
        noRoomStopController.cancel("engine-stopped")
        playbackController.reset()
        lastRoomSwitchMs = 0
        highAccuracyUntilMs = 0L
        highAccuracyStartedAtMs = 0L
        lastMotionBoostMs = 0L
        suppressNextProximityRoomAction = false
        lastConfirmedWifiRoomId = null
        lastConfirmedWifiBssid = null
        lastConfirmedWifiSsid = null
        lastConfirmedWifiAtMs = 0L
        getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
    }

    fun reevaluate(reason: String) {
        val config = proximityConfigStore.config.value
        if (shouldRunProximity(config)) {
            if (proximityJob?.isActive != true) {
                Log.d(TAG, "Reevaluating proximity engine ($reason): start")
                startProximityEngine()
            }
        } else {
            Log.d(TAG, "Reevaluating proximity engine ($reason): stop")
            stopEngine()
        }
    }

    private fun isWithinSchedule(): Boolean {
        val schedule = proximityConfigStore.config.value.schedule.normalized()
        if (!schedule.enabled) return true
        val now = java.util.Calendar.getInstance()
        val dayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK)
        // Calendar: Sun=1..Sat=7, our schedule: Mon=1..Sun=7
        val day = if (dayOfWeek == java.util.Calendar.SUNDAY) 7 else dayOfWeek - 1
        if (day !in schedule.days) return false
        val minuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val startMinute = schedule.effectiveStartMinuteOfDay
        val endMinute = schedule.effectiveEndMinuteOfDay
        return if (startMinute <= endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    // endregion

    private fun currentConnectedWifi(): ProximityScanner.ConnectedWifiInfo? =
        proximityScanner.readConnectedWifiInfo()

    private fun ProximityScanner.ConnectedWifiInfo?.toWifiMatchContext(): WifiMatchContext =
        WifiMatchContext(
            bssid = this?.bssid,
            ssid = this?.ssid
        )

}
