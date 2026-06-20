package net.asksakis.massdroidv2.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.proximity.MotionGate
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.VolumeSetArgs
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.ui.MainActivity
import javax.inject.Inject

/**
 * Dedicated foreground service for Follow Me (room detection).
 *
 * Why this exists: the proximity scanner used to be hosted inside [PlaybackService], a Media3
 * `MediaLibraryService` whose foreground service is owned by Media3 and only stays up (as a
 * mediaPlayback type) while something is playing. With no media the OS reclaimed the whole process
 * and scanning silently died. This service holds its OWN `connectedDevice` foreground service for as
 * long as Follow Me is enabled, keeping the process alive and scanning independent of playback.
 *
 * It hosts [ProximityController] unchanged; the two small dependencies the controller used to take
 * from PlaybackService are reconstructed here from singletons:
 *  - shouldBlockProximitySelectionForBt: BT A2DP output present AND a Sendspin session is up
 *    (SendspinManager.connectionState != DISCONNECTED). This avoids touching the PlaybackService-local
 *    SendspinCoordinator.
 *  - sendVolumeCommand: the same volume RPC, issued through the shared WebSocket client.
 *
 * Lifecycle is self-gating: started (plain startService, from a foreground context) at app launch and
 * when Follow Me is toggled on; it goes foreground only while config.enabled, and stops itself when
 * disabled. START_STICKY so it is restored after a process kill while still enabled.
 */
@AndroidEntryPoint
class FollowMeService : Service() {

    companion object {
        private const val TAG = "FollowMeSvc"
        private const val CHANNEL_ID = "massdroid_followme_active"
        private const val NOTIFICATION_ID = 7
        const val PROXIMITY_REEVALUATE_ACTION = "net.asksakis.massdroidv2.PROXIMITY_REEVALUATE"
        const val PROXIMITY_PLAY_ACTION = "net.asksakis.massdroidv2.PROXIMITY_PLAY"

        /** Start (or nudge) the service. Safe to call from any foreground context; self-gates on config. */
        fun start(context: Context) {
            context.startService(Intent(context, FollowMeService::class.java))
        }
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var proximityConfigStore: ProximityConfigStore
    @Inject lateinit var proximityScanner: ProximityScanner
    @Inject lateinit var roomDetector: RoomDetector
    @Inject lateinit var motionGate: MotionGate
    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var wsClient: MaWebSocketClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var proximityController: ProximityController? = null
    private var isForeground = false
    private var configJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        proximityController = ProximityController(
            service = this,
            scope = scope,
            playerRepository = playerRepository,
            musicRepository = musicRepository,
            proximityConfigStore = proximityConfigStore,
            proximityScanner = proximityScanner,
            roomDetector = roomDetector,
            motionGate = motionGate,
            shouldBlockProximitySelectionForBt = { shouldBlockProximitySelectionForBt() },
            sendVolumeCommand = { playerId, volume -> sendVolumeCommand(playerId, volume) },
        ).also { it.start() }

        // Hold the foreground notification only while Follow Me is enabled; tear down when disabled.
        configJob = scope.launch {
            proximityConfigStore.config
                .map { it.enabled }
                .distinctUntilChanged()
                .collect { enabled -> if (enabled) enterForeground() else stopFollowMe() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If launched while already enabled, make sure we are foreground promptly.
        if (proximityConfigStore.config.value.enabled) enterForeground()
        proximityController?.handleStartCommand(intent)
        return START_STICKY
    }

    private fun enterForeground() {
        if (isForeground) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        isForeground = true
    }

    private fun stopFollowMe() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        configJob?.cancel()
        proximityController?.stop()
        proximityController = null
        scope.cancel()
        super.onDestroy()
    }

    /** Reconstructed from singletons so we do not depend on the PlaybackService-local SendspinCoordinator. */
    private fun shouldBlockProximitySelectionForBt(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val btA2dpActive = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        return btA2dpActive && sendspinManager.connectionState.value != SendspinState.DISCONNECTED
    }

    private fun sendVolumeCommand(playerId: String, volume: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                wsClient.sendCommand(
                    MaCommands.Players.CMD_VOLUME_SET,
                    VolumeSetArgs(playerId = playerId, volumeLevel = volume).toJson(),
                    awaitResponse = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Volume command failed: $e")
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Follow Me")
            .setContentText("Detecting your room")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Follow Me active", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Follow Me is scanning for your room"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
