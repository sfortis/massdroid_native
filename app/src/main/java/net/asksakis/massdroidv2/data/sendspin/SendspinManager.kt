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
        private const val TIME_SYNC_INTERVAL_MS = 1000L
        private const val TIME_SYNC_SAMPLES = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var timeSyncJob: Job? = null
    private var messageJob: Job? = null
    private var binaryJob: Job? = null
    private var stateJob: Job? = null

    // Clock sync: offset = server_clock - local_clock (microseconds)
    private val offsetSamples = mutableListOf<Long>()
    @Volatile var clockOffsetUs: Long = 0L; private set
    @Volatile var clockSynced: Boolean = false; private set

    private val _connectionState = MutableStateFlow(SendspinState.DISCONNECTED)
    val connectionState: StateFlow<SendspinState> = _connectionState.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _streamCodec = MutableStateFlow<String?>(null)
    val streamCodec: StateFlow<String?> = _streamCodec.asStateFlow()

    private var currentVolume = 100
    private var muted = false
    private var clientId: String = ""
    private var clientName: String = ""

    fun start(url: String, token: String, clientId: String, clientName: String) {
        this.clientId = clientId
        this.clientName = clientName
        _enabled.value = true
        _connectionState.value = SendspinState.CONNECTING

        // Cancel ALL existing jobs including heartbeat from previous session
        // to prevent them from sending non-auth messages on the new WebSocket
        heartbeatJob?.cancel()
        heartbeatJob = null

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
                if (binaryCount <= 3 || binaryCount % 1000 == 0) {
                    Log.d(TAG, "Binary message #$binaryCount: ${data.size} bytes")
                }
                audio.clockOffsetUs = clockOffsetUs
                audio.onBinaryMessage(data)
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
                client.sendClientState(volume = currentVolume, muted = muted)
                startHeartbeat()
                startTimeSync()
            }

            is SendspinIncoming.ServerTime -> {
                val now = System.nanoTime() / 1000 // local monotonic microseconds
                val t1 = incoming.payload.clientTransmitted
                val t2 = incoming.payload.serverReceived
                val t3 = incoming.payload.serverTransmitted
                val t4 = now
                val offset = ((t2 - t1) + (t3 - t4)) / 2
                synchronized(offsetSamples) {
                    offsetSamples.add(offset)
                    if (offsetSamples.size > TIME_SYNC_SAMPLES) offsetSamples.removeFirst()
                    clockOffsetUs = offsetSamples.sorted()[offsetSamples.size / 2] // median
                    clockSynced = offsetSamples.size >= 3
                }
                if (offsetSamples.size <= 3 || offsetSamples.size % 8 == 0) {
                    Log.d(TAG, "Clock sync: offset=${clockOffsetUs}us, samples=${offsetSamples.size}")
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
                audio.setVolume(if (muted) 0f else currentVolume / 100f)
                _streamCodec.value = info.codec.uppercase()
                client.updateState(SendspinState.STREAMING)
            }

            is SendspinIncoming.StreamEnd -> {
                Log.d(TAG, "Stream ended")
                audio.clearBuffer()
            }

            is SendspinIncoming.StreamClear -> {
                Log.d(TAG, "Stream clear")
                audio.clearBuffer()
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
                if (!muted) audio.setVolume(vol / 100f)
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
                client.sendClientState(volume = currentVolume, muted = muted)
            }
        }
    }

    private fun startTimeSync() {
        timeSyncJob?.cancel()
        synchronized(offsetSamples) {
            offsetSamples.clear()
            clockSynced = false
        }
        timeSyncJob = scope.launch {
            while (true) {
                val clientTimeUs = System.nanoTime() / 1000
                client.sendTimeRequest(clientTimeUs)
                delay(TIME_SYNC_INTERVAL_MS)
            }
        }
    }

    fun pauseAudio() {
        audio.setPaused(true)
    }

    fun resumeAudio() {
        audio.setPaused(false)
    }

    fun setVolume(volume: Int) {
        currentVolume = volume
        if (!muted) audio.setVolume(volume / 100f)
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        audio.setMuted(muted)
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
        audio.release()
        _connectionState.value = SendspinState.DISCONNECTED
        _streamCodec.value = null
        Log.d(TAG, "Sendspin stopped")
    }
}
