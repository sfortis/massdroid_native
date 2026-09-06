package net.asksakis.massdroidv2.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.util.Log
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SendspinVolumeCoordinator
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher
import net.asksakis.massdroidv2.playback.SendspinCoordinator
import net.asksakis.massdroidv2.playback.SendspinMetadata
import net.asksakis.massdroidv2.tv.ui.TvMainActivity
import javax.inject.Inject

/**
 * Foreground service that makes the Shield itself a Sendspin player: it drives
 * the shared :core [SendspinCoordinator] (which owns the Sendspin engine + Oboe
 * output) WITHOUT the phone-only Android Auto / Follow Me wiring.
 *
 * It also publishes a [MediaSessionCompat] with live metadata + playback state
 * and a MediaStyle notification, so the Android TV home and system controls show
 * a "now playing" surface for this device, like any other music player. Transport
 * commands from that surface route back into the Sendspin controller.
 */
@AndroidEntryPoint
class TvPlaybackService : Service() {

    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var sendspinVolumeCoordinator: SendspinVolumeCoordinator
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var wsClient: MaWebSocketClient
    @Inject lateinit var shortcutDispatcher: ShortcutActionDispatcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var coordinator: SendspinCoordinator
    private lateinit var mediaSession: MediaSessionCompat

    private var lastMetadata: SendspinMetadata? = null
    private var isPlaying = false

    private val mediaCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { coordinator.controller?.handlePlay() }
        override fun onPause() { coordinator.controller?.handlePause() }
        override fun onSkipToNext() { coordinator.controller?.handleNext() }
        override fun onSkipToPrevious() { coordinator.controller?.handlePrev() }
        override fun onSeekTo(pos: Long) { coordinator.controller?.handleSeek(pos) }
        override fun onStop() { coordinator.controller?.handlePause() }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Not active yet. An active session with no metadata is picked up by Google's
        // cross-device media controls and shown on every phone on the account as a
        // "MassDroid TV / SHIELD Android TV" card with a dead play button, while the
        // TV is on another app entirely. The session goes active when a stream does.
        mediaSession = MediaSessionCompat(this, "MassDroidTv").apply {
            setCallback(mediaCallback)
            isActive = false
        }
        startForegroundCompat()
        coordinator = SendspinCoordinator(
            context = this,
            scope = scope,
            sendspinManager = sendspinManager,
            settingsRepository = settingsRepository,
            playerRepository = playerRepository,
            wsClient = wsClient,
            volumeCoordinator = sendspinVolumeCoordinator,
            shortcutDispatcher = shortcutDispatcher,
            clientName = "MassDroid TV",
            onConnectionStateChanged = {},
            onTargetChanged = {},
            onActive = { mainHandler.post { onStreamActive() } },
            onInactive = { mainHandler.post { onStreamInactive() } },
            onWifiConnected = {},
            onMetadata = { meta -> mainHandler.post { onMetadata(meta) } },
            onPlayingChanged = { playing -> mainHandler.post { onPlayingChanged(playing) } },
            onStreamEnded = { mainHandler.post { clearSession("stream ended") } },
        )
        coordinator.start()
        defaultPlayerIcon()
    }

    private fun onMetadata(meta: SendspinMetadata) {
        lastMetadata = meta
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, meta.durationMs)
            .apply {
                meta.art?.let {
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                }
            }
            .build()
        mediaSession.setMetadata(md)
        // A real track arrived: that is what makes this session worth showing, whether the
        // stream is playing or paused. Paused-before-metadata must not leave it inactive.
        if (hasTrack()) mediaSession.isActive = true
        updateNotification()
    }

    private fun onPlayingChanged(playing: Boolean) {
        isPlaying = playing
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val pb = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                state,
                lastMetadata?.positionMs ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f
            )
            .build()
        mediaSession.setPlaybackState(pb)
        // Paused with a real track is still a session worth showing (the remote card can
        // resume it); paused with nothing loaded is not.
        mediaSession.isActive = playing || hasTrack()
        updateNotification()
    }

    private fun hasTrack(): Boolean = lastMetadata?.title?.isNotBlank() == true

    private fun onStreamActive() {
        // Deliberately nothing. This fires when the Sendspin CLIENT connects, which happens
        // a few seconds after boot with no track loaded; activating here recreated the dead
        // remote card. The session goes active from onMetadata/onPlayingChanged instead.
    }

    /** Sendspin disabled or the coordinator destroyed. */
    private fun onStreamInactive() = clearSession("sendspin inactive")

    /**
     * Nothing is playing any more: the stream ended for good (server stopped, player
     * deselected, device ungrouped, connection lost), or Sendspin went away. Drop the track
     * and the session with it, so nothing on the network keeps offering controls for a
     * speaker that is not playing anything. Tying this to the coordinator's onInactive alone
     * left the last title in place after a real stream end, and hasTrack() kept the session
     * active on it. A pause or a track change never reaches here (see onStreamEnded).
     */
    private fun clearSession(reason: String) {
        Log.d(TAG, "Clearing media session: $reason")
        isPlaying = false
        lastMetadata = null
        mediaSession.setMetadata(null)
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build()
        )
        mediaSession.isActive = false
        updateNotification()
    }

    /**
     * Default this device's MA player icon to a television glyph once it has
     * registered, so the Shield shows as a TV (not a generic speaker) in Music
     * Assistant. MA persists it as the per-player CONF_ENTRY_PLAYER_ICON.
     */
    private fun defaultPlayerIcon() {
        scope.launch {
            repeat(ICON_WAIT_TICKS) {
                val playerId = coordinator.playerId
                if (playerId != null) {
                    runCatching { playerRepository.savePlayerConfig(playerId, mapOf("icon" to "mdi-television")) }
                    return@launch
                }
                delay(1_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        coordinator.destroy()
        mediaSession.isActive = false
        mediaSession.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val meta = lastMetadata
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TvMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(meta?.title?.takeIf { it.isNotBlank() } ?: "MassDroid TV")
            .setContentText(meta?.artist?.takeIf { it.isNotBlank() } ?: "Ready as a synced speaker")
            .setLargeIcon(meta?.art)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
            )
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "TvPlayback"
        private const val CHANNEL_ID = "tv_playback"
        private const val NOTIFICATION_ID = 1
        private const val ICON_WAIT_TICKS = 30

        fun start(context: android.content.Context) {
            val intent = Intent(context, TvPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
