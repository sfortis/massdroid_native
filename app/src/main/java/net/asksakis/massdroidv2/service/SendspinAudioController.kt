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
import net.asksakis.massdroidv2.domain.repository.MusicRepository
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
    private val musicRepository: MusicRepository,
    private val wsClient: MaWebSocketClient,
    private val onMetadataChanged: (SendspinMetadata) -> Unit,
    private val onStateChanged: (ready: Boolean, streaming: Boolean, playing: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "SendspinCtrl"
        private const val PAUSE_DEBOUNCE_MS = 400L
        private const val RECONNECT_STORM_WINDOW_MS = 45_000L
        private const val RECONNECT_STORM_THRESHOLD = 3
        private const val RECONNECT_COOLDOWN_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    private var wasPlayingBeforeDisconnect = false
    private var lastSendspinReportedPlaying = false
    private var resumePositionSeconds = 0.0
    private var resumeTrackUri: String? = null
    private var resumeQueueTrackUris: List<String> = emptyList()
    private var currentTrackUri: String? = null
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    var currentIsPlaying = false; private set
    private var optimisticUntil = 0L
    var sendspinPlayerId: String? = null; private set
    private var lastKnownSendspinQueueTrackUris: List<String> = emptyList()
    private val collectorJobs = mutableListOf<Job>()
    private var lastPlayingAtMs = 0L
    private val reconnectDropLock = Any()
    private val reconnectDropTimestampsMs = ArrayDeque<Long>()
    private var reconnectCooldownUntilMs = 0L

    fun start() {
        if (collectorJobs.isNotEmpty()) {
            Log.d(TAG, "start() ignored: already running")
            return
        }

        setupAudioFocus()
        requestAudioFocus()
        registerNoisyReceiver()
        acquireLocks()

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

                if (wasStreaming && !isStreaming) {
                    val sendspinWasPlaying = playerRepository.players.value
                        .firstOrNull { it.playerId == sendspinPlayerId }
                        ?.state == PlaybackState.PLAYING
                    wasPlayingBeforeDisconnect = sendspinWasPlaying
                    resumePositionSeconds = currentPositionMs / 1000.0
                    resumeTrackUri = currentTrackUri
                    resumeQueueTrackUris = lastKnownSendspinQueueTrackUris
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
                val selectedId = playerRepository.selectedPlayer.value?.playerId
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

        // Collector 4: Queue snapshot on change
        collectorJobs += scope.launch {
            playerRepository.queueItemsChanged.collect { queueId ->
                if (queueId != sendspinPlayerId) return@collect
                snapshotSendspinQueue(queueId)
            }
        }

        // Collector 5: Read settings and start sendspin
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
                restoreStartupSendspinSnapshotIfNeeded(clientId)
            }
        }

        // Collector 6: Resume playback when MA reconnects after a drop
        collectorJobs += scope.launch {
            var connectedBefore = false
            wsClient.connectionState.collect { state ->
                val isConnected = state is ConnectionState.Connected
                if (isConnected && connectedBefore) {
                    waitForReconnectCooldownIfNeeded()
                    if (wsClient.connectionState.value !is ConnectionState.Connected) {
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
                        val ssReady = withTimeoutOrNull(10000) {
                            sendspinManager.connectionState
                                .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
                        }
                        if (ssReady == null) {
                            Log.w(TAG, "Reconnect: sendspin didn't reach ready state, skipping auto-resume")
                        } else {
                            Log.d(TAG, "Reconnect: sendspin reached $ssReady")
                            val readyPlayer = withTimeoutOrNull(5000) {
                                playerRepository.players
                                    .map { list -> list.find { it.playerId == clientId } }
                                    .first { it != null && it.state != PlaybackState.PLAYING }
                            }
                            Log.d(TAG, "Reconnect: sendspin player state=${readyPlayer?.state ?: "timeout"}")
                            val seekTo = resumePositionSeconds
                            val trackUri = resumeTrackUri
                            Log.d(TAG, "Resuming on sendspin $clientId at ${seekTo}s, uri=$trackUri")
                            var resumed = false
                            for (attempt in 1..3) {
                                val ssState = sendspinManager.connectionState.value
                                if (ssState != SendspinState.SYNCING && ssState != SendspinState.STREAMING) {
                                    Log.d(TAG, "Sendspin no longer ready ($ssState), aborting resume")
                                    break
                                }
                                try {
                                    if (trackUri != null) {
                                        val idx = waitForQueueTrackIndex(clientId, trackUri)
                                        if (idx >= 0) {
                                            Log.d(TAG, "Found track at queue index $idx, using play_index")
                                            musicRepository.playQueueIndex(clientId, idx)
                                        } else if (restoreSavedQueueSnapshot(clientId, trackUri)) {
                                            val restoredIdx = waitForQueueTrackIndex(clientId, trackUri)
                                            if (restoredIdx >= 0) {
                                                Log.d(TAG, "Restored saved queue snapshot, using play_index=$restoredIdx")
                                                musicRepository.playQueueIndex(clientId, restoredIdx)
                                            } else {
                                                Log.w(TAG, "Saved queue snapshot restored but track still missing, aborting auto-resume")
                                                break
                                            }
                                        } else {
                                            Log.w(TAG, "Track not found in queue and no saved snapshot available, aborting auto-resume")
                                            break
                                        }
                                    } else {
                                        Log.d(TAG, "No track URI saved, using generic play")
                                        playerRepository.play(clientId)
                                    }
                                    if (seekTo > 1.0 && trackUri != null) {
                                        val streaming = withTimeoutOrNull(5000) {
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

    fun stop() {
        for (job in collectorJobs) job.cancel(CancellationException("Sendspin stop"))
        collectorJobs.clear()
        wasPlayingBeforeDisconnect = false
        lastSendspinReportedPlaying = false
        synchronized(reconnectDropLock) {
            reconnectDropTimestampsMs.clear()
        }
        reconnectCooldownUntilMs = 0L
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

    // region Queue helpers

    private suspend fun waitForQueueTrackIndex(queueId: String, trackUri: String): Int {
        repeat(8) { attempt ->
            val queueItems = musicRepository.getQueueItems(queueId)
            val idx = queueItems.indexOfFirst { it.track?.uri == trackUri }
            if (idx >= 0) return idx
            if (attempt < 7) delay(350)
        }
        return -1
    }

    private suspend fun restoreSavedQueueSnapshot(queueId: String, trackUri: String): Boolean {
        val snapshotUris = resumeQueueTrackUris
            .filter { it.isNotBlank() }
            .distinct()
        if (snapshotUris.isEmpty() || trackUri !in snapshotUris) return false
        Log.d(TAG, "Restoring saved sendspin queue snapshot (${snapshotUris.size} tracks)")
        musicRepository.playMedia(queueId, snapshotUris, option = "replace")
        return true
    }

    private suspend fun snapshotSendspinQueue(queueId: String) {
        try {
            val uris = musicRepository.getQueueItems(queueId, limit = 500, offset = 0)
                .mapNotNull { it.track?.uri?.takeIf { uri -> uri.isNotBlank() } }
            if (uris.isNotEmpty()) {
                lastKnownSendspinQueueTrackUris = uris
                settingsRepository.setSendspinSnapshot(
                    trackUri = currentTrackUri,
                    positionSeconds = currentPositionMs / 1000.0,
                    queueUris = uris
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to snapshot sendspin queue: ${e.message}")
        }
    }

    private suspend fun restoreStartupSendspinSnapshotIfNeeded(queueId: String) {
        val selectedPlayerId = settingsRepository.selectedPlayerId.first()
        if (selectedPlayerId != queueId) return

        val snapshotTrackUri = settingsRepository.sendspinSnapshotTrackUri.first()
        val snapshotQueueUris = settingsRepository.sendspinSnapshotQueueUris.first()
        val snapshotPositionSeconds = settingsRepository.sendspinSnapshotPositionSeconds.first()
        if (snapshotTrackUri.isNullOrBlank() || snapshotQueueUris.isEmpty()) return

        val currentQueueUris = try {
            musicRepository.getQueueItems(queueId, limit = 500, offset = 0)
                .mapNotNull { it.track?.uri?.takeIf { uri -> uri.isNotBlank() } }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect sendspin queue before startup restore: ${e.message}")
            emptyList()
        }

        if (currentQueueUris == snapshotQueueUris) {
            lastKnownSendspinQueueTrackUris = currentQueueUris
            return
        }

        Log.d(TAG, "Restoring persisted sendspin startup snapshot (${snapshotQueueUris.size} tracks)")
        musicRepository.playMedia(queueId, snapshotQueueUris, option = "replace")
        val restoredIdx = waitForQueueTrackIndex(queueId, snapshotTrackUri)
        if (restoredIdx >= 0) {
            Log.d(TAG, "Persisted sendspin snapshot restored at queue index=$restoredIdx without autoplay")
            lastKnownSendspinQueueTrackUris = snapshotQueueUris
        } else {
            Log.w(TAG, "Persisted sendspin snapshot restored but track still missing")
        }
    }

    // endregion

    // region Reconnect storm detection

    private fun registerReconnectDrop(nowMs: Long = System.currentTimeMillis()) {
        synchronized(reconnectDropLock) {
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
    }

    private suspend fun waitForReconnectCooldownIfNeeded() {
        val remainingMs = reconnectCooldownUntilMs - System.currentTimeMillis()
        if (remainingMs > 0) {
            Log.w(TAG, "Reconnect cooldown active, delaying auto-resume by ${remainingMs}ms")
            delay(remainingMs)
        }
    }

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
