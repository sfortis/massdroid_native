package net.asksakis.massdroidv2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.auto.AaMetrics
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.VolumeSetArgs
import net.asksakis.massdroidv2.data.websocket.sendCommand
import net.asksakis.massdroidv2.data.proximity.MotionGate
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.ui.MainActivity
import net.asksakis.massdroidv2.ui.ShortcutActionDispatcher
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "PlaybackSvc"
        private const val CONN_CHANNEL_ID = "massdroid_connection"
        private const val CONN_NOTIFICATION_ID = 3
        const val PROXIMITY_PLAY_ACTION = "net.asksakis.massdroidv2.PROXIMITY_PLAY"
        const val PROXIMITY_REEVALUATE_ACTION = "net.asksakis.massdroidv2.PROXIMITY_REEVALUATE"
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var localSpeakerVolumeBridge: net.asksakis.massdroidv2.data.sendspin.LocalSpeakerVolumeBridge
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var sendspinCoordinator: SendspinCoordinator
    private lateinit var androidAutoController: AndroidAutoController
    private lateinit var androidAutoBrowseController: AndroidAutoBrowseController
    private lateinit var proximityController: ProximityController
    private lateinit var sleepTimerManager: SleepTimerManager
    private val sleepTimerOriginalVolumes = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        AaMetrics.start()
        createConnectionNotificationChannel()
        createSendspinCoordinator()
        createAndroidAutoBrowseController()
        createAndroidAutoController()
        createProximityController()
        sendspinCoordinator.start()
        createSleepTimer()
        proximityController.start()
    }

    private fun createAndroidAutoController() {
        androidAutoController = AndroidAutoController(
            service = this,
            scope = scope,
            playerRepository = playerRepository,
            musicRepository = musicRepository,
            wsClient = wsClient,
            sendspinManager = sendspinManager,
            sendspinPlayerId = { sendspinCoordinator.playerId },
            isSendspinActive = { sendspinCoordinator.isActive },
            sendspinController = { sendspinCoordinator.controller },
            shouldRouteToSendspin = { sendspinCoordinator.shouldRouteToSendspin() },
            activePlayerId = { activePlayerId() },
            sendVolumeCommand = { playerId, volume -> sendVolumeCommand(playerId, volume) },
            onPlaybackStopped = { reason -> proximityController.cancelNoRoomStopTimer(reason) },
        )
        androidAutoController.start(androidAutoBrowseController.callback)
    }

    private fun createAndroidAutoBrowseController() {
        androidAutoBrowseController = AndroidAutoBrowseController(
            context = this,
            scope = scope,
            musicRepository = musicRepository,
            playerRepository = playerRepository,
            genreRepository = genreRepository,
            shortcutDispatcher = shortcutDispatcher,
            activeQueueId = { activePlayerId() ?: playerRepository.queueState.value?.queueId },
            isSendspinActive = { sendspinCoordinator.isActive },
            sendspinController = { sendspinCoordinator.controller },
            onCustomCommand = { action -> androidAutoController.handleCustomCommand(action) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        proximityController.handleStartCommand(intent)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createSendspinCoordinator() {
        sendspinCoordinator = SendspinCoordinator(
            context = this,
            scope = scope,
            sendspinManager = sendspinManager,
            settingsRepository = settingsRepository,
            playerRepository = playerRepository,
            wsClient = wsClient,
            localVolumeBridge = localSpeakerVolumeBridge,
            shortcutDispatcher = shortcutDispatcher,
            onConnectionStateChanged = { updateConnectionNotification() },
            onTargetChanged = { reason -> androidAutoController.onSendspinTargetChanged(reason) },
            onActive = { reason -> androidAutoController.onSendspinActive(reason) },
            onInactive = { reason -> androidAutoController.onSendspinInactive(reason) },
            onWifiConnected = { reason -> proximityController.resetAwayMode(reason) },
        )
    }

    private fun createProximityController() {
        proximityController = ProximityController(
            service = this,
            scope = scope,
            playerRepository = playerRepository,
            musicRepository = musicRepository,
            proximityConfigStore = proximityConfigStore,
            proximityScanner = proximityScanner,
            roomDetector = roomDetector,
            motionGate = motionGate,
            shouldBlockProximitySelectionForBt = { sendspinCoordinator.shouldBlockProximitySelectionForBt() },
            sendVolumeCommand = { playerId, volume -> sendVolumeCommand(playerId, volume) },
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
                            || p.playerId == sendspinCoordinator.playerId) {
                            sleepTimerOriginalVolumes[p.playerId] = p.volumeLevel
                        }
                    }
                }
                val originals = sleepTimerOriginalVolumes
                for ((id, origVol) in originals) {
                    val targetVol = (origVol * fraction).toInt()
                    if (id == sendspinCoordinator.playerId) {
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
                if (sendspinCoordinator.isActive) {
                    sendspinCoordinator.controller?.handlePause()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return androidAutoController.getSession()
    }

    override fun onDestroy() {
        proximityController.stop()
        try { unregisterReceiver(sleepTimerCancelReceiver) } catch (_: Exception) { }
        sendspinCoordinator.destroy()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(CONN_NOTIFICATION_ID)
        androidAutoController.stop()
        scope.cancel()
        AaMetrics.stop()
        super.onDestroy()
    }

}
