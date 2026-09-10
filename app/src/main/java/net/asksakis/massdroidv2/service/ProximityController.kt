package net.asksakis.massdroidv2.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.proximity.DetectResult
import net.asksakis.massdroidv2.data.proximity.DetectedRoom
import net.asksakis.massdroidv2.data.proximity.MotionGate
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.ProximityTransferMode
import net.asksakis.massdroidv2.data.proximity.effectiveTransferMode
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.data.proximity.RoomDetector.WifiMatchContext
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository

class ProximityController(
    private val service: android.app.Service,
    private val scope: CoroutineScope,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val proximityConfigStore: ProximityConfigStore,
    private val proximityScanner: ProximityScanner,
    private val roomDetector: RoomDetector,
    private val motionGate: MotionGate,
    private val shouldBlockProximitySelectionForBt: () -> Boolean,
    private val sendVolumeCommand: (playerId: String, volume: Int) -> Unit,
    /** True while a Bluetooth sink flagged as car audio is connected; see [CarAudioPresence]. */
    private val carAudioConnected: kotlinx.coroutines.flow.StateFlow<Boolean>,
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

        /**
         * How long after the last step the scan keeps running while the detector has not
         * yet agreed with itself. The old rule stopped at a 60 s cap counted from the
         * FIRST step, so a 55 s walk left a 2 s tail: the correct room landed with two
         * seconds to spare (2026-09-06, 07:41:53 vs 07:41:55) and a slightly longer walk
         * would have frozen the previous room. This is the ceiling, not the usual case:
         * the scan stops the moment the detection settles.
         */
        private const val SETTLE_AFTER_MOTION_MAX_MS = 90_000L

        /** Only reached if the idle-mode broadcast never arrives; long on purpose. */
        private const val DOZE_FALLBACK_POLL_MS = 10 * 60 * 1000L

        /**
         * A room may be committed only once the picture has stopped changing: no device
         * seen for the first time since the scan started for this long, and the scan at
         * least [BUFFER_MIN_AGE_MS] old. At a cold start the buffer went 5, 7, 8 devices
         * over twenty seconds and the room was committed at 7, on the two loudest anchors'
         * silence. Detection still runs and still accumulates its consecutive wins during
         * warm-up, so the commit lands the moment the gate opens rather than two reads later.
         */
        private const val BUFFER_STABLE_MS = 5_000L
        private const val BUFFER_MIN_AGE_MS = 4_000L

        /** How long the buffer must stay quiet before a read. */
        private const val BUFFER_QUIET_MS = 1_000L
        private const val HIGH_ACCURACY_MAX_MS = 60_000L
        private const val BG_CONFIRM_MIN_DEVICES = 4
        private const val MOTION_BOOST_DEBOUNCE_MS = 1_000L
        /** A skipped motion boost is logged at most this often, not once per trigger. */
        private const val GATE_LOG_INTERVAL_MS = 60_000L
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
    /** Wall-clock of the last loop pass that saw the phone moving. */
    private var lastMotionSeenMs = 0L
    /** True while both scans are deliberately off because the phone is still and the room settled. */
    private var scanningSuspended = false
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
        // A protected system broadcast, so it must be registered as exported.
        androidx.core.content.ContextCompat.registerReceiver(
            service,
            dozeReceiver,
            android.content.IntentFilter(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        observeProximityConfig()
        noRoomStopController.start()
    }

    fun stop() {
        stopEngine()
        noRoomStopController.stop()
        try { service.unregisterReceiver(bleScanReceiver) } catch (_: Exception) { }
        try { service.unregisterReceiver(dozeReceiver) } catch (_: Exception) { }
    }

    fun handleStartCommand(intent: Intent?): Boolean {
        return playbackController.handleStartCommand(intent)
    }

    private fun <T> getSystemService(serviceClass: Class<T>): T? = service.getSystemService(serviceClass)
    private fun getSystemService(name: String): Any? = service.getSystemService(name)
    private fun checkSelfPermission(permission: String): Int = service.checkSelfPermission(permission)

    /**
     * Fires when the device enters or leaves doze. The doze branch of the main loop waits on
     * this instead of polling every 30 s: Android already tells us the moment it wakes the
     * device (its own significant-motion detector, the charger, the screen), so the first
     * room change after a long idle no longer pays up to half a minute of polling latency.
     */
    private val dozeChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val dozeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                dozeChanged.tryEmit(Unit)
            }
        }
    }

    private val bleScanReceiver = object : android.content.BroadcastReceiver() {
        @android.annotation.SuppressLint("InlinedApi")
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val errorCode = intent?.getIntExtra(android.bluetooth.le.BluetoothLeScanner.EXTRA_ERROR_CODE, 0) ?: 0
            if (errorCode != 0) {
                // Nothing is delivered after an error; the next ensureScans restarts the scan.
                proximityScanner.onBackgroundScanError(errorCode)
                return
            }
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
            // Decide from the SAME picture the main loop sees. The raw batch is MAC-only
            // (the offloaded filter cannot match names), so judging it alone let the
            // receiver and the loop vote for different rooms on the same walk.
            val merged = scanController.readDetectionSnapshot(preferFresh = true)
            evaluateSnapshot(
                trigger = "bg-merged",
                devices = merged,
                config = config,
                wifi = currentConnectedWifi(),
                motionActive = motionGate.isMoving.value,
                minDevicesToCommit = BG_CONFIRM_MIN_DEVICES
            )
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
            // Bluetooth is a RUNTIME gate, not a persisted toggle. While BT is off we stop scanning
            // but keep the user's enabled intent in config, so a BT flap (common on car connect) no
            // longer permanently disables Follow Me: it resumes automatically when BT returns. The
            // settings UI already surfaces this paused state ("Bluetooth is off. Turn it on to detect
            // room changes.") off config.enabled && !btEnabled.
            combine(
                proximityConfigStore.config,
                proximityScanner.observeBluetoothState().distinctUntilChanged(),
            ) { config, btOn -> config to btOn }
                .collect { (config, btOn) ->
                    val shouldRun = shouldRunProximity(config) && btOn
                    when {
                        !shouldRun -> stopEngine()
                        proximityJob?.isActive != true -> startProximityEngine()
                        else -> applyConfigWhileRunning(config)
                    }
                }
        }
    }

    /**
     * A config write while the engine runs is NOT a restart. The detector reads the config on
     * every read, and the scan filters are reconciled from it by [ensureScans] on the next
     * cycle. Restarting here made a room calibration cost far more than it needed: `stopEngine`
     * cleared the held room and the buffer, cancelled the notification and spent two scan
     * starts, and the warm gate then withheld commits until the buffer had refilled. The one
     * thing to handle is a held room that the write removed, because the detector still holds it.
     */
    private fun applyConfigWhileRunning(config: net.asksakis.massdroidv2.data.proximity.ProximityConfig) {
        val held = roomDetector.currentRoom.value ?: return
        val room = config.rooms.find { it.id == held.roomId }
        if (room == null) {
            Log.d(TAG, "Held room ${held.roomName} was removed from the config, clearing")
            roomDetector.reset()
            getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
            return
        }
        // A new speaker or name for the room we are in: re-bind without re-detecting. Later
        // reads of the same room id are "no change" and would never refresh the stored copy,
        // so reapplyConfirmedRoom() kept selecting the old speaker.
        roomDetector.refreshHeldRoom(DetectedRoom(room.id, room.name, room.playerId, room.playerName))
    }

    private fun shouldRunProximity(config: net.asksakis.massdroidv2.data.proximity.ProximityConfig): Boolean {
        return config.enabled && config.rooms.isNotEmpty() && hasFollowMePermissions()
    }

    /**
     * Whether an entry point may start BLE scanning right now: inside the schedule and not
     * in doze. Both the engine start and the startup warm-up ask this; the warm-up used to
     * check only the schedule and started scans in doze that the loop's doze gate then
     * stopped on its first pass, one start and one stop against the budget for no data.
     * On doze exit the loop starts the motion gate and the active branches resume scans.
     */
    private fun radioAllowed(): Boolean = isWithinSchedule() && !isDeviceInDoze()

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

        if (radioAllowed()) {
            motionGate.start()
            // Fast from the first millisecond. Starting LOW_POWER here and asking for
            // LOW_LATENCY in the warm-up two lines later never worked: the scan
            // controller defers a mode flip within 10 s of a restart, so the whole cold
            // start ran at the low duty cycle and took 27 s to hear all eight anchors.
            ensureScans(lowPower = false)
            scanController.startBackgroundScanForConfig(proximityConfigStore.config.value)
        }

        proximityJob = scope.launch {
            var scheduleSuspended = !isWithinSchedule()
            // Startup warmup: use short high-accuracy snapshots instead of 4s burst spacing.
            if (radioAllowed()) {
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
                // A room confirmed while a selection lock is held is dropped by the
                // repository, and the room detector will not confirm the same room
                // again, so the switch was lost for good. Leaving the car is exactly
                // that case: the head unit can hold the Bluetooth link for minutes
                // after it is switched off, so the car-audio lock outlives the drive
                // and can still be in place when the office is detected (measured:
                // room confirmed at confidence 1.00 and rejected in the same
                // millisecond, lock released 94 seconds later). Re-evaluate the
                // CURRENT room when a lock goes away, rather than replaying a stale
                // intent, so a room change during the lock is still respected.
                var wasLocked = playerRepository.selectionLock.value != null
                playerRepository.selectionLock.collect { lock ->
                    val locked = lock != null
                    val released = wasLocked && !locked
                    wasLocked = locked
                    if (released) syncSelectedPlayerToCurrentRoom("selection-lock-released")
                }
            }

            launch {
                var wasMoving = motionGate.isMoving.value
                motionGate.isMoving.collect { moving ->
                    val risingEdge = moving && !wasMoving
                    wasMoving = moving
                    if (!risingEdge) return@collect
                    // Steps are a walk, and a walk is how the user arrives somewhere, so being
                    // away from every anchor does not gate them; only the car does.
                    if (skipMotionBoost(carOnly = true)) return@collect
                    performMotionDetection("Motion boost: immediate high-accuracy escalation", "motion-open", "immediate")
                }
            }

            launch {
                motionGate.motionEvents.collect {
                    if (skipMotionBoost(carOnly = false)) return@collect
                    performMotionDetection("Motion boost: significant-motion refresh", "motion-refresh", "significant-motion")
                }
            }

            launch {
                // Leaving the car is the arrival somewhere: the office, or a room at home after
                // parking. Re-detect at once instead of waiting for the next step window, and
                // drop away mode so the first read is not throttled to the away cadence.
                var wasConnected = carAudioConnected.value
                carAudioConnected.collect { connected ->
                    val released = wasConnected && !connected
                    wasConnected = connected
                    if (!released) return@collect
                    resetAwayMode("car-released")
                    performMotionDetection("Motion boost: car audio released, re-detecting the room", "car-released", "car-released")
                }
            }

            /*
             * Main detection loop.
             *
             * Three operating modes based on device state:
             *   SCREEN_ON  – persistent BLE scan + periodic burst reads
             *   SCREEN_OFF + MOTION – wake-lock fast-path burst reads
             *   SCREEN_OFF + IDLE  - no scan at all, waiting on the sensor hub
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
            // Give startup the same bounded settle window a walk gets: with no room yet and
            // no motion seen, the loop would otherwise suspend on its very first pass.
            lastMotionSeenMs = System.currentTimeMillis()
            while (proximityConfigStore.config.value.enabled) {

                // ── Gate: schedule ──
                if (!isWithinSchedule()) {
                    if (!scheduleSuspended) { suspendProximityForSchedule(); scheduleSuspended = true }
                    kotlinx.coroutines.delay(60_000); continue
                }
                // The schedule resumes only when the radio may run: resuming inside doze
                // started scans and the warm-up that the doze gate right below stopped
                // again. While in doze the doze gate handles the wait; the resume happens
                // on the first pass after doze exit.
                if (scheduleSuspended && radioAllowed()) { resumeProximityAfterSchedule(); scheduleSuspended = false }

                // ── Gate: doze ──
                if (isDeviceInDoze()) {
                    // Go through the same suspend/resume pair as the idle branch, so the
                    // suspended flag stays truthful. Restarting the scans directly here left
                    // it set: a doze exit without motion (plugging in the charger) then ran
                    // both scans for ever, because suspendScanning() saw "already suspended".
                    suspendScanning()
                    motionGate.stop()
                    highAccuracyUntilMs = 0L; highAccuracyStartedAtMs = 0L
                    // Event-driven: wake on the idle-mode broadcast. The timeout is only a
                    // safety net for a missed broadcast, not the normal path.
                    withTimeoutOrNull(DOZE_FALLBACK_POLL_MS) { dozeChanged.first() }
                    if (!isDeviceInDoze() && isWithinSchedule()) {
                        // Only the motion gate. Starting the scans here had the next pass
                        // stopping them again on a still phone, one start and one stop against
                        // the scan-start budget for no data; the active branches resume them.
                        motionGate.start()
                    }
                    continue
                }

                // ── Evaluate state ──
                val isMoving = motionGate.isMoving.value
                val screenOn = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
                // UI-requested high accuracy (live monitoring) only matters while the screen is on:
                // the settings UI can't be visible with the screen off, and if its lifecycle fails to
                // clear the flag (screen times out while still composed) it would otherwise pin the
                // screen-off motion burst + wakelock loop forever. Gate it on screenOn defensively.
                val highAccuracy = updateHighAccuracyWindow(isMoving) ||
                    (screenOn && proximityScanner.uiHighAccuracyRequested)
                val nowMs = System.currentTimeMillis()
                val cooledDown = nowMs - lastRoomSwitchMs >= COOLDOWN_AFTER_SWITCH_MS
                if (isMoving) lastMotionSeenMs = nowMs
                // Keep listening after the walk ends until the detector agrees with itself,
                // bounded so an ambiguous spot cannot hold the radio open for ever.
                val settling = !isDetectionSettled() &&
                    lastMotionSeenMs > 0L &&
                    nowMs - lastMotionSeenMs < SETTLE_AFTER_MOTION_MAX_MS

                when {
                    // ── Screen ON ──
                    screenOn && cooledDown -> {
                        resumeScanning()
                        // Away mode: no room matched for 5 min, conserve battery
                        if (isInAwayMode()) {
                            ensureScans(lowPower = true)
                            burstScan("away")
                            kotlinx.coroutines.delay(AWAY_MODE_SCAN_INTERVAL_MS)
                            continue
                        }
                        // Normal: scan aggressiveness follows motion state, and a buffer that is
                        // still filling counts as a reason to hurry. The warm gate bounds it:
                        // once no new device has appeared for a few seconds this drops back.
                        val warming = !bufferWarm()
                        ensureScans(lowPower = !highAccuracy && !warming)
                        burstScan(if (highAccuracy) "motion" else "screen")
                        // While the buffer is still filling the scan is already fast, so poll
                        // at the fast cadence too; otherwise a commit whose gate opened at
                        // second 14 waited for the 12 s tick and landed at second 19.
                        awaitBufferQuiet(
                            if (highAccuracy || warming) MOTION_SCAN_INTERVAL_MS else BURST_SCAN_INTERVAL_MS * 3
                        )
                    }

                    // ── Screen OFF + motion ──
                    // Also the tail after motion: the phone is down but the detector has not
                    // yet confirmed where, so this is exactly the moment to keep both scans up.
                    // Motion, or the tail after it until the detector settles. NOT the
                    // high-accuracy window: once settled and still, that window only kept
                    // the radio on for nothing.
                    !screenOn && (isMoving || settling) && cooledDown -> {
                        resumeScanning()
                        screenOffMotionBurst()
                        awaitBufferQuiet(MOTION_SCAN_INTERVAL_MS)
                    }

                    // ── Screen OFF + idle (or transient cooldown) ──
                    // Detection while idle is handled by the PendingIntent bg-receiver and the
                    // dedicated motion collectors, so the loop need not spin. When truly idle
                    // (screen off, no motion) wait for motion (instant wake, preserves the fast
                    // path) or the idle poll interval, instead of waking the CPU every 2s.
                    // Transient cooldown states keep the short poll so normal cadence resumes fast.
                    else -> {
                        if (!screenOn && !isMoving && !settling) {
                            // Follow Me follows the PHONE. Once it is still AND the detector has
                            // settled on a room, nothing new can be learned until it moves, so
                            // BOTH scans stop and no room decision is taken until motion.
                            //
                            // The first version of this stopped only the persistent scan and
                            // left the MAC-only batch scan feeding the detector. That scan
                            // cannot hear NAME anchors, the scorer read their silence as
                            // "definitely not in that room", and a still phone in the living
                            // room was moved to the bathroom speaker four minutes after it had
                            // correctly found the living room. Stopping the data source is what
                            // makes the decision safe, not filtering the decision.
                            suspendScanning()
                            withTimeoutOrNull(SCREEN_OFF_IDLE_SCAN_INTERVAL_MS) {
                                motionGate.isMoving.first { it }
                            }
                        } else {
                            resumeScanning()
                            ensureScans(lowPower = bufferWarm())
                            kotlinx.coroutines.delay(2_000)
                        }
                    }
                }
            }
        }
    }

    private suspend fun runStartupWarmup() {
        ensureScans(lowPower = false)
        repeat(STARTUP_WARMUP_SNAPSHOTS) { index ->
            if (index > 0) {
                kotlinx.coroutines.delay(STARTUP_WARMUP_INTERVAL_MS)
            }
            val devices = scanController.readDetectionSnapshot(preferFresh = true)
            if (devices.isEmpty()) return@repeat
            val rssi = scanController.buildDetectionAnchorSnapshot(devices, proximityConfigStore.config.value)
            // Warm-up primes the detector's consecutive-win count on a filling buffer; it
            // must never be the read that commits. It used to be exactly that: its first
            // read was win one, the main loop's first burst win two, four seconds in.
            roomDetector.detectDetailed(
                scanResults = rssi,
                config = proximityConfigStore.config.value,
                motionActive = false,
                wifi = currentConnectedWifi().toWifiMatchContext(),
                commitRoomChange = false
            )
            if (roomDetector.currentRoom.value != null) return
        }
        if (!updateHighAccuracyWindow(motionGate.isMoving.value) && !proximityScanner.uiHighAccuracyRequested) {
            ensureScans(lowPower = true)
        }
    }

    private fun suspendProximityForSchedule() {
        Log.d(TAG, "Proximity schedule inactive: suspending Follow Me")
        proximityQuickRetryJob?.cancel()
        proximityQuickRetryJob = null
        suspendScanning()
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
        resumeScanning()
        ensureScans(lowPower = bufferWarm())
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
        if (playerRepository.selectPlayer(room.playerId)) {
            Log.d(TAG, "Proximity selected player ($reason): ${room.playerName}")
        } else {
            Log.d(TAG, "Proximity select rejected by a selection lock ($reason): ${room.playerName}")
        }
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

    /**
     * The one place a snapshot becomes a room decision. Every trigger (main-loop burst,
     * screen-off motion fast path, motion boost, quick retry, batch receiver) used to carry
     * its own copy of this block and the copies drifted: paths that skipped empty snapshots
     * never evaluated Wi-Fi-only rooms, and a path that withheld the BLE commit dropped the
     * Wi-Fi answer with it.
     *
     * BLE evidence needs a warm buffer (see [bufferWarm]): a cold radio hearing nothing says
     * nothing, so such a read is evaluated for Wi-Fi only. A warm radio hearing nothing is
     * evidence and is counted (it is how "left all rooms" gets counted with the screen off).
     * The Wi-Fi override commits on its own evidence inside the detector, independent of the
     * BLE commit gate.
     */
    private fun evaluateSnapshot(
        trigger: String,
        devices: List<ProximityScanner.ScannedDevice>,
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig,
        wifi: ProximityScanner.ConnectedWifiInfo?,
        motionActive: Boolean,
        minDevicesToCommit: Int = 0,
    ): DetectResult {
        if (shouldHoldWifiOnlyRoom(config, wifi)) {
            Log.d(TAG, "Wi-Fi-only room hold ($trigger): keeping ${roomDetector.currentRoom.value?.roomName}")
            return DetectResult.NoDecision
        }
        Log.d(TAG, "BLE snapshot ($trigger): ${devices.size} devices")
        scanController.logBleDevices(trigger, devices, config)
        val rssiMap = scanController.buildDetectionAnchorSnapshot(devices, config)
        val warm = bufferWarm()
        val wifiContext = wifi.toWifiMatchContext()
        val hadRoom = roomDetector.currentRoom.value != null
        val result = if (rssiMap.isEmpty() && !warm) {
            roomDetector.detectWifiOnly(config, wifiContext)
        } else {
            roomDetector.detectDetailed(
                rssiMap,
                config,
                motionActive,
                wifiContext,
                commitRoomChange = warm && rssiMap.size >= minDevicesToCommit
            )
        }
        when (result) {
            is DetectResult.Confirmed -> handleConfirmedRoom(result.room, config, hadRoom)
            is DetectResult.Borderline -> {
                if (shouldQuickRetryBorderline(result.winner.roomId, motionActive)) {
                    scheduleQuickProximityRetry("$trigger:${result.reason}")
                }
            }
            else -> Unit
        }
        return result
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

    /**
     * Sleep until the buffer changes materially, then until it has been quiet for
     * [BUFFER_QUIET_MS], so one read sees a whole burst of results rather than its first
     * packet. [maxWaitMs] is the ceiling when nothing changes at all, which makes the old
     * polling interval the worst case instead of the normal case, drain included.
     */
    private suspend fun awaitBufferQuiet(maxWaitMs: Long) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        withTimeoutOrNull(maxWaitMs) { proximityScanner.bufferChanged.first() } ?: return
        // Drain inside the same ceiling, so the old interval really is the worst case.
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return
            withTimeoutOrNull(minOf(BUFFER_QUIET_MS, remaining)) { proximityScanner.bufferChanged.first() } ?: return
        }
    }

    /** See [BUFFER_STABLE_MS]. False while no persistent scan is running. */
    private fun bufferWarm(): Boolean {
        val w = proximityScanner.bufferWarmth()
        if (!w.scanRunning || w.scanFailed || w.scanStartedMs == 0L) return false
        val now = System.currentTimeMillis()
        return now - w.scanStartedMs >= BUFFER_MIN_AGE_MS && now - w.lastNewDeviceMs >= BUFFER_STABLE_MS
    }

    /**
     * Whether the detector currently agrees with itself: it holds a room, and the most
     * recent read named that same room. A held room with a dissenting last read is an
     * oscillation in progress and is not settled.
     */
    private fun isDetectionSettled(): Boolean {
        val room = roomDetector.currentRoom.value ?: return false
        val last = roomDetector.lastDetection.value ?: return false
        // Agreement must come from a read taken after the phone stopped moving; a read from
        // before the walk agreeing with the room we then left is not settlement.
        return last.roomId == room.roomId && roomDetector.lastDetectionAtMs > lastMotionSeenMs
    }

    /**
     * Turn every BLE source off. The buffer is kept so a wake within the 30 s retain
     * window still has the last full reading; anything older prunes itself.
     * A pending quick retry is cancelled because it would re-read that buffer and vote.
     */
    private fun suspendScanning() {
        if (scanningSuspended) return
        scanningSuspended = true
        proximityQuickRetryJob?.cancel()
        proximityQuickRetryJob = null
        scanController.stopPersistentScan(clearBuffers = false)
        scanController.stopBackgroundScan()
        Log.d(TAG, "Scanning suspended: phone still and room settled")
    }

    /** Undo [suspendScanning]. Idempotent, so every active branch may call it freely. */
    private fun resumeScanning() {
        if (!scanningSuspended) return
        scanningSuspended = false
        scanController.startBackgroundScanForConfig(proximityConfigStore.config.value)
        Log.d(TAG, "Scanning resumed")
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

    private fun ensureScans(lowPower: Boolean) {
        scanController.ensureScans(lowPower = lowPower, config = proximityConfigStore.config.value)
    }

    /** Reset away mode so the next scan cycle runs at full speed. */
    fun resetAwayMode(reason: String) {
        if (!isInAwayMode()) return
        Log.d(TAG, "Away mode reset ($reason)")
        roomDetector.resetNoMatchStreak()
        highAccuracyStartedAtMs = System.currentTimeMillis()
    }

    private var lastGateLogMs = 0L

    /**
     * Whether a motion boost should not run at all, and why.
     *
     * In the car the significant-motion sensor fires every few seconds and every trigger
     * cost a wakelock and a BLE burst that could hear nothing: 66 triggers and 384 empty
     * reads in 15 minutes on 2026-09-10. Two gates: a connected car-audio device (the
     * user's own flag, see [CarAudioPresence]) blocks every boost; being away from every
     * anchor for five minutes blocks significant-motion refreshes only, because a step
     * window is a walk and a walk may be the arrival. The car's falling edge and the next
     * confirmed room lift the gates. Logged at most once a minute.
     */
    private fun skipMotionBoost(carOnly: Boolean): Boolean {
        val reason = when {
            carAudioConnected.value -> "car audio connected"
            !carOnly && isInAwayMode() -> "away from every anchor"
            else -> return false
        }
        val now = System.currentTimeMillis()
        if (now - lastGateLogMs >= GATE_LOG_INTERVAL_MS) {
            lastGateLogMs = now
            Log.d(TAG, "Motion boost skipped: $reason")
        }
        return true
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
            ensureScans(lowPower = false)
            val wifi = currentConnectedWifi()
            val cfg = proximityConfigStore.config.value
            for (burst in 1..2) {
                val devices = scanController.readFastPathSnapshotWithWarmRetry(
                    logPrefix = "BLE fast-path", detailPrefix = "motion $burst/2"
                )
                val result = evaluateSnapshot("fast-path:motion-$burst", devices, cfg, wifi, motionActive = true)
                if (result is DetectResult.Confirmed) break
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
        resumeScanning()
        ensureScans(lowPower = false)
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
                    evaluateSnapshot(
                        trigger = "motion-boost:$detailPrefix",
                        devices = devices,
                        config = proximityConfigStore.config.value,
                        wifi = currentConnectedWifi(),
                        motionActive = true
                    )
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
            evaluateSnapshot("snapshot:$trigger", devices, config, currentConnectedWifi(), motionActive)
        } catch (e: Exception) {
            Log.w(TAG, "Burst scan failed: ${e.message}")
        }
    }

    private fun scheduleQuickProximityRetry(reason: String) {
        if (proximityQuickRetryJob?.isActive == true) return
        proximityQuickRetryJob = scope.launch {
            // A retry only makes sense on new evidence; wait for the buffer to move, with
            // the old fixed delay kept as the ceiling.
            awaitBufferQuiet(QUICK_RETRY_DELAY_MS * 4)
            val config = proximityConfigStore.config.value
            if (!config.enabled || !isWithinSchedule()) return@launch
            if (System.currentTimeMillis() - lastRoomSwitchMs < COOLDOWN_AFTER_SWITCH_MS) return@launch

            val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val screenOn = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON

            try {
                if (!screenOn && motionGate.isMoving.value) {
                    ensureScans(lowPower = false)
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
                }
                // Same commit floor as the batch receiver. This retry re-reads the buffer that a
                // receiver read may have just rejected as too small, and it used to commit by
                // default, so the rejection could be undone 1.5 s later by the very read it had
                // scheduled.
                val result = evaluateSnapshot(
                    trigger = "quick-retry:$reason",
                    devices = devices,
                    config = config,
                    wifi = currentConnectedWifi(),
                    motionActive = motionGate.isMoving.value,
                    minDevicesToCommit = BG_CONFIRM_MIN_DEVICES
                )
                val outcome = when (result) {
                    is DetectResult.Confirmed -> "confirmed ${result.room.roomName}"
                    is DetectResult.Borderline -> "borderline ${result.winner.roomName} (${result.reason})"
                    DetectResult.NoCoverage -> "no coverage"
                    DetectResult.NoDecision -> "no decision"
                }
                Log.d(TAG, "Quick retry ($reason): $outcome from ${devices.size} devices")
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

        val transferMode = config.effectiveTransferMode()
        if (transferMode == ProximityTransferMode.SELECT_ONLY) {
            // Quietly hand the user the room's controls (mini player, volume rocker); no move, no prompt.
            selectProximityPlayer(detected, "select-only")
            return
        }

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

        if (transferMode == ProximityTransferMode.AUTO_TRANSFER) {
            // Move-here: transfer silently if there is something playing to move, otherwise just hand
            // over the room's controls. Never notify - notifications are an ASK-only concept.
            if (sourcePlayerId != null && sourcePlayerId != detected.playerId) {
                playbackController.applyRoomVolume(detected)
                playbackController.performProximityTransfer(sourcePlayerId, detected)
            } else {
                selectProximityPlayer(detected, "auto-transfer-no-source")
            }
            return
        }

        // ASK is the only mode that notifies: prompt the user to confirm the move/play.
        playbackController.showActionNotification(
            room = detected,
            canTransfer = sourcePlayerId != null && sourcePlayerId != detected.playerId,
            sourcePlayerId = sourcePlayerId
        )
    }

    /** Select the room's player (respecting the BT-A2DP/Sendspin guard and availability). */
    private fun selectProximityPlayer(detected: DetectedRoom, reason: String) {
        if (shouldBlockProximitySelectionForBt.invoke()) {
            Log.d(TAG, "Proximity select skipped ($reason): BT A2DP sendspin active")
            return
        }
        if (!playbackController.isPlayerAvailable(detected.playerId)) return
        if (playerRepository.selectedPlayer.value?.playerId == detected.playerId) return
        if (playerRepository.selectPlayer(detected.playerId)) {
            Log.d(TAG, "Proximity selected player ($reason): ${detected.playerName}")
        } else {
            // Dropped by a lock, most often the car-audio one while the head unit
            // still holds the Bluetooth link. The lock-release collector in
            // start() re-evaluates the room rather than leaving this lost.
            Log.d(TAG, "Proximity select rejected by a selection lock ($reason): ${detected.playerName}")
        }
    }

    private fun stopEngine() {
        proximityJob?.cancel()
        proximityJob = null
        // Lifecycle state belongs to the engine instance. Left set across a restart (BT off
        // and on while idle) the next idle pass saw "already suspended" and never stopped the
        // scans the restart had started.
        scanningSuspended = false
        motionGate.stop()
        scanController.stopPersistentScan()
        scanController.stopBackgroundScan()
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

    /**
     * Re-apply the room already confirmed, for when whatever was blocking the
     * switch has gone.
     *
     * A room is confirmed once. If the Bluetooth hold was up at that moment the
     * selection is skipped and nothing ever revisits it, so parking the car and
     * walking into a room the app had already recognised left the wrong player
     * selected until the room changed again. The detector still holds the
     * confirmed room, so re-applying needs no extra state.
     */
    fun reapplyConfirmedRoom(reason: String) {
        val detected = roomDetector.currentRoom.value ?: return
        if (!shouldRunProximity(proximityConfigStore.config.value)) return
        if (!isWithinSchedule()) return
        if (playerRepository.selectedPlayer.value?.playerId == detected.playerId) return
        selectProximityPlayer(detected, reason)
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
