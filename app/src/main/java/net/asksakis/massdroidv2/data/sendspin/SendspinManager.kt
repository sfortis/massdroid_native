package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.SessionEventBus

class SendspinManager(
    private val client: SendspinClient,
    private val syncEngine: SendspinSyncEngine,
    private val directEngine: SendspinDirectEngine,
    sessionEventBus: SessionEventBus,
) {
    // Active engine. Solo (DIRECT) by default; swapped to the grouped engine on
    // a group join and back on leave. The two are fully separate instances so
    // neither carries the other's timeline state across a group boundary
    // (which is already a hard relock).
    @Volatile private var engine: SendspinAudioEngine = directEngine
    private val audio: SendspinAudioEngine get() = engine
    private var routingChangedCallback: (() -> Unit)? = null
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
    var onClockOffsetPersist: ((serverMinusWallUs: Long) -> Unit)? = null
    private var clockOffsetPersistCount = 0


    private val _connectionState = MutableStateFlow(SendspinState.DISCONNECTED)
    val connectionState: StateFlow<SendspinState> = _connectionState.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    // Server-pushed volume and mute events. Consumed by the
    // SendspinVolumeCoordinator to translate MA-side values into the phone's
    // STREAM_MUSIC so local Sendspin playback is controlled by a single gain
    // stage (the system volume). replay=1 + DROP_OLDEST so the LATEST value is
    // never lost: a tryEmit during a brief collector gap (coordinator start/stop
    // cycle) or a rapid multi-step ramp used to be silently dropped on a
    // capacity-bounded, replay-0 flow, leaving STREAM_MUSIC out of sync. Volume
    // is idempotent (only the latest target matters), so coalescing is correct.
    private val _serverVolumeEvents = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val serverVolumeEvents: SharedFlow<Int> = _serverVolumeEvents.asSharedFlow()
    private val _serverMuteEvents = MutableSharedFlow<Boolean>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val serverMuteEvents: SharedFlow<Boolean> = _serverMuteEvents.asSharedFlow()

    private val _streamCodec = MutableStateFlow<String?>(null)
    val streamCodec: StateFlow<String?> = _streamCodec.asStateFlow()

    /**
     * Snapshot of the most recent Sendspin stream/start payload. Exposed so
     * the UI can show the actual output format (post server re-encode) next
     * to the source-side info. `null` between tracks or before the first
     * stream/start.
     */
    data class StreamFormatSnapshot(
        val codec: String,
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
    )
    private val _streamFormat = MutableStateFlow<StreamFormatSnapshot?>(null)
    val streamFormat: StateFlow<StreamFormatSnapshot?> = _streamFormat.asStateFlow()
    private val _networkMode = MutableStateFlow("WiFi")
    val networkMode: StateFlow<String> = _networkMode.asStateFlow()
    private val _syncState = MutableStateFlow(audio.syncState)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    data class SyncSample(
        val errorMs: Float,
        val outputLatencyMs: Float,
        val filterErrorMs: Float,
        val dacAbsoluteMs: Float? = null,
    )
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

    init {
        scope.launch {
            sessionEventBus.resets.collect {
                if (_enabled.value || _connectionState.value != SendspinState.DISCONNECTED) {
                    Log.d(TAG, "Session reset received, stopping sendspin")
                    stop()
                }
            }
        }
    }

    /**
     * Begin the Sendspin lifecycle. The [credentialsProvider] is called by the
     * client on every (re)connect attempt so a rotated MA token is picked up
     * automatically. The client owns its own exponential backoff and retry
     * cap; this method is safe to call repeatedly (it will just refresh the
     * provider and trigger an immediate retry).
     */
    fun start(
        clientId: String,
        clientName: String,
        credentialsProvider: suspend () -> SendspinClient.Credentials?
    ) {
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

        // Handle ordered protocol messages. Text control messages and binary audio frames
        // must be processed in WebSocket order for seek/stream-clear correctness.
        messageJob?.cancel()
        binaryJob?.cancel()
        binaryJob = null
        var binaryCount = 0
        messageJob = scope.launch {
            client.messages.collect { message ->
                when (message) {
                    is SendspinMessage.Text -> handleIncoming(message.incoming)
                    is SendspinMessage.Binary -> {
                        binaryCount++
                        val gen = audio.currentConfigureGeneration()
                        if (binaryCount <= 3 || binaryCount % 1000 == 0) {
                            Log.d(TAG, "Binary message #$binaryCount: ${message.data.size} bytes")
                        }
                        audio.onBinaryMessage(message.data, gen)
                    }
                }
            }
        }

        client.start(clientId, credentialsProvider)
    }

    /**
     * Force the transport to reconnect with fresh credentials. Used by the
     * orchestrator when MA has just reconnected: the existing Sendspin
     * session may have been invalidated server-side during the outage, so we
     * re-handshake without tearing down the manager's collectors.
     *
     * Also recovers from terminal ERROR (auth_error, credentials-exhaustion):
     * the client side rearms its lifecycle as long as a credentials provider
     * was registered, so a fresh MA session is enough to wake Sendspin back
     * up. This is the primary mitigation for #43.
     */
    fun refresh() {
        if (!_enabled.value) {
            Log.d(TAG, "refresh ignored: manager never started")
            return
        }
        Log.d(TAG, "refresh: forwarding to client")
        client.refresh()
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
                // Always run time sync, even in DIRECT mode. Keeps the Kalman
                // clock warm so group join has instant precision (no 2-3s wait).
                startTimeSync()
            }

            is SendspinIncoming.ServerTime -> {
                val t1 = incoming.payload.clientTransmitted
                val t2 = incoming.payload.serverReceived
                val t3 = incoming.payload.serverTransmitted
                // T4 stamped at the WS onMessage callback (earliest point); fall
                // back to now only if missing. Avoids coroutine-dispatch delay
                // biasing the NTP offset low (which made us play ~tens of ms late).
                val t4 = incoming.clientReceivedUs.takeIf { it > 0L } ?: (System.nanoTime() / 1000)
                val rttUs = (t4 - t1) - (t3 - t2)
                // Reject absurd RTT only during initial convergence (first 5 samples).
                // Tell the synchronizer about each reject so it can back off the
                // request cadence — otherwise an overloaded server holds us in
                // the 300 ms send loop forever (every reject leaves
                // currentSampleCount untouched, so the fast-sync gate never lifts).
                if (rttUs > 150_000L && clockSynchronizer.currentSampleCount() < 5) {
                    Log.d(TAG, "Clock sync: REJECTED rtt=${rttUs}us (startup, samples=${clockSynchronizer.currentSampleCount()})")
                    clockSynchronizer.markStartupRejected()
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
                // Group state managed by player list collector in SendspinAudioController.
                // group/update arrives for both solo and multi groups, can't distinguish here.
                Log.d("sendspindbg", ">>> group/update")
            }

            is SendspinIncoming.StreamStart -> {
                val info = incoming.payload.player
                val startType = if (hasActiveProtocolStream) ProtocolStartType.CONTINUATION else ProtocolStartType.NEW_STREAM
                Log.d("sendspindbg", ">>> stream/start $startType ${info.codec} ${info.sampleRate}Hz buf=${audio.bufferDurationMs()}ms sync=${audio.syncState}")
                hasActiveProtocolStream = true
                audio.configure(info.codec, info.sampleRate, info.channels, info.bitDepth, info.codecHeader, startType)
                // Local playback gain is now STREAM_MUSIC-driven via
                // LocalSpeakerVolumeBridge, so the engine stays at its default
                // 1.0 AudioTrack gain. Only the mute state needs to propagate.
                val engineMuted = (engine as? SendspinPlaybackEngine)?.syncMuted ?: false
                if (!engineMuted) {
                    audio.setMuted(muted)
                }
                _streamCodec.value = info.codec.uppercase()
                _streamFormat.value = StreamFormatSnapshot(
                    codec = info.codec.uppercase(),
                    sampleRate = info.sampleRate,
                    bitDepth = info.bitDepth,
                    channels = info.channels,
                )
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
                // Hand off to the local volume bridge; STREAM_MUSIC is now the
                // single source of truth for local playback gain.
                _serverVolumeEvents.tryEmit(vol)
                Log.d(TAG, "Volume set to $vol (bridged to STREAM_MUSIC)")
            }
            "mute" -> {
                val m = playerCmd.mute ?: true
                muted = m
                _serverMuteEvents.tryEmit(m)
                // Also tell the engine: it uses this to silence/unsilence the
                // AudioTrack during fade-in and sync-mute transitions.
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
        // High covariance: persisted offset is a rough hint, may be stale after sleep/reboot.
        // Fresh NTP samples will quickly dominate. 1e9 = ~31ms error -> filter converges in 3-4 samples.
        clockSynchronizer.softReset(estimatedOffset, preserveDrift = false, initialCovariance = 1_000_000_000.0)
        clockSynced = true
        Log.d(TAG, "Clock offset seeded: ${estimatedOffset}us (from serverMinusWall=${serverMinusWallUs}us)")
    }

    private fun startTimeSync() {
        timeSyncJob?.cancel()
        val previousOffsetUs = clockSynchronizer.currentOffsetUs()
        if (previousOffsetUs != 0L) {
            clockSynchronizer.softReset(
                previousOffsetUs = previousOffsetUs,
                initialCovariance = 1_000_000_000.0,
            )
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

    /** True when the active engine is the solo (DIRECT) one (no group sync). */
    fun isSoloEngine(): Boolean = engine === directEngine

    /**
     * Freeze the output across a transient interruption WITHOUT dropping the
     * ring (solo only): the buffered audio survives and resumes instantly. The
     * server feeds realtime after a flush and never rebuilds the deep buffer, so
     * freezing is the only way to keep it. See [SendspinPlaybackEngine.freezeOutput].
     */
    fun freezeOutput() {
        (engine as? SendspinPlaybackEngine)?.freezeOutput()
    }

    fun unfreezeOutput() {
        (engine as? SendspinPlaybackEngine)?.unfreezeOutput()
    }

    private fun perceptualGain(volume: Int): Float {
        val linear = (volume.coerceIn(0, 100)) / 100f
        return linear * linear
    }

    /**
     * Track the MA-side volume value locally but don't apply it as engine gain.
     * The local gain stage is STREAM_MUSIC, driven by [LocalSpeakerVolumeBridge].
     */
    fun setVolume(volume: Int) {
        currentVolume = volume
    }

    fun duck() {
        // Transient AudioTrack attenuation. Keeps currentVolume unchanged so
        // restoreVolume() can bring us back to full gain.
        if (!muted) audio.setVolume(0.5f)
        Log.d(TAG, "Duck -> 0.5 AudioTrack gain")
    }

    fun restoreVolume() {
        if (!muted) audio.setVolume(1f)
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        audio.setMuted(muted)
    }

    fun requestFormat(codec: String, sampleRate: Int = 48000, bitDepth: Int = 16, channels: Int = 2) {
        Log.d(TAG, "Requesting format change: $codec ${sampleRate}Hz/${bitDepth}bit ${channels}ch")
        client.sendRequestFormat(codec, sampleRate, bitDepth, channels)
    }

    fun setInSyncGroup(grouped: Boolean) {
        val target: SendspinAudioEngine = if (grouped) syncEngine else directEngine
        if (!grouped && timeSyncJob != null) {
            // DIRECT (solo) is a pure FIFO with no peer to phase-lock to: it never
            // uses the clock (computeLocalPlan anchors locally on serverTs deltas).
            // Stop the periodic time-sync to save CPU + WS traffic while solo;
            // it is restarted on a group join below.
            Log.d(TAG, "DIRECT solo: stopping clock time-sync (not used without a group)")
            timeSyncJob?.cancel()
            timeSyncJob = null
        }
        if (engine === target) {
            // Already on the right engine. Keep the clock wired/running for sync.
            if (grouped) {
                audio.clockSynchronizer = clockSynchronizer
                if (timeSyncJob?.isActive != true && client.state.value != SendspinState.DISCONNECTED) {
                    startTimeSync()
                }
            }
            return
        }

        Log.d(TAG, "Engine swap: ${engine::class.simpleName} -> ${target::class.simpleName} (grouped=$grouped)")
        // Carry over the user/route settings, tear down the outgoing engine
        // (stops its playback thread + AudioTrack), then wire up the incoming
        // one. The next stream/start (configure) sets it up fresh — a group
        // join/leave is already a hard relock boundary.
        val carrySyncDelay = engine.syncDelayMs
        val carryAcoustic = engine.routeAcousticExtraUs
        engine.onSyncStateChanged = null
        engine.onSyncSample = null
        engine.release()

        engine = target
        target.clockSynchronizer = clockSynchronizer
        target.syncDelayMs = carrySyncDelay
        target.routeAcousticExtraUs = carryAcoustic
        (target as? SendspinPlaybackEngine)?.onRoutingChanged = routingChangedCallback
        setupSyncStateCallback()
        _syncState.value = target.syncState
        if (grouped && timeSyncJob?.isActive != true && client.state.value != SendspinState.DISCONNECTED) {
            startTimeSync()
        }
        Log.d(TAG, "Engine swap done: clock samples=${clockSynchronizer.currentSampleCount()} error=${clockSynchronizer.errorUs()}us")
    }

    /** Get the actual routed device type from the AudioTrack, or null if unavailable. */
    fun getRoutedDeviceType(): Int? = audio.getRoutedDeviceType()

    fun setOnRoutingChangedCallback(callback: () -> Unit) {
        routingChangedCallback = callback
        (engine as? SendspinPlaybackEngine)?.onRoutingChanged = callback
    }

    fun onOutputRouteChanged(reason: String) {
        Log.d(TAG, "Output route changed: $reason")
        audio.onOutputRouteChanged(reason)
    }

    fun setSyncDelayMs(delayMs: Int) {
        // Client-side UX nudge: -1000..+1000 ms, positive = play later
        // (intuitive sign, matches Music Assistant web UI). Not the same as
        // the Sendspin spec static_delay_ms — that is derived from the
        // acoustic calibration when reporting client/state to the server.
        val clamped = delayMs.coerceIn(-1000, 1000)
        val oldDelay = audio.syncDelayMs
        if (clamped == oldDelay) return
        audio.syncDelayMs = clamped
        audio.shiftAnchorForSyncDelayChange(clamped - oldDelay)
        // Value changes immediately (affects targetLocalPlayUs, late-frame
        // detection). Full alignment effect at next startup (seek/track
        // change). No flush: flushing causes buffer storm and desync.
        // Note: sync delay is a CLIENT-side UX nudge so it does NOT need to
        // be reported to the server; client/state only reports the spec
        // static_delay (derived from acoustic calibration).
        Log.d(TAG, "Sync delay: ${oldDelay}ms -> ${clamped}ms")
    }

    @Volatile private var isCellularTransport = false

    fun setCellularHint(cellular: Boolean) {
        isCellularTransport = cellular
        _networkMode.value = if (cellular) "Mobile" else "WiFi"
        engine.setCellularTransport(cellular)
    }

    private fun sendCurrentState(syncState: String) {
        // Per the Sendspin spec, static_delay_ms reports the device's known
        // external delay beyond the audio port so the server can adjust
        // buffer headroom. The acoustic calibration measures exactly that
        // (route-specific BT/external speaker latency), so we report it as
        // the spec field. Clamped to spec range 0..5000. The local UX nudge
        // (syncDelayMs) does NOT propagate to the server — it is purely a
        // client-side scheduling adjustment.
        val specStaticDelayMs = (audio.routeAcousticExtraUs / 1000L)
            .coerceIn(0L, 5000L)
            .toInt()
        client.sendClientState(
            volume = currentVolume,
            muted = muted,
            syncState = syncState,
            staticDelayMs = specStaticDelayMs
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

    fun setRouteAcousticExtraUs(valueUs: Long) {
        audio.routeAcousticExtraUs = valueUs
        Log.d(TAG, "Acoustic extra: ${valueUs / 1000}ms")
    }

    fun getRoutedDeviceProductName(): String? = audio.getRoutedDeviceProductName()

    fun acousticExtraMs(): Long = audio.routeAcousticExtraUs / 1000

    fun bufferedAudioMs(): Long = audio.bufferDurationMs()
    fun outputLatencyMs(): Long = audio.measuredOutputLatencyUs / 1000
    fun absoluteSyncMs(): Float {
        val e = engine as? SendspinPlaybackEngine ?: return 0f
        // Live drift = intended timeline minus DAC presentation, smoothed in the
        // native callback (the value the resampler/snap drives toward 0). Only
        // meaningful for grouped/SYNC while SYNCHRONIZED; DIRECT is a pure FIFO
        // with no peer, so it reads ~0.
        return e.dacGroundTruthErrorMs() ?: (e.startupOffsetMs + e.smoothedSyncErrorMs).toFloat()
    }
    fun isSyncMuted(): Boolean = (engine as? SendspinPlaybackEngine)?.syncMuted ?: false
    fun clockSampleCount(): Int = clockSynchronizer.currentSampleCount()
    fun clockErrorUs(): Long = clockSynchronizer.errorUs()
    fun clockRttUs(): Long = clockSynchronizer.lastRttUs()
    fun clockDriftPpm(): Double = clockSynchronizer.driftPpm()
    fun clockOffsetUs(): Long = clockSynchronizer.currentOffsetUs()
    fun resyncCount(): Int = (engine as? SendspinPlaybackEngine)?.resyncCount ?: 0
    fun correctionModeName(): String = engine.correctionMode.name

    fun bufferedAudioBytes(): Long = audio.bufferedBytes()

    fun setupSyncStateCallback() {
        audio.onSyncSample = { errorMs, outLatMs, filterErrMs, dacAbsoluteMs ->
            val sample = SyncSample(errorMs, outLatMs, filterErrMs, dacAbsoluteMs)
            val history = _syncHistory.value.toMutableList()
            history.add(sample)
            if (history.size > 60) history.removeAt(0)
            _syncHistory.value = history
        }
        audio.onSyncStateChanged = { state ->
            _syncState.value = state
            val stateStr = mapEngineStateToProtocolState(state)
            if (stateStr != lastSentSyncState) {
                lastSentSyncState = stateStr
                lastCallbackSentAtMs = System.currentTimeMillis()
                Log.d("sendspindbg", ">>> client/state: $stateStr (from $state) buf=${audio.bufferDurationMs()}ms")
                sendCurrentState(stateStr)
            }
        }
    }

    private fun currentSyncStatePayloadValue(): String =
        mapEngineStateToProtocolState(_syncState.value)

    /**
     * Translate our internal [SyncState] enum to the wire-level
     * `client/state` field defined by the Sendspin protocol.
     *
     * Per spec (`src/spec.md`) and the reference sendspin-js client
     * (`src/core/core.ts:handleStreamClear`), the only legal values
     * are `'synchronized'`, `'error'`, and `'external_source'`. The
     * `'error'` state is documented to signal **unrecoverable**
     * problems (buffer underrun the client cannot keep up with, clock
     * sync failure, etc.) — not transient buffer flushes.
     *
     * Our engine flips to `SYNC_ERROR_REBUFFERING` on every user-initiated
     * seek as part of the discontinuity flush, *with the buffer still
     * full*. Reporting that as `'error'` made the MA server take the
     * heavyweight `stream/end + stream/start NEW_STREAM` path instead
     * of the lightweight `stream/clear` it would use for an in-sync
     * client; on a 3rd back-to-back seek that pushed wall latency from
     * ~400 ms to ~7 s as the server's stream pipeline queued up.
     *
     * `IDLE` (no active stream yet, e.g. between tracks after
     * stream/end) also used to map to `'error'` and caused a
     * Disconnected/Connected flap after every track end. Both map to
     * `'synchronized'` now, matching reference-client behaviour. If we
     * ever need to express true underrun, we'll add an explicit
     * `SyncState` variant for it instead of overloading the existing
     * recovery state.
     */
    private fun mapEngineStateToProtocolState(state: SyncState): String = when (state) {
        SyncState.IDLE -> "synchronized"
        SyncState.SYNCHRONIZED -> "synchronized"
        SyncState.HOLDOVER_PLAYING_FROM_BUFFER -> "synchronized"
        SyncState.SYNC_ERROR_REBUFFERING -> "synchronized"
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
        val finalSyncState = audio.syncState
        engine.release()
        _connectionState.value = SendspinState.DISCONNECTED
        _streamCodec.value = null
        _streamFormat.value = null
        _syncState.value = finalSyncState
        _serverMetadata.value = null
        Log.d(TAG, "Sendspin stopped")
    }
}
