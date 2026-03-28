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

enum class SendspinState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    HANDSHAKING,
    SYNCING,
    STREAMING,
    ERROR
}

class SendspinClient(
    private val httpClientProvider: () -> OkHttpClient,
    private val json: Json
) {
    companion object {
        private const val TAG = "SendspinClient"
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private val BACKOFF_MS = longArrayOf(1000, 2000, 3000, 5000, 10000)
        private const val BINARY_FRAME_BUFFER_CAPACITY = 2048
    }

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    private val _state = MutableStateFlow(SendspinState.DISCONNECTED)
    val state: StateFlow<SendspinState> = _state.asStateFlow()

    private val _textMessages = MutableSharedFlow<SendspinIncoming>(extraBufferCapacity = 64)
    val textMessages: SharedFlow<SendspinIncoming> = _textMessages.asSharedFlow()

    private val _binaryMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = BINARY_FRAME_BUFFER_CAPACITY)
    val binaryMessages: SharedFlow<ByteArray> = _binaryMessages.asSharedFlow()

    private var errorMessage: String? = null
    private var shouldReconnect = false
    private var reconnectAttempt = 0
    @Volatile
    private var connectionGeneration = 0
    private var droppedBinaryFrames = 0

    // Saved for reconnect
    private var savedUrl: String? = null
    private var savedToken: String? = null
    private var savedClientId: String? = null

    @Synchronized
    fun connect(serverUrl: String, token: String, clientId: String) {
        shouldReconnect = false
        cancelReconnect()
        webSocket?.close(1000, null)
        webSocket = null
        savedUrl = serverUrl
        savedToken = token
        savedClientId = clientId
        shouldReconnect = true
        reconnectAttempt = 0
        doConnectInternal(serverUrl, token, clientId)
    }

    private fun doConnect(serverUrl: String, token: String, clientId: String) {
        synchronized(this) {
            doConnectInternal(serverUrl, token, clientId)
        }
    }

    private fun doConnectInternal(serverUrl: String, token: String, clientId: String) {
        val gen = ++connectionGeneration
        webSocket?.close(1000, null)
        webSocket = null

        _state.value = SendspinState.CONNECTING
        errorMessage = null

        val wsUrl = buildSendspinUrl(serverUrl)
        Log.d(TAG, "Connecting to $wsUrl (gen=$gen)")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClientProvider().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != connectionGeneration) return
                reconnectAttempt = 0
                droppedBinaryFrames = 0
                _state.value = SendspinState.AUTHENTICATING
                // Proxy mode: first message must be auth
                val auth = SendspinAuthMessage(token = token, clientId = clientId)
                val msg = json.encodeToString(auth)
                Log.d(TAG, "Sendspin WebSocket opened, sending auth")
                webSocket.send(msg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != connectionGeneration) return
                val incoming = SendspinIncoming.parse(text, json)
                _textMessages.tryEmit(incoming)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen != connectionGeneration) return
                if (!_binaryMessages.tryEmit(bytes.toByteArray())) {
                    droppedBinaryFrames++
                    if (droppedBinaryFrames <= 5 || droppedBinaryFrames % 100 == 0) {
                        Log.w(TAG, "Dropping binary audio frame(s): dropped=$droppedBinaryFrames")
                    }
                } else if (droppedBinaryFrames != 0) {
                    Log.d(TAG, "Recovered after dropping $droppedBinaryFrames binary frame(s)")
                    droppedBinaryFrames = 0
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "Sendspin closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "Sendspin closed: $code $reason")
                this@SendspinClient.webSocket = null
                _state.value = SendspinState.DISCONNECTED
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != connectionGeneration) return
                Log.e(TAG, "Sendspin failure: ${t.message}")
                this@SendspinClient.webSocket = null
                errorMessage = t.message
                _state.value = SendspinState.ERROR
                // Don't auto-reconnect on network errors; SendspinAudioController
                // will restart with a fresh token after MA WebSocket reconnects
            }
        })
    }

    private fun buildSendspinUrl(serverUrl: String): String {
        // Proxy mode: same host/port as MA server, path /sendspin
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

    fun sendClientState(volume: Int = 100, muted: Boolean = false, syncState: String = "synchronized") {
        val state = SendspinClientState(
            payload = ClientStatePayload(
                state = syncState,
                player = PlayerStateInfo(volume = volume, muted = muted)
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

    fun sendGoodbye(reason: String = "user_request") {
        val goodbye = SendspinGoodbye(payload = GoodbyePayload(reason = reason))
        val msg = json.encodeToString(goodbye)
        webSocket?.send(msg)
    }

    fun updateState(newState: SendspinState) {
        _state.value = newState
    }

    fun getErrorMessage(): String? = errorMessage

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val url = savedUrl ?: return
        val token = savedToken ?: return
        val clientId = savedClientId ?: return

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            _state.value = SendspinState.ERROR
            shouldReconnect = false
            return
        }

        val delay = BACKOFF_MS[reconnectAttempt.coerceAtMost(BACKOFF_MS.size - 1)]
        reconnectAttempt++
        Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delay)
            if (shouldReconnect) doConnect(url, token, clientId)
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    @Synchronized
    fun disconnect() {
        shouldReconnect = false
        cancelReconnect()
        if (webSocket != null) {
            sendGoodbye()
        }
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _state.value = SendspinState.DISCONNECTED
    }

    fun isConnected(): Boolean = webSocket != null
}
