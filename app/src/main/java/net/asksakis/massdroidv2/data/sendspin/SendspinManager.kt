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
    private val syncEngine: SendspinSyncEngine,
    private val directEngine: SendspinDirectEngine,
) {
    @Volatile private var activeEngine: SendspinAudioEngine = directEngine
    private val audio: SendspinAudioEngine get() = activeEngine
    companion object {
        private const val TAG = "SendspinMgr"
        private const val HEARTBEAT_INTERVAL_MS = 2000L
        private const val ENGINE_SWITCH_DEBOUNCE_MS = 2000L
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
    var onClockOffsetPersist: ((serverMinusWallUs: Long) -> Unit)? = null
    private var clockOffsetPersistCount = 0


    private val _connectionState = MutableStateFlow(SendspinState.DISCONNECTED)
    val connectionState: StateFlow<SendspinState> = _connectionState.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _streamCodec = MutableStateFlow<String?>(null)
    val streamCodec: StateFlow<String?> = _streamCodec.asStateFlow()
    private val _networkMode = MutableStateFlow("WiFi")
    val networkMode: StateFlow<String> = _networkMode.asStateFlow()
    private val _syncState = MutableStateFlow(audio.syncState)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    data class SyncSample(val errorMs: Float, val outputLatencyMs: Float, val filterErrorMs: Float)
    private val _syncHistory = MutableStateFlow<List<SyncSample>>(emptyList())
    val syncHistory: StateFlow<List<SyncSample>> = _syncHistory.asStateFlow()
    private val _serverMetadata = MutableStateFlow<ServerMetadataPayload?>(null)
    val serverMetadata: StateFlow<ServerMetadataPayload?> = _serverMetadata.asStateFlow()

    var currentVolume = 100
        private set
    private var muted = false
    @Volatile private var hasActiveProtocolStream = false
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
                sendCurrentState(currentSyncStatePayloadValue())
                startHeartbeat()
                // Time sync only needed for SyncEngine (group mode)
                if (activeEngine === syncEngine) {
                    startTimeSync()
                }
            }

            is SendspinIncoming.ServerTime -> {
                val t1 = incoming.payload.clientTransmitted
                val t2 = incoming.payload.serverReceived
                val t3 = incoming.payload.serverTransmitted
                val t4 = System.nanoTime() / 1000
                val rttUs = (t4 - t1) - (t3 - t2)
                // Reject absurd RTT only during initial convergence (first 5 samples)
                if (rttUs > 150_000L && clockSynchronizer.currentSampleCount() < 5) {
                    Log.d(TAG, "Clock sync: REJECTED rtt=${rttUs}us (startup, samples=${clockSynchronizer.currentSampleCount()})")
                    return
                }
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
                        "ready=${clockSynchronizer.isReadyForPlaybackStart()} samples=$count")
                }
                // Persist offset every ~30 samples for next startup seed
                clockOffsetPersistCount++
                if (clockOffsetPersistCount >= 100 && clockSynchronizer.errorUs() < 2_000) {
                    clockOffsetPersistCount = 0
                    val nanoUs = System.nanoTime() / 1000
                    val wallUs = System.currentTimeMillis() * 1000L
                    val serverMinusWall = clockSynchronizer.currentOffsetUs() + nanoUs - wallUs
                    onClockOffsetPersist?.invoke(serverMinusWall)
                }
            }

            is SendspinIncoming.GroupUpdate -> {
                // Informational only. Group detection via setInSyncGroup() from player state.
            }

            is SendspinIncoming.StreamStart -> {
                val info = incoming.payload.player
                val startType = if (hasActiveProtocolStream) ProtocolStartType.CONTINUATION else ProtocolStartType.NEW_STREAM
                Log.d("sendspindbg", ">>> stream/start $startType ${info.codec} ${info.sampleRate}Hz buf=${audio.bufferDurationMs()}ms sync=${audio.syncState}")
                hasActiveProtocolStream = true
                audio.configure(info.codec, info.sampleRate, info.channels, info.bitDepth, info.codecHeader, startType)
                audio.setVolume(if (muted) 0f else perceptualGain(currentVolume))
                _streamCodec.value = info.codec.uppercase()
                client.updateState(SendspinState.STREAMING)
            }

            is SendspinIncoming.StreamEnd -> {
                Log.d("sendspindbg", ">>> stream/end proto_active=$hasActiveProtocolStream buf=${audio.bufferDurationMs()}ms sync=${audio.syncState}")
                hasActiveProtocolStream = false
                audio.onStreamEnd()
            }

            is SendspinIncoming.StreamClear -> {
                Log.d("sendspindbg", ">>> stream/clear buf=${audio.bufferDurationMs()}ms sync=${audio.syncState}")
                // stream/clear: clear buffers but keep stream context active
                _serverMetadata.value = null
                audio.clearBuffer()
            }

            is SendspinIncoming.ServerState -> {
                Log.d("sendspindbg", ">>> server/state metadata=${incoming.payload.metadata != null}")
                _serverMetadata.value = incoming.payload.metadata
            }

            is SendspinIncoming.ServerCommand -> {
                Log.d("sendspindbg", ">>> server/command ${incoming.payload.player?.command}")
                handleCommand(incoming.payload)
            }

            is SendspinIncoming.Unknown -> {
                Log.d("sendspindbg", ">>> unknown type: ${incoming.type}")
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
                sendCurrentState(currentSyncStatePayloadValue())
            }
        }
    }

    /**
     * Seed Kalman from persisted value. We store server_time - wallClock (reboot-safe),
     * then reconstruct the nanoTime-based offset on restore.
     */
    fun seedClockOffset(serverMinusWallUs: Long) {
        if (serverMinusWallUs == 0L) return
        val nowNanoUs = System.nanoTime() / 1000
        val nowWallUs = System.currentTimeMillis() * 1000L
        val estimatedOffset = serverMinusWallUs - nowNanoUs + nowWallUs
        clockSynchronizer.softReset(estimatedOffset, preserveDrift = false)
        clockSynced = true
        Log.d(TAG, "Clock offset seeded: ${estimatedOffset}us (from serverMinusWall=${serverMinusWallUs}us)")
    }

    private fun startTimeSync() {
        timeSyncJob?.cancel()
        val previousOffsetUs = clockSynchronizer.currentOffsetUs()
        if (previousOffsetUs != 0L) {
            clockSynchronizer.softReset(previousOffsetUs)
            clockSynced = true
        }
        clockOffsetPersistCount = 0
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

    private var lastEngineSwitchMs = 0L

    fun setInSyncGroup(grouped: Boolean) {
        val target = if (grouped) syncEngine else directEngine
        if (target === activeEngine) return
        // Debounce: ignore rapid group state flicker
        val now = System.currentTimeMillis()
        if (now - lastEngineSwitchMs < ENGINE_SWITCH_DEBOUNCE_MS) {
            Log.d(TAG, "Engine switch debounced (${now - lastEngineSwitchMs}ms)")
            return
        }
        lastEngineSwitchMs = now
        Log.d(TAG, "Switching engine: ${if (grouped) "SyncEngine" else "DirectEngine"}")
        activeEngine.onSyncStateChanged = null
        activeEngine.onSyncSample = null
        activeEngine.release()
        activeEngine = target
        activeEngine.clockSynchronizer = clockSynchronizer
        (target as? SendspinDirectEngine)?.setCellularTransport(isCellularTransport)
        setupSyncStateCallback()
        _syncState.value = activeEngine.syncState
        // Start/stop time sync based on engine
        if (grouped) {
            startTimeSync()
        } else {
            timeSyncJob?.cancel()
            timeSyncJob = null
        }
    }

    fun seedOutputLatency(persistedUs: Long) {
        audio.seedOutputLatency(persistedUs)
    }

    fun setOutputLatencyPersistCallback(callback: (Long) -> Unit) {
        audio.onOutputLatencyMeasured = callback
    }

    fun setStaticDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(0, 5000)
        val oldDelay = audio.staticDelayMs
        if (clamped == oldDelay) return
        audio.staticDelayMs = clamped
        audio.shiftAnchorForDelayChange(clamped - oldDelay)
        // Value changes immediately (affects targetLocalPlayUs, late-frame detection).
        // Full alignment effect at next startup (seek/track change).
        // No flush: flushing causes buffer storm and desync.
        // Notify server of new delay so it adjusts buffer headroom
        sendCurrentState(currentSyncStatePayloadValue())
        Log.d(TAG, "Static delay: ${oldDelay}ms -> ${clamped}ms")
    }

    @Volatile private var isCellularTransport = false

    fun setCellularHint(cellular: Boolean) {
        isCellularTransport = cellular
        _networkMode.value = if (cellular) "Mobile" else "WiFi"
        (activeEngine as? SendspinDirectEngine)?.setCellularTransport(cellular)
    }

    private fun sendCurrentState(syncState: String) {
        // Send only user-specified delay to server. The hw pipeline latency
        // is compensated locally in targetLocalPlayUs() and should NOT be
        // reported to the server (it's internal AudioTrack compensation).
        client.sendClientState(
            volume = currentVolume,
            muted = muted,
            syncState = syncState,
            staticDelayMs = audio.staticDelayMs.coerceAtLeast(0)
        )
    }

    fun expectDiscontinuity(reason: String) {
        Log.d("sendspindbg", "expectDiscontinuity($reason) buf=${audio.bufferDurationMs()}ms sync=${audio.syncState}")
        audio.expectDiscontinuity(reason)
        Log.d("sendspindbg", "expectDiscontinuity($reason) DONE buf=${audio.bufferDurationMs()}ms sync=${audio.syncState}")
    }

    fun onTransportFailure() {
        audio.onTransportFailure()
        _syncState.value = audio.syncState
    }

    fun bufferedAudioMs(): Long = audio.bufferDurationMs()

    fun bufferedAudioBytes(): Long = audio.bufferedBytes()

    fun setupSyncStateCallback() {
        audio.onSyncSample = { errorMs, outLatMs, filterErrMs ->
            val sample = SyncSample(errorMs, outLatMs, filterErrMs)
            val history = _syncHistory.value.toMutableList()
            history.add(sample)
            if (history.size > 60) history.removeAt(0)
            _syncHistory.value = history
        }
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
                Log.d("sendspindbg", ">>> client/state: $stateStr (from $state) buf=${audio.bufferDurationMs()}ms")
                sendCurrentState(stateStr)
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
        hasActiveProtocolStream = false
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
        audio.onSyncSample = null
        _syncHistory.value = emptyList()
        syncEngine.release()
        directEngine.release()
        _connectionState.value = SendspinState.DISCONNECTED
        _streamCodec.value = null
        _syncState.value = audio.syncState
        _serverMetadata.value = null
        Log.d(TAG, "Sendspin stopped")
    }
}
