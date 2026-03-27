package net.asksakis.massdroidv2.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import java.util.UUID

data class SendspinMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val art: Bitmap?,
    val artUrl: String?,
    val trackUri: String?
)

class SendspinAudioController(
    private val context: Context,
    private val sendspinManager: SendspinManager,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val onMetadataChanged: (SendspinMetadata) -> Unit,
    private val onStateChanged: (ready: Boolean, streaming: Boolean, playing: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "SendspinCtrl"
        private const val PAUSE_DEBOUNCE_MS = 400L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Coroutine exception: ${e.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Audio focus
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var hasAudioFocus = false

    // Noisy audio receiver (headset unplug)
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy (headset unplugged), pausing")
                val id = sendspinPlayerId ?: return
                currentIsPlaying = false
                sendspinManager.pauseAudio()
                optimisticUntil = System.currentTimeMillis() + 1000
                notifyStateChanged()
                scope.launch { playerRepository.pause(id) }
            }
        }
    }

    // Locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // State
    private var currentArt: Bitmap? = null
    private var currentArtUrl: String? = null
    var isStreaming = false; private set
    var isReady = false; private set
    private var lastSendspinReportedPlaying = false
    private var currentTrackUri: String? = null
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    var currentIsPlaying = false; private set
    private var optimisticUntil = 0L
    var sendspinPlayerId: String? = null; private set
    private val collectorJobs = mutableListOf<Job>()
    private var lastPlayingAtMs = 0L

    fun start() {
        if (collectorJobs.isNotEmpty()) {
            Log.d(TAG, "start() ignored: already running")
            return
        }

        setupAudioFocus()
        registerNoisyReceiver()

        // Immediately start sendspin connection
        scope.launch { ensureSendspinConnected() }

        // Collector 1: Observe connection state
        collectorJobs += scope.launch {
            sendspinManager.connectionState.collect { state ->
                val wasStreaming = isStreaming
                isStreaming = state == SendspinState.STREAMING
                val wasReady = isReady
                isReady = state == SendspinState.SYNCING || state == SendspinState.STREAMING
                Log.d(TAG, "Sendspin state: $state, isStreaming=$isStreaming, isReady=$isReady")

                if (!wasStreaming && isStreaming) {
                    acquireLocks()
                }
                if (wasStreaming && !isStreaming) {
                    releaseLocks()
                    Log.d(TAG, "Sendspin dropped while streaming")
                }

                val outsideOptimistic = System.currentTimeMillis() >= optimisticUntil
                if (isStreaming && !wasStreaming) {
                    if (outsideOptimistic) {
                        currentIsPlaying = true
                        lastPlayingAtMs = System.currentTimeMillis()
                    }
                } else if (wasStreaming && state == SendspinState.SYNCING) {
                    if (outsideOptimistic) currentIsPlaying = false
                } else if (!isStreaming && state != SendspinState.SYNCING) {
                    if (outsideOptimistic) currentIsPlaying = false
                    currentTitle = ""
                    currentArtist = ""
                    currentAlbum = ""
                    currentDurationMs = 0
                    currentPositionMs = 0
                }
                notifyStateChanged()
            }
        }

        // Collector 2: Observe sendspin player metadata from the players list
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

                    notifyMetadataChanged()
                    notifyStateChanged()
                }
        }

        // Collector 3: Immediate audio pause/resume when app UI controls the sendspin player
        collectorJobs += scope.launch {
            playerRepository.playbackIntent.collect { willPlay ->
                val selectedId = playerRepository.selectedPlayer.value?.playerId ?: return@collect
                if (selectedId != sendspinPlayerId) return@collect
                if (willPlay) {
                    currentIsPlaying = true
                    lastPlayingAtMs = System.currentTimeMillis()
                    if (!hasAudioFocus) requestAudioFocus()
                    if (isReady) {
                        sendspinManager.resumeAudio()
                    } else {
                        ensureSendspinConnected()
                    }
                } else {
                    if (!isReady) return@collect
                    currentIsPlaying = false
                    sendspinManager.pauseAudio()
                }
                notifyStateChanged()
            }
        }

        // Collector 4: Read settings and start sendspin
        collectorJobs += scope.launch {
            val url = settingsRepository.serverUrl.first()
            val token = wsClient.authToken ?: settingsRepository.authToken.first()
            if (url.isBlank() || token.isBlank()) {
                Log.e(TAG, "No server URL or token, cannot start sendspin")
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
                Log.d(TAG, "Sendspin started, playerId=$clientId")
            } else {
                Log.d(TAG, "Sendspin already $ssState, skipping redundant start")
            }

            launch {
                val readyState = withTimeoutOrNull(10_000) {
                    sendspinManager.connectionState
                        .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
                }
                if (readyState == null) {
                    Log.w(TAG, "Startup: sendspin did not reach ready state, skipping snapshot restore")
                    return@launch
                }
                withTimeoutOrNull(5_000) {
                    playerRepository.players
                        .map { list -> list.any { it.playerId == clientId } }
                        .first { it }
                }
            }
        }

        // Collector 6: Restart Sendspin WebSocket when MA reconnects
        collectorJobs += scope.launch {
            var connectedBefore = false
            wsClient.connectionState.collect { state ->
                val isConnected = state is ConnectionState.Connected
                if (isConnected && connectedBefore) {
                    val url = settingsRepository.serverUrl.first()
                    val token = wsClient.authToken ?: settingsRepository.authToken.first()
                    var clientId = settingsRepository.sendspinClientId.first()
                    if (clientId == null) {
                        clientId = UUID.randomUUID().toString()
                        settingsRepository.setSendspinClientId(clientId)
                    }

                    val currentSsState = sendspinManager.connectionState.value
                    if (currentSsState == SendspinState.DISCONNECTED || currentSsState == SendspinState.ERROR) {
                        Log.d(TAG, "MA reconnected, sendspin is $currentSsState, restarting")
                        if (url.isNotBlank() && token.isNotBlank()) {
                            sendspinManager.start(url, token, clientId, "MassDroid")
                        }
                    } else {
                        Log.d(TAG, "MA reconnected, sendspin already $currentSsState")
                    }
                }
                if (isConnected) connectedBefore = true
            }
        }
    }

    fun stop() {
        for (job in collectorJobs) job.cancel(CancellationException("Sendspin stop"))
        collectorJobs.clear()
        lastSendspinReportedPlaying = false
        abandonAudioFocus()
        unregisterNoisyReceiver()
        releaseLocks()
        sendspinManager.stop()
        currentIsPlaying = false
        isReady = false
        isStreaming = false
        notifyStateChanged()
        Log.d(TAG, "Sendspin controller stopped")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    // region Public playback commands

    fun handlePlay() {
        val id = sendspinPlayerId ?: return

        playerRepository.selectPlayer(id)
        currentIsPlaying = true
        lastPlayingAtMs = System.currentTimeMillis()
        optimisticUntil = System.currentTimeMillis() + 1000
        notifyStateChanged()
        if (!hasAudioFocus) requestAudioFocus()
        if (isReady) sendspinManager.resumeAudio()
        scope.launch {
            if (!isReady) ensureSendspinConnected()
            playerRepository.play(id)
        }
    }

    fun handlePause() {
        val id = sendspinPlayerId ?: return

        currentIsPlaying = false
        optimisticUntil = System.currentTimeMillis() + 1000
        notifyStateChanged()
        sendspinManager.pauseAudio()
        scope.launch { playerRepository.pause(id) }
    }

    fun handlePlayPause() {
        val id = sendspinPlayerId ?: return
        val wantPlay = !currentIsPlaying
        if (wantPlay) {
    
            currentIsPlaying = true
            lastPlayingAtMs = System.currentTimeMillis()
            if (!hasAudioFocus) requestAudioFocus()
            if (isReady) sendspinManager.resumeAudio()
        } else {
            currentIsPlaying = false
            sendspinManager.pauseAudio()
        }
        optimisticUntil = System.currentTimeMillis() + 1000
        notifyStateChanged()
        scope.launch {
            if (wantPlay && !isReady) ensureSendspinConnected()
            playerRepository.playPause(id)
        }
    }

    fun handleNext() {
        val id = sendspinPlayerId ?: return
        scope.launch { playerRepository.next(id) }
    }

    fun handlePrev() {
        val id = sendspinPlayerId ?: return
        scope.launch { playerRepository.previous(id) }
    }

    fun handleSeek(posMs: Long) {
        val id = sendspinPlayerId ?: return
        scope.launch { playerRepository.seek(id, posMs / 1000.0) }
    }

    // endregion

    // region Audio focus

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
        if (!::focusRequest.isInitialized) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasAudioFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }

    // endregion

    // region Noisy receiver

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
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
                context.unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
    }

    // endregion

    // region Locks

    private fun acquireLocks() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MassDroid::Sendspin")
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)

        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(wifiMode, "MassDroid::Sendspin")
        wifiLock?.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // endregion

    // region Sendspin connection helpers

    private suspend fun ensureSendspinConnected(): Boolean {
        val state = sendspinManager.connectionState.value
        if (state == SendspinState.SYNCING || state == SendspinState.STREAMING) return true

        if (state != SendspinState.DISCONNECTED && state != SendspinState.ERROR) {
            Log.d(TAG, "Sendspin is $state, waiting for ready")
            return withTimeoutOrNull(10000) {
                sendspinManager.connectionState
                    .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
            } != null
        }

        val url = settingsRepository.serverUrl.first()
        val token = wsClient.authToken ?: settingsRepository.authToken.first()
        val clientId = sendspinPlayerId ?: return false
        if (url.isBlank() || token.isBlank()) return false

        Log.d(TAG, "Restarting sendspin for playback (was $state)")
        sendspinManager.start(url, token, clientId, "MassDroid")

        return withTimeoutOrNull(10000) {
            sendspinManager.connectionState
                .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
        } != null
    }

    // endregion

    // endregion

    // region Art loading

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

    // endregion

    // region Notification callbacks

    private fun notifyMetadataChanged() {
        onMetadataChanged(
            SendspinMetadata(
                title = currentTitle,
                artist = currentArtist,
                album = currentAlbum,
                durationMs = currentDurationMs,
                positionMs = currentPositionMs,
                art = currentArt,
                artUrl = currentArtUrl,
                trackUri = currentTrackUri
            )
        )
    }

    private fun notifyStateChanged() {
        onStateChanged(isReady, isStreaming, currentIsPlaying)
    }

    // endregion
}
