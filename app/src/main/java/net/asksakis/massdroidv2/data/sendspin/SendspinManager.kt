package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SendspinManager(
    private val client: SendspinClient,
    private val audio: AudioStreamManager,
) {
    companion object {
        private const val TAG = "SendspinMgr"
        private const val HEARTBEAT_INTERVAL_MS = 2000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var timeSyncJob: Job? = null
    private var messageJob: Job? = null
    private var binaryJob: Job? = null
    private var stateJob: Job? = null
    private val clockSynchronizer = ClockSynchronizer()

    // Clock sync: Kalman filter is the primary offset source
    @Volatile var clockSynced: Boolean = false; private set

    private val _connectionState = MutableStateFlow(SendspinState.DISCONNECTED)
    val connectionState: StateFlow<SendspinState> = _connectionState.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _streamCodec = MutableStateFlow<String?>(null)
    val streamCodec: StateFlow<String?> = _streamCodec.asStateFlow()
    private val _syncState = MutableStateFlow(audio.syncState)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    private val _serverMetadata = MutableStateFlow<ServerMetadataPayload?>(null)
    val serverMetadata: StateFlow<ServerMetadataPayload?> = _serverMetadata.asStateFlow()

    var currentVolume = 100
        private set
    private var muted = false
    @Volatile private var lastSentSyncState = ""
    @Volatile private var lastCallbackSentAtMs = 0L
    private var clientId: String = ""
    private var clientName: String = ""

    fun start(url: String, token: String, clientId: String, clientName: String) {
        this.clientId = clientId
        this.clientName = clientName
        _enabled.value = true
        _connectionState.value = SendspinState.CONNECTING

        // Cancel ALL existing jobs including heartbeat/timesync from previous session
        // to prevent them from sending non-auth messages on the new WebSocket
        heartbeatJob?.cancel()
        heartbeatJob = null
        timeSyncJob?.cancel()
        timeSyncJob = null

        // Observe client state changes
        stateJob?.cancel()
        stateJob = scope.launch {
            client.state.collect { state ->
                _connectionState.value = state
            }
        }

        // Handle text messages
        messageJob?.cancel()
        messageJob = scope.launch {
            client.textMessages.collect { incoming ->
                handleIncoming(incoming)
            }
        }

        // Handle binary messages (audio)
        binaryJob?.cancel()
        var binaryCount = 0
        binaryJob = scope.launch {
            client.binaryMessages.collect { data ->
                binaryCount++
                val gen = audio.currentConfigureGeneration()
                if (binaryCount <= 3 || binaryCount % 1000 == 0) {
                    Log.d(TAG, "Binary message #$binaryCount: ${data.size} bytes")
                }
                audio.onBinaryMessage(data, gen)
            }
        }

        client.connect(url, token, clientId)
    }

    private fun handleIncoming(incoming: SendspinIncoming) {
        when (incoming) {
            is SendspinIncoming.AuthOk -> {
                Log.d(TAG, "Auth OK, sending hello")
                client.updateState(SendspinState.HANDSHAKING)
                client.sendHello(clientId, clientName)
            }

            is SendspinIncoming.AuthError -> {
                Log.e(TAG, "Auth error: ${incoming.message}")
                client.updateState(SendspinState.ERROR)
            }

            is SendspinIncoming.ServerHello -> {
                Log.d(TAG, "Server hello received")
                client.updateState(SendspinState.SYNCING)
                setupSyncStateCallback()
                client.sendClientState(
                    volume = currentVolume,
                    muted = muted,
                    syncState = currentSyncStatePayloadValue()
                )
                startHeartbeat()
                startTimeSync()
            }

            is SendspinIncoming.ServerTime -> {
                val t1 = incoming.payload.clientTransmitted
                val t2 = incoming.payload.serverReceived
                val t3 = incoming.payload.serverTransmitted
                val t4 = System.nanoTime() / 1000
                val rttUs = (t4 - t1) - (t3 - t2)
                clockSynchronizer.processTimeResponse(
                    clientTransmittedUs = t1,
                    serverReceivedUs = t2,
                    serverTransmittedUs = t3,
                    clientReceivedUs = t4
                )
                clockSynced = clockSynchronizer.isSynced()
                val count = clockSynchronizer.currentSampleCount()
                if (count <= 5 || count % 20 == 0) {
                    Log.d(TAG, "Clock sync: offset=${clockSynchronizer.currentOffsetUs()}us " +
                        "error=${clockSynchronizer.errorUs()}us rtt=${rttUs}us " +
                        "synced=$clockSynced samples=$count")
                }
            }

            is SendspinIncoming.GroupUpdate -> {
                // Group state updates are informative only for now.
            }

            is SendspinIncoming.StreamStart -> {
                val info = incoming.payload.player
                Log.d(TAG, "Stream start: ${info.codec} ${info.sampleRate}Hz/${info.bitDepth}bit ${info.channels}ch, " +
                        "codecHeader=${info.codecHeader != null}")
                audio.configure(info.codec, info.sampleRate, info.channels, info.bitDepth, info.codecHeader)
                audio.setVolume(if (muted) 0f else perceptualGain(currentVolume))
                _streamCodec.value = info.codec.uppercase()
                client.updateState(SendspinState.STREAMING)
            }

            is SendspinIncoming.StreamEnd -> {
                audio.onStreamEnd()
            }

            is SendspinIncoming.StreamClear -> {
                Log.d(TAG, "Stream clear")
                _serverMetadata.value = null
                audio.clearBuffer()
            }

            is SendspinIncoming.ServerState -> {
                _serverMetadata.value = incoming.payload.metadata
            }

            is SendspinIncoming.ServerCommand -> {
                handleCommand(incoming.payload)
            }

            is SendspinIncoming.Unknown -> {
                Log.d(TAG, "Unknown message type: ${incoming.type}")
            }
        }
    }

    private fun handleCommand(payload: ServerCommandPayload) {
        val playerCmd = payload.player ?: return
        when (playerCmd.command) {
            "volume" -> {
                val vol = playerCmd.volume ?: return
                currentVolume = vol
                if (!muted) audio.setVolume(perceptualGain(vol))
                Log.d(TAG, "Volume set to $vol")
            }
            "mute" -> {
                val m = playerCmd.mute ?: true
                muted = m
                audio.setMuted(m)
                Log.d(TAG, "Mute set to $m")
            }
            else -> {
                Log.d(TAG, "Unknown command: ${playerCmd.command}")
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (System.currentTimeMillis() - lastCallbackSentAtMs < 500L) continue
                client.sendClientState(
                    volume = currentVolume,
                    muted = muted,
                    syncState = currentSyncStatePayloadValue()
                )
            }
        }
    }

    private fun startTimeSync() {
        timeSyncJob?.cancel()
        val previousOffsetUs = clockSynchronizer.currentOffsetUs()
        clockSynchronizer.softReset(previousOffsetUs)
        clockSynced = previousOffsetUs != 0L
        audio.clockSynchronizer = clockSynchronizer
        timeSyncJob = scope.launch {
            while (true) {
                val clientTimeUs = System.nanoTime() / 1000
                client.sendTimeRequest(clientTimeUs)
                delay(clockSynchronizer.currentSyncIntervalMs)
            }
        }
    }

    fun pauseAudio() {
        audio.setPaused(true)
    }

    fun resumeAudio() {
        audio.setPaused(false)
    }

    private fun perceptualGain(volume: Int): Float {
        val linear = (volume.coerceIn(0, 100)) / 100f
        return linear * linear
    }

    fun setVolume(volume: Int) {
        currentVolume = volume
        if (!muted) audio.setVolume(perceptualGain(volume))
    }

    fun duck() {
        // Lower AudioTrack gain without changing currentVolume, so restoreVolume() recovers
        val originalGain = perceptualGain(currentVolume)
        val duckedGain = originalGain * 0.5f
        Log.d(TAG, "Duck: vol=$currentVolume gain=$originalGain -> ducked=$duckedGain")
        if (!muted) audio.setVolume(duckedGain)
    }

    fun restoreVolume() {
        if (!muted) audio.setVolume(perceptualGain(currentVolume))
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        audio.setMuted(muted)
    }

    fun requestFormat(codec: String, sampleRate: Int = 48000, bitDepth: Int = 16, channels: Int = 2) {
        Log.d(TAG, "Requesting format change: $codec ${sampleRate}Hz/${bitDepth}bit ${channels}ch")
        client.sendRequestFormat(codec, sampleRate, bitDepth, channels)
    }

    fun setStaticDelayMs(delayMs: Int) {
        audio.staticDelayMs = delayMs.coerceIn(0, 5000)
        Log.d(TAG, "Static delay set to ${audio.staticDelayMs}ms")
    }

    fun expectDiscontinuity(reason: String) {
        audio.expectDiscontinuity(reason)
    }

    fun onTransportFailure() {
        audio.onTransportFailure()
        _syncState.value = audio.syncState
    }

    fun bufferedAudioMs(): Long = audio.bufferDurationMs()

    fun bufferedAudioBytes(): Long = audio.bufferedBytes()

    fun setupSyncStateCallback() {
        audio.onSyncStateChanged = { state ->
            _syncState.value = state
            val stateStr = when (state) {
                SyncState.IDLE -> "synchronized"
                SyncState.SYNCHRONIZED -> "synchronized"
                SyncState.HOLDOVER_PLAYING_FROM_BUFFER -> "synchronized"
                SyncState.SYNC_ERROR_REBUFFERING -> "error"
            }
            if (stateStr != lastSentSyncState) {
                lastSentSyncState = stateStr
                lastCallbackSentAtMs = System.currentTimeMillis()
                Log.d(TAG, "Sending client/state: $stateStr (from $state)")
                client.sendClientState(volume = currentVolume, muted = muted, syncState = stateStr)
            }
        }
    }

    private fun currentSyncStatePayloadValue(): String {
        return when (_syncState.value) {
            SyncState.IDLE -> "synchronized"
            SyncState.SYNCHRONIZED -> "synchronized"
            SyncState.HOLDOVER_PLAYING_FROM_BUFFER -> "synchronized"
            SyncState.SYNC_ERROR_REBUFFERING -> "error"
        }
    }

    fun stop() {
        _enabled.value = false
        heartbeatJob?.cancel()
        timeSyncJob?.cancel()
        messageJob?.cancel()
        binaryJob?.cancel()
        stateJob?.cancel()
        heartbeatJob = null
        timeSyncJob = null
        messageJob = null
        binaryJob = null
        stateJob = null
        client.disconnect()
        audio.onSyncStateChanged = null
        audio.release()
        _connectionState.value = SendspinState.DISCONNECTED
        _streamCodec.value = null
        _syncState.value = audio.syncState
        _serverMetadata.value = null
        Log.d(TAG, "Sendspin stopped")
    }
}
