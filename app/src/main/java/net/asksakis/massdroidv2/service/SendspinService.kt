package net.asksakis.massdroidv2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.KeyEvent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.ui.MainActivity
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class SendspinService : Service() {

    companion object {
        private const val TAG = "SendspinService"
        private const val PAUSE_DEBOUNCE_MS = 400L
        private const val SESSION_POSITION_STEP_MS = 1000L
        private const val RECONNECT_STORM_WINDOW_MS = 45_000L
        private const val RECONNECT_STORM_THRESHOLD = 3
        private const val RECONNECT_COOLDOWN_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        const val ACTION_START = "net.asksakis.massdroidv2.SENDSPIN_START"
        const val ACTION_STOP = "net.asksakis.massdroidv2.SENDSPIN_STOP"
        private const val ACTION_PLAY_PAUSE = "net.asksakis.massdroidv2.SENDSPIN_PLAY_PAUSE"
        private const val ACTION_NEXT = "net.asksakis.massdroidv2.SENDSPIN_NEXT"
        private const val ACTION_PREV = "net.asksakis.massdroidv2.SENDSPIN_PREV"
        private const val CHANNEL_ID = "sendspin_channel"
        private const val NOTIFICATION_ID = 2
    }

    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var musicRepository: net.asksakis.massdroidv2.domain.repository.MusicRepository
    @Inject lateinit var wsClient: MaWebSocketClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Audio focus
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var hasAudioFocus = false

    // Noisy audio receiver (headset unplug)
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy (headset unplugged), pausing")
                val id = sendspinPlayerId ?: return
                // Immediately stop local audio (before server roundtrip)
                currentIsPlaying = false
                sendspinManager.pauseAudio()
                optimisticUntil = System.currentTimeMillis() + 1000
                updateMediaSession()
                updateNotification()
                scope.launch { playerRepository.pause(id) }
            }
        }
    }

    private var currentArt: Bitmap? = null
    private var currentArtUrl: String? = null
    private var isStreaming = false
    private var isSendspinReady = false
    private var wasPlayingBeforeDisconnect = false
    private var lastSendspinReportedPlaying = false
    private var resumePositionSeconds = 0.0
    private var resumeTrackUri: String? = null
    private var currentTrackUri: String? = null
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    private var currentIsPlaying = false
    private var optimisticUntil = 0L
    private var sendspinPlayerId: String? = null
    private val collectorJobs = mutableListOf<Job>()
    private var lastPlayingAtMs = 0L
    private var lastSessionPbState = Int.MIN_VALUE
    private var lastSessionPositionBucket = Long.MIN_VALUE
    private var lastSessionTitle = ""
    private var lastSessionArtist = ""
    private var lastSessionAlbum = ""
    private var lastSessionDurationMs = -1L
    private var lastSessionArtUrl: String? = null
    private val reconnectDropTimestampsMs = ArrayDeque<Long>()
    private var reconnectCooldownUntilMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        setupAudioFocus()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSendspin()
            ACTION_STOP -> stopSendspin()
            ACTION_PLAY_PAUSE -> {
                val id = sendspinPlayerId ?: return START_STICKY
                val wantPlay = !currentIsPlaying
                if (wantPlay) {
                    currentIsPlaying = true
                    lastPlayingAtMs = System.currentTimeMillis()
                    if (!hasAudioFocus) requestAudioFocus()
                    if (isSendspinReady) sendspinManager.resumeAudio()
                } else {
                    currentIsPlaying = false
                    sendspinManager.pauseAudio()
                }
                optimisticUntil = System.currentTimeMillis() + 1000
                updateMediaSession()
                scope.launch {
                    if (wantPlay && !isSendspinReady) ensureSendspinConnected()
                    playerRepository.playPause(id)
                }
            }
            ACTION_NEXT -> {
                val id = sendspinPlayerId ?: return START_STICKY
                scope.launch { playerRepository.next(id) }
            }
            ACTION_PREV -> {
                val id = sendspinPlayerId ?: return START_STICKY
                scope.launch { playerRepository.previous(id) }
            }
        }
        return START_STICKY
    }

    private fun setupAudioFocus() {
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttrs)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus gained")
                        hasAudioFocus = true
                        if (isStreaming) {
                            sendspinManager.resumeAudio()
                            sendspinManager.setVolume(100)
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.d(TAG, "Audio focus lost permanently")
                        hasAudioFocus = false
                        if (isStreaming) {
                            val id = sendspinPlayerId
                            if (id != null) {
                                scope.launch { playerRepository.pause(id) }
                            }
                        }
                        sendspinManager.pauseAudio()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "Audio focus lost transiently")
                        hasAudioFocus = false
                        if (isStreaming) {
                            sendspinManager.pauseAudio()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d(TAG, "Audio focus: ducking")
                        sendspinManager.setVolume(30)
                    }
                }
            }
            .build()
    }

    private fun requestAudioFocus(): Boolean {
        val result = audioManager.requestAudioFocus(focusRequest)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Audio focus request: ${if (hasAudioFocus) "granted" else "denied"}")
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasAudioFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
    }

    private fun hasBluetoothAudioOutput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

    private fun autoSelectSendspinIfBluetooth() {
        if (hasBluetoothAudioOutput()) {
            val id = sendspinPlayerId ?: return
            val currentSelectedId = playerRepository.selectedPlayer.value?.playerId
            if (currentSelectedId != id) {
                Log.d(TAG, "BT AVRCP command with BT audio output, selecting sendspin player")
                playerRepository.selectPlayer(id)
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MassDroidSpeaker").apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    }
                    val keyCode = keyEvent?.keyCode
                    val action = when (keyEvent?.action) {
                        KeyEvent.ACTION_DOWN -> "DOWN"
                        KeyEvent.ACTION_UP -> "UP"
                        else -> "?"
                    }
                    val keyName = when (keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY_PAUSE"
                        KeyEvent.KEYCODE_MEDIA_PLAY -> "PLAY"
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> "PAUSE"
                        KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREVIOUS"
                        KeyEvent.KEYCODE_MEDIA_STOP -> "STOP"
                        KeyEvent.KEYCODE_HEADSETHOOK -> "HEADSETHOOK"
                        else -> "keyCode=$keyCode"
                    }
                    Log.d(TAG, "BT mediaButton: $keyName $action, currentIsPlaying=$currentIsPlaying, pbState=${if (currentIsPlaying) "PLAYING" else if (isSendspinReady) "PAUSED" else "BUFFERING"}")
                    autoSelectSendspinIfBluetooth()
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
                override fun onStop() {
                    Log.d(TAG, "MediaSession onStop callback")
                    stopSendspin()
                }
                override fun onPlay() {
                    Log.d(TAG, "MediaSession onPlay")
                    val id = sendspinPlayerId ?: return
                    currentIsPlaying = true
                    lastPlayingAtMs = System.currentTimeMillis()
                    optimisticUntil = System.currentTimeMillis() + 1000
                    updateMediaSession()
                    if (!hasAudioFocus) requestAudioFocus()
                    if (isSendspinReady) sendspinManager.resumeAudio()
                    scope.launch {
                        if (!isSendspinReady) ensureSendspinConnected()
                        playerRepository.play(id)
                    }
                }
                override fun onPause() {
                    Log.d(TAG, "MediaSession onPause")
                    val id = sendspinPlayerId ?: return
                    currentIsPlaying = false
                    optimisticUntil = System.currentTimeMillis() + 1000
                    updateMediaSession()
                    sendspinManager.pauseAudio()
                    scope.launch { playerRepository.pause(id) }
                }
                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSession onSkipToNext")
                    val id = sendspinPlayerId ?: return
                    scope.launch {
                        try { playerRepository.next(id) }
                        catch (e: Exception) { Log.w(TAG, "MediaSession next failed: ${e.message}") }
                    }
                }
                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSession onSkipToPrevious")
                    val id = sendspinPlayerId ?: return
                    scope.launch {
                        try { playerRepository.previous(id) }
                        catch (e: Exception) { Log.w(TAG, "MediaSession previous failed: ${e.message}") }
                    }
                }
                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "MediaSession onSeekTo $pos")
                    val id = sendspinPlayerId ?: return
                    scope.launch {
                        try { playerRepository.seek(id, pos / 1000.0) }
                        catch (e: Exception) { Log.w(TAG, "MediaSession seek failed: ${e.message}") }
                    }
                }
            })
            isActive = true
        }
    }

    private suspend fun ensureSendspinConnected(): Boolean {
        val state = sendspinManager.connectionState.value
        if (state == SendspinState.SYNCING || state == SendspinState.STREAMING) return true

        // If mid-handshake, just wait for it to finish
        if (state != SendspinState.DISCONNECTED && state != SendspinState.ERROR) {
            Log.d(TAG, "Sendspin is $state, waiting for ready")
            return kotlinx.coroutines.withTimeoutOrNull(10000) {
                sendspinManager.connectionState
                    .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
            } != null
        }

        // Disconnected or error: restart
        val url = settingsRepository.serverUrl.first()
        val token = wsClient.authToken ?: settingsRepository.authToken.first()
        val clientId = sendspinPlayerId ?: return false
        if (url.isBlank() || token.isBlank()) return false

        Log.d(TAG, "Restarting sendspin for playback (was $state)")
        sendspinManager.start(url, token, clientId, "MassDroid")

        return kotlinx.coroutines.withTimeoutOrNull(10000) {
            sendspinManager.connectionState
                .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
        } != null
    }

    @Synchronized
    private fun startSendspin() {
        if (collectorJobs.isNotEmpty()) {
            Log.d(TAG, "startSendspin ignored: service already initialized")
            updateNotification()
            return
        }

        requestAudioFocus()
        registerNoisyReceiver()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Observe connection state
        collectorJobs += scope.launch {
            sendspinManager.connectionState.collect { state ->
                val wasStreaming = isStreaming
                isStreaming = state == SendspinState.STREAMING
                isSendspinReady = state == SendspinState.SYNCING || state == SendspinState.STREAMING
                Log.d(TAG, "Sendspin state: $state, isStreaming=$isStreaming, isSendspinReady=$isSendspinReady")
                // Track if sendspin dropped while actively streaming (for auto-resume)
                if (wasStreaming && !isStreaming) {
                    val sendspinWasPlaying = playerRepository.players.value
                        .firstOrNull { it.playerId == sendspinPlayerId }
                        ?.state == PlaybackState.PLAYING
                    wasPlayingBeforeDisconnect = sendspinWasPlaying
                    resumePositionSeconds = currentPositionMs / 1000.0
                    resumeTrackUri = currentTrackUri
                    registerReconnectDrop()
                    Log.d(
                        TAG,
                        "Sendspin dropped while streaming, sendspinWasPlaying=$sendspinWasPlaying, " +
                                "currentIsPlaying=$currentIsPlaying, pos=${resumePositionSeconds}s, uri=$resumeTrackUri"
                    )
                }
                val outsideOptimistic = System.currentTimeMillis() >= optimisticUntil
                if (isStreaming && !wasStreaming) {
                    if (outsideOptimistic) {
                        currentIsPlaying = true
                        lastPlayingAtMs = System.currentTimeMillis()
                    }
                    updateMediaSession()
                    updateNotification()
                } else if (wasStreaming && state == SendspinState.SYNCING) {
                    if (outsideOptimistic) currentIsPlaying = false
                    updateMediaSession()
                    updateNotification()
                } else if (!isStreaming && state != SendspinState.SYNCING) {
                    if (outsideOptimistic) currentIsPlaying = false
                    currentTitle = when (state) {
                        SendspinState.STREAMING -> ""
                        SendspinState.SYNCING -> "Ready"
                        SendspinState.HANDSHAKING,
                        SendspinState.AUTHENTICATING,
                        SendspinState.CONNECTING -> "Connecting..."
                        SendspinState.ERROR -> "Connection error"
                        SendspinState.DISCONNECTED -> "Disconnected"
                    }
                    currentArtist = ""
                    currentAlbum = ""
                    currentDurationMs = 0
                    currentPositionMs = 0
                    updateMediaSession()
                    updateNotification()
                }
            }
        }

        // Observe sendspin player metadata from the players list (not selectedPlayer)
        collectorJobs += scope.launch {
            playerRepository.players
                .map { list -> list.find { it.playerId == sendspinPlayerId } }
                .distinctUntilChanged()
                .collect { player ->
                    lastSendspinReportedPlaying = player?.state == PlaybackState.PLAYING
                    val outsideOptimistic = System.currentTimeMillis() >= optimisticUntil
                    if (outsideOptimistic) {
                        if (lastSendspinReportedPlaying) {
                            currentIsPlaying = true
                            lastPlayingAtMs = System.currentTimeMillis()
                        } else if (currentIsPlaying && isStreaming) {
                            val sincePlayingMs = System.currentTimeMillis() - lastPlayingAtMs
                            if (sincePlayingMs >= PAUSE_DEBOUNCE_MS) {
                                currentIsPlaying = false
                            } else {
                                Log.d(TAG, "Ignoring transient pause jitter (${sincePlayingMs}ms)")
                            }
                        } else {
                            currentIsPlaying = false
                        }
                    }
                    if (!isStreaming || player == null) return@collect
                    val media = player.currentMedia
                    val title = media?.title ?: "MassDroid Speaker"
                    val artist = media?.artist ?: ""
                    val album = media?.album ?: ""
                    val durationMs = ((media?.duration ?: 0.0) * 1000).toLong()
                    val artUrl = media?.imageUrl

                    val metadataChanged = title != currentTitle ||
                            artist != currentArtist ||
                            album != currentAlbum ||
                            durationMs != currentDurationMs
                    val artChanged = artUrl != currentArtUrl

                    currentTitle = title
                    currentArtist = artist
                    currentAlbum = album
                    currentDurationMs = durationMs
                    currentTrackUri = media?.uri

                    if (artChanged) {
                        currentArtUrl = artUrl
                        currentArt = loadArt(artUrl)
                    }

                    currentPositionMs = ((media?.elapsedTime ?: 0.0) * 1000).toLong()

                    updateMediaSession()

                    if (metadataChanged || artChanged) {
                        updateNotification()
                    }
                }
        }

        // Immediate audio pause/resume when app UI controls the sendspin player
        collectorJobs += scope.launch {
            playerRepository.playbackIntent.collect { willPlay ->
                val selectedId = playerRepository.selectedPlayer.value?.playerId
                if (selectedId != sendspinPlayerId) return@collect
                if (willPlay) {
                    currentIsPlaying = true
                    lastPlayingAtMs = System.currentTimeMillis()
                    if (!hasAudioFocus) requestAudioFocus()
                    if (isSendspinReady) {
                        sendspinManager.resumeAudio()
                    } else {
                        ensureSendspinConnected()
                    }
                } else {
                    if (!isSendspinReady) return@collect
                    currentIsPlaying = false
                    sendspinManager.pauseAudio()
                }
                updateMediaSession()
            }
        }

        // Read settings and start sendspin
        collectorJobs += scope.launch {
            val url = settingsRepository.serverUrl.first()
            val token = settingsRepository.authToken.first()
            if (url.isBlank() || token.isBlank()) {
                Log.e(TAG, "No server URL or token, stopping")
                stopSelf()
                return@launch
            }

            var clientId = settingsRepository.sendspinClientId.first()
            if (clientId == null) {
                clientId = UUID.randomUUID().toString()
                settingsRepository.setSendspinClientId(clientId)
            }

            sendspinPlayerId = clientId
            val ssState = sendspinManager.connectionState.value
            if (ssState == SendspinState.DISCONNECTED || ssState == SendspinState.ERROR) {
                sendspinManager.start(url, token, clientId, "MassDroid")
                Log.d(TAG, "Sendspin started via service, playerId=$clientId")
            } else {
                Log.d(TAG, "Sendspin already $ssState, skipping redundant start")
            }
        }

        // Resume playback when MA reconnects after a drop (only if was playing before)
        collectorJobs += scope.launch {
            var connectedBefore = false
            wsClient.connectionState.collect { state ->
                val isConnected = state is net.asksakis.massdroidv2.data.websocket.ConnectionState.Connected
                if (isConnected && connectedBefore) {
                    waitForReconnectCooldownIfNeeded()
                    if (wsClient.connectionState.value !is net.asksakis.massdroidv2.data.websocket.ConnectionState.Connected) {
                        Log.d(TAG, "Reconnect cooldown ended after connection changed, skipping cycle")
                        return@collect
                    }
                    val url = settingsRepository.serverUrl.first()
                    val token = wsClient.authToken ?: settingsRepository.authToken.first()
                    var clientId = settingsRepository.sendspinClientId.first()
                    if (clientId == null) {
                        clientId = UUID.randomUUID().toString()
                        settingsRepository.setSendspinClientId(clientId)
                    }

                    // Only restart sendspin if it's idle (not mid-connection or streaming)
                    val currentSsState = sendspinManager.connectionState.value
                    if (currentSsState == SendspinState.DISCONNECTED || currentSsState == SendspinState.ERROR) {
                        Log.d(TAG, "MA reconnected, sendspin is $currentSsState, restarting")
                        if (url.isNotBlank() && token.isNotBlank()) {
                            sendspinManager.start(url, token, clientId, "MassDroid")
                        }
                    } else {
                        Log.d(TAG, "MA reconnected, sendspin already $currentSsState, skipping restart")
                    }

                    Log.d(TAG, "Reconnect: clientId=$clientId, wasPlaying=$wasPlayingBeforeDisconnect")
                    if (wasPlayingBeforeDisconnect) {
                        wasPlayingBeforeDisconnect = false
                        // Wait for sendspin handshake
                        val ssReady = kotlinx.coroutines.withTimeoutOrNull(10000) {
                            sendspinManager.connectionState
                                .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
                        }
                        if (ssReady == null) {
                            Log.w(TAG, "Reconnect: sendspin didn't reach ready state, skipping auto-resume")
                        } else {
                            Log.d(TAG, "Reconnect: sendspin reached $ssReady")
                            // Wait for server to register the sendspin player
                            val readyPlayer = kotlinx.coroutines.withTimeoutOrNull(5000) {
                                playerRepository.players
                                    .map { list -> list.find { it.playerId == clientId } }
                                    .first { it != null && it.state != PlaybackState.PLAYING }
                            }
                            Log.d(TAG, "Reconnect: sendspin player state=${readyPlayer?.state ?: "timeout"}")
                            // Resume on the SENDSPIN player, not the selected UI player
                            val seekTo = resumePositionSeconds
                            val trackUri = resumeTrackUri
                            Log.d(TAG, "Resuming on sendspin $clientId at ${seekTo}s, uri=$trackUri")
                            var resumed = false
                            for (attempt in 1..3) {
                                // Abort if sendspin dropped again
                                val ssState = sendspinManager.connectionState.value
                                if (ssState != SendspinState.SYNCING && ssState != SendspinState.STREAMING) {
                                    Log.d(TAG, "Sendspin no longer ready ($ssState), aborting resume")
                                    break
                                }
                                try {
                                    // Start playback
                                    if (trackUri != null) {
                                        val queueItems = musicRepository.getQueueItems(clientId)
                                        val idx = queueItems.indexOfFirst { it.track?.uri == trackUri }
                                        if (idx >= 0) {
                                            Log.d(TAG, "Found track at queue index $idx, using play_index")
                                            musicRepository.playQueueIndex(clientId, idx)
                                        } else {
                                            Log.d(TAG, "Track not in queue, using play_media")
                                            musicRepository.playMedia(clientId, trackUri)
                                        }
                                    } else {
                                        Log.d(TAG, "No track URI saved, using generic play")
                                        playerRepository.play(clientId)
                                    }
                                    // Wait for stream to be active, verify track, then seek
                                    if (seekTo > 1.0 && trackUri != null) {
                                        val streaming = kotlinx.coroutines.withTimeoutOrNull(5000) {
                                            sendspinManager.connectionState
                                                .first { it == SendspinState.STREAMING }
                                        }
                                        if (streaming != null && currentTrackUri == trackUri) {
                                            Log.d(TAG, "Stream active, correct track, seeking to ${seekTo}s")
                                            playerRepository.seek(clientId, seekTo)
                                        } else if (streaming != null) {
                                            Log.w(TAG, "Track changed after resume (now=$currentTrackUri, expected=$trackUri), skipping seek")
                                        } else {
                                            Log.w(TAG, "Stream didn't start in 5s, skipping seek")
                                        }
                                    }
                                    resumed = true
                                    Log.d(TAG, "Resume succeeded (attempt $attempt)")
                                    break
                                } catch (e: Exception) {
                                    Log.w(TAG, "Resume attempt $attempt failed: ${e.message}")
                                    if (attempt < 3) {
                                        delay(1500)
                                        // Re-check after delay
                                        val postDelaySs = sendspinManager.connectionState.value
                                        if (postDelaySs != SendspinState.SYNCING && postDelaySs != SendspinState.STREAMING) {
                                            Log.d(TAG, "Sendspin disconnected during retry delay ($postDelaySs), aborting")
                                            break
                                        }
                                    }
                                }
                            }
                            if (!resumed) Log.e(TAG, "Resume failed after 3 attempts")
                        }
                    } else {
                        Log.d(TAG, "Reconnect: skipping auto-resume, was not playing before disconnect")
                    }
                }
                if (isConnected) connectedBefore = true
            }
        }
    }

    private suspend fun loadArt(url: String?): Bitmap? {
        if (url == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val client = wsClient.getImageClient()
                val request = okhttp3.Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load album art: ${e.message}")
                null
            }
        }
    }

    private fun registerReconnectDrop(nowMs: Long = System.currentTimeMillis()) {
        reconnectDropTimestampsMs.addLast(nowMs)
        while (reconnectDropTimestampsMs.isNotEmpty() &&
            nowMs - reconnectDropTimestampsMs.first() > RECONNECT_STORM_WINDOW_MS
        ) {
            reconnectDropTimestampsMs.removeFirst()
        }
        if (reconnectDropTimestampsMs.size >= RECONNECT_STORM_THRESHOLD) {
            reconnectCooldownUntilMs = maxOf(reconnectCooldownUntilMs, nowMs + RECONNECT_COOLDOWN_MS)
            Log.w(
                TAG,
                "Reconnect storm detected (${reconnectDropTimestampsMs.size} drops in ${RECONNECT_STORM_WINDOW_MS}ms), " +
                        "cooling down for ${RECONNECT_COOLDOWN_MS}ms"
            )
            reconnectDropTimestampsMs.clear()
        }
    }

    private suspend fun waitForReconnectCooldownIfNeeded() {
        val remainingMs = reconnectCooldownUntilMs - System.currentTimeMillis()
        if (remainingMs > 0) {
            Log.w(TAG, "Reconnect cooldown active, delaying auto-resume by ${remainingMs}ms")
            delay(remainingMs)
        }
    }

    private fun updateMediaSession() {
        val actions = PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO

        val pbState = when {
            currentIsPlaying && isStreaming -> PlaybackStateCompat.STATE_PLAYING
            isSendspinReady -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_BUFFERING
        }
        val positionBucket = currentPositionMs / SESSION_POSITION_STEP_MS
        val metadataChanged = currentTitle != lastSessionTitle ||
                currentArtist != lastSessionArtist ||
                currentAlbum != lastSessionAlbum ||
                currentDurationMs != lastSessionDurationMs ||
                currentArtUrl != lastSessionArtUrl
        val playbackChanged = pbState != lastSessionPbState
        val positionChanged = positionBucket != lastSessionPositionBucket

        if (!metadataChanged && !playbackChanged && !positionChanged) return

        Log.d(TAG, "MediaSession state: playing=$currentIsPlaying, streaming=$isStreaming -> pbState=$pbState")

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(pbState, currentPositionMs, if (pbState == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
        mediaSession?.setPlaybackState(stateBuilder.build())

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
        if (currentDurationMs > 0) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
        }
        currentArt?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession?.setMetadata(metadataBuilder.build())

        lastSessionPbState = pbState
        lastSessionPositionBucket = positionBucket
        lastSessionTitle = currentTitle
        lastSessionArtist = currentArtist
        lastSessionAlbum = currentAlbum
        lastSessionDurationMs = currentDurationMs
        lastSessionArtUrl = currentArtUrl
    }

    private fun stopSendspin() {
        for (job in collectorJobs) job.cancel(CancellationException("Sendspin restart"))
        collectorJobs.clear()
        wasPlayingBeforeDisconnect = false
        lastSendspinReportedPlaying = false
        lastSessionPbState = Int.MIN_VALUE
        lastSessionPositionBucket = Long.MIN_VALUE
        lastSessionTitle = ""
        lastSessionArtist = ""
        lastSessionAlbum = ""
        lastSessionDurationMs = -1L
        lastSessionArtUrl = null
        reconnectDropTimestampsMs.clear()
        reconnectCooldownUntilMs = 0L
        abandonAudioFocus()
        unregisterNoisyReceiver()
        sendspinManager.stop()
        mediaSession?.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Sendspin service stopped")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MassDroid Speaker",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when MassDroid is acting as a speaker"
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun actionIntent(action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                this, code,
                Intent(this, SendspinService::class.java).apply { this.action = action },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val session = mediaSession ?: return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MassDroid Speaker")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle.ifEmpty { "MassDroid Speaker" })
            .setContentText(currentArtist.ifEmpty { if (isStreaming) "Streaming" else "" })
            .setSubText(currentAlbum)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        currentArt?.let { builder.setLargeIcon(it) }

        builder.addAction(androidx.media3.session.R.drawable.media3_icon_previous, "Previous", actionIntent(ACTION_PREV, 10))

        val playPauseIcon = if (currentIsPlaying)
            androidx.media3.session.R.drawable.media3_icon_pause
        else
            androidx.media3.session.R.drawable.media3_icon_play
        val playPauseLabel = if (currentIsPlaying) "Pause" else "Play"
        builder.addAction(playPauseIcon, playPauseLabel, actionIntent(ACTION_PLAY_PAUSE, 11))

        builder.addAction(androidx.media3.session.R.drawable.media3_icon_next, "Next", actionIntent(ACTION_NEXT, 12))
        builder.addAction(androidx.media3.session.R.drawable.media3_icon_stop, "Stop", actionIntent(ACTION_STOP, 13))

        builder.setStyle(
            MediaNotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(actionIntent(ACTION_STOP, 14))
        )

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MassDroid::Sendspin")
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)

        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MassDroid::Sendspin")
        wifiLock?.acquire()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        unregisterNoisyReceiver()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        sendspinManager.stop()
        super.onDestroy()
    }
}
