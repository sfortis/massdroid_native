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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

enum class SendspinState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    HANDSHAKING,
    SYNCING,
    STREAMING,
    ERROR
}

sealed class SendspinMessage {
    data class Text(val incoming: SendspinIncoming) : SendspinMessage()
    data class Binary(val data: ByteArray) : SendspinMessage()
}

/**
 * Sendspin WebSocket client. Owns the full transport lifecycle:
 *
 *  - Single state machine: start -> CONNECTING -> AUTHENTICATING -> (protocol
 *    states driven by the manager via updateState) -> READY (SYNCING/STREAMING).
 *  - One reconnect path: any transport loss (close OR failure) routes through
 *    [onTransportLost] which schedules an exponentially-backed-off retry up to
 *    [BACKOFF_MAX_MS]. Reconnects keep firing as long as [shouldRun] is true.
 *  - Credentials are pulled from [credentialsProvider] on every attempt, so a
 *    stale or rotated token is always replaced with a fresh one before we open
 *    a socket.
 *  - [SendspinState.ERROR] is terminal. It is reached only when (a) the
 *    server returns auth_error, (b) the manager pushes ERROR for a protocol
 *    failure, or (c) the credentials provider can't supply credentials for
 *    [MAX_PROVIDER_FAILURES] consecutive attempts. Terminal ERROR clears
 *    [shouldRun], so an external orchestrator must call [refresh] or
 *    stop/start to resume.
 */
