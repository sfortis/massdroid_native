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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import net.asksakis.massdroidv2.data.sendspin.SyncState
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

    // Audio route detection: uses AudioTrack.getRoutedDevice() as canonical source
    @Volatile private var currentOutputRoute = "unknown"
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) = checkRouteChange()
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) = checkRouteChange()
    }

    private fun classifyDeviceType(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bt"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        else -> "speaker"
    }

    private fun resolveOutputRoute(): String {
        // Primary: ask the actual AudioTrack where it's routing (canonical truth)
        sendspinManager.getRoutedDeviceType()?.let { return classifyDeviceType(it) }
        // Fallback: heuristic from connected devices
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothA2dpOn) return "bt"
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when {
            devices.any { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "wired"
            devices.any { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE } -> "usb"
            else -> "speaker"
        }
    }

    private fun checkRouteChange() {
        val newRoute = resolveOutputRoute()
        if (newRoute == currentOutputRoute) return
        val oldRoute = currentOutputRoute
        currentOutputRoute = newRoute
        Log.d(TAG, "Audio route changed: $oldRoute -> $newRoute")
        sendspinManager.onOutputRouteChanged("$oldRoute->$newRoute")
    }

    // Locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // State
    private var currentArt: Bitmap? = null
    private var currentArtUrl: String? = null
    @Volatile var isStreaming = false; private set
    @Volatile var isReady = false; private set
    @Volatile private var transportState = SendspinState.DISCONNECTED
    @Volatile private var localSyncState = SyncState.IDLE
    @Volatile private var lastSendspinReportedPlaying = false
    private var currentTrackUri: String? = null
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    val currentDisplayedTitle: String get() = currentTitle
    val currentDisplayedArtist: String get() = currentArtist
    val currentDisplayedAlbum: String get() = currentAlbum
    val currentDisplayedDurationMs: Long get() = currentDurationMs
    val currentDisplayedPositionMs: Long get() = currentPositionMs
    val currentDisplayedArtUrl: String? get() = currentArtUrl
    var currentIsPlaying = false; private set
    private var optimisticUntil = 0L
    var sendspinPlayerId: String? = null; private set
    private val collectorJobs = mutableListOf<Job>()
    private var autoRecoveryJob: Job? = null
    private var reconnectJob: Deferred<Boolean>? = null
    private var lastPlayingAtMs = 0L

    /** Use MA player timeline as source of truth (matches seek command target). */
    private fun serverPositionMs(rawPositionMs: Long): Long {
        return rawPositionMs.coerceAtLeast(0L)
    }

    fun start() {
        currentOutputRoute = resolveOutputRoute()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        if (collectorJobs.isNotEmpty()) {
            Log.d(TAG, "start() ignored: already running")
            return
        }

        // AudioTrack routing change listener (canonical route detection from actual track)
        sendspinManager.setOnRoutingChangedCallback { checkRouteChange() }

        setupAudioFocus()
        registerNoisyReceiver()

        // Persist callback for clock offset measurements
        sendspinManager.onClockOffsetPersist = { serverMinusWallUs ->
            scope.launch { settingsRepository.setSendspinClockOffsetUs(serverMinusWallUs) }
        }
        // Eager group check before connect so engine starts in correct mode
        scope.launch {
            val ssId = settingsRepository.sendspinClientId.first()
            if (ssId != null) {
                // Wait for player data (up to 5s) if empty on cold start
                val allPlayers = playerRepository.players.value.ifEmpty {
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        playerRepository.players.first { it.isNotEmpty() }
                    } ?: emptyList()
                }
                val self = allPlayers.find { it.playerId == ssId }
                val selfInGroup = self?.groupChilds?.any { it != ssId } == true
                val childOfOther = allPlayers.any { it.playerId != ssId && ssId in it.groupChilds }
                if (selfInGroup || childOfOther) {
                    sendspinManager.setInSyncGroup(true)
                    Log.d(TAG, "Eager group check: inGroup=true before connect")
                } else {
                    sendspinManager.setInSyncGroup(false)
                    Log.d(TAG, "Eager group check: inGroup=false, switching to DIRECT")
                }
            }
            ensureSendspinConnected()
        }
        scope.launch {
            val persistedOffset = settingsRepository.sendspinClockOffsetUs.first()
            sendspinManager.seedClockOffset(persistedOffset)
        }

        collectorJobs += scope.launch {
            settingsRepository.sendspinStaticDelayMs.collect { delayMs ->
                sendspinManager.setStaticDelayMs(delayMs)
            }
        }

        // Collector 1: Observe connection state
        collectorJobs += scope.launch {
            sendspinManager.connectionState.collect { state ->
                val wasStreaming = transportState == SendspinState.STREAMING
                val wasError = transportState == SendspinState.ERROR
                transportState = state
                recomputeAvailability()
                Log.d(TAG, "Sendspin state: $state, isStreaming=$isStreaming, isReady=$isReady, sync=$localSyncState")

                if (!wasStreaming && transportState == SendspinState.STREAMING) {
                    acquireLocks()
                }
                if (wasStreaming && transportState != SendspinState.STREAMING) {
                    releaseLocks()
                    Log.d(TAG, "Sendspin dropped while streaming")
                }

                // Auto-recover Sendspin if it fails while MA WS is still connected
                if (state == SendspinState.ERROR && !wasError) {
                    sendspinManager.onTransportFailure()
                }

                if (state == SendspinState.ERROR &&
                    wsClient.connectionState.value is ConnectionState.Connected
                ) {
                    autoRecoveryJob?.cancel()
                    autoRecoveryJob = scope.launch {
                        delay(2000)
                        if (sendspinManager.connectionState.value == SendspinState.ERROR) {
                            Log.d(TAG, "Sendspin ERROR while MA connected, auto-recovering")
                            ensureSendspinConnected()
                        }
                    }
                }

                val outsideOptimistic = System.currentTimeMillis() >= optimisticUntil
                if (isStreaming && !wasStreaming) {
                    if (!hasAudioFocus) requestAudioFocus()
                    if (outsideOptimistic) {
                        currentIsPlaying = true
                        lastPlayingAtMs = System.currentTimeMillis()
                    }
                } else if (wasStreaming && state == SendspinState.SYNCING) {
                    if (outsideOptimistic) currentIsPlaying = false
                } else if (!isStreaming && state != SendspinState.SYNCING && outsideOptimistic) {
                    currentIsPlaying = false
                }
                notifyStateChanged()
            }
        }

        collectorJobs += scope.launch {
            sendspinManager.syncState
                .collect { state ->
                    localSyncState = state
                    recomputeAvailability()
                    if (state == SyncState.SYNC_ERROR_REBUFFERING &&
                        System.currentTimeMillis() >= optimisticUntil
                    ) {
                        currentIsPlaying = false
                    } else if (state == SyncState.HOLDOVER_PLAYING_FROM_BUFFER &&
                        System.currentTimeMillis() >= optimisticUntil
                    ) {
                        currentIsPlaying = true
                    }
                    notifyStateChanged()
                }
        }

        collectorJobs += scope.launch {
            sendspinManager.serverMetadata.collect { metadata ->
                if (metadata == null) return@collect

                val title = metadata.title?.takeIf { it.isNotBlank() } ?: currentTitle
                val artist = metadata.artist ?: currentArtist
                val album = metadata.album ?: currentAlbum
                val durationMs = metadata.progress?.trackDuration ?: currentDurationMs
                val rawPositionMs = metadata.progress?.trackProgress
                val positionMs = rawPositionMs?.let(::serverPositionMs) ?: currentPositionMs
                val artUrl = metadata.artworkUrl ?: currentArtUrl
                val artChanged = artUrl != currentArtUrl

                currentTitle = title
                currentArtist = artist
                currentAlbum = album
                currentDurationMs = durationMs
                currentPositionMs = positionMs

                if (artChanged) {
                    currentArtUrl = artUrl
                    currentArt = loadArt(artUrl)
                }

                val playbackSpeed = metadata.progress?.playbackSpeed
                if (playbackSpeed != null && System.currentTimeMillis() >= optimisticUntil) {
                    currentIsPlaying = playbackSpeed > 0
                    if (currentIsPlaying) {
                        lastPlayingAtMs = System.currentTimeMillis()
                    }
                }

                notifyMetadataChanged()
                notifyStateChanged()
            }
        }

        collectorJobs += scope.launch {
            playerRepository.discontinuityCommands.collect { command ->
                if (command.playerId != sendspinPlayerId) return@collect
                val reason = when (command.kind) {
                    net.asksakis.massdroidv2.domain.repository.PlayerDiscontinuityCommand.Kind.NEXT -> "next"
                    net.asksakis.massdroidv2.domain.repository.PlayerDiscontinuityCommand.Kind.PREVIOUS -> "previous"
                    net.asksakis.massdroidv2.domain.repository.PlayerDiscontinuityCommand.Kind.SEEK -> "seek"
                }
                Log.d("sendspindbg", "discontinuity command: $reason buf=${sendspinManager.bufferedAudioMs()}ms")
                sendspinManager.expectDiscontinuity(reason)
            }
        }

        // Collector 2: Observe sendspin player metadata from the players list
        collectorJobs += scope.launch {
            playerRepository.players
                .map { list ->
                    val ssId = sendspinPlayerId ?: return@map Pair<net.asksakis.massdroidv2.domain.model.Player?, Boolean>(null, false)
                    val self = list.find { it.playerId == ssId }
                    val selfInGroup = self?.activeGroup != null || self?.groupChilds?.isNotEmpty() == true
                    val childOfOther = list.any { it.playerId != ssId && ssId in it.groupChilds }
                    Pair(self, selfInGroup || childOfOther)
                }
                .distinctUntilChanged()
                .collect { (player, inGroup) ->
                    // Don't decide group state until player data is available
                    if (player == null) return@collect
                    Log.d(TAG, "Group check: inGroup=$inGroup player=${player.displayName}")
                    sendspinManager.setInSyncGroup(inGroup)
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
                    val hasMeaningfulMetadata =
                        media?.title?.isNotBlank() == true ||
                        media?.artist?.isNotBlank() == true ||
                        media?.album?.isNotBlank() == true ||
                        media?.imageUrl != null
                    if (!hasMeaningfulMetadata) {
                        notifyStateChanged()
                        return@collect
                    }
                    val title = media?.title?.takeIf { it.isNotBlank() } ?: currentTitle.ifBlank { "MassDroid Speaker" }
                    val artist = media?.artist?.takeIf { it.isNotBlank() } ?: currentArtist
                    val album = media?.album?.takeIf { it.isNotBlank() } ?: currentAlbum
                    val durationMs = ((media?.duration ?: 0.0) * 1000).toLong()
                    val artUrl = media?.imageUrl

                    val artChanged = artUrl != currentArtUrl

                    currentTitle = title
                    currentArtist = artist
                    currentAlbum = album
                    currentDurationMs = durationMs
                    currentTrackUri = media?.uri ?: currentTrackUri

                    if (artChanged && artUrl != null) {
                        currentArtUrl = artUrl
                        currentArt = loadArt(artUrl)
                    }

                    currentPositionMs = serverPositionMs(((media?.elapsedTime ?: 0.0) * 1000).toLong())

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
                // Init sendspin volume from phone volume so server doesn't reset to 100%
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val phonePercent = if (maxVol > 0) (curVol * 100 / maxVol) else 50
                sendspinManager.setVolume(phonePercent)
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

                    // Always force-restart Sendspin after MA reconnect.
                    // After server reboot, the existing Sendspin WS may be connected
                    // (SYNCING/STREAMING) but the server no longer recognizes the player.
                    val currentSsState = sendspinManager.connectionState.value
                    Log.d(TAG, "MA reconnected, sendspin is $currentSsState, force-restarting")
                    if (url.isNotBlank() && token.isNotBlank()) {
                        sendspinManager.stop()
                        sendspinManager.start(url, token, clientId, "MassDroid")
                    }
                }
                if (isConnected) connectedBefore = true
            }
        }
    }

    fun stop() {
        for (job in collectorJobs) job.cancel(CancellationException("Sendspin stop"))
        collectorJobs.clear()
        autoRecoveryJob?.cancel()
        autoRecoveryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
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
        try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
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
            if (!isReady && !ensureSendspinConnected()) {
                currentIsPlaying = false
                notifyStateChanged()
                return@launch
            }
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
            if (wantPlay && !isReady && !ensureSendspinConnected()) {
                currentIsPlaying = false
                notifyStateChanged()
                return@launch
            }
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
                        Log.d(TAG, "Audio focus gained, isStreaming=$isStreaming isReady=$isReady")
                        hasAudioFocus = true
                        if (isStreaming) {
                            sendspinManager.resumeAudio()
                            sendspinManager.restoreVolume()
                        } else if (isReady) {
                            // After phone call: server may have stopped streaming.
                            // Resume by sending play command.
                            sendspinManager.resumeAudio()
                            val id = sendspinPlayerId
                            if (id != null) {
                                scope.launch { playerRepository.play(id) }
                            }
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
                        Log.d(TAG, "Audio focus: ducking (pre-duck vol=${sendspinManager.currentVolume})")
                        sendspinManager.duck()
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

    // region Format + connection helpers

    private suspend fun applyPreferredFormatForCurrentNetwork(playerId: String) {
        try {
            val formatName = settingsRepository.sendspinAudioFormat.first()
            val format = net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.fromStored(formatName)
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ?: false
            val apiValue = format.toApiValue(isWifi)
            playerRepository.savePlayerConfig(playerId, mapOf("preferred_sendspin_format" to apiValue))
            Log.d(TAG, "Applied format $format ($apiValue) for ${if (isWifi) "WiFi" else "Mobile"}")
        } catch (e: Exception) {
            Log.w(TAG, "Format apply failed: ${e.message}")
        }
    }

    // region Sendspin connection helpers

    private suspend fun ensureSendspinConnected(): Boolean {
        reconnectJob?.let { existing ->
            if (existing.isActive) {
                Log.d(TAG, "Reconnect already in progress, waiting")
                return existing.await()
            }
            reconnectJob = null
        }

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

        val job = scope.async {
            applyPreferredFormatForCurrentNetwork(clientId)
            Log.d(TAG, "Restarting sendspin for playback (was $state)")
            sendspinManager.start(url, token, clientId, "MassDroid")

            withTimeoutOrNull(10000) {
                sendspinManager.connectionState
                    .first { it == SendspinState.SYNCING || it == SendspinState.STREAMING }
            } != null
        }
        reconnectJob = job
        return try {
            job.await()
        } finally {
            if (reconnectJob === job) reconnectJob = null
        }
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

    private fun recomputeAvailability() {
        isStreaming = transportState == SendspinState.STREAMING
        isReady = (transportState == SendspinState.SYNCING || transportState == SendspinState.STREAMING) &&
            localSyncState != SyncState.SYNC_ERROR_REBUFFERING && localSyncState != SyncState.IDLE
    }

    // endregion
}
