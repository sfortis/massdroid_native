package net.asksakis.massdroidv2.data.websocket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.math.min

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val serverInfo: ServerInfo) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

class MaWebSocketClient(
    private val baseOkHttpClient: OkHttpClient,
    private val json: Json
) {
    companion object {
        private const val TAG = "MaWsClient"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val STABLE_CONNECTION_RESET_MS = 30_000L
        private const val COMMAND_RETRY_DELAY_MS = 140L
    }

    private var okHttpClient: OkHttpClient = baseOkHttpClient
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val partialResults = ConcurrentHashMap<String, MutableList<JsonElement>>()

    var serverInfo: ServerInfo? = null
        private set
    var authToken: String? = null
        private set
    private var serverUrl: String? = null
    private var pendingLogin: Pair<String, String>? = null
    private var savedCredentials: Pair<String, String>? = null
    private var onTokenReceived: ((String) -> Unit)? = null
    private var userDisconnected = false
    private var reconnectJob: Job? = null
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var reconnectAttempts = 0
    private var connectionGeneration = 0
    private var lastAuthenticatedAtMs = 0L

    fun setSavedCredentials(username: String, password: String) {
        if (username.isNotBlank() && password.isNotBlank()) {
            savedCredentials = username to password
        }
    }

    fun configureMtls(privateKey: PrivateKey, certChain: Array<X509Certificate>) {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", privateKey, charArrayOf(), certChain)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, charArrayOf())
        }

        // Trust all server certs (the server cert validation is handled by the system)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }

        val trustManager = tmf.trustManagers.first() as X509TrustManager

        okHttpClient = baseOkHttpClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        Log.d(TAG, "mTLS configured with client certificate")
    }

    fun clearMtls() {
        okHttpClient = baseOkHttpClient
        Log.d(TAG, "mTLS cleared")
    }

    fun connect(url: String, token: String) {
        val hasOngoingReconnect = reconnectJob?.isActive == true
        val sameEndpoint = serverUrl == url && authToken == token
        val isActiveOrConnecting = webSocket != null ||
                _connectionState.value is ConnectionState.Connecting ||
                _connectionState.value is ConnectionState.Connected
        if (!userDisconnected && sameEndpoint && (isActiveOrConnecting || hasOngoingReconnect)) {
            Log.d(TAG, "connect() ignored: connection already active/connecting for $url")
            return
        }
        serverUrl = url
        authToken = token
        pendingLogin = null
        userDisconnected = false
        cancelReconnect()
        doConnect(url)
    }

    fun connectWithLogin(
        url: String,
        username: String,
        password: String,
        onToken: (String) -> Unit
    ) {
        val hasOngoingReconnect = reconnectJob?.isActive == true
        val sameEndpoint = serverUrl == url && pendingLogin == (username to password)
        val isActiveOrConnecting = webSocket != null ||
                _connectionState.value is ConnectionState.Connecting ||
                _connectionState.value is ConnectionState.Connected
        if (!userDisconnected && sameEndpoint && (isActiveOrConnecting || hasOngoingReconnect)) {
            Log.d(TAG, "connectWithLogin() ignored: connection already active/connecting for $url")
            return
        }
        serverUrl = url
        authToken = null
        pendingLogin = username to password
        onTokenReceived = onToken
        userDisconnected = false
        cancelReconnect()
        doConnect(url)
    }

    private fun doConnect(url: String) {
        // Close any existing connection first
        webSocket?.close(1000, null)
        webSocket = null

        // Validate: HTTP only allowed for private/local network hosts
        if (url.startsWith("http://", ignoreCase = true)) {
            val host = try { java.net.URI(url).host } catch (_: Exception) { null }
            if (host != null && !isPrivateHost(host)) {
                _connectionState.value = ConnectionState.Error(
                    "HTTP is only allowed for local network addresses. Use HTTPS for remote servers."
                )
                return
            }
        }

        val gen = ++connectionGeneration
        _connectionState.value = ConnectionState.Connecting
        val wsUrl = url.trimEnd('/')
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/ws"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != connectionGeneration) return
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "handleMessage error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "WebSocket closed: $code $reason")
                this@MaWebSocketClient.webSocket = null
                _connectionState.value = ConnectionState.Disconnected
                maybeResetBackoffForStableConnection()
                failAllPending("Connection closed")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != connectionGeneration) return
                Log.e(TAG, "WebSocket failure: ${t.message}")
                this@MaWebSocketClient.webSocket = null
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                maybeResetBackoffForStableConnection()
                failAllPending("Connection lost")
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        userDisconnected = true
        cancelReconnect()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        failAllPending("Disconnected")
    }

    private fun scheduleReconnect() {
        if (userDisconnected) return
        val url = serverUrl ?: return
        if (authToken == null && pendingLogin == null && savedCredentials == null) return
        if (reconnectJob?.isActive == true) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val attempt = reconnectAttempts + 1
            val jitterFactor = ThreadLocalRandom.current().nextDouble(0.85, 1.30)
            val delayMs = (currentBackoffMs * jitterFactor)
                .toLong()
                .coerceIn(INITIAL_BACKOFF_MS, MAX_BACKOFF_MS)
            Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt=$attempt, base=${currentBackoffMs}ms)")
            delay(delayMs)
            if (userDisconnected) return@launch
            reconnectAttempts = attempt
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            Log.d(TAG, "Attempting reconnect to $url")
            doConnect(url)
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        resetReconnectBackoff()
    }

    private fun resetReconnectBackoff() {
        reconnectAttempts = 0
        currentBackoffMs = INITIAL_BACKOFF_MS
    }

    private fun handleMessage(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject

            when {
                "server_id" in obj -> {
                    serverInfo = json.decodeFromJsonElement<ServerInfo>(obj)
                    Log.d(TAG, "Server: ${serverInfo?.serverVersion}")
                    authenticate()
                }

                "message_id" in obj -> {
                    val messageId = obj["message_id"]!!.jsonPrimitive.content

                    if ("error" in obj || "error_code" in obj) {
                        val errorCode = obj["error_code"]?.jsonPrimitive?.intOrNull
                        val errorMsg = obj["error"]?.jsonPrimitive?.contentOrNull
                            ?: obj["details"]?.jsonPrimitive?.contentOrNull
                            ?: "Unknown error"
                        Log.e(TAG, "RPC error [$messageId]: $errorMsg (code=$errorCode)")
                        pendingRequests.remove(messageId)?.completeExceptionally(
                            MaApiException(errorMsg, errorCode ?: -1)
                        )
                        return
                    }

                    val result = obj["result"]
                    val partial = obj["partial"]?.jsonPrimitive?.booleanOrNull ?: false

                    if (partial) {
                        // Ignore unsolicited partial chunks for commands we did not await.
                        if (!pendingRequests.containsKey(messageId) && !partialResults.containsKey(messageId)) {
                            return
                        }
                        val list = partialResults.getOrPut(messageId) { mutableListOf() }
                        if (result is JsonArray) list.addAll(result) else if (result != null) list.add(result)
                    } else {
                        val deferred = pendingRequests.remove(messageId)
                        val accumulated = partialResults.remove(messageId)
                        if (accumulated != null) {
                            if (result is JsonArray) accumulated.addAll(result) else if (result != null) accumulated.add(result)
                            deferred?.complete(JsonArray(accumulated))
                        } else {
                            deferred?.complete(result)
                        }
                    }
                }

                "event" in obj -> {
                    val event = json.decodeFromJsonElement<ServerEvent>(obj)
                    if (!_events.tryEmit(event)) {
                        scope.launch {
                            _events.emit(event)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun authenticate() {
        scope.launch {
            try {
                val login = pendingLogin
                if (login != null) {
                    loginWithCredentials(login.first, login.second)
                } else if (authToken != null) {
                    // Try token auth first, fallback to saved credentials if it fails
                    try {
                        authorizeWithToken(authToken!!)
                    } catch (e: Exception) {
                        Log.w(TAG, "Token auth failed: ${e.message}, trying saved credentials")
                        val creds = savedCredentials
                        if (creds != null) {
                            loginWithCredentials(creds.first, creds.second)
                        } else {
                            throw e  // No fallback available
                        }
                    }
                } else {
                    _connectionState.value = ConnectionState.Error("No token or credentials available")
                    return@launch
                }

                Log.d(TAG, "Authenticated")
                lastAuthenticatedAtMs = System.currentTimeMillis()
                _connectionState.value = ConnectionState.Connected(serverInfo!!)
            } catch (e: Exception) {
                Log.e(TAG, "Auth failed: ${e.message}")
                authToken = null
                _connectionState.value = ConnectionState.Error("Authentication failed: ${e.message}")
            }
        }
    }

    private fun maybeResetBackoffForStableConnection(nowMs: Long = System.currentTimeMillis()) {
        val connectedForMs = nowMs - lastAuthenticatedAtMs
        if (lastAuthenticatedAtMs > 0L && connectedForMs >= STABLE_CONNECTION_RESET_MS) {
            Log.d(TAG, "Stable connection lasted ${connectedForMs}ms, resetting reconnect backoff")
            resetReconnectBackoff()
        }
    }

    private suspend fun loginWithCredentials(username: String, password: String) {
        Log.d(TAG, "Logging in with username: $username")
        val loginResult = sendCommand(MaCommands.Auth.LOGIN, buildJsonObject {
            put("username", username)
            put("password", password)
            put("device_name", "MassDroid")
        })
        val loginObj = loginResult?.jsonObject
        val success = loginObj?.get("success")?.jsonPrimitive?.booleanOrNull ?: false
        val token = loginObj?.get("access_token")?.jsonPrimitive?.contentOrNull
        val error = loginObj?.get("error")?.jsonPrimitive?.contentOrNull

        if (!success || token.isNullOrBlank()) {
            throw MaApiException(error ?: "Login failed", -1)
        }

        authToken = token
        pendingLogin = null
        onTokenReceived?.invoke(token)
        onTokenReceived = null
        Log.d(TAG, "Login successful")
        authorizeWithToken(token)
    }

    private suspend fun authorizeWithToken(token: String) {
        sendCommand(MaCommands.Auth.AUTH, buildJsonObject {
            put("token", token)
            put("device_name", "MassDroid")
        })
    }

    suspend fun sendCommand(
        command: String,
        args: JsonObject? = null,
        awaitResponse: Boolean = true,
        timeoutMs: Long = 30_000
    ): JsonElement? {
        waitUntilReadyForCommand(command = command, awaitResponse = awaitResponse, timeoutMs = timeoutMs)
        val maxAttempts = if (shouldRetryCommand(command)) 2 else 1
        var attempt = 1
        while (attempt <= maxAttempts) {
            val messageId = UUID.randomUUID().toString().replace("-", "").take(12)
            val deferred = if (awaitResponse) {
                CompletableDeferred<JsonElement?>().also { pendingRequests[messageId] = it }
            } else {
                null
            }

            val msg = buildJsonObject {
                put("command", command)
                put("message_id", messageId)
                if (args != null) put("args", args)
            }

            val ws = webSocket
            if (ws == null || !ws.send(msg.toString())) {
                pendingRequests.remove(messageId)
                if (attempt < maxAttempts) {
                    Log.w(TAG, "sendCommand('$command') send failed on attempt $attempt, retrying")
                    delay(COMMAND_RETRY_DELAY_MS)
                    waitUntilReadyForCommand(command = command, awaitResponse = awaitResponse, timeoutMs = timeoutMs)
                    attempt++
                    continue
                }
                throw MaApiException("WebSocket not connected", -1)
            }
            if (!awaitResponse) return null

            try {
                return withTimeout(timeoutMs) { deferred!!.await() }
            } catch (_: TimeoutCancellationException) {
                if (attempt < maxAttempts) {
                    Log.w(TAG, "sendCommand('$command') timed out on attempt $attempt, retrying")
                    delay(COMMAND_RETRY_DELAY_MS)
                    waitUntilReadyForCommand(command = command, awaitResponse = awaitResponse, timeoutMs = timeoutMs)
                    attempt++
                    continue
                }
                throw MaApiException("Request timed out", -1)
            } finally {
                // Ensure no stale pending/partial state remains if request times out or caller is canceled.
                pendingRequests.remove(messageId)
                partialResults.remove(messageId)
            }
        }

        throw MaApiException("Command failed", -1)
    }

    private suspend fun waitUntilReadyForCommand(
        command: String,
        awaitResponse: Boolean,
        timeoutMs: Long
    ) {
        if (isAuthCommand(command)) return
        if (_connectionState.value is ConnectionState.Connected) return

        val waitTimeoutMs = if (awaitResponse) {
            min(timeoutMs, 5_000L)
        } else {
            min(timeoutMs, 2_000L)
        }

        try {
            withTimeout(waitTimeoutMs) {
                connectionState.first { it is ConnectionState.Connected }
            }
        } catch (_: TimeoutCancellationException) {
            throw MaApiException("WebSocket not authenticated yet", -1)
        }
    }

    private fun isAuthCommand(command: String): Boolean {
        return command == "auth" || command == "auth/login"
    }

    private fun shouldRetryCommand(command: String): Boolean {
        return !isAuthCommand(command)
    }

    private fun failAllPending(reason: String) {
        pendingRequests.keys.toList().forEach { id ->
            pendingRequests.remove(id)?.completeExceptionally(MaApiException(reason, -1))
        }
        partialResults.clear()
    }

    fun getImageUrl(imagePath: String, size: Int = 500): String? {
        // Use the user-configured server URL (external), not the internal base_url
        val base = serverUrl?.trimEnd('/') ?: return null
        return "${base}/imageproxy?path=${java.net.URLEncoder.encode(imagePath, "UTF-8")}&size=$size"
    }

    /** Expose current OkHttpClient so Coil can use same mTLS config */
    fun getHttpClient(): OkHttpClient = okHttpClient

    /** Client with finite timeouts for image/API downloads (inherits mTLS + connection pool) */
    fun getImageClient(): OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val parts = host.split(".")
        if (parts.size != 4) return false
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size != 4) return false
        return when {
            nums[0] == 10 -> true
            nums[0] == 172 && nums[1] in 16..31 -> true
            nums[0] == 192 && nums[1] == 168 -> true
            nums[0] == 127 -> true
            else -> false
        }
    }
}

class MaApiException(message: String, val code: Int) : Exception(message)