class SendspinClient(
    private val httpClientProvider: () -> OkHttpClient,
    private val json: Json
) {
    companion object {
        private const val TAG = "SendspinClient"
        // Exponential schedule capped at 60s. Indexed by attempt count.
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 60_000)
        private const val BACKOFF_MAX_MS = 60_000L
        private const val JITTER_FRACTION = 0.30
        private const val BINARY_FRAME_BUFFER_CAPACITY = 2048
        // If the provider keeps returning null/blank credentials, we give up
        // and move to ERROR so the orchestrator can take corrective action.
        private const val MAX_PROVIDER_FAILURES = 8
    }

    data class Credentials(val serverUrl: String, val token: String)

    // @Volatile because send* methods (sendHello, sendClientState, sendTimeRequest,
    // sendRequestFormat, sendGoodbye) read the field from coroutine dispatchers
    // outside the @Synchronized lifecycle methods that write it. Without the
    // visibility guarantee, a thread could observe a stale closed handle (or a
    // stale null) after a reconnect, leading to dropped sends or NPE-like races.
    @Volatile private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    private val _state = MutableStateFlow(SendspinState.DISCONNECTED)
    val state: StateFlow<SendspinState> = _state.asStateFlow()

    private val _textMessages = MutableSharedFlow<SendspinIncoming>(extraBufferCapacity = 64)
    val textMessages: SharedFlow<SendspinIncoming> = _textMessages.asSharedFlow()

    private val _binaryMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = BINARY_FRAME_BUFFER_CAPACITY)
    val binaryMessages: SharedFlow<ByteArray> = _binaryMessages.asSharedFlow()

    // Ordered protocol stream. Sendspin control messages and audio frames share one WebSocket;
    // processing them via separate collectors can reorder stream/clear vs binary frames.
    private val _messages = MutableSharedFlow<SendspinMessage>(extraBufferCapacity = BINARY_FRAME_BUFFER_CAPACITY)
    val messages: SharedFlow<SendspinMessage> = _messages.asSharedFlow()

    private var errorMessage: String? = null
    @Volatile private var shouldRun = false
    private val attempt = AtomicInteger(0)
    private val providerFailures = AtomicInteger(0)
    @Volatile private var connectionGeneration = 0
    private val droppedBinaryFrames = AtomicInteger(0)
    private var clientIdInternal: String = ""
    private var credentialsProvider: (suspend () -> Credentials?)? = null

    /**
     * Begin the lifecycle. If already running, this just refreshes the
     * credentials provider and triggers an immediate retry (useful when the
     * caller knows credentials have rotated).
     */
    @Synchronized
    fun start(clientId: String, credentialsProvider: suspend () -> Credentials?) {
        this.clientIdInternal = clientId
        this.credentialsProvider = credentialsProvider
        cancelReconnect()
        shouldRun = true
        attempt.set(0)
        providerFailures.set(0)
        scheduleConnect(delayMs = 0L)
    }

    /**
     * Trigger an immediate fresh reconnect using the current credentials
     * provider. This is the canonical recovery entry point used by the
     * orchestrator after MA has reconnected.
     *
     * Recovery from terminal ERROR is intentional: a previous auth failure
     * or credentials-exhaustion put us into shouldRun=false, but the caller
     * is signalling that conditions have changed (fresh MA session = fresh
     * token). As long as the credentials provider is still registered
     * (start() was called at least once and stop() was not), refresh()
     * re-arms the lifecycle and triggers an immediate retry. Without this,
     * a single transient auth_error during car-BT use would leave the
     * Sendspin player dead until app restart — the exact symptom of #43.
     */
    @Synchronized
    fun refresh() {
        val provider = credentialsProvider
        if (provider == null) {
            Log.d(TAG, "refresh ignored: never started")
            return
        }
        Log.d(TAG, "refresh: forcing reconnect (shouldRun was $shouldRun)")
        cancelReconnect()
        closeSocket(1000, "Refresh")
        shouldRun = true
        attempt.set(0)
        providerFailures.set(0)
        scheduleConnect(delayMs = 0L)
    }

    @Synchronized
    fun stop() {
        shouldRun = false
        cancelReconnect()
        val ws = webSocket
        if (ws != null) {
            try {
                sendGoodbye()
            } catch (_: Throwable) {
                // ignored: socket may already be in failed state
            }
            ws.close(1000, "Client disconnect")
        }
        webSocket = null
        _state.value = SendspinState.DISCONNECTED
        attempt.set(0)
        providerFailures.set(0)
        credentialsProvider = null
    }

    /**
     * Backwards-compatible alias for [stop]. Kept so existing call sites in
     * the manager keep working.
     */
    fun disconnect() = stop()

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun closeSocket(code: Int, reason: String?) {
        val ws = webSocket
        webSocket = null
        ws?.close(code, reason)
    }

    @Synchronized
    private fun scheduleConnect(delayMs: Long) {
        if (!shouldRun) return
        cancelReconnect()
        // Always launch through scope so the Job is tracked. Immediate
        // (delayMs<=0) attempts still use launch + skip the delay so a
        // subsequent cancelReconnect() can interrupt them — preventing two
        // openWebSocket() coroutines from racing after back-to-back
        // start()/refresh() calls.
        val effectiveDelay = delayMs.coerceAtLeast(0L)
        if (effectiveDelay == 0L) {
            Log.d(TAG, "Connecting immediately (attempt=${attempt.get()})")
        } else {
            Log.d(TAG, "Reconnect scheduled in ${effectiveDelay}ms (attempt=${attempt.get()})")
        }
        reconnectJob = scope.launch {
            if (effectiveDelay > 0L) delay(effectiveDelay)
            if (!shouldRun) return@launch
            runConnectAttempt()
        }
    }

    private suspend fun runConnectAttempt() {
        val provider = credentialsProvider
        if (provider == null) {
            Log.w(TAG, "No credentials provider; abandoning lifecycle")
            synchronized(this@SendspinClient) {
                shouldRun = false
                _state.value = SendspinState.ERROR
            }
            return
        }
        val creds = try {
            provider()
        } catch (t: Throwable) {
            // CancellationException must propagate so the caller (scope or
            // explicit stop()) can unwind cleanly. Swallowing it would cause
            // a stop-mid-connect to be charged against providerFailures and,
            // after MAX_PROVIDER_FAILURES cancellations, the lifecycle would
            // move to terminal ERROR for no real reason.
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "Credentials provider threw: ${t.message}")
            null
        }
        if (creds == null || creds.serverUrl.isBlank() || creds.token.isBlank()) {
            val failures = providerFailures.incrementAndGet()
            if (failures >= MAX_PROVIDER_FAILURES) {
                Log.w(TAG, "Credentials unavailable after $failures attempts; moving to ERROR")
                synchronized(this@SendspinClient) {
                    shouldRun = false
                    _state.value = SendspinState.ERROR
                }
                return
            }
            Log.d(TAG, "Credentials unavailable, will retry (providerFailures=$failures)")
            scheduleConnect(nextBackoffMs())
            return
        }
        providerFailures.set(0)
        openWebSocket(creds)
    }

    private fun openWebSocket(creds: Credentials) {
        // Re-check lifecycle under the monitor and bump the generation
        // atomically with the socket-handle assignment. Without the recheck,
        // a stop() call that fired AFTER the coroutine's pre-launch
        // shouldRun guard but BEFORE this point would leave an orphaned
        // socket open.
        val gen = synchronized(this) {
            if (!shouldRun) {
                Log.d(TAG, "openWebSocket aborted: lifecycle stopped while attempt was scheduled")
                return
            }
            ++connectionGeneration
        }
        closeSocket(1000, null)

        _state.value = SendspinState.CONNECTING
        errorMessage = null

        val wsUrl = buildSendspinUrl(creds.serverUrl)
        Log.d(TAG, "Connecting to $wsUrl (gen=$gen, attempt=${attempt.get()})")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClientProvider().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != connectionGeneration) return
                droppedBinaryFrames.set(0)
                _state.value = SendspinState.AUTHENTICATING
                val auth = SendspinAuthMessage(token = creds.token, clientId = clientIdInternal)
                val msg = json.encodeToString(auth)
                Log.d(TAG, "Sendspin WebSocket opened, sending auth")
                webSocket.send(msg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != connectionGeneration) return
                // Stamp the NTP receive time (T4) at the earliest possible point
                // (before deserialize + coroutine dispatch), matching the JS
                // reference. Capturing T4 later biases the clock offset low ->
                // we play late. Only server/time needs it.
                val rxUs = System.nanoTime() / 1000
                val parsed = SendspinIncoming.parse(text, json)
                val incoming = if (parsed is SendspinIncoming.ServerTime) {
                    parsed.copy(clientReceivedUs = rxUs)
                } else {
                    parsed
                }
                if (!_messages.tryEmit(SendspinMessage.Text(incoming))) {
                    Log.w(TAG, "Dropping text message: $incoming")
                }
                _textMessages.tryEmit(incoming)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen != connectionGeneration) return
                val data = bytes.toByteArray()
                if (!_messages.tryEmit(SendspinMessage.Binary(data))) {
                    val dropped = droppedBinaryFrames.incrementAndGet()
                    if (dropped <= 5 || dropped % 100 == 0) {
                        Log.w(TAG, "Dropping binary audio frame(s): dropped=$dropped")
                    }
                } else {
                    val previousDropped = droppedBinaryFrames.getAndSet(0)
                    if (previousDropped != 0) {
                        Log.d(TAG, "Recovered after dropping $previousDropped binary frame(s)")
                    }
                }
                _binaryMessages.tryEmit(data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "Sendspin closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "Sendspin closed: $code $reason")
                onTransportLost("closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != connectionGeneration) return
                Log.e(TAG, "Sendspin failure: ${t.message}")
                errorMessage = t.message
                onTransportLost("failure: ${t.message}")
            }
        })
    }

    /**
     * Single transport-loss handler. Used by both [WebSocketListener.onClosed]
     * and [WebSocketListener.onFailure] so reconnect logic lives in exactly
     * one place.
     */
    private fun onTransportLost(reason: String) {
        synchronized(this) {
            webSocket = null
            // Don't clobber a terminal ERROR pushed by the manager (auth_error).
            if (_state.value != SendspinState.ERROR) {
                _state.value = SendspinState.DISCONNECTED
            }
            if (!shouldRun) {
                Log.d(TAG, "Transport lost while stopped, no reconnect: $reason")
                return
            }
            val delayMs = nextBackoffMs()
            Log.d(TAG, "Transport lost ($reason), reconnecting in ${delayMs}ms")
            scheduleConnect(delayMs)
        }
    }

    private fun nextBackoffMs(): Long {
        val current = attempt.getAndIncrement()
        val idx = current.coerceAtMost(BACKOFF_MS.size - 1)
        val base = BACKOFF_MS[idx]
        val jitter = (base * JITTER_FRACTION * Random.nextDouble()).toLong()
        return (base + jitter).coerceAtMost(BACKOFF_MAX_MS)
    }

    private fun buildSendspinUrl(serverUrl: String): String {
        val base = serverUrl.trimEnd('/')
            .replace("http://", "ws://")
            .replace("https://", "wss://")
        return "$base/sendspin"
    }

    fun sendHello(clientId: String, clientName: String) {
        val hello = SendspinClientHello(
            payload = ClientHelloPayload(
                clientId = clientId,
                name = clientName
            )
        )
        val msg = json.encodeToString(hello)
        webSocket?.send(msg)
    }

    fun sendClientState(volume: Int = 100, muted: Boolean = false, syncState: String = "synchronized", staticDelayMs: Int = 0) {
        val state = SendspinClientState(
            payload = ClientStatePayload(
                state = syncState,
                player = PlayerStateInfo(volume = volume, muted = muted, staticDelayMs = staticDelayMs)
            )
        )
        val msg = json.encodeToString(state)
        webSocket?.send(msg)
    }

    fun sendTimeRequest(clientTimeUs: Long) {
        val timeReq = SendspinClientTime(
            payload = ClientTimePayload(clientTransmitted = clientTimeUs)
        )
        val msg = json.encodeToString(timeReq)
        webSocket?.send(msg)
    }

    fun sendRequestFormat(codec: String, sampleRate: Int = 48000, bitDepth: Int = 16, channels: Int = 2) {
        val req = SendspinRequestFormat(
            payload = RequestFormatPayload(
                player = RequestFormatPlayerPayload(codec = codec, sampleRate = sampleRate, bitDepth = bitDepth, channels = channels)
            )
        )
        val msg = json.encodeToString(req)
        webSocket?.send(msg)
    }

    fun sendGoodbye(reason: String = "user_request") {
        val goodbye = SendspinGoodbye(payload = GoodbyePayload(reason = reason))
        val msg = json.encodeToString(goodbye)
        webSocket?.send(msg)
    }

    /**
     * Protocol-state update from the manager. Two side effects:
     *
     *  - Successful protocol states ([SendspinState.SYNCING], [SendspinState.STREAMING])
     *    reset the backoff counter so a later transient drop starts at the
     *    short end of the schedule, not at the cap.
     *  - [SendspinState.ERROR] is terminal: it stops the lifecycle. The
     *    manager pushes this only for protocol-level fatal errors (auth_error).
     */
    fun updateState(newState: SendspinState) {
        synchronized(this) {
            _state.value = newState
            when (newState) {
                SendspinState.SYNCING, SendspinState.STREAMING -> {
                    val previous = attempt.getAndSet(0)
                    if (previous != 0) {
                        Log.d(TAG, "Connection healthy, resetting backoff (was attempt=$previous)")
                    }
                    providerFailures.set(0)
                }
                SendspinState.ERROR -> {
                    Log.w(TAG, "Terminal ERROR pushed by manager, stopping lifecycle")
                    shouldRun = false
                    cancelReconnect()
                }
                else -> {}
            }
        }
    }

    fun getErrorMessage(): String? = errorMessage

    fun isConnected(): Boolean = webSocket != null
}
