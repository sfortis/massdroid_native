package net.asksakis.massdroidv2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.BitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.VolumeSetArgs
import net.asksakis.massdroidv2.data.websocket.sendCommand
import net.asksakis.massdroidv2.data.proximity.DetectedRoom
import net.asksakis.massdroidv2.data.proximity.DetectResult
import net.asksakis.massdroidv2.data.proximity.MotionGate
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.data.proximity.RoomDetector.WifiMatchContext
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.ui.MainActivity
import net.asksakis.massdroidv2.ui.ShortcutAction
import net.asksakis.massdroidv2.ui.ShortcutActionDispatcher
import okhttp3.Request
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "PlaybackSvc"
        private const val PAGE_SIZE_DEFAULT = 50
        private const val VOLUME_STEP = RemoteControlPlayer.VOLUME_SCALE
        private const val VOLUME_OVERRIDE_MS = 15_000L
        private const val CONN_CHANNEL_ID = "massdroid_connection"
        private const val CONN_NOTIFICATION_ID = 3
        private const val PROXIMITY_CHANNEL_ID = "massdroid_proximity_v2"
        private const val PROXIMITY_NOTIFICATION_ID = 4
        private const val PROXIMITY_TRANSFER_ACTION = "net.asksakis.massdroidv2.PROXIMITY_TRANSFER"
        const val PROXIMITY_PLAY_ACTION = "net.asksakis.massdroidv2.PROXIMITY_PLAY"
        const val PROXIMITY_REEVALUATE_ACTION = "net.asksakis.massdroidv2.PROXIMITY_REEVALUATE"
        private const val BURST_SCAN_INTERVAL_MS = 4_000L
        private const val MOTION_SCAN_INTERVAL_MS = 2_000L
        private const val QUICK_RETRY_DELAY_MS = 1_500L
        private const val COOLDOWN_AFTER_SWITCH_MS = 15_000L
        private const val NO_ROOM_STOP_TIMEOUT_MS = 10 * 60 * 1000L
        private const val HIGH_ACCURACY_WINDOW_MS = 30_000L
        private const val HIGH_ACCURACY_MAX_MS = 60_000L
        private const val HIGH_ACCURACY_SNAPSHOT_RETAIN_MS = 30_000L
        private const val HIGH_ACCURACY_FRESH_SNAPSHOT_MS = 8_000L
        private const val BLE_DEBUG_DEVICE_LIMIT = 8
        private const val BG_CONFIRM_MIN_DEVICES = 4
        private const val MOTION_BOOST_DEBOUNCE_MS = 1_000L
        private const val STARTUP_WARMUP_SNAPSHOTS = 3
        private const val STARTUP_WARMUP_INTERVAL_MS = 1_200L
        private const val PROXIMITY_COMMAND_DEDUPE_MS = 12_000L
        private const val WIFI_ROOM_GRACE_MS = 10_000L
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var sleepTimerBridge: SleepTimerBridge
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var wsClient: MaWebSocketClient
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var shortcutDispatcher: ShortcutActionDispatcher
    @Inject lateinit var playHistoryRepository: net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
    @Inject lateinit var genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository
    @Inject lateinit var proximityConfigStore: ProximityConfigStore
    @Inject lateinit var proximityScanner: ProximityScanner
    @Inject lateinit var roomDetector: RoomDetector
    @Inject lateinit var motionGate: MotionGate

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var remotePlayer: RemoteControlPlayer? = null
    private var sendspinPlayerId: String? = null
    private var cachedSearchResults: SearchResult? = null
    private var cachedArtworkUrl: String? = null
    @Volatile private var cachedArtworkData: ByteArray? = null
    private var optimisticVolume: Int? = null
    private var volumeOverrideUntil: Long = 0
    private var sendspinController: SendspinAudioController? = null
    private var sendspinActive = false
    private lateinit var sleepTimerManager: SleepTimerManager
    private val sleepTimerOriginalVolumes = mutableMapOf<String, Int>()
    private var proximityJob: Job? = null
    private var proximityQuickRetryJob: Job? = null
    private var noRoomStopJob: Job? = null
    private var noRoomStopArmedPlayerId: String? = null
    private var pendingProximityTransfer: DetectedRoom? = null
    private var pendingTransferSourcePlayerId: String? = null
    private var lastRoomSwitchMs = 0L
    private var highAccuracyUntilMs = 0L
    private var highAccuracyStartedAtMs = 0L
    private var persistentScanLowPower: Boolean? = null
    private var lastMotionBoostMs = 0L
    private var lastProximityTransferCommand: RecentProximityTransferCommand? = null
    private var lastRoomPlaybackCommand: RecentRoomPlaybackCommand? = null
    private var suppressNextProximityRoomAction = false
    private var lastConfirmedWifiRoomId: String? = null
    private var lastConfirmedWifiBssid: String? = null
    private var lastConfirmedWifiSsid: String? = null
    private var lastConfirmedWifiAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        createConnectionNotificationChannel()
        remotePlayer = createRemotePlayer()
        createMediaSession()
        loadSendspinPlayerId()
        observePlayerState()
        observeQueueItems()
        createSendspinController()
        createSleepTimer()
        observeSendspinEnabled()
        observeConnectionState()
        observeShortcutActions()
        observeAudioFormatPreference()
        registerBtAudioDeviceCallback()
        createProximityNotificationChannel()
        registerReceiver(bleScanReceiver, android.content.IntentFilter(ProximityScanner.BLE_SCAN_ACTION), RECEIVER_NOT_EXPORTED)
        observeProximityConfig()
        observeBluetoothState()
        observeRoomActivity()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PROXIMITY_TRANSFER_ACTION -> {
                val room = pendingProximityTransfer ?: return super.onStartCommand(intent, flags, startId)
                val sourceId = pendingTransferSourcePlayerId
                pendingProximityTransfer = null
                pendingTransferSourcePlayerId = null
                getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
                if (sourceId != null) performProximityTransfer(sourceId, room)
            }
            PROXIMITY_PLAY_ACTION -> {
                val room = pendingProximityTransfer ?: return super.onStartCommand(intent, flags, startId)
                val roomConfig = proximityConfigStore.config.value.rooms.find { it.id == room.roomId }
                pendingProximityTransfer = null
                pendingTransferSourcePlayerId = null
                getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
                scope.launch {
                    try {
                        playerRepository.selectPlayer(room.playerId)
                        val queueItems = try { musicRepository.getQueueItems(room.playerId, limit = 1) } catch (_: Exception) { emptyList() }
                        if (queueItems.isNotEmpty()) {
                            playerRepository.play(room.playerId)
                            applyRoomVolume(room)
                            Log.d(TAG, "Proximity play on: ${room.playerName}")
                        } else {
                            performRoomPlayback(room, roomConfig)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Proximity play failed: ${e.message}")
                    }
                }
            }
            PROXIMITY_REEVALUATE_ACTION -> {
                reevaluateProximityEngine("intent")
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun loadSendspinPlayerId() {
        scope.launch {
            settingsRepository.sendspinClientId.collect { id ->
                sendspinPlayerId = id
            }
        }
    }

    private fun createSendspinController() {
        sendspinController = SendspinAudioController(
            context = this,
            sendspinManager = sendspinManager,
            settingsRepository = settingsRepository,
            playerRepository = playerRepository,
            wsClient = wsClient,
            onMetadataChanged = { _ -> },
            onStateChanged = { _, _, _ -> updateConnectionNotification() }
        )
    }

    private fun createSleepTimer() {
        sleepTimerManager = SleepTimerManager(
            context = this,
            scope = scope,
            bridge = sleepTimerBridge,
            onFadeFraction = { fraction ->
                // Cache original volumes on first fade call, restore on fraction=1.0
                if (fraction < 1f && sleepTimerOriginalVolumes.isEmpty()) {
                    for (p in playerRepository.players.value) {
                        if (p.state == net.asksakis.massdroidv2.domain.model.PlaybackState.PLAYING
                            || p.playerId == sendspinPlayerId) {
                            sleepTimerOriginalVolumes[p.playerId] = p.volumeLevel
                        }
                    }
                }
                val originals = sleepTimerOriginalVolumes
                for ((id, origVol) in originals) {
                    val targetVol = (origVol * fraction).toInt()
                    if (id == sendspinPlayerId) {
                        sendspinManager.setVolume(targetVol)
                    } else {
                        scope.launch {
                            try { playerRepository.setVolume(id, targetVol) } catch (_: Exception) {}
                        }
                    }
                }
                if (fraction >= 1f) sleepTimerOriginalVolumes.clear()
            },
            onStop = {
                // Stop ALL playing players, not just selected
                val playingPlayers = playerRepository.players.value.filter {
                    it.state == net.asksakis.massdroidv2.domain.model.PlaybackState.PLAYING
                }
                for (p in playingPlayers) {
                    try { playerRepository.pause(p.playerId) } catch (_: Exception) {}
                }
                if (sendspinActive) {
                    sendspinController?.handlePause()
                }
            }
        )

        // Register cancel broadcast receiver
        val filter = android.content.IntentFilter(SleepTimerManager.ACTION_CANCEL)
        androidx.core.content.ContextCompat.registerReceiver(
            this, sleepTimerCancelReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val sleepTimerCancelReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            if (intent.action == SleepTimerManager.ACTION_CANCEL) {
                sleepTimerManager.cancel()
            }
        }
    }

    private fun observeSendspinEnabled() {
        scope.launch {
            settingsRepository.sendspinEnabled.collect { enabled ->
                Log.d(TAG, "Sendspin enabled: $enabled")
                if (enabled && !sendspinActive) {
                    // Only start if WS is connected
                    val connected = wsClient.connectionState.value is ConnectionState.Connected
                    if (connected) {
                        sendspinActive = true
                        sendspinController?.start()
                    }
                } else if (!enabled && sendspinActive) {
                    sendspinActive = false
                    sendspinController?.stop()
                }
            }
        }
    }

    private fun observeConnectionState() {
        scope.launch {
            wsClient.connectionState.collect { state ->
                updateConnectionNotification()
                // Auto-start sendspin on first connect if enabled
                if (state is ConnectionState.Connected) {
                    val sendspinOn = settingsRepository.sendspinEnabled.first()
                    if (sendspinOn && !sendspinActive) {
                        sendspinActive = true
                        sendspinController?.start()
                    }
                }
            }
        }
    }

    private fun observeAudioFormatPreference() {
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val wifiState = kotlinx.coroutines.flow.MutableStateFlow(isOnWifi(connectivityManager))

        val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) {
                val wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                if (wifi != wifiState.value) {
                    Log.d(TAG, "Network changed: ${if (wifi) "WiFi" else "Mobile"}")
                }
                wifiState.value = wifi
            }

            override fun onLost(network: android.net.Network) {
                val wifi = isOnWifi(connectivityManager)
                Log.d(TAG, "Network lost, fallback: ${if (wifi) "WiFi" else "Mobile/None"}")
                wifiState.value = wifi
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        // Apply format on user preference change only (not network change).
        // Network-based format (Smart mode) is applied at next stream start via Sendspin hello.
        // Wait for DataStore to load before collecting, so we don't send the default "SMART"
        // to the server on startup (overriding the user's saved preference).
        scope.launch {
            var lastFormat = settingsRepository.sendspinAudioFormat.first()
            settingsRepository.sendspinAudioFormat
                .distinctUntilChanged()
                .collect { formatName ->
                    if (formatName == lastFormat) { lastFormat = formatName; return@collect }
                    lastFormat = formatName
                    val playerId = settingsRepository.sendspinClientId.first() ?: return@collect
                    if (wsClient.connectionState.value !is ConnectionState.Connected) return@collect
                    val format = net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.fromStored(formatName)
                    val isWifi = wifiState.value
                    val apiValue = format.toApiValue(isWifi)
                    val netType = if (isWifi) "WiFi" else "Mobile"
                    Log.d(TAG, "Audio format preference changed: $format, network=$netType, sending $apiValue")
                    try {
                        playerRepository.savePlayerConfig(playerId, mapOf("preferred_sendspin_format" to apiValue))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update audio format: ${e.message}")
                    }
                }
        }
        // On network change, just log. Format will be applied at next Sendspin connect.
        scope.launch {
            wifiState.collect { isWifi ->
                val netType = if (isWifi) "WiFi" else "Mobile"
                val format = net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.fromStored(
                    settingsRepository.sendspinAudioFormat.first()
                )
                Log.d(TAG, "Network: $netType (format $format will apply at next connect)")
            }
        }
    }

    private fun isOnWifi(cm: android.net.ConnectivityManager): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun observeShortcutActions() {
        scope.launch {
            shortcutDispatcher.pendingAction
                .filterNotNull()
                .collect { action ->
                    if (action !is ShortcutAction.PlayNow) return@collect
                    shortcutDispatcher.consume()
                    Log.d(TAG, "PlayNow shortcut: sendspinActive=$sendspinActive")
                    val sendspinOn = settingsRepository.sendspinEnabled.first()
                    if (!sendspinOn) {
                        settingsRepository.setSendspinEnabled(true)
                    } else if (!sendspinActive) {
                        val connected = wsClient.connectionState.value is ConnectionState.Connected
                        if (connected) {
                            sendspinActive = true
                            sendspinController?.start()
                        }
                    }
                    val id = sendspinPlayerId ?: settingsRepository.sendspinClientId.first()
                    if (id != null) {
                        sendspinController?.handlePlay()
                    }
                }
        }
    }

    // region Connection state notification

    private fun createConnectionNotificationChannel() {
        val channel = NotificationChannel(
            CONN_CHANNEL_ID,
            "MassDroid Connection",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Shows MassDroid connection status"
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun updateConnectionNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val connState = wsClient.connectionState.value
        val text = when (connState) {
            is ConnectionState.Connected -> "Connected to Music Assistant"
            is ConnectionState.Connecting -> "Connecting..."
            is ConnectionState.Error -> "Connection error"
            is ConnectionState.Disconnected -> {
                manager.cancel(CONN_NOTIFICATION_ID)
                return
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CONN_CHANNEL_ID)
            .setContentTitle("MassDroid")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        manager.notify(CONN_NOTIFICATION_ID, notification)
    }

    // endregion

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
            logBleDevices("bg-receiver", backgroundDevices, config)
            val currentWifi = currentConnectedWifi()
            if (shouldHoldWifiOnlyRoom(config, currentWifi)) {
                Log.d(TAG, "Wi-Fi-only room hold (bg-receiver): keeping ${roomDetector.currentRoom.value?.roomName}")
                return
            }
            val rssiMap = buildDetectionAnchorSnapshot(backgroundDevices, config)
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

    private fun createProximityNotificationChannel() {
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
            proximityConfigStore.config.collect { config ->
                val shouldRun = shouldRunProximity(config)
                val structureChanged = config.enabled != wasEnabled || config.rooms.size != roomCount
                wasEnabled = config.enabled
                roomCount = config.rooms.size
                if (shouldRun && (structureChanged || proximityJob?.isActive != true)) {
                    startProximityEngine()
                } else if (!shouldRun) {
                    stopProximityEngine()
                } else if (!config.stopWhenNoRoomActive) {
                    cancelNoRoomStopTimer("disabled")
                } else if (roomDetector.currentRoom.value == null) {
                    scheduleNoRoomStopIfNeeded("enabled-while-no-room")
                }
            }
        }
    }

    private fun observeBluetoothState() {
        scope.launch {
            proximityScanner.observeBluetoothState()
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) return@collect

                    val config = proximityConfigStore.config.value
                    if (!config.enabled) return@collect

                    Log.d(TAG, "Bluetooth turned off: disabling Follow Me")
                    proximityConfigStore.update { it.copy(enabled = false) }
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

    private fun observeRoomActivity() {
        scope.launch {
            var previousRoomId: String? = null
            roomDetector.currentRoom.collect { room ->
                if (room != null) {
                    previousRoomId = room.roomId
                    cancelNoRoomStopTimer("room-active")
                } else {
                    if (previousRoomId != null) {
                        scheduleNoRoomStopIfNeeded("room-lost")
                    }
                    previousRoomId = null
                }
            }
        }
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
        stopProximityEngine()
        Log.d(TAG, "Starting proximity engine")

        // Skip full radio startup if outside schedule
        if (isWithinSchedule()) {
            motionGate.start()
            ensurePersistentScan(lowPower = true)
            startBackgroundScanForConfig(proximityConfigStore.config.value)
        }

        proximityJob = scope.launch {
            var scheduleSuspended = !isWithinSchedule()
            // Startup warmup: use short high-accuracy snapshots instead of 4s burst spacing.
            if (isWithinSchedule()) {
                runStartupWarmup()
                val config = proximityConfigStore.config.value
                val hasWifiOnlyRooms = config.rooms.any { room ->
                    room.stickToConnectedWifi &&
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

            // Main loop: screen-on = persistent snapshot, screen-off = PendingIntent background scan
            while (proximityConfigStore.config.value.enabled) {
                // Fully suspend proximity subsystem outside schedule
                if (!isWithinSchedule()) {
                    if (!scheduleSuspended) {
                        suspendProximityForSchedule()
                        scheduleSuspended = true
                    }
                    kotlinx.coroutines.delay(60_000)
                    continue
                }

                if (scheduleSuspended) {
                    resumeProximityAfterSchedule()
                    scheduleSuspended = false
                }

                if (isDeviceInDoze()) {
                    stopPersistentScanTracked()
                    proximityScanner.stopBackgroundScan()
                    motionGate.stop()
                    highAccuracyUntilMs = 0L
                    highAccuracyStartedAtMs = 0L
                    kotlinx.coroutines.delay(30_000)
                    if (!isDeviceInDoze() && isWithinSchedule()) {
                        motionGate.start()
                        startBackgroundScanForConfig(proximityConfigStore.config.value)
                        ensurePersistentScan(lowPower = true)
                    }
                    continue
                }

                val isMoving = motionGate.isMoving.value
                val highAccuracy = updateHighAccuracyWindow(isMoving) || proximityScanner.uiHighAccuracyRequested
                val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val screenOn = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
                val cooledDown = System.currentTimeMillis() - lastRoomSwitchMs >= COOLDOWN_AFTER_SWITCH_MS

                if (screenOn && cooledDown) {
                    ensurePersistentScan(lowPower = !highAccuracy)
                    if (proximityScanner.zeroDeviceStreak >= 5) {
                        Log.w(TAG, "Scanner recovery: ${proximityScanner.zeroDeviceStreak} zero-device reads")
                        stopPersistentScanTracked()
                        ensurePersistentScan(lowPower = !highAccuracy)
                        proximityScanner.zeroDeviceStreak = 0
                    }
                    burstScan(if (highAccuracy) "motion" else "screen")
                    val interval = if (highAccuracy) MOTION_SCAN_INTERVAL_MS else BURST_SCAN_INTERVAL_MS * 3
                    kotlinx.coroutines.delay(interval)
                } else if (highAccuracy && !screenOn && cooledDown) {
                    // Screen off + motion: keep the persistent scanner in high-accuracy mode
                    // and read quick snapshots instead of starting standalone scans.
                    val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "massdroid:proximity")
                    wl.acquire(15_000)
                    try {
                        ensurePersistentScan(lowPower = false)
                        val wifi = currentConnectedWifi()
                        val cfg = proximityConfigStore.config.value
                        for (burst in 1..2) {
                            val devices = readFastPathSnapshotWithWarmRetry(
                                logPrefix = "BLE fast-path",
                                detailPrefix = "motion $burst/2"
                            )
                            if (devices.isNotEmpty()) {
                                if (shouldHoldWifiOnlyRoom(cfg, wifi)) {
                                    Log.d(TAG, "Wi-Fi-only room hold (fast-path:motion-$burst): keeping ${roomDetector.currentRoom.value?.roomName}")
                                    break
                                }
                                Log.d(TAG, "BLE fast-path (motion $burst/2): ${devices.size} devices")
                                logBleDevices("fast-path:motion-$burst", devices, cfg)
                                val rssiMap = buildDetectionAnchorSnapshot(devices, cfg)
                                val hadCurrentRoomBeforeDetection = roomDetector.currentRoom.value != null
                                when (val result = roomDetector.detectDetailed(
                                    rssiMap,
                                    cfg,
                                    motionActive = true,
                                    wifi = wifi.toWifiMatchContext()
                                )) {
                                    is DetectResult.Confirmed -> {
                                        handleConfirmedRoom(result.room, cfg, hadCurrentRoomBeforeDetection)
                                        break
                                    }
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
                    kotlinx.coroutines.delay(MOTION_SCAN_INTERVAL_MS)
                } else {
                    ensurePersistentScan(lowPower = true)
                    kotlinx.coroutines.delay(2_000)
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
            val devices = readDetectionSnapshot(preferFresh = true)
            if (devices.isEmpty()) return@repeat
            val rssi = buildDetectionAnchorSnapshot(devices, proximityConfigStore.config.value)
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
        stopPersistentScanTracked()
        proximityScanner.stopBackgroundScan()
        motionGate.stop()
        roomDetector.reset()
        cancelNoRoomStopTimer("outside-schedule")
        pendingProximityTransfer = null
        pendingTransferSourcePlayerId = null
        highAccuracyUntilMs = 0L
        highAccuracyStartedAtMs = 0L
        lastMotionBoostMs = 0L
        getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
    }

    private suspend fun resumeProximityAfterSchedule() {
        Log.d(TAG, "Proximity schedule active: resuming Follow Me")
        suppressNextProximityRoomAction = true
        motionGate.start()
        startBackgroundScanForConfig(proximityConfigStore.config.value)
        ensurePersistentScan(lowPower = true)
        runStartupWarmup()
        syncSelectedPlayerToCurrentRoom("schedule-resume")
        Log.d(TAG, "Proximity warmup: ${roomDetector.currentRoom.value?.roomName ?: "no room"}")
    }

    private fun syncSelectedPlayerToCurrentRoom(reason: String) {
        val room = roomDetector.currentRoom.value ?: return
        if (shouldBlockProximitySelectionForBt()) {
            Log.d(TAG, "Proximity selected player skipped ($reason): BT A2DP sendspin active")
            return
        }
        if (!isPlayerAvailable(room.playerId)) return
        if (playerRepository.selectedPlayer.value?.playerId == room.playerId) return
        playerRepository.selectPlayer(room.playerId)
        Log.d(TAG, "Proximity selected player ($reason): ${room.playerName}")
    }

    private fun currentWifiOnlyRoomConfig(
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig
    ): net.asksakis.massdroidv2.data.proximity.RoomConfig? {
        val currentRoomId = roomDetector.currentRoom.value?.roomId ?: return null
        return config.rooms.find { it.id == currentRoomId && it.stickToConnectedWifi }
    }

    private fun shouldHoldWifiOnlyRoom(
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig,
        wifi: ProximityScanner.ConnectedWifiInfo?
    ): Boolean {
        val currentRoom = currentWifiOnlyRoomConfig(config) ?: return false
        val expectedBssid = currentRoom.connectedBssid
        val expectedSsid = currentRoom.connectedSsid

        if (!expectedBssid.isNullOrBlank() && wifi?.bssid != null && expectedBssid.equals(wifi.bssid, ignoreCase = true)) {
            lastConfirmedWifiRoomId = currentRoom.id
            lastConfirmedWifiBssid = expectedBssid
            lastConfirmedWifiSsid = expectedSsid
            lastConfirmedWifiAtMs = System.currentTimeMillis()
            return false
        }

        if (!expectedSsid.isNullOrBlank() && wifi?.ssid != null && expectedSsid.equals(wifi.ssid, ignoreCase = true)) {
            lastConfirmedWifiRoomId = currentRoom.id
            lastConfirmedWifiBssid = wifi.bssid
            lastConfirmedWifiSsid = expectedSsid
            lastConfirmedWifiAtMs = System.currentTimeMillis()
            return false
        }

        if (wifi != null) return false

        val now = System.currentTimeMillis()
        return lastConfirmedWifiRoomId == currentRoom.id &&
            (
                (!expectedBssid.isNullOrBlank() && lastConfirmedWifiBssid == expectedBssid) ||
                    (!expectedSsid.isNullOrBlank() && lastConfirmedWifiSsid == expectedSsid)
                ) &&
            now - lastConfirmedWifiAtMs <= WIFI_ROOM_GRACE_MS
    }

    private fun handleConfirmedRoom(
        detected: DetectedRoom,
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig,
        hadCurrentRoomBeforeDetection: Boolean
    ) {
        val roomConfig = config.rooms.find { it.id == detected.roomId }
        val currentWifi = currentConnectedWifi()
        if (roomConfig?.stickToConnectedWifi == true &&
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
        if (!hadCurrentRoomBeforeDetection && roomConfig?.stickToConnectedWifi == true) {
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
        if (persistentScanLowPower == lowPower) return
        if (persistentScanLowPower != null) {
            Log.d(
                TAG,
                "Persistent mode switch: ${if (persistentScanLowPower == true) "LOW_POWER" else "LOW_LATENCY"} -> ${if (lowPower) "LOW_POWER" else "LOW_LATENCY"}"
            )
        }
        stopPersistentScanTracked(clearBuffers = false)
        proximityScanner.startPersistentScan(lowPower = lowPower)
        persistentScanLowPower = lowPower
    }

    private fun stopPersistentScanTracked(clearBuffers: Boolean = true) {
        proximityScanner.stopPersistentScan(clearBuffers = clearBuffers)
        persistentScanLowPower = null
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
                    val devices = readFastPathSnapshotWithWarmRetry(
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
                        logBleDevices("motion-boost:$detailPrefix", devices, cfg)
                        val rssiMap = buildDetectionAnchorSnapshot(devices, cfg)
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
                readDetectionSnapshot(preferFresh = true)
            } else {
                proximityScanner.readSnapshot()
            }
            val currentWifi = currentConnectedWifi()
            if (shouldHoldWifiOnlyRoom(config, currentWifi)) {
                Log.d(TAG, "Wi-Fi-only room hold (burst:$trigger): keeping ${roomDetector.currentRoom.value?.roomName}")
                return
            }
            val rssiMap = buildDetectionAnchorSnapshot(devices, config)
            Log.d(TAG, "BLE snapshot ($trigger): ${devices.size} devices")
            logBleDevices("snapshot:$trigger", devices, config)

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
                    readFastPathSnapshotWithWarmRetry(
                        logPrefix = "Quick retry",
                        detailPrefix = reason
                    )
                } else {
                    readDetectionSnapshot(preferFresh = motionGate.isMoving.value)
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
                logBleDevices("quick-retry:$reason", devices, config)
                val currentWifi = currentConnectedWifi()
                if (shouldHoldWifiOnlyRoom(config, currentWifi)) {
                    Log.d(TAG, "Wi-Fi-only room hold (quick-retry:$reason): keeping ${roomDetector.currentRoom.value?.roomName}")
                    return@launch
                }

                val hadCurrentRoomBeforeDetection = roomDetector.currentRoom.value != null
                when (val result = roomDetector.detectDetailed(
                    buildDetectionAnchorSnapshot(devices, config),
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

    private suspend fun readFastPathSnapshotWithWarmRetry(
        logPrefix: String,
        detailPrefix: String
    ): List<net.asksakis.massdroidv2.data.proximity.ProximityScanner.ScannedDevice> {
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

        kotlinx.coroutines.delay(750)
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

    private fun readDetectionSnapshot(preferFresh: Boolean): List<net.asksakis.massdroidv2.data.proximity.ProximityScanner.ScannedDevice> {
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

    private fun preferredNameAnchors(
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig
    ): Set<String> = config.rooms
        .flatMap { room ->
            room.beaconProfiles
                .filter { it.anchorType == net.asksakis.massdroidv2.data.proximity.AnchorType.NAME }
                .map { it.anchorKey }
        }
        .toSet()

    private fun buildDetectionAnchorSnapshot(
        devices: Collection<ProximityScanner.ScannedDevice>,
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig
    ): Map<String, Int> = proximityScanner.buildAnchorSnapshot(
        devices,
        preferredNameAnchors(config)
    )

    private fun logBleDevices(
        trigger: String,
        devices: List<ProximityScanner.ScannedDevice>,
        config: net.asksakis.massdroidv2.data.proximity.ProximityConfig = proximityConfigStore.config.value
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

    private fun startBackgroundScanForConfig(config: net.asksakis.massdroidv2.data.proximity.ProximityConfig) {
        val hasNameAnchors = config.rooms.any { room ->
            room.beaconProfiles.any { it.anchorType == net.asksakis.massdroidv2.data.proximity.AnchorType.NAME }
        }
        val macAddresses = config.rooms
            .flatMap { room ->
                room.beaconProfiles
                    .filter { it.anchorType == net.asksakis.massdroidv2.data.proximity.AnchorType.MAC }
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

    private fun scheduleNoRoomStopIfNeeded(reason: String) {
        if (noRoomStopJob?.isActive == true) return
        val config = proximityConfigStore.config.value
        if (!shouldRunProximity(config) || !config.stopWhenNoRoomActive) return
        if (!isWithinSchedule()) return
        val player = playerRepository.selectedPlayer.value ?: return
        if (player.state != PlaybackState.PLAYING) return
        noRoomStopArmedPlayerId = player.playerId

        noRoomStopJob = scope.launch {
            Log.d(TAG, "No-room stop timer armed ($reason): ${player.displayName} in 10m")
            delay(NO_ROOM_STOP_TIMEOUT_MS)

            val latestConfig = proximityConfigStore.config.value
            val currentRoom = roomDetector.currentRoom.value
            val armedPlayerId = noRoomStopArmedPlayerId
            val armedPlayer = armedPlayerId?.let { id -> playerRepository.players.value.find { it.playerId == id } }
            if (!shouldRunProximity(latestConfig) || !latestConfig.stopWhenNoRoomActive) return@launch
            if (currentRoom != null) return@launch
            if (armedPlayer == null || armedPlayer.state != PlaybackState.PLAYING) return@launch

            try {
                playerRepository.pause(armedPlayer.playerId)
                Log.d(TAG, "No-room stop timer fired: paused ${armedPlayer.displayName}")
            } catch (e: Exception) {
                Log.w(TAG, "No-room stop failed: ${e.message}")
            }
        }
    }

    private fun cancelNoRoomStopTimer(reason: String) {
        if (noRoomStopJob?.isActive == true) {
            Log.d(TAG, "No-room stop timer canceled ($reason)")
        }
        noRoomStopJob?.cancel()
        noRoomStopJob = null
        noRoomStopArmedPlayerId = null
    }

    private fun handleRoomChange(detected: DetectedRoom, config: net.asksakis.massdroidv2.data.proximity.ProximityConfig) {
        proximityQuickRetryJob?.cancel()
        proximityQuickRetryJob = null
        cancelNoRoomStopTimer("room-confirmed")
        if (!isWithinSchedule()) return
        val roomConfig = config.rooms.find { it.id == detected.roomId }
        val playbackContext = resolveProximityPlaybackContext(detected.playerId)
        val sourcePlayerId = playbackContext.transferSourcePlayer?.playerId
        val targetIsPlaying = playbackContext.targetPlayer?.state == PlaybackState.PLAYING
        if (pendingProximityTransfer?.roomId == detected.roomId) return
        lastRoomSwitchMs = System.currentTimeMillis()
        Log.d(TAG, "Room confirmed: ${detected.roomName} -> ${detected.playerName}")
        if (suppressNextProximityRoomAction) {
            suppressNextProximityRoomAction = false
            if (
                !shouldBlockProximitySelectionForBt() &&
                isPlayerAvailable(detected.playerId) &&
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

        applyRoomVolume(detected)
        showRoomDetectedNotification(detected)

        if (targetIsPlaying) {
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
            performProximityTransfer(sourcePlayerId, detected)
        } else {
            showProximityNotification(
                room = detected,
                canTransfer = sourcePlayerId != null && sourcePlayerId != detected.playerId,
                sourcePlayerId = sourcePlayerId
            )
        }
    }

    private fun stopProximityEngine() {
        proximityJob?.cancel()
        proximityJob = null
        motionGate.stop()
        stopPersistentScanTracked()
        proximityScanner.stopBackgroundScan()
        roomDetector.reset()
        proximityQuickRetryJob?.cancel()
        proximityQuickRetryJob = null
        cancelNoRoomStopTimer("engine-stopped")
        pendingProximityTransfer = null
        pendingTransferSourcePlayerId = null
        lastRoomSwitchMs = 0
        highAccuracyUntilMs = 0L
        highAccuracyStartedAtMs = 0L
        lastMotionBoostMs = 0L
        lastProximityTransferCommand = null
        lastRoomPlaybackCommand = null
        suppressNextProximityRoomAction = false
        lastConfirmedWifiRoomId = null
        lastConfirmedWifiBssid = null
        lastConfirmedWifiSsid = null
        lastConfirmedWifiAtMs = 0L
        getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
    }

    private fun reevaluateProximityEngine(reason: String) {
        val config = proximityConfigStore.config.value
        if (shouldRunProximity(config)) {
            if (proximityJob?.isActive != true) {
                Log.d(TAG, "Reevaluating proximity engine ($reason): start")
                startProximityEngine()
            } else if (!config.stopWhenNoRoomActive) {
                cancelNoRoomStopTimer("reeval-disabled")
            } else if (roomDetector.currentRoom.value == null) {
                scheduleNoRoomStopIfNeeded("reeval-no-room")
            }
        } else {
            Log.d(TAG, "Reevaluating proximity engine ($reason): stop")
            stopProximityEngine()
        }
    }

    private fun showProximityNotification(room: DetectedRoom, canTransfer: Boolean, sourcePlayerId: String?) {
        pendingProximityTransfer = room
        pendingTransferSourcePlayerId = sourcePlayerId
        val transferIntent = Intent(PROXIMITY_TRANSFER_ACTION, null, this, PlaybackService::class.java)
        val transferPending = PendingIntent.getService(
            this, 0, transferIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playIntent = Intent(PROXIMITY_PLAY_ACTION, null, this, PlaybackService::class.java)
        val playPending = PendingIntent.getService(
            this, 1, playIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val actionLabel = if (canTransfer) "Move Here" else "Play Here"

        val notification = NotificationCompat.Builder(this, PROXIMITY_CHANNEL_ID)
            .setContentTitle("You're near ${room.roomName}")
            .setContentText("$actionLabel on ${room.playerName}?")
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(R.drawable.ic_notification, actionLabel, if (canTransfer) transferPending else playPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setTimeoutAfter(120_000)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(PROXIMITY_NOTIFICATION_ID, notification)
    }

    private fun showRoomDetectedNotification(room: DetectedRoom) {
        val notification = NotificationCompat.Builder(this, PROXIMITY_CHANNEL_ID)
            .setContentTitle("Now in ${room.roomName}")
            .setContentText("Follow Me detected ${room.roomName}")
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setTimeoutAfter(10_000)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(PROXIMITY_NOTIFICATION_ID, notification)
    }

    private fun performProximityTransfer(sourcePlayerId: String, room: DetectedRoom) {
        if (sourcePlayerId == room.playerId) return
        if (!isPlayerAvailable(room.playerId)) {
            Log.w(TAG, "Proximity transfer skipped: ${room.playerName} not available")
            return
        }
        if (shouldSkipRecentTransfer(sourcePlayerId, room)) {
            Log.d(TAG, "Proximity transfer skipped: duplicate recent move $sourcePlayerId -> ${room.playerId} (${room.roomName})")
            return
        }
        rememberTransferCommand(sourcePlayerId, room)
        scope.launch {
            try {
                musicRepository.transferQueue(sourcePlayerId, room.playerId)
                playerRepository.selectPlayer(room.playerId)
                applyRoomVolume(room)
                Log.d(TAG, "Proximity transfer: $sourcePlayerId -> ${room.playerId}")
            } catch (e: Exception) {
                Log.w(TAG, "Proximity transfer failed: ${e.message}")
            }
        }
    }

    private suspend fun performRoomPlayback(room: DetectedRoom, roomConfig: net.asksakis.massdroidv2.data.proximity.RoomConfig?) {
        if (!isPlayerAvailable(room.playerId)) {
            Log.w(TAG, "Proximity play skipped: ${room.playerName} not available")
            return
        }
        val playback = roomConfig?.playbackConfig
        val uri = playback?.playlistUri
        if (uri != null) {
            if (shouldSkipRecentRoomPlayback(room, uri)) {
                Log.d(TAG, "Proximity room playback skipped: duplicate recent command on ${room.playerName} for $uri")
                return
            }
            rememberRoomPlaybackCommand(room, uri)
            musicRepository.playMedia(room.playerId, uri, option = "replace")
            if (playback.shuffle) {
                try { musicRepository.shuffleQueue(room.playerId, true) } catch (_: Exception) { }
            }
            applyRoomVolume(room)
            Log.d(TAG, "Proximity: playing '${playback.playlistName}' on ${room.playerName} (shuffle=${playback.shuffle})")
        } else {
            Log.d(TAG, "Proximity play: no queue or playlist on ${room.playerName}")
            android.widget.Toast.makeText(this, "No queue on ${room.playerName}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyRoomVolume(room: DetectedRoom) {
        if (!isPlayerAvailable(room.playerId)) return
        val roomConfig = proximityConfigStore.config.value.rooms.find { it.id == room.roomId } ?: return
        val playback = roomConfig.playbackConfig
        if (!playback.volumeEnabled) return
        val volume = (playback.volumeLevel * 10).coerceIn(0, 100)
        sendVolumeCommand(room.playerId, volume)
        Log.d(TAG, "Proximity volume: ${room.playerName} -> $volume%")
    }

    private fun isPlayerAvailable(playerId: String): Boolean {
        return playerRepository.players.value.any { it.playerId == playerId && it.available }
    }

    private fun shouldSkipRecentTransfer(sourcePlayerId: String, room: DetectedRoom): Boolean {
        val recent = lastProximityTransferCommand ?: return false
        val now = System.currentTimeMillis()
        return now - recent.issuedAtMs <= PROXIMITY_COMMAND_DEDUPE_MS
            && recent.sourcePlayerId == sourcePlayerId
            && recent.targetPlayerId == room.playerId
            && recent.roomId == room.roomId
    }

    private fun rememberTransferCommand(sourcePlayerId: String, room: DetectedRoom) {
        lastProximityTransferCommand = RecentProximityTransferCommand(
            sourcePlayerId = sourcePlayerId,
            targetPlayerId = room.playerId,
            roomId = room.roomId,
            issuedAtMs = System.currentTimeMillis()
        )
    }

    private fun shouldSkipRecentRoomPlayback(room: DetectedRoom, playlistUri: String): Boolean {
        val recent = lastRoomPlaybackCommand ?: return false
        val now = System.currentTimeMillis()
        return now - recent.issuedAtMs <= PROXIMITY_COMMAND_DEDUPE_MS
            && recent.playerId == room.playerId
            && recent.roomId == room.roomId
            && recent.playlistUri == playlistUri
    }

    private fun rememberRoomPlaybackCommand(room: DetectedRoom, playlistUri: String) {
        lastRoomPlaybackCommand = RecentRoomPlaybackCommand(
            playerId = room.playerId,
            roomId = room.roomId,
            playlistUri = playlistUri,
            issuedAtMs = System.currentTimeMillis()
        )
    }

    private data class ProximityPlaybackContext(
        val targetPlayer: net.asksakis.massdroidv2.domain.model.Player?,
        val transferSourcePlayer: net.asksakis.massdroidv2.domain.model.Player?,
        val ambiguousTransferPlayers: List<net.asksakis.massdroidv2.domain.model.Player>
    )

    private data class RecentProximityTransferCommand(
        val sourcePlayerId: String,
        val targetPlayerId: String,
        val roomId: String,
        val issuedAtMs: Long
    )

    private data class RecentRoomPlaybackCommand(
        val playerId: String,
        val roomId: String,
        val playlistUri: String,
        val issuedAtMs: Long
    )

    private fun resolveProximityPlaybackContext(targetPlayerId: String): ProximityPlaybackContext {
        val players = playerRepository.players.value.filter { it.available }
        val targetPlayer = players.find { it.playerId == targetPlayerId }
        val selectedPlayer = playerRepository.selectedPlayer.value
        val selectedAvailablePlayer = selectedPlayer?.let { selected ->
            players.find { it.playerId == selected.playerId }
        }
        val playingPlayers = players.filter { it.state == PlaybackState.PLAYING }
        val transferCandidates = playingPlayers.filter { it.playerId != targetPlayerId }

        val resolvedTransferSource = when {
            transferCandidates.size == 1 -> transferCandidates.first()
            transferCandidates.isEmpty() &&
                selectedAvailablePlayer != null &&
                selectedAvailablePlayer.playerId != targetPlayerId -> selectedAvailablePlayer
            else -> null
        }

        return ProximityPlaybackContext(
            targetPlayer = targetPlayer,
            transferSourcePlayer = resolvedTransferSource,
            ambiguousTransferPlayers = if (resolvedTransferSource == null && transferCandidates.size > 1) {
                transferCandidates
            } else {
                emptyList()
            }
        )
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

    private fun activePlayerId(): String? {
        return playerRepository.selectedPlayer.value?.playerId
    }

    private fun sendVolumeCommand(playerId: String, volume: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                wsClient.sendCommand(
                    MaCommands.Players.CMD_VOLUME_SET,
                    VolumeSetArgs(playerId = playerId, volumeLevel = volume),
                    awaitResponse = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Volume command failed: $e")
            }
        }
    }

    private var btAudioCallback: android.media.AudioDeviceCallback? = null

    private fun registerBtAudioDeviceCallback() {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        btAudioCallback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                val btAdded = addedDevices.any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                val sendspinTarget = sendspinPlayerId
                if (btAdded && sendspinActive && sendspinTarget != null) {
                    val currentSelected = playerRepository.selectedPlayer.value?.playerId
                    if (currentSelected != sendspinTarget) {
                        val deviceName = addedDevices.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        }?.productName
                        Log.d(TAG, "BT A2DP connected ($deviceName), auto-selecting Sendspin player")
                        playerRepository.selectPlayer(sendspinTarget)
                    }
                }
            }
        }
        am.registerAudioDeviceCallback(btAudioCallback, null)
    }

    private fun isBtA2dpActive(): Boolean {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    private fun shouldRoutToSendspin(): Boolean =
        isBtA2dpActive() && sendspinActive && sendspinPlayerId != null
            && playerRepository.selectedPlayer.value?.playerId != sendspinPlayerId

    private fun shouldBlockProximitySelectionForBt(): Boolean =
        isBtA2dpActive() && sendspinActive && sendspinPlayerId != null

    private fun createRemotePlayer(): RemoteControlPlayer {
        return RemoteControlPlayer(
            Looper.getMainLooper(),
            onPlay = {
                val routeSendspin = shouldRoutToSendspin()
                if (routeSendspin) {
                    Log.d(TAG, "RemotePlayer onPlay -> BT A2DP active, routing to Sendspin")
                    sendspinController?.handlePlay()
                } else {
                    Log.d(TAG, "RemotePlayer onPlay -> selected player")
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.play(id) }
                }
            },
            onPause = {
                if (shouldRoutToSendspin()) {
                    sendspinController?.handlePause()
                } else {
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.pause(id) }
                }
            },
            onNext = {
                if (shouldRoutToSendspin()) {
                    sendspinController?.handleNext()
                } else {
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.next(id) }
                }
            },
            onPrevious = {
                if (shouldRoutToSendspin()) {
                    sendspinController?.handlePrev()
                } else {
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.previous(id) }
                }
            },
            onSeek = { positionMs ->
                if (shouldRoutToSendspin()) {
                    val id = sendspinPlayerId ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.seek(id, positionMs / 1000.0) }
                } else {
                    val id = activePlayerId() ?: return@RemoteControlPlayer
                    scope.launch { playerRepository.seek(id, positionMs / 1000.0) }
                }
            },
            onVolumeUp = {
                val player = playerRepository.selectedPlayer.value ?: return@RemoteControlPlayer
                val baseVol = if (System.currentTimeMillis() < volumeOverrideUntil) {
                    optimisticVolume ?: player.volumeLevel
                } else {
                    player.volumeLevel
                }
                val newVol = (baseVol + VOLUME_STEP).coerceAtMost(RemoteControlPlayer.MAX_VOLUME)
                optimisticVolume = newVol
                volumeOverrideUntil = System.currentTimeMillis() + VOLUME_OVERRIDE_MS
                remotePlayer?.updateVolume(newVol)
                sendVolumeCommand(player.playerId, newVol)
            },
            onVolumeDown = {
                val player = playerRepository.selectedPlayer.value ?: return@RemoteControlPlayer
                val baseVol = if (System.currentTimeMillis() < volumeOverrideUntil) {
                    optimisticVolume ?: player.volumeLevel
                } else {
                    player.volumeLevel
                }
                val newVol = (baseVol - VOLUME_STEP).coerceAtLeast(0)
                optimisticVolume = newVol
                volumeOverrideUntil = System.currentTimeMillis() + VOLUME_OVERRIDE_MS
                remotePlayer?.updateVolume(newVol)
                sendVolumeCommand(player.playerId, newVol)
            },
            onVolumeSet = { volume ->
                val player = playerRepository.selectedPlayer.value ?: return@RemoteControlPlayer
                val clamped = volume.coerceIn(0, RemoteControlPlayer.MAX_VOLUME)
                optimisticVolume = clamped
                volumeOverrideUntil = System.currentTimeMillis() + VOLUME_OVERRIDE_MS
                remotePlayer?.updateVolume(clamped)
                sendVolumeCommand(player.playerId, clamped)
            }
        )
    }

    private fun createMediaSession() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaLibrarySession = MediaLibrarySession.Builder(this, remotePlayer!!, libraryCallback)
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(SyncBitmapLoader())
            .build()
        Log.d(TAG, "MediaLibrarySession created")
    }

    /** Observe selected player state for media notification. */
    private fun observePlayerState() {
        scope.launch {
            combine(
                playerRepository.selectedPlayer,
                playerRepository.queueState,
                playerRepository.elapsedTime
                    .map { (it / 3).toLong() }
                    .distinctUntilChanged()
            ) { player, queue, _ ->
                player to queue
            }.collect { (player, queue) ->
                val elapsed = playerRepository.elapsedTime.value
                if (player == null) return@collect
                if (player.state != PlaybackState.PLAYING) {
                    cancelNoRoomStopTimer("playback-stopped")
                }

                val isSendspinPlayer = sendspinPlayerId != null &&
                    player.playerId == sendspinPlayerId

                val sendspinMeta = if (isSendspinPlayer && sendspinActive) sendspinController else null

                val currentTrack = queue?.currentItem?.track
                val title = sendspinMeta?.currentDisplayedTitle?.takeIf { it.isNotBlank() }
                    ?: currentTrack?.name
                    ?: player.currentMedia?.title
                    ?: ""
                val artist = sendspinMeta?.currentDisplayedArtist?.takeIf { it.isNotBlank() }
                    ?: currentTrack?.artistNames
                    ?: player.currentMedia?.artist
                    ?: ""
                val album = sendspinMeta?.currentDisplayedAlbum?.takeIf { it.isNotBlank() }
                    ?: currentTrack?.albumName
                    ?: player.currentMedia?.album
                    ?: ""
                val duration = sendspinMeta?.currentDisplayedDurationMs?.takeIf { it > 0 }
                    ?.div(1000.0)
                    ?: currentTrack?.duration
                    ?: queue?.currentItem?.duration
                    ?: player.currentMedia?.duration
                    ?: 0.0
                val imageUrl = sendspinMeta?.currentDisplayedArtUrl
                    ?: currentTrack?.imageUrl
                    ?: queue?.currentItem?.imageUrl
                    ?: player.currentMedia?.imageUrl

                if (cachedArtworkUrl == null && imageUrl != null) {
                    Log.d(TAG, "First artwork URL: $imageUrl (track=${currentTrack?.imageUrl != null}, qi=${queue?.currentItem?.imageUrl != null}, media=${player.currentMedia?.imageUrl != null})")
                }

                updateArtwork(imageUrl)

                val effectiveVolume = if (optimisticVolume != null) {
                    if (player.volumeLevel == optimisticVolume) {
                        optimisticVolume = null
                        volumeOverrideUntil = 0
                        player.volumeLevel
                    } else if (System.currentTimeMillis() > volumeOverrideUntil) {
                        optimisticVolume = null
                        player.volumeLevel
                    } else {
                        optimisticVolume!!
                    }
                } else {
                    player.volumeLevel
                }

                remotePlayer?.updateState(
                    isPlaying = sendspinMeta?.currentIsPlaying ?: (player.state == PlaybackState.PLAYING),
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = (duration * 1000).toLong(),
                    positionMs = sendspinMeta?.currentDisplayedPositionMs ?: (elapsed * 1000).toLong(),
                    artworkData = cachedArtworkData,
                    volumeLevel = effectiveVolume,
                    isMuted = player.volumeMuted,
                    isRemotePlayback = !isSendspinPlayer
                )
            }
        }
    }

    /** Fetch full queue items when queue changes (for car display queue list). */
    private fun observeQueueItems() {
        scope.launch {
            playerRepository.queueItemsChanged.collect { queueId ->
                fetchQueueItems(queueId)
            }
        }
    }

    private fun fetchQueueItems(queueId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val items = musicRepository.getQueueItems(queueId)
                val entries = items.map { qi ->
                    QueueEntry(
                        id = qi.queueItemId.toStableLongId(),
                        title = qi.track?.name ?: qi.name,
                        artist = qi.track?.artistNames ?: "",
                        album = qi.track?.albumName ?: "",
                        durationMs = ((qi.track?.duration ?: qi.duration) * 1000).toLong()
                    )
                }
                withContext(Dispatchers.Main) {
                    remotePlayer?.updateQueue(entries)
                }
                Log.d(TAG, "Queue updated: ${entries.size} items for $queueId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load queue items for $queueId", e)
            }
        }
    }

    private fun updateArtwork(imageUrl: String?) {
        if (imageUrl != null && imageUrl != cachedArtworkUrl) {
            Log.d(TAG, "Artwork URL changed: $imageUrl")
            cachedArtworkUrl = imageUrl
            cachedArtworkData = null
            scope.launch(Dispatchers.IO) { downloadArtwork(imageUrl) }
        } else if (imageUrl == null && cachedArtworkUrl != null) {
            Log.d(TAG, "Artwork URL cleared")
            cachedArtworkUrl = null
            cachedArtworkData = null
        }
    }

    private fun downloadArtwork(url: String) {
        try {
            val request = Request.Builder().url(url).build()
            val (code, contentType, rawBytes) = wsClient.getImageClient().newCall(request).execute().use { response ->
                Triple(response.code, response.header("Content-Type"), response.body?.bytes())
            }
            Log.d(TAG, "Artwork HTTP $code, type=$contentType, bytes=${rawBytes?.size ?: 0} for $url")
            if (url != cachedArtworkUrl) {
                Log.d(TAG, "Ignoring stale artwork response for $url (current=$cachedArtworkUrl)")
                return
            }
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val resized = resizeArtwork(rawBytes)
                if (url != cachedArtworkUrl) {
                    Log.d(TAG, "Ignoring stale resized artwork for $url (current=$cachedArtworkUrl)")
                    return
                }
                cachedArtworkData = resized
                Log.d(TAG, "Artwork decoded+resized: ${resized.size} bytes, setting on player")
                scope.launch {
                    if (url != cachedArtworkUrl) {
                        Log.d(TAG, "Skipping setArtwork for stale URL $url")
                        return@launch
                    }
                    remotePlayer?.setArtwork(resized)
                    Log.d(TAG, "Artwork setArtwork() called on Main thread")
                }
            } else {
                Log.w(TAG, "Artwork response empty for $url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Artwork download failed: $url", e)
        }
    }

    private fun resizeArtwork(rawBytes: ByteArray, maxSize: Int = 320): ByteArray {
        val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
        val scale = maxSize.toFloat() / maxOf(original.width, original.height)
        if (scale >= 1f) {
            original.recycle()
            return rawBytes
        }
        val scaled = Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt(),
            (original.height * scale).toInt(),
            true
        )
        original.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
        scaled.recycle()
        return out.toByteArray()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        btAudioCallback?.let {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.unregisterAudioDeviceCallback(it)
        }
        stopProximityEngine()
        try { unregisterReceiver(bleScanReceiver) } catch (_: Exception) { }
        sendspinController?.destroy()
        sendspinController = null
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(CONN_NOTIFICATION_ID)
        scope.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    // region Browse / Search callbacks

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            val playerCommands = Player.Commands.Builder().addAllCommands().build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        @OptIn(UnstableApi::class)
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            // Notification buttons -> normal flow (controls selected player)
            val isNotification = session.isMediaNotificationController(controllerInfo)
            if (isNotification) return false

            // BT/hardware buttons: route to sendspin when active, consume otherwise
            // (prevent accidental playback on remote players from BT auto-play)
            val ctrl = sendspinController ?: return true
            if (!sendspinActive) return true
            ctrl.sendspinPlayerId ?: return true

            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            } ?: return false
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return true

            val keyName = when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> "PLAY"
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "PAUSE"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY_PAUSE"
                KeyEvent.KEYCODE_HEADSETHOOK -> "HEADSETHOOK"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREVIOUS"
                else -> "UNKNOWN(${keyEvent.keyCode})"
            }

            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> ctrl.handlePlay()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> ctrl.handlePause()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                    ctrl.handlePlayPause()
                KeyEvent.KEYCODE_MEDIA_NEXT -> ctrl.handleNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ctrl.handlePrev()
                else -> return false
            }
            Log.d(TAG, "BT media button routed to sendspin: ${keyEvent.keyCode}")
            return true
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("MassDroid")
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val effectivePageSize = if (pageSize > 0) pageSize else PAGE_SIZE_DEFAULT
            return scope.future(Dispatchers.IO) {
                val items = try {
                    when (parentId) {
                        "root" -> buildRootCategories()
                        "recently_played" -> loadAlbums(page, effectivePageSize, "last_played")
                        "artists" -> loadArtists(page, effectivePageSize)
                        "albums" -> loadAlbums(page, effectivePageSize)
                        "playlists" -> loadPlaylists(page, effectivePageSize)
                        "tracks" -> loadTracks(page, effectivePageSize)
                        "genres" -> buildGenreList()
                        "genre_radio" -> buildGenreRadioList()
                        else -> when {
                            parentId.startsWith("genre|") -> loadGenreArtists(parentId.removePrefix("genre|"))
                            else -> loadSubItems(parentId, page, effectivePageSize)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onGetChildren($parentId) failed", e)
                    emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch(Dispatchers.IO) {
                try {
                    val result = musicRepository.search(query)
                    cachedSearchResults = result
                    val totalCount = result.artists.size + result.albums.size +
                        result.tracks.size + result.playlists.size
                    session.notifySearchResultChanged(browser, query, totalCount, params)
                } catch (e: Exception) {
                    Log.e(TAG, "onSearch($query) failed", e)
                }
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return scope.future(Dispatchers.IO) {
                val result = cachedSearchResults ?: try {
                    musicRepository.search(query)
                } catch (e: Exception) {
                    Log.e(TAG, "onGetSearchResult($query) failed", e)
                    SearchResult()
                }
                val allItems = result.artists.map { it.toBrowsableMediaItem() } +
                    result.albums.map { it.toBrowsableMediaItem() } +
                    result.tracks.map { it.toPlayableMediaItem() } +
                    result.playlists.map { it.toBrowsableMediaItem() }
                val effectivePageSize = if (pageSize > 0) pageSize else PAGE_SIZE_DEFAULT
                val paged = allItems.drop(page * effectivePageSize).take(effectivePageSize)
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
            }
        }

        @OptIn(UnstableApi::class)
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val queueId = activePlayerId()
                ?: playerRepository.queueState.value?.queueId
            if (queueId == null) {
                Log.w(TAG, "onAddMediaItems: no active queue")
                return Futures.immediateFuture(emptyList())
            }
            scope.launch(Dispatchers.IO) {
                for (item in mediaItems) {
                    val mediaId = item.mediaId
                    when {
                        mediaId == "smart_mix" -> {
                            Log.d(TAG, "onAddMediaItems: dispatching Smart Mix")
                            shortcutDispatcher.dispatch(ShortcutAction.SmartMix)
                        }
                        mediaId.startsWith("genre_radio|") -> {
                            val genre = mediaId.removePrefix("genre_radio|")
                            Log.d(TAG, "onAddMediaItems: dispatching Genre Radio '$genre'")
                            shortcutDispatcher.dispatch(ShortcutAction.GenreRadio(genre))
                        }
                        else -> {
                            val uri = item.requestMetadata.mediaUri?.toString()
                                ?: item.mediaId.takeIf { it.contains("/") }
                            val resolvedUri = uri ?: resolveMediaIdToUri(mediaId)
                            if (resolvedUri != null) {
                                try {
                                    Log.d(TAG, "onAddMediaItems: playing $resolvedUri (mediaId=$mediaId)")
                                    playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                                    musicRepository.playMedia(queueId, resolvedUri, option = "replace")
                                } catch (e: Exception) {
                                    Log.e(TAG, "playMedia failed for $resolvedUri", e)
                                }
                            } else {
                                Log.w(TAG, "onAddMediaItems: could not resolve mediaId=$mediaId")
                            }
                        }
                    }
                }
            }
            return Futures.immediateFuture(mediaItems)
        }
    }

    // endregion

    private fun resolveMediaIdToUri(mediaId: String): String? {
        // Format: "type|provider|itemId" -> "provider://type/itemId"
        val parts = mediaId.split("|")
        if (parts.size != 3) return null
        val (type, provider, itemId) = parts
        return "$provider://$type/$itemId"
    }

    // region Content tree builders

    private fun buildRootCategories(): List<MediaItem> = listOf(
        browseFolder("recently_played", "Recently Played", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browseFolder("artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        browseFolder("albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browseFolder("playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        browseFolder("tracks", "Tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browseFolder("genres", "Genres", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        playableItem("smart_mix", "Smart Mix", MediaMetadata.MEDIA_TYPE_PLAYLIST),
        browseFolder("genre_radio", "Genre Radio", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
    )

    private suspend fun buildGenreList(): List<MediaItem> {
        val genres = genreRepository.libraryGenres()
        return genres.map { genre ->
            browseFolder("genre|$genre", genre.replaceFirstChar { it.uppercase() }, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
        }
    }

    private suspend fun loadGenreArtists(genre: String): List<MediaItem> {
        val artists = genreRepository.libraryArtistsForGenre(genre)
        return artists.map { a ->
            browseFolder("artist|${a.provider}|${a.itemId}", a.name, MediaMetadata.MEDIA_TYPE_ARTIST)
        }
    }

    private suspend fun buildGenreRadioList(): List<MediaItem> {
        val genres = genreRepository.topGenres(days = 60, limit = 20)
        return genres.map { genreScore ->
            val genre = genreScore.genre.replaceFirstChar { it.uppercase() }
            playableItem("genre_radio|${genreScore.genre}", genre, MediaMetadata.MEDIA_TYPE_PLAYLIST)
        }
    }

    private fun playableItem(mediaId: String, title: String, mediaType: Int): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()
    }

    private fun browseFolder(mediaId: String, title: String, mediaType: Int): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()
    }

    private suspend fun loadArtists(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getArtists(
            limit = pageSize,
            offset = page * pageSize,
            orderBy = "name"
        ).map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadAlbums(
        page: Int,
        pageSize: Int,
        orderBy: String = "name"
    ): List<MediaItem> {
        return musicRepository.getAlbums(
            limit = pageSize,
            offset = page * pageSize,
            orderBy = orderBy
        ).map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadPlaylists(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getPlaylists(
            limit = pageSize,
            offset = page * pageSize
        ).map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadTracks(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getTracks(
            limit = pageSize,
            offset = page * pageSize,
            orderBy = "last_played"
        ).map { it.toPlayableMediaItem() }
    }

    private suspend fun loadSubItems(
        parentId: String,
        page: Int,
        pageSize: Int
    ): List<MediaItem> {
        val parts = parentId.split("|")
        if (parts.size != 3) return emptyList()
        val (type, provider, itemId) = parts
        return when (type) {
            "artist" -> musicRepository.getArtistAlbums(itemId, provider)
                .map { it.toBrowsableMediaItem() }
            "album" -> musicRepository.getAlbumTracks(itemId, provider)
                .map { it.toPlayableMediaItem() }
            "playlist" -> musicRepository.getPlaylistTracks(itemId, provider)
                .map { it.toPlayableMediaItem() }
            else -> emptyList()
        }
    }

    // endregion

    // region Domain -> MediaItem converters

    private fun Artist.toBrowsableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("artist|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                .build()
        )
        .build()

    private fun Album.toBrowsableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("album|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artistNames.ifEmpty { null })
                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .build()
        )
        .build()

    private fun Track.toPlayableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("track|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artistNames.ifEmpty { null })
                .setAlbumTitle(albumName.ifEmpty { null })
                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .build()
        )
        .build()

    private fun Playlist.toBrowsableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("playlist|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .build()
        )
        .build()

    // endregion
}

/**
 * Synchronous BitmapLoader that decodes artwork immediately.
 * Avoids the async decode race in Media3's default CacheBitmapLoader
 * which uses reference equality on cloned byte arrays (cache always misses).
 */
@OptIn(UnstableApi::class)
private class SyncBitmapLoader : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bitmap != null) Futures.immediateFuture(bitmap)
            else Futures.immediateFailedFuture(IllegalArgumentException("Cannot decode bitmap"))
        } catch (e: Exception) {
            Futures.immediateFailedFuture(e)
        }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return Futures.immediateFailedFuture(UnsupportedOperationException("URI loading not supported"))
    }
}

data class QueueEntry(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

private fun String.toStableLongId(): Long {
    var hash = 1125899906842597L
    for (char in this) {
        hash = 31L * hash + char.code.toLong()
    }
    return hash
}

@OptIn(UnstableApi::class)
class RemoteControlPlayer(
    looper: Looper,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onVolumeUp: () -> Unit = {},
    private val onVolumeDown: () -> Unit = {},
    private val onVolumeSet: (Int) -> Unit = {}
) : SimpleBasePlayer(looper) {

    private var _isPlaying = false
    private var _title = ""
    private var _artist = ""
    private var _album = ""
    private var _durationMs = 0L
    private var _positionMs = 0L
    private var _artworkData: ByteArray? = null
    private var _queueEntries: List<QueueEntry> = emptyList()
    private var _volumeLevel = 0
    private var _isMuted = false
    private var _isRemotePlayback = true

    val currentTitle: String get() = _title
    val currentArtist: String get() = _artist
    val currentAlbum: String get() = _album
    val currentDurationMs: Long get() = _durationMs
    val currentPositionMs: Long get() = _positionMs

    fun updateState(
        isPlaying: Boolean,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        positionMs: Long,
        artworkData: ByteArray? = null,
        volumeLevel: Int = _volumeLevel,
        isMuted: Boolean = _isMuted,
        isRemotePlayback: Boolean = _isRemotePlayback
    ) {
        _isPlaying = isPlaying
        _title = title
        _artist = artist
        _album = album
        _durationMs = durationMs
        _positionMs = positionMs
        if (artworkData != null) _artworkData = artworkData
        _volumeLevel = volumeLevel
        _isMuted = isMuted
        _isRemotePlayback = isRemotePlayback
        invalidateState()
    }

    fun updateVolume(volume: Int) {
        _volumeLevel = volume
        invalidateState()
    }

    fun setArtwork(data: ByteArray) {
        _artworkData = data
        invalidateState()
    }

    fun updateQueue(entries: List<QueueEntry>) {
        _queueEntries = entries
        invalidateState()
    }

    override fun getState(): State {
        val currentMetadataBuilder = MediaMetadata.Builder()
            .setTitle(_title)
            .setArtist(_artist)
            .setAlbumTitle(_album)
        _artworkData?.let {
            currentMetadataBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val currentMetadata = currentMetadataBuilder.build()

        val playlist = if (_queueEntries.isNotEmpty()) {
            ImmutableList.copyOf(_queueEntries.mapIndexed { index, entry ->
                val meta = if (index == 0) currentMetadata else {
                    MediaMetadata.Builder()
                        .setTitle(entry.title)
                        .setArtist(entry.artist.ifEmpty { null })
                        .setAlbumTitle(entry.album.ifEmpty { null })
                        .build()
                }
                val durMs = if (index == 0 && _durationMs > 0) _durationMs else entry.durationMs
                val item = MediaItem.Builder().setMediaMetadata(meta).build()
                MediaItemData.Builder(entry.id)
                    .setMediaItem(item)
                    .setMediaMetadata(meta)
                    .setDurationUs(if (durMs > 0) durMs * 1000 else C_TIME_UNSET)
                    .build()
            })
        } else if (_title.isNotEmpty()) {
            val item = MediaItem.Builder().setMediaMetadata(currentMetadata).build()
            val single = MediaItemData.Builder(item.hashCode().toLong())
                .setMediaItem(item)
                .setMediaMetadata(currentMetadata)
                .setDurationUs(if (_durationMs > 0) _durationMs * 1000 else C_TIME_UNSET)
                .build()
            ImmutableList.of(single)
        } else {
            ImmutableList.of()
        }

        val playbackType = if (_isRemotePlayback) {
            DeviceInfo.PLAYBACK_TYPE_REMOTE
        } else {
            DeviceInfo.PLAYBACK_TYPE_LOCAL
        }

        val commandsBuilder = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_GET_METADATA,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_SET_MEDIA_ITEM
            )
        if (_isRemotePlayback) {
            commandsBuilder.addAll(
                COMMAND_GET_DEVICE_VOLUME,
                COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
            )
        }

        return State.Builder()
            .setAvailableCommands(commandsBuilder.build())
            .setPlayWhenReady(_isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (_title.isNotEmpty()) STATE_READY else STATE_IDLE)
            .setContentPositionMs(if (_positionMs > 0) _positionMs else 0)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .setDeviceInfo(DeviceInfo.Builder(playbackType).setMinVolume(0).setMaxVolume(20).build())
            .setDeviceVolume(_volumeLevel / VOLUME_SCALE)
            .setIsDeviceMuted(_isMuted)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) onPlay() else onPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onNext()
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onPrevious()
            else -> onSeek(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        onVolumeUp()
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        onVolumeDown()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(volume: Int, flags: Int): ListenableFuture<*> {
        val maVolume = (volume * VOLUME_SCALE).coerceIn(0, MAX_VOLUME)
        onVolumeSet(maVolume)
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val C_TIME_UNSET = Long.MIN_VALUE + 1
        internal const val VOLUME_SCALE = 5
        internal const val MAX_VOLUME = 100
    }
}
