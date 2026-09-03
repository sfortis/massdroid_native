package net.asksakis.massdroidv2.data.websocket

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import androidx.annotation.VisibleForTesting
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

/**
 * True when a lifecycle-driven auto-connect should fire.
 *
 * [ConnectionState.Error] counts, not just a clean [ConnectionState.Disconnected]: a
 * spent retry budget parks the client in Error, and every auto-connect entry point
 * must be able to revive it from there. Checking only Disconnected meant an app
 * launch could not recover a connection the retry loop had abandoned.
 */
fun ConnectionState.needsConnect(): Boolean =
    this is ConnectionState.Disconnected || this is ConnectionState.Error

class MaWebSocketClient(
    private val baseOkHttpClient: OkHttpClient,
    private val json: Json,
    private val accountNoticeReporter: net.asksakis.massdroidv2.data.util.AccountNoticeReporter? = null,
    // Application context for the default-network callback that auto-revives the
    // WS when connectivity returns after the retry budget is exhausted. Nullable
    // so non-Android callers/tests can construct the client without it.
    private val appContext: Context? = null
) {
    companion object {
        private const val TAG = "MaWsClient"
        private const val AGGRESSIVE_RETRY_MS = 1_000L
        private const val AGGRESSIVE_RETRY_COUNT = 30
        private const val PATIENT_RETRY_MS = 5_000L
        private const val PATIENT_RETRY_COUNT = 30
        private const val MAX_RETRY_COUNT = AGGRESSIVE_RETRY_COUNT + PATIENT_RETRY_COUNT
        /**
         * Cadence once the retry budget is spent. Slow enough that a long outage costs
         * a handful of radio wake-ups per hour, frequent enough that the client comes
         * back on its own when the server returns without any network transition.
         */
        private const val IDLE_RETRY_MS = 15 * 60_000L
        private const val STABLE_CONNECTION_RESET_MS = 30_000L
        // Coalesce the burst of network callbacks a single transition emits
        // (onAvailable + repeated onLinkPropertiesChanged) into one revive.
        private const val NETWORK_REVIVE_DEBOUNCE_MS = 1_000L
        private const val COMMAND_RETRY_DELAY_MS = 140L
        /**
         * Backstop for a socket that opens but never sends the `server_id`
         * handshake. Generous enough to cover a slow (up to 10 s connectTimeout)
         * TCP + upgrade plus the handshake that follows, short enough that a
         * permanently stalled server surfaces a clear error instead of an
         * indefinite "Connecting...".
         */
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        // music_assistant_models InsufficientPermissions (admin-gated command, MA 2.9.0+).
        private const val ERROR_INSUFFICIENT_PERMISSIONS = 22
    }

    private var okHttpClient: OkHttpClient = baseOkHttpClient
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    /** True after at least one successful connection (enables auto-reconnect). */
    @Volatile private var hasConnectedSuccessfully = false
    /** True when a reconnect attempt is scheduled or in-flight. */
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()
    private val _startupReady = MutableStateFlow(false)
    val startupReady: StateFlow<Boolean> = _startupReady.asStateFlow()

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val partialResults = ConcurrentHashMap<String, MutableList<JsonElement>>()

    /**
     * Rolling 1-second counter of outbound WS commands. Logs a summary
     * whenever the rate exceeds 5/sec so spam from the volume / playback
     * paths is immediately visible in logcat. Survives across reconnects.
     */
    private val commandRateTracker = CommandRateTracker()

    private class CommandRateTracker {
        private val windowMs = 1_000L
        private val warnThreshold = 5
        private val timestamps = ArrayDeque<Long>()
        private val perCommand = HashMap<String, Int>()

        @Synchronized
        fun tick(command: String) {
            val now = System.currentTimeMillis()
            timestamps.addLast(now)
            perCommand.merge(command, 1) { a, b -> a + b }
            while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
                timestamps.removeFirst()
            }
            val size = timestamps.size
            if (size >= warnThreshold) {
                val summary = perCommand.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .joinToString(", ") { "${it.key}=${it.value}" }
                Log.w("WSOut", "Burst: ${size}/s — $summary")
            }
            // Reset per-command counts roughly every window so it stays
            // representative; small inaccuracy at the edges is fine for
            // diagnostic logging.
            if (timestamps.size <= 1) perCommand.clear()
        }
    }

    var serverInfo: ServerInfo? = null
        private set
    var authToken: String? = null
        private set
    private var serverUrl: String? = null
    private var pendingLogin: Pair<String, String>? = null
    private var savedCredentials: Pair<String, String>? = null
    private var onTokenReceived: ((String) -> Unit)? = null
    @Volatile var userDisconnected = false
        private set
    private var reconnectJob: Job? = null

    /** Consumed retry budget; drives the aggressive -> patient -> give-up escalation. */
    @VisibleForTesting
    internal var reconnectAttempts = 0
    /**
     * Guards the window between opening the socket and receiving the server
     * handshake (`server_id`). OkHttp's connectTimeout only covers the TCP +
     * WebSocket upgrade; once the socket is open, a server that never sends its
     * handshake would leave us in [ConnectionState.Connecting] forever with no
     * error. Cancelled the moment the handshake arrives (auth then governs via
     * its own per-command timeout).
     */
    private var handshakeWatchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkReviveMs = 0L
    @Volatile
    private var connectionGeneration = 0

    /**
     * When the current connection authenticated, or 0 once that has been consumed
     * by a disconnect. Read from the OkHttp callback threads, written from the auth
     * coroutine, hence volatile. See [consumeStableConnectionBackoffReset].
     */
    @VisibleForTesting
    @Volatile
    internal var lastAuthenticatedAtMs = 0L

    fun setSavedCredentials(username: String, password: String) {
        if (username.isNotBlank() && password.isNotBlank()) {
            savedCredentials = username to password
        }
    }

    /**
     * Convert a server-supplied UTC timestamp (seconds since epoch, fractional)
     * into a local-monotonic value compatible with [System.currentTimeMillis].
     *
     * MA's `elapsed_time_last_updated` is a wall-clock value reported by the
     * server. If the client's clock is close to the server's (NTP-synced
     * devices), the simple `* 1000` conversion is accurate enough for our
     * interpolation needs (we only care about the delta vs. "now"). When the
     * resulting value would land in the future relative to the current local
     * clock, we clamp to "now" — that's the same defensive behaviour the MA
     * frontend's [computeElapsedTime] uses to avoid negative deltas.
     */
    fun serverWallSecondsToLocalMs(serverWallSeconds: Double): Long {
        val candidate = (serverWallSeconds * 1000.0).toLong()
        val now = System.currentTimeMillis()
        return if (candidate > now) now else candidate
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

    fun markStartupReady() {
        _startupReady.value = true
    }

    fun connect(url: String, token: String) {
        // A pending retry suppresses this call, EXCEPT an idle-phase sleep: that one
        // is up to IDLE_RETRY_MS away, and a caller asking to connect now (the user
        // opening the app, an auto-connect site seeing a dead client) must not be
        // told to wait it out. Measured in the field: after the retry budget was
        // spent, opening the app logged "connect() ignored: connection already
        // active/connecting" and every command then failed on the auth timeout,
        // leaving force-stop as the only way back.
        val hasOngoingReconnect = reconnectJob?.isActive == true && !isIdleRetrySleeping()
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
        ensureNetworkCallback()
        cancelReconnect()
        doConnect(url)
    }

    fun connectWithLogin(
        url: String,
        username: String,
        password: String,
        onToken: (String) -> Unit
    ) {
        // Same idle-sleep exception as [connect].
        val hasOngoingReconnect = reconnectJob?.isActive == true && !isIdleRetrySleeping()
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
        ensureNetworkCallback()
        cancelReconnect()
        doConnect(url)
    }

    private fun doConnect(url: String) {
        // Close any existing connection first
        webSocket?.close(1000, null)
        webSocket = null

        // Warn about HTTP on non-private networks
        if (url.startsWith("http://", ignoreCase = true)) {
            val host = try { java.net.URI(url).host } catch (_: Exception) { null }
            if (host != null && !isPrivateHost(host)) {
                Log.w(TAG, "Using insecure HTTP for non-local host: $host. Consider HTTPS.")
            }
        }

        val gen = ++connectionGeneration
        _connectionState.value = ConnectionState.Connecting
        startHandshakeWatchdog(gen)
        val wsUrl = url.trimEnd('/')
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/ws"

        // Relax hostname verification for private/local hosts (cert may not match local hostname)
        val host = try { java.net.URI(url).host } catch (_: Exception) { null }
        val client = if (host != null && isPrivateHost(host)) {
            okHttpClient.newBuilder()
                .hostnameVerifier { _, _ -> true }
                .build()
        } else {
            okHttpClient
        }

        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid server URL: $wsUrl", e)
            _connectionState.value = ConnectionState.Error("Invalid server URL. Include http:// or https://")
            return
        }
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

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
                cancelHandshakeWatchdog()
                this@MaWebSocketClient.webSocket = null
                _connectionState.value = ConnectionState.Disconnected
                consumeStableConnectionBackoffReset()
                failAllPending("Connection closed")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != connectionGeneration) return
                Log.e(TAG, "WebSocket failure: ${t.message}")
                cancelHandshakeWatchdog()
                this@MaWebSocketClient.webSocket = null
                failAllPending("Connection lost")

                if (isPermanentSslError(t)) {
                    Log.e(TAG, "Permanent SSL/TLS error, not retrying")
                    _connectionState.value = ConnectionState.Error(
                        "TLS certificate mismatch. The server certificate doesn't match this hostname."
                    )
                    return
                }

                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                consumeStableConnectionBackoffReset()
                scheduleReconnect()
            }
        })
    }

    /**
     * Fail an attempt that opened the socket but never received the server
     * handshake. Only fires while this attempt is still the current generation
     * and still [ConnectionState.Connecting]; a completed handshake cancels it
     * ([cancelHandshakeWatchdog]), so it never preempts a slow auth. Mirrors the
     * [onFailure] path (surface an error, then let [scheduleReconnect]'s
     * `hasConnectedSuccessfully` guard decide whether to retry).
     */
    private fun startHandshakeWatchdog(gen: Int) {
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (gen != connectionGeneration) return@launch
            if (_connectionState.value !is ConnectionState.Connecting) return@launch
            Log.e(TAG, "Handshake timeout: no server response within ${HANDSHAKE_TIMEOUT_MS}ms")
            webSocket?.close(1000, "handshake timeout")
            webSocket = null
            failAllPending("Handshake timeout")
            _connectionState.value = ConnectionState.Error(
                "Server not responding. Check the address and that Music Assistant is reachable on this network."
            )
            consumeStableConnectionBackoffReset()
            scheduleReconnect()
        }
    }

    private fun cancelHandshakeWatchdog() {
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = null
    }

    fun disconnect() {
        userDisconnected = true
        hasConnectedSuccessfully = false
        lastAuthenticatedAtMs = 0L
        cancelReconnect()
        cancelHandshakeWatchdog()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        failAllPending("Disconnected")
    }

    /**
     * Called when the device's active network transport changes (WiFi ↔ cellular,
     * roaming, captive portal exit, etc.). Evicts the OkHttp connection pool so
     * subsequent image (Coil) and HTTP API requests pick up fresh connections on
     * the new route — without this, the pool hands out idle keep-alive entries
     * bound to the previous transport's dead local IP, visible as missing cover
     * art after a network change.
     *
     * Deliberately does NOT cancel the live MA WebSocket. SendspinAudioController
     * force-restarts Sendspin on every MA reconnect (see Collector 6) to recover
     * from server reboots, which cuts audio playback. We rely on OkHttp's
     * pingInterval to detect actually-dead sockets while letting connections
     * that survive transport migration keep streaming seamlessly. evictAll()
     * does not affect active WebSocket streams — only idle pool entries are
     * closed.
     */
    fun handleTransportChange() {
        Log.d(TAG, "Transport change: evicting connection pool")
        okHttpClient.connectionPool.evictAll()
    }

    /**
     * Register a process-lifetime default-network callback so the WS auto-revives
     * when usable connectivity returns. The retry budget ([MAX_RETRY_COUNT]) is
     * finite: a long outage (e.g. roaming onto an AP whose subnet can't route to
     * the server) exhausts it, the client gives up into [ConnectionState.Error],
     * and nothing else re-triggers it. This covers the missing wake-up:
     *  - onAvailable: a new default network (WiFi<->mobile handover).
     *  - onLinkPropertiesChanged: a same-network DHCP/subnet change (a roam that
     *    swaps the local IP onto a subnet that can finally reach the server) —
     *    this one fires no onAvailable, so it was previously invisible.
     * Idempotent; a no-op when constructed without a Context.
     */
    private fun ensureNetworkCallback() {
        if (networkCallback != null) return
        val cm = appContext?.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkAvailable("available")
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                onNetworkAvailable("link-change")
        }
        networkCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
            Log.d(TAG, "Network callback registered (auto-revive)")
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}")
            networkCallback = null
        }
    }

    /**
     * Connectivity (re)appeared. Evict pooled connections bound to the old route,
     * then revive the WS if it isn't live: reset the backoff to aggressive and
     * reconnect, even out of the terminal Error state the exhausted retry budget
     * left us in. No-op while connected/connecting, after a user disconnect, or
     * before the first successful connection (don't auto-connect a never-connected
     * client). Debounced so one transition's callback burst yields one revive.
     */
    private fun onNetworkAvailable(reason: String) {
        scope.launch {
            handleTransportChange()
            if (userDisconnected || !hasConnectedSuccessfully) return@launch
            val url = serverUrl ?: return@launch
            val state = _connectionState.value
            if (state is ConnectionState.Connected || state is ConnectionState.Connecting) return@launch
            val now = System.currentTimeMillis()
            if (now - lastNetworkReviveMs < NETWORK_REVIVE_DEBOUNCE_MS) return@launch
            lastNetworkReviveMs = now
            // An idle-phase sleep can be IDLE_RETRY_MS long: fresh connectivity must
            // preempt it rather than wait out the window. Read before the reset clears it.
            val wasIdleRetry = isIdleRetrySleeping()
            resetReconnectBackoff()
            if (reconnectJob?.isActive == true && !wasIdleRetry) {
                Log.d(TAG, "Network $reason: backoff reset, retry already in flight")
                return@launch
            }
            if (wasIdleRetry) reconnectJob?.cancel()
            Log.d(TAG, "Network $reason: reviving WS connection")
            doConnect(url)
        }
    }

    private fun scheduleReconnect() {
        if (userDisconnected) return
        if (!hasConnectedSuccessfully) return // don't auto-retry initial connection failures
        val url = serverUrl ?: return
        if (authToken == null && pendingLogin == null && savedCredentials == null) return
        if (reconnectJob?.isActive == true) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val attempt = reconnectAttempts + 1
            if (attempt == MAX_RETRY_COUNT + 1) {
                // Budget spent. Settle into the idle phase: surface the plain error and
                // stop advertising an active retry, but keep probing slowly instead of
                // giving up outright (see retryDelayBaseMs).
                Log.w(TAG, "Retry budget ($MAX_RETRY_COUNT) spent, dropping to idle retries")
                _connectionState.value = ConnectionState.Error("Connection lost. Check server and retry.")
            }
            _isReconnecting.value = attempt <= MAX_RETRY_COUNT
            val jitterFactor = ThreadLocalRandom.current().nextDouble(0.85, 1.15)
            val delayMs = (retryDelayBaseMs(attempt) * jitterFactor).toLong()
            val phase = if (attempt > MAX_RETRY_COUNT) "idle" else "$attempt/$MAX_RETRY_COUNT"
            Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt=$phase)")
            // Pinned BEFORE the sleep, not after.
            //
            // This is what tells everyone else which phase we are in, and an idle
            // sleep is up to IDLE_RETRY_MS long, so the counter has to be right
            // WHILE we sleep rather than once we wake. Updating it afterwards left
            // `reconnectAttempts` at MAX_RETRY_COUNT during the idle sleep, so
            // `isIdleRetrySleeping` read false and returning connectivity was
            // dismissed as "retry already in flight": measured in the field, the
            // network came back 88 seconds into a 992-second sleep and the app sat
            // there for the remaining 15 minutes.
            //
            // Coerced to the sentinel so the counter cannot run away over a long
            // outage.
            reconnectAttempts = pinnedAttemptCounter(attempt)
            delay(delayMs)
            if (userDisconnected) return@launch
            doConnect(url)
        }
    }

    /**
     * Retry cadence: aggressive (~1 s) for the first [AGGRESSIVE_RETRY_COUNT] attempts,
     * patient (~5 s) up to [MAX_RETRY_COUNT], then idle ([IDLE_RETRY_MS]) forever.
     *
     * The idle phase exists because giving up outright stranded the client: on the
     * outage that motivated this, ConnectivityManager reported NO network transition
     * for 4 hours (the device had a network, just no route to the server), so
     * [onNetworkAvailable] never fired and nothing else re-triggered a connect. The
     * client would have stayed in [ConnectionState.Error] until an app restart, taking
     * Sendspin and Follow Me down with it. A ~15 min probe costs a handful of radio
     * wake-ups per hour instead of the ~3600 the unbounded aggressive loop caused.
     */
    @VisibleForTesting
    internal fun retryDelayBaseMs(attempt: Int): Long = when {
        attempt <= AGGRESSIVE_RETRY_COUNT -> AGGRESSIVE_RETRY_MS
        attempt <= MAX_RETRY_COUNT -> PATIENT_RETRY_MS
        else -> IDLE_RETRY_MS
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
        resetReconnectBackoff()
    }

    private fun resetReconnectBackoff() {
        reconnectAttempts = 0
    }

    /**
     * Whether a reconnect job is currently sleeping out an IDLE-phase delay, i.e. a
     * wait of up to [IDLE_RETRY_MS] rather than the seconds an aggressive or patient
     * retry takes.
     *
     * The distinction decides whether a fresh reason to connect (returning
     * connectivity, or the user opening the app) should preempt the pending retry.
     * Interrupting a 1-second aggressive retry buys nothing and reopens the
     * reconnect-storm risk the retry budget exists to prevent; leaving a 15-minute
     * idle sleep in place means the app looks broken for a quarter of an hour after
     * the network is already back.
     */
    private fun isIdleRetrySleeping(): Boolean =
        reconnectJob?.isActive == true && isIdleRetryPhase(reconnectAttempts)

    /**
     * Whether [attempts] is past the retry budget, i.e. the next wait is an
     * [IDLE_RETRY_MS] one. Separate from [isIdleRetrySleeping] so the threshold can
     * be pinned by a test without a live reconnect job: the field failure was
     * exactly an off-by-one here, with the counter still reading [MAX_RETRY_COUNT]
     * during the first idle sleep instead of the sentinel above it.
     */
    @VisibleForTesting
    internal fun isIdleRetryPhase(attempts: Int): Boolean = attempts > MAX_RETRY_COUNT

    /** The value [scheduleReconnect] pins the counter to for a given attempt. */
    @VisibleForTesting
    internal fun pinnedAttemptCounter(attempt: Int): Int = attempt.coerceAtMost(MAX_RETRY_COUNT + 1)

    private fun handleMessage(text: String) {
        try {
            val obj = json.parseToJsonElement(text).jsonObject

            when {
                "server_id" in obj -> {
                    // Handshake arrived: auth now governs its own bounded timeout.
                    cancelHandshakeWatchdog()
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
                        // MA 2.9.0+ gates some write commands (create/remove group, save player
                        // config, ...) behind an admin role and returns InsufficientPermissions
                        // for everyone else. Surface a single friendly app-level notice from this
                        // one choke point so every admin-gated command is covered.
                        if (errorCode == ERROR_INSUFFICIENT_PERMISSIONS) {
                            accountNoticeReporter?.reportPermissionDenied()
                        }
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
                val info = serverInfo
                if (info == null) {
                    _connectionState.value = ConnectionState.Error("Missing server handshake")
                    return@launch
                }
                _connectionState.value = ConnectionState.Connected(info)
                hasConnectedSuccessfully = true
            } catch (e: Exception) {
                Log.e(TAG, "Auth failed: ${e.message}")
                authToken = null
                _connectionState.value = ConnectionState.Error("Authentication failed: ${e.message}")
            }
        }
    }

    /**
     * A connection that just dropped after having been authenticated for at least
     * [STABLE_CONNECTION_RESET_MS] earns a fresh retry budget: that drop is a
     * one-off (server restart, roam) rather than an unreachable server.
     *
     * Called from the disconnect paths only, and it CONSUMES the marker. Without
     * clearing [lastAuthenticatedAtMs] the elapsed time kept growing after the
     * socket died, so every subsequent failure also looked "stable" and reset the
     * budget again. That pinned `attempt` at 1 ([AGGRESSIVE_RETRY_MS]) forever, so
     * an unreachable server (observed: DNS failing while off-VPN on cellular)
     * produced a ~1 retry/s storm that never exhausted [MAX_RETRY_COUNT] and kept
     * the mobile radio awake for hours. [onNetworkAvailable] remains the way a
     * genuinely new route revives an exhausted budget.
     */
    @VisibleForTesting
    internal fun consumeStableConnectionBackoffReset(nowMs: Long = System.currentTimeMillis()) {
        val authenticatedAtMs = lastAuthenticatedAtMs
        lastAuthenticatedAtMs = 0L
        if (authenticatedAtMs <= 0L) return
        val connectedForMs = nowMs - authenticatedAtMs
        if (connectedForMs >= STABLE_CONNECTION_RESET_MS) {
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
        timeoutMs: Long = 30_000,
    ): JsonElement? {
        waitUntilReadyForCommand(command = command, awaitResponse = awaitResponse, timeoutMs = timeoutMs)
        if (_connectionState.value !is ConnectionState.Connected && !isAuthCommand(command)) return null
        // Two distinct retries live below, and they are safe in different cases.
        // A failed SEND never reached the server, so repeating it is safe for every
        // command, transport included. A TIMEOUT did reach it and may already have
        // taken effect, so only commands that can be repeated harmlessly get that one.
        val maxAttempts = if (isAuthCommand(command)) 1 else 2
        val mayRetryAfterTimeout = shouldRetryCommand(command)
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

            // Per-command outbound trace. Tagged separately so it can be
            // grepped under "WSOut" regardless of which subsystem fired it.
            // args summary capped at 120 chars to keep the line scannable. That
            // cap is also what keeps the `auth` command's token out of the log:
            // 120 chars reach the JWT header and part of the payload but stop
            // before the signature, so what lands in a log the user can share is
            // not a usable credential. Raising the cap would start distributing
            // whole tokens.
            Log.d("WSOut", "→ $command args=${args?.toString()?.take(120)}")
            commandRateTracker.tick(command)
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
                if (attempt < maxAttempts && mayRetryAfterTimeout) {
                    Log.w(TAG, "sendCommand('$command') timed out on attempt $attempt, retrying")
                    delay(COMMAND_RETRY_DELAY_MS)
                    waitUntilReadyForCommand(command = command, awaitResponse = awaitResponse, timeoutMs = timeoutMs)
                    attempt++
                    continue
                }
                throw MaApiException("Request timed out", MaApiException.TIMEOUT_CODE)
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
            Log.w(TAG, "sendCommand aborted: WebSocket not authenticated within timeout")
            return
        }
    }

    private fun isAuthCommand(command: String): Boolean {
        return command == "auth" || command == "auth/login"
    }

    private fun shouldRetryCommand(command: String): Boolean = isRetryableCommand(command)

    private fun failAllPending(reason: String) {
        pendingRequests.keys.toList().forEach { id ->
            pendingRequests.remove(id)?.complete(null)
        }
        partialResults.clear()
    }

    // Image URL building (proxy_id route, legacy form, host rewrite) now lives in ImageUrlResolver.
    // The WS client only exposes the connection-context it owns: the external server URL and the
    // off-LAN host check, both of which depend on the live serverUrl set during connect.

    /** The user-configured external server URL (not the server's internal base_url). */
    fun externalServerUrl(): String? = serverUrl

    /**
     * MA API schema version from the server hello (`server_id` message), or null before connect.
     * Schema 31 is the MA 2.9 line: the imageproxy `proxy_id` era where the legacy
     * `/imageproxy?path=` route rejects private/LAN and non-http paths with HTTP 400.
     */
    fun serverSchemaVersion(): Int? = serverInfo?.schemaVersion

    /**
     * True when [host] is a private/LAN address that is NOT the host we reach the server through.
     * A "remotely accessible" image on such a host is only reachable on that LAN, so off-LAN
     * (cellular) or via a different remote/VPN endpoint it must be fetched through imageproxy
     * (the server resolves it on the LAN and serves it from whatever host we already reach).
     */
    fun isOffLanImageHost(host: String): Boolean {
        if (!isPrivateHost(host)) return false
        val serverHost = runCatching { java.net.URI(serverUrl).host }.getOrNull()
        return serverHost == null || !host.equals(serverHost, ignoreCase = true)
    }

    /** Expose current OkHttpClient so Coil can use same mTLS config */
    fun getHttpClient(): OkHttpClient = okHttpClient

    /** Client with finite timeouts for image/API downloads (inherits mTLS + connection pool) */
    fun getImageClient(): OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun isPermanentSslError(t: Throwable): Boolean {
        val cause = t.cause ?: t
        return cause is javax.net.ssl.SSLPeerUnverifiedException ||
            (cause is javax.net.ssl.SSLHandshakeException &&
                cause.message?.contains("not verified", ignoreCase = true) == true)
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".ts.net")) return true
        val parts = host.split(".")
        if (parts.size != 4) return false
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size != 4) return false
        return when {
            nums[0] == 10 -> true
            nums[0] == 172 && nums[1] in 16..31 -> true
            nums[0] == 192 && nums[1] == 168 -> true
            nums[0] == 127 -> true
            nums[0] == 100 && nums[1] in 64..127 -> true // CGNAT / Tailscale
            else -> false
        }
    }
}

class MaApiException(message: String, val code: Int) : Exception(message) {
    /**
     * True when no answer arrived before the deadline.
     *
     * Worth separating from a refusal: a refusal is the server's answer and says the
     * command did not take effect, while a timeout says nothing at all. The command may
     * have run and simply been answered late, so a caller must not assume it can be
     * repeated or worked around item by item.
     */
    val isTimeout: Boolean get() = code == TIMEOUT_CODE

    companion object {
        /** Marks "no response", as opposed to any error code the server itself returns. */
        const val TIMEOUT_CODE = -2
    }
}
