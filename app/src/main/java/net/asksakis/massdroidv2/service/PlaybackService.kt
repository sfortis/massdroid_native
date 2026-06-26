package net.asksakis.massdroidv2.service

import net.asksakis.massdroidv2.playback.SleepTimerBridge
import net.asksakis.massdroidv2.playback.SleepTimerManager
import net.asksakis.massdroidv2.playback.SendspinCoordinator

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
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.ui.MainActivity
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "PlaybackSvc"
        private const val CONN_CHANNEL_ID = "massdroid_connection"
        private const val CONN_NOTIFICATION_ID = 3
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var sendspinVolumeCoordinator: net.asksakis.massdroidv2.data.sendspin.SendspinVolumeCoordinator
    @Inject lateinit var sleepTimerBridge: SleepTimerBridge
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var wsClient: MaWebSocketClient
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var shortcutDispatcher: ShortcutActionDispatcher
    @Inject lateinit var playHistoryRepository: net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
    @Inject lateinit var genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var sendspinCoordinator: SendspinCoordinator
    private lateinit var androidAutoController: AndroidAutoController
    private lateinit var androidAutoBrowseController: AndroidAutoBrowseController
    private lateinit var sleepTimerManager: SleepTimerManager
    private val sleepTimerOriginalVolumes = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        AaMetrics.start()
        createConnectionNotificationChannel()
        createSendspinCoordinator()
        createAndroidAutoBrowseController()
        createAndroidAutoController()
        sendspinCoordinator.start()
        createSleepTimer()
        // Follow Me (room detection) now lives in its own FollowMeService with a connectedDevice
        // foreground service, so scanning survives without media. Bootstrap it here at launch; it
        // self-gates on config.enabled (goes foreground only while Follow Me is on, else stops).
        FollowMeService.start(this)
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
            onPlaybackStopped = { /* Follow Me's no-room-stop timer self-guards on playback state (now in FollowMeService) */ },
            trackedBrowsePaths = { androidAutoBrowseController.trackedParentIds() },
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
            isConnected = { wsClient.connectionState.value is ConnectionState.Connected },
        )
    }

    private fun createSendspinCoordinator() {
        sendspinCoordinator = SendspinCoordinator(
            context = this,
            scope = scope,
            sendspinManager = sendspinManager,
            settingsRepository = settingsRepository,
            playerRepository = playerRepository,
            wsClient = wsClient,
            volumeCoordinator = sendspinVolumeCoordinator,
            shortcutDispatcher = shortcutDispatcher,
            isAutomotive = net.asksakis.massdroidv2.BuildConfig.IS_AUTOMOTIVE,
            onConnectionStateChanged = { updateConnectionNotification() },
            onTargetChanged = { reason -> androidAutoController.onSendspinTargetChanged(reason) },
            onActive = { reason -> androidAutoController.onSendspinActive(reason) },
            onInactive = { reason -> androidAutoController.onSendspinInactive(reason) },
            onWifiConnected = { /* Follow Me has its own Wi-Fi monitor in ProximityScanner (now in FollowMeService) */ },
        )
    }

    private fun createSleepTimer() {
        sleepTimerManager = SleepTimerManager(
            context = this,
            scope = scope,
            bridge = sleepTimerBridge,
            notificationIcon = R.drawable.ic_notification,
            onFadeFraction = { fraction, targetPlayerId ->
                // Snapshot the target player's original volume on the
                // first fade tick, restore it when fraction returns to
                // 1.0 (cancel) or after stop completes. Scope strictly
                // to the player the user chose — sibling speakers and
                // group members the user didn't pick keep playing.
                if (fraction < 1f && sleepTimerOriginalVolumes.isEmpty()) {
                    val target = playerRepository.players.value
                        .firstOrNull { it.playerId == targetPlayerId }
                    if (target != null) {
                        sleepTimerOriginalVolumes[target.playerId] = target.volumeLevel
                    }
                }
                val originalVol = sleepTimerOriginalVolumes[targetPlayerId] ?: return@SleepTimerManager
                val targetVol = (originalVol * fraction).toInt()
                if (targetPlayerId == sendspinCoordinator.playerId) {
                    // Sendspin path: route through the coordinator so the
                    // single-source-of-truth sync logic applies (no direct
                    // STREAM_MUSIC writes; MA push only).
                    sendspinVolumeCoordinator.onSleepTimerFade(targetPlayerId, targetVol)
                } else {
                    scope.launch {
                        try { playerRepository.setVolume(targetPlayerId, targetVol) } catch (_: Exception) {}
                    }
                }
                if (fraction >= 1f) sleepTimerOriginalVolumes.clear()
            },
            onStop = { targetPlayerId ->
                // Pause only the target player. If it's a Sendspin
                // player, also tell the controller to release any
                // local audio focus state.
                try { playerRepository.pause(targetPlayerId) } catch (_: Exception) {}
                if (targetPlayerId == sendspinCoordinator.playerId && sendspinCoordinator.isActive) {
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
        try { unregisterReceiver(sleepTimerCancelReceiver) } catch (_: Exception) { }
        sendspinCoordinator.destroy()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(CONN_NOTIFICATION_ID)
        androidAutoController.stop()
        androidAutoBrowseController.clearTrackedParentIds()
        scope.cancel()
        AaMetrics.stop()
        super.onDestroy()
    }

}
