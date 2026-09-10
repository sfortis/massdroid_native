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
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.proximity.MotionGate
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
import net.asksakis.massdroidv2.data.proximity.ProximityScanner
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.data.sendspin.CarAudioPresence
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
        /** From the "Follow Me stopped" notification: a tap is a user interaction, so foreground is allowed. */
        const val RESUME_ACTION = "net.asksakis.massdroidv2.FOLLOW_ME_RESUME"
        private const val RESUME_NOTIFICATION_ID = 8

        /**
         * Start (or nudge) the service; it self-gates on config.
         *
         * A refused start must never propagate. The only caller is
         * [PlaybackService.onCreate], and that service can be created from a BACKGROUND
         * context (a MediaBrowser bind from Android Auto / Bixby / a Samsung routine, or
         * right after an install). On Android 12+ [Context.startService] then throws
         * BackgroundServiceStartNotAllowedException, and because that escaped onCreate it
         * took the whole process down with "Unable to create service PlaybackService" -
         * the app would not launch at all, repeatedly. Both refusal types
         * (Background/ForegroundServiceStartNotAllowedException) are
         * IllegalStateException subclasses. Skipping the nudge is safe: the service is
         * started again from a foreground entry point, and it is sticky once running.
         */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, FollowMeService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Start refused (no foreground context): ${e.javaClass.simpleName}")
            }
        }

        /**
         * Start from a context the platform exempts from the background restriction (a
         * BOOT_COMPLETED or MY_PACKAGE_REPLACED broadcast, see [FollowMeStartReceiver]). This
         * must be `startForegroundService`: a plain `startService` from the background is
         * refused regardless of the exemption. The caller has checked that Follow Me is
         * enabled, so `onStartCommand` goes foreground at once.
         */
        fun startFromExemption(context: Context, reason: String) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FollowMeService::class.java))
                Log.i(TAG, "Started after $reason")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Start after $reason refused: ${e.javaClass.simpleName}")
            }
        }
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var proximityConfigStore: ProximityConfigStore
    @Inject lateinit var proximityScanner: ProximityScanner
    @Inject lateinit var roomDetector: RoomDetector
    @Inject lateinit var motionGate: MotionGate
    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var carAudioPresence: CarAudioPresence
    @Inject lateinit var wsClient: MaWebSocketClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var proximityController: ProximityController? = null
    private var isForeground = false
    private var configJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        carAudioPresence.start()
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
            carAudioConnected = carAudioPresence.connected,
        ).also { it.start() }

        // Getting out of the car is the moment the hold above stops applying,
        // and by then the room has usually already been confirmed - so nothing
        // would revisit it. Audio stopping flowing is that moment: it covers a
        // Bluetooth disconnect, a pause, and a route change back to the phone
        // alike, without this service having to track any of them.
        scope.launch {
            sendspinManager.audioResourcesActive.collect { flowing ->
                if (!flowing) proximityController?.reapplyConfirmedRoom("bt-audio-released")
            }
        }

        // Hold the foreground notification only while Follow Me is enabled; tear down when disabled.
        // Gate on load() first: config starts as the disabled-by-default value and is populated
        // asynchronously from disk. On a START_STICKY background restart there is no Activity to load
        // it early, so acting on the default enabled=false here would call stopSelf() and the service
        // would never come back (sticky restart does not fire again after an explicit stop). Awaiting
        // load() makes the first collected value authoritative and closes that suicide race.
        configJob = scope.launch {
            proximityConfigStore.load()
            proximityConfigStore.config
                .map { it.enabled }
                .distinctUntilChanged()
                .collect { enabled -> if (enabled) enterForeground() else stopFollowMe() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If launched while already enabled, make sure we are foreground promptly. A resume tap
        // goes foreground without waiting for the config: the notification only exists while
        // Follow Me is enabled, and a fresh process has not loaded the config yet.
        if (intent?.action == RESUME_ACTION || proximityConfigStore.config.value.enabled) enterForeground()
        proximityController?.handleStartCommand(intent)
        return START_STICKY
    }

    /**
     * Go foreground, or stop if the platform refuses.
     *
     * A START_STICKY restart after a process kill (the user swiping the task away is the
     * common one, an install is another) recreates this service from the BACKGROUND, and
     * on Android 12+ `startForeground` then throws ForegroundServiceStartNotAllowedException.
     * Uncaught, that took the process down, the sticky restart brought it straight back into
     * the same refusal, and the phone showed "MassDroid keeps stopping" (2026-09-07, two
     * crashes three seconds apart). The exception is an IllegalStateException subclass, so
     * the catch also covers older platforms' variants. Without foreground status this service
     * cannot hold its BLE scans, so it stops itself; the next foreground entry point
     * (MainActivity, PlaybackService) starts it again, and an explicit stop ends the sticky loop.
     */
    private fun enterForeground() {
        if (isForeground) return
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
            isForeground = true
            getSystemService(NotificationManager::class.java)?.cancel(RESUME_NOTIFICATION_ID)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException and its API 31+ parent both derive from this.
            refuseForeground("started from the background", e)
        } catch (e: SecurityException) {
            // API 34+: the connectedDevice type needs a granted Bluetooth permission at the moment of
            // the call; a user who revokes Nearby devices later would otherwise crash the app here.
            refuseForeground("permission missing", e)
        }
    }

    private fun refuseForeground(reason: String, e: Exception) {
        Log.w(TAG, "Foreground refused ($reason); stopping until resumed", e)
        isForeground = false
        // Silence here left room detection off without anyone knowing. A plain notification
        // is allowed from the background, and tapping it is a user interaction, which is one
        // of the platform's exemptions: the service may then go foreground again.
        getSystemService(NotificationManager::class.java)?.notify(RESUME_NOTIFICATION_ID, buildResumeNotification())
        stopSelf()
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
    private fun shouldBlockProximitySelectionForBt(): Boolean =
        net.asksakis.massdroidv2.data.sendspin.bluetoothHoldsRoomSwitch(
            routedDeviceType = sendspinManager.getRoutedDeviceType(),
            audioFlowing = sendspinManager.audioResourcesActive.value,
        )

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

    private fun buildResumeNotification(): android.app.Notification {
        val resume = PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, FollowMeService::class.java).setAction(RESUME_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Follow Me stopped")
            .setContentText("Tap to resume room detection")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(resume)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
