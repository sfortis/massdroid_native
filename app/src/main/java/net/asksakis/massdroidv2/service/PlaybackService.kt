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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.VolumeSetArgs
import net.asksakis.massdroidv2.data.websocket.sendCommand
import net.asksakis.massdroidv2.data.proximity.DetectedRoom
import net.asksakis.massdroidv2.data.proximity.MotionGate
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomDetector
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
        private const val PROXIMITY_PLAY_ACTION = "net.asksakis.massdroidv2.PROXIMITY_PLAY"
        private const val BURST_SCAN_INTERVAL_MS = 4_000L
        private const val COOLDOWN_AFTER_SWITCH_MS = 15_000L
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var sendspinManager: SendspinManager
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
    private var proximityJob: Job? = null
    private var pendingProximityTransfer: DetectedRoom? = null
    private var pendingTransferSourcePlayerId: String? = null
    private var lastRoomSwitchMs = 0L

    override fun onCreate() {
        super.onCreate()
        createConnectionNotificationChannel()
        remotePlayer = createRemotePlayer()
        createMediaSession()
        loadSendspinPlayerId()
        observePlayerState()
        observeQueueItems()
        createSendspinController()
        observeSendspinEnabled()
        observeConnectionState()
        observeShortcutActions()
        registerBtAudioDeviceCallback()
        createProximityNotificationChannel()
        registerReceiver(bleScanReceiver, android.content.IntentFilter(ProximityScanner.BLE_SCAN_ACTION), RECEIVER_NOT_EXPORTED)
        observeProximityConfig()
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
                pendingProximityTransfer = null
                pendingTransferSourcePlayerId = null
                getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
                scope.launch {
                    try {
                        playerRepository.selectPlayer(room.playerId)
                        // Check if player has queue items; if empty, start Smart Mix
                        val queueItems = try { musicRepository.getQueueItems(room.playerId, limit = 1) } catch (_: Exception) { emptyList() }
                        if (queueItems.isNotEmpty()) {
                            playerRepository.play(room.playerId)
                            Log.d(TAG, "Proximity play on: ${room.playerName}")
                        } else {
                            Log.d(TAG, "Proximity play: empty queue, dispatching Smart Mix on ${room.playerName}")
                            shortcutDispatcher.dispatch(ShortcutAction.SmartMix)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Proximity play failed: ${e.message}")
                    }
                }
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
            musicRepository = musicRepository,
            wsClient = wsClient,
            onMetadataChanged = { _ -> },
            onStateChanged = { _, _, _ ->
                updateConnectionNotification()
            }
        )
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
            if (!config.enabled) return
            if (System.currentTimeMillis() - lastRoomSwitchMs < COOLDOWN_AFTER_SWITCH_MS) return
            val rssiMap = results.associate { it.device.address to it.rssi }
            val detected = roomDetector.detect(rssiMap, config)
            if (detected != null) {
                Log.d(TAG, "Background room change: ${detected.roomName}")
                handleRoomChange(detected, config)
            }
        }
    }

    // region Proximity Playback

    private fun createProximityNotificationChannel() {
        val channel = NotificationChannel(
            PROXIMITY_CHANNEL_ID, "Proximity Playback", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Room detection and playback transfer"; setShowBadge(false) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun hasBleScanPermission(): Boolean {
        val granted = { perm: String -> checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            granted(android.Manifest.permission.BLUETOOTH_SCAN) && granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun observeProximityConfig() {
        scope.launch {
            proximityConfigStore.load()
            var wasEnabled = false
            var roomCount = 0
            proximityConfigStore.config.collect { config ->
                val shouldRun = config.enabled && config.rooms.isNotEmpty() && hasBleScanPermission()
                val structureChanged = config.enabled != wasEnabled || config.rooms.size != roomCount
                wasEnabled = config.enabled
                roomCount = config.rooms.size
                if (shouldRun && (structureChanged || proximityJob?.isActive != true)) {
                    startProximityEngine()
                } else if (!shouldRun) {
                    stopProximityEngine()
                }
            }
        }
    }

    /**
     * Motion-gated proximity engine:
     * 1. MotionGate (sensor hub) detects movement -> opens 30s window
     * 2. During window: BLE burst scans every 4s with wake lock
     * 3. RoomDetector classifies using anchored beacons + confidence
     * 4. On confirmed room change: notification or auto-transfer
     * 5. No selectPlayer until user action (notification tap)
     */
    private fun startProximityEngine() {
        stopProximityEngine()
        Log.d(TAG, "Starting proximity engine")

        motionGate.start()
        proximityScanner.startPersistentScan(lowPower = false)

        // PendingIntent background scan as fallback for screen-off
        val beaconAddresses = proximityConfigStore.config.value.rooms
            .flatMap { r -> r.beacons.map { it.address } }.toSet()
        if (beaconAddresses.isNotEmpty()) {
            proximityScanner.startBackgroundScan(beaconAddresses)
        }

        proximityJob = scope.launch {
            // Warmup: 2 snapshots to reach confidence
            for (i in 1..2) {
                kotlinx.coroutines.delay(BURST_SCAN_INTERVAL_MS)
                val devices = proximityScanner.readSnapshot()
                roomDetector.detect(devices.associate { it.address to it.rssi }, proximityConfigStore.config.value)
            }
            Log.d(TAG, "Proximity warmup: ${roomDetector.currentRoom.value?.roomName ?: "no room"}")

            // Main loop: screen-on = persistent snapshot, screen-off = PendingIntent background scan
            while (proximityConfigStore.config.value.enabled) {
                val isMoving = motionGate.isMoving.value
                val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val screenOn = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
                val cooledDown = System.currentTimeMillis() - lastRoomSwitchMs >= COOLDOWN_AFTER_SWITCH_MS

                if (screenOn && cooledDown) {
                    // Screen on: ensure persistent scan running, read snapshot
                    proximityScanner.startPersistentScan(lowPower = false)
                    burstScan("screen")
                    kotlinx.coroutines.delay(BURST_SCAN_INTERVAL_MS * 3)
                } else if (isMoving && !screenOn && cooledDown) {
                    // Screen off + motion: pause persistent scan, do exclusive scanOnce
                    // PendingIntent background scan continues independently (OS-managed)
                    val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "massdroid:proximity")
                    wl.acquire(10_000)
                    try {
                        proximityScanner.stopPersistentScan()
                        val devices = proximityScanner.scanOnce(lowPower = false)
                        if (devices.isNotEmpty()) {
                            Log.d(TAG, "BLE fast-path (motion): ${devices.size} devices")
                            val cfg = proximityConfigStore.config.value
                            val detected = roomDetector.detect(devices.associate { it.address to it.rssi }, cfg)
                            if (detected != null) handleRoomChange(detected, cfg)
                        } else {
                            Log.d(TAG, "BLE fast-path: 0 devices (BLE radio blocked)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "BLE fast-path failed: ${e.message}")
                    } finally {
                        if (wl.isHeld) wl.release()
                    }
                    kotlinx.coroutines.delay(BURST_SCAN_INTERVAL_MS)
                } else {
                    kotlinx.coroutines.delay(2_000)
                }
            }
        }
    }

    /** Read BLE snapshot from persistent scan and detect room */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun burstScan(trigger: String) {
        val config = proximityConfigStore.config.value
        if (!config.enabled) return

        try {
            val devices = proximityScanner.readSnapshot()
            Log.d(TAG, "BLE snapshot ($trigger): ${devices.size} devices")

            val detected = roomDetector.detect(devices.associate { it.address to it.rssi }, config)

            if (detected != null) handleRoomChange(detected, config)
        } catch (e: Exception) {
            Log.w(TAG, "Burst scan failed: ${e.message}")
        }
    }

    private fun handleRoomChange(detected: DetectedRoom, config: net.asksakis.massdroidv2.data.proximity.ProximityConfig) {
        val previousPlayer = playerRepository.selectedPlayer.value
        val sourcePlayerId = previousPlayer?.playerId
        val wasPlaying = previousPlayer?.state == PlaybackState.PLAYING
        // Already on this player and playing - nothing to do
        if (sourcePlayerId == detected.playerId && wasPlaying) return
        // Already notified for this room (no duplicate notifications)
        if (pendingProximityTransfer?.roomId == detected.roomId) return
        lastRoomSwitchMs = System.currentTimeMillis()
        Log.d(TAG, "Room confirmed: ${detected.roomName} -> ${detected.playerName}")

        if (config.autoTransfer && wasPlaying && sourcePlayerId != null && sourcePlayerId != detected.playerId) {
            performProximityTransfer(sourcePlayerId, detected)
        } else {
            showProximityNotification(detected, wasPlaying, sourcePlayerId)
        }
    }

    private fun stopProximityEngine() {
        proximityJob?.cancel()
        proximityJob = null
        motionGate.stop()
        proximityScanner.stopPersistentScan()
        proximityScanner.stopBackgroundScan()
        roomDetector.reset()
        pendingProximityTransfer = null
        pendingTransferSourcePlayerId = null
        lastRoomSwitchMs = 0
        getSystemService(NotificationManager::class.java)?.cancel(PROXIMITY_NOTIFICATION_ID)
    }

    private fun showProximityNotification(room: DetectedRoom, wasPlaying: Boolean, sourcePlayerId: String?) {
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
        val canTransfer = wasPlaying && sourcePlayerId != null && sourcePlayerId != room.playerId
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

    private fun performProximityTransfer(sourcePlayerId: String, room: DetectedRoom) {
        if (sourcePlayerId == room.playerId) return
        scope.launch {
            try {
                musicRepository.transferQueue(sourcePlayerId, room.playerId)
                playerRepository.selectPlayer(room.playerId)
                Log.d(TAG, "Proximity transfer: $sourcePlayerId -> ${room.playerId}")
            } catch (e: Exception) {
                Log.w(TAG, "Proximity transfer failed: ${e.message}")
            }
        }
    }

    // endregion

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
                if (btAdded && sendspinActive && sendspinPlayerId != null) {
                    val currentSelected = playerRepository.selectedPlayer.value?.playerId
                    if (currentSelected != sendspinPlayerId) {
                        val deviceName = addedDevices.firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        }?.productName
                        Log.d(TAG, "BT A2DP connected ($deviceName), auto-selecting Sendspin player")
                        Log.d(TAG, "BT A2DP connected ($deviceName), auto-selecting Sendspin player")
                        playerRepository.selectPlayer(sendspinPlayerId!!)
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

                val isSendspinPlayer = sendspinPlayerId != null &&
                    player.playerId == sendspinPlayerId

                val currentTrack = queue?.currentItem?.track
                val title = currentTrack?.name ?: player.currentMedia?.title ?: ""
                val artist = currentTrack?.artistNames ?: player.currentMedia?.artist ?: ""
                val album = currentTrack?.albumName ?: player.currentMedia?.album ?: ""
                val duration = currentTrack?.duration ?: queue?.currentItem?.duration
                    ?: player.currentMedia?.duration ?: 0.0
                val imageUrl = currentTrack?.imageUrl
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
                    isPlaying = player.state == PlaybackState.PLAYING,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = (duration * 1000).toLong(),
                    positionMs = (elapsed * 1000).toLong(),
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
    private var _isRemotePlayback = false

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
