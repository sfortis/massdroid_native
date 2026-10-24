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
import net.asksakis.massdroidv2.data.sendspin.noise.NoiseClientSession
import net.asksakis.massdroidv2.data.sendspin.noise.NoiseProtocolException
import net.asksakis.massdroidv2.data.sendspin.noise.ResolvedPsk
import net.asksakis.massdroidv2.data.sendspin.noise.SendspinNoise
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
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

    /** A player audio chunk, its header read for the connection's wire. */
    class Audio(val chunk: SendspinAudioChunk) : SendspinMessage()

    /** Any other binary role message, with its leading type byte. */
    class Binary(val data: ByteArray) : SendspinMessage()
}

/** Builds the Noise session for one connection attempt. */
fun interface NoiseSessionFactory {
    /** @throws IOException when the identity cannot be loaded or created. */
    fun create(): NoiseClientSession
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
 *  - Two transports. The legacy one exchanges cleartext JSON and binary frames.
 *    The Noise one (MA 2.10.0 and later, chosen by passing a [NoiseSessionFactory]
 *    to [start]) runs the Noise handshake right after the proxy accepts the
 *    auth frame, inside this class, and from then on every frame is an
 *    encrypted binary frame. The manager sees the same decoded messages either
 *    way, except that it never receives auth_ok on Noise.
 *  - A Noise handshake failure or a frame that fails authentication closes the
 *    socket and goes through the normal backoff. There is no fallback to the
 *    legacy transport, because a paired server refuses it anyway.
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
        // From onOpen to the end of the Noise handshake. The MA proxy allows 10 s
        // for the auth frame and aiosendspin 30 s per handshake message; a healthy
        // bring-up takes well under a second.
        private const val NOISE_BRING_UP_TIMEOUT_MS = 15_000L
        private const val CLOSE_PROTOCOL_ERROR = 1002
    }

    data class Credentials(val serverUrl: String, val token: String)

    // @Volatile because the connect and close paths write it from different
    // threads than the ones that read it. Without the visibility guarantee, a
    // thread could observe a stale closed handle (or a stale null) after a
    // reconnect. The send helpers deliberately do NOT read this one: see
    // [link].
    @Volatile private var webSocket: WebSocket? = null

    /** A socket the send helpers may write to, with the Noise session that encrypts for it. */
    private class Link(val socket: WebSocket, val noise: NoiseClientSession?)

    /**
     * The only socket the send helpers may use: on the legacy transport, once the
     * auth frame has been written to it; on Noise, once handshake message 2 has.
     *
     * OkHttp assigns the handle when `newWebSocket` returns, which is before the
     * handshake completes, and it queues anything sent in that window to go out
     * first. A periodic sender such as the clock's time request could therefore
     * put its frame ahead of the auth frame, and the server closes the socket
     * with 4001 "First message must be auth" when the first frame carries any
     * other type. That happened on three consecutive reconnect attempts on
     * 2026-09-17 at 17:39, costing thirteen seconds of silence before the fourth
     * attempt won the race. On Noise, a cleartext frame in the middle of the
     * handshake would break it the same way.
     */
    @Volatile private var link: Link? = null

    /**
     * The wire of the current connection. The plaintext transport is always [SendspinWire.LEGACY];
     * on Noise it is known once handshake message 1 has been read.
     */
    val wire: SendspinWire get() = link?.noise?.wire ?: SendspinWire.LEGACY
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
    private var noiseSessionFactory: NoiseSessionFactory? = null

    /**
     * Begin the lifecycle. If already running, this just refreshes the
     * credentials provider and triggers an immediate retry (useful when the
     * caller knows credentials have rotated).
     */
    @Synchronized
    fun start(
        clientId: String,
        noiseSessionFactory: NoiseSessionFactory? = null,
        credentialsProvider: suspend () -> Credentials?,
    ) {
        this.clientIdInternal = clientId
        this.credentialsProvider = credentialsProvider
        this.noiseSessionFactory = noiseSessionFactory
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
        link = null
        // Same reason as in closeSocket: a callback already on its way must not
        // find its generation still current.
        ++connectionGeneration
        _state.value = SendspinState.DISCONNECTED
        attempt.set(0)
        providerFailures.set(0)
        credentialsProvider = null
        noiseSessionFactory = null
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

    /**
     * Drop the current socket and everything that belongs to it.
     *
     * The generation is bumped here rather than only in [openWebSocket] because
     * closing does not stop callbacks that are already in flight. An `onOpen`
     * for the socket this call just abandoned would otherwise still pass the
     * generation guard, send auth into a dead handle and publish it as the
     * authenticated one, which is the invariant [link] exists to hold.
     */
    private fun closeSocket(code: Int, reason: String?) {
        val ws = synchronized(this) {
            ++connectionGeneration
            val current = webSocket
            webSocket = null
            link = null
            current
        }
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
        synchronized(this) {
            if (!shouldRun) {
                Log.d(TAG, "openWebSocket aborted: lifecycle stopped while attempt was scheduled")
                return
            }
        }
        // Closing bumps the generation, so this attempt has to claim its own
        // afterwards: taking it first would leave the new socket's callbacks
        // looking stale against the generation the close moved on to.
        closeSocket(1000, null)
        val gen = synchronized(this) { ++connectionGeneration }

        // A fresh session per attempt: handshake state never carries over.
        val noise = try {
            noiseSessionFactory?.create()
        } catch (e: IOException) {
            Log.e(TAG, "Sendspin identity unavailable: ${e.message}")
            errorMessage = e.message
            scheduleConnect(nextBackoffMs())
            return
        }

        _state.value = SendspinState.CONNECTING
        errorMessage = null

        val wsUrl = buildSendspinUrl(creds.serverUrl)
        Log.d(TAG, "Connecting to $wsUrl (gen=$gen, attempt=${attempt.get()}, noise=${noise != null})")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = httpClientProvider().newWebSocket(request, ConnectionListener(gen, creds.token, noise))
    }

    /**
     * Callbacks of one socket. Every callback first checks that its generation is
     * still the current one, so a socket that was replaced can no longer act.
     */
    private inner class ConnectionListener(
        private val gen: Int,
        private val token: String,
        private val noise: NoiseClientSession?,
    ) : WebSocketListener() {
        // Reader thread only.
        private var authAccepted = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != connectionGeneration) return
            droppedBinaryFrames.set(0)
            _state.value = SendspinState.AUTHENTICATING
            val auth = SendspinAuthMessage(token = token, clientId = clientIdInternal)
            val msg = json.encodeToString(auth)
            Log.d(TAG, "Sendspin WebSocket opened, sending auth")
            webSocket.send(msg)
            if (noise == null) {
                // Only now may anything else be written: the auth frame is first
                // in the queue, so whatever follows arrives second.
                link = Link(webSocket, null)
            } else {
                armNoiseBringUpTimeout(gen, noise)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != connectionGeneration) return
            // Stamp the NTP receive time (T4) at the earliest possible point
            // (before deserialize + coroutine dispatch), matching the JS
            // reference. Capturing T4 later biases the clock offset low ->
            // we play late. Only server/time needs it.
            val rxUs = System.nanoTime() / 1000
            when {
                noise == null -> deliverText(text, rxUs, null)
                !noise.isEstablished -> onNoiseBringUpText(webSocket, noise, text)
                else -> abortConnection(gen, "cleartext frame on an encrypted connection")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (gen != connectionGeneration) return
            if (noise == null) {
                deliverBinary(gen, bytes.toByteArray(), SendspinWire.LEGACY)
                return
            }
            // T4 before the decrypt, for the same reason as on the text path.
            val rxUs = System.nanoTime() / 1000
            val received = try {
                noise.receive(bytes.toByteArray()) { frame -> webSocket.send(frame.toByteString()) }
            } catch (e: NoiseProtocolException) {
                abortConnection(gen, e.message ?: "transport frame rejected")
                return
            }
            when (received) {
                null -> Unit
                is NoiseClientSession.Received.Text -> deliverText(received.text, rxUs, noise)
                is NoiseClientSession.Received.Binary ->
                    deliverBinary(gen, received.data, noise.wire ?: SendspinWire.LEGACY)
                is NoiseClientSession.Received.Rehandshake -> {
                    Log.d(TAG, "Noise re-handshake onto a ${received.psk.category} PSK")
                    emitText(SendspinIncoming.NoiseRehandshake)
                }
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
            onTransportLost("closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != connectionGeneration) return
            Log.e(TAG, "Sendspin failure: ${t.message}")
            errorMessage = t.message
            onTransportLost("failure: ${t.message}")
        }

        /**
         * The cleartext part of a Noise connection: the proxy's auth answer, then
         * `server/init` and handshake message 1. auth_ok stays here, because on
         * Noise it is not the manager's cue to send the hello.
         */
        private fun onNoiseBringUpText(webSocket: WebSocket, noise: NoiseClientSession, text: String) {
            try {
                if (!authAccepted) {
                    when (val incoming = SendspinIncoming.parse(text, json)) {
                        SendspinIncoming.AuthOk -> {
                            authAccepted = true
                            _state.value = SendspinState.HANDSHAKING
                            webSocket.send(noise.clientInitText)
                        }
                        is SendspinIncoming.AuthError -> emitText(incoming)
                        else -> abortConnection(gen, "expected auth_ok, got $incoming")
                    }
                    return
                }
                noise.onHandshakeText(text)?.let { reply -> webSocket.send(reply) }
                if (noise.isEstablished) {
                    // Message 2 is queued, so encrypted frames may follow it.
                    link = Link(webSocket, noise)
                    Log.i(TAG, "Noise session up: server=${noise.serverId}, psk=${noise.psk?.category}, wire=${noise.wire}")
                    emitText(SendspinIncoming.NoiseSessionUp)
                }
            } catch (e: NoiseProtocolException) {
                abortConnection(gen, e.message ?: "handshake failed")
            } catch (e: IllegalArgumentException) {
                abortConnection(gen, "malformed frame during bring-up: ${e.message}")
            }
        }
    }

    private fun deliverText(text: String, rxUs: Long, noise: NoiseClientSession?) {
        val parsed = SendspinIncoming.parse(text, json)
        // Ended here on the reader thread rather than in the manager, so the
        // exchange is closed before any later frame is read. On the legacy wire
        // it stays open during pairing, because the server then reads the next
        // frame as client/pair-finalize. On rc1 any server/activate ends it,
        // because pairing runs beside the other traffic.
        if (noise != null && parsed is SendspinIncoming.ServerActivate &&
            (noise.wire == SendspinWire.RC1 || ACTIVITY_PAIRING !in parsed.payload.activities)
        ) {
            noise.endExchange()
        }
        val incoming = if (parsed is SendspinIncoming.ServerTime) {
            parsed.copy(clientReceivedUs = rxUs)
        } else {
            parsed
        }
        emitText(incoming)
    }

    private fun emitText(incoming: SendspinIncoming) {
        if (!_messages.tryEmit(SendspinMessage.Text(incoming))) {
            Log.w(TAG, "Dropping text message: $incoming")
        }
        _textMessages.tryEmit(incoming)
    }

    private fun deliverBinary(gen: Int, data: ByteArray, wire: SendspinWire) {
        val message = try {
            SendspinAudioChunk.parse(data, wire)?.let(SendspinMessage::Audio) ?: SendspinMessage.Binary(data)
        } catch (e: SendspinAudioChunk.MalformedException) {
            abortConnection(gen, e.message ?: "malformed audio message")
            return
        }
        if (!_messages.tryEmit(message)) {
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

    /** Fails a Noise connection that has not finished its bring-up in time. */
    private fun armNoiseBringUpTimeout(gen: Int, noise: NoiseClientSession) {
        scope.launch {
            delay(NOISE_BRING_UP_TIMEOUT_MS)
            if (gen == connectionGeneration && !noise.isEstablished) {
                abortConnection(gen, "Noise handshake timed out")
            }
        }
    }

    /**
     * Ends a connection whose Noise layer failed. The socket is closed without an
     * application message, as the spec asks, and the attempt counts against the
     * backoff like any other transport loss. Closing bumps the generation, so the
     * socket's own onClosed is ignored and the loss is reported here instead.
     */
    private fun abortConnection(gen: Int, reason: String) {
        synchronized(this) {
            if (gen != connectionGeneration) return
            Log.w(TAG, "Sendspin Noise connection failed: $reason")
            errorMessage = reason
            closeSocket(CLOSE_PROTOCOL_ERROR, null)
            onTransportLost("noise: $reason")
        }
    }

    /**
     * Single transport-loss handler. Used by both [WebSocketListener.onClosed]
     * and [WebSocketListener.onFailure] so reconnect logic lives in exactly
     * one place.
     */
    private fun onTransportLost(reason: String) {
        synchronized(this) {
            webSocket = null
            link = null
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

    fun sendHello(clientId: String, clientName: String, supportedFormats: List<AudioFormatSpec>, deviceInfo: DeviceInfo) {
        val hello = SendspinClientHello(
            payload = ClientHelloPayload(
                clientId = clientId,
                name = clientName,
                deviceInfo = deviceInfo,
                playerV1Support = PlayerV1Support(supportedFormats = supportedFormats),
            )
        )
        sendText(json.encodeToString(hello))
    }

    fun sendClientState(available: Boolean, player: PlayerStateInfo?) {
        val state = SendspinClientState(
            payload = ClientStatePayload(available = available, player = player)
        )
        sendText(json.encodeToString(state))
    }

    /** `client/state` on the rc1 wire; without a player role the player object is left out. */
    fun sendRc1ClientState(available: Boolean, player: Rc1PlayerState?) {
        val text = if (player != null) {
            json.encodeToString(SendspinRc1ClientState(payload = Rc1ClientStatePayload(available, player)))
        } else {
            json.encodeToString(SendspinRc1AvailabilityState(payload = Rc1AvailabilityPayload(available)))
        }
        sendText(text)
    }

    fun sendTimeRequest(clientTimeUs: Long) {
        val timeReq = SendspinClientTime(
            payload = ClientTimePayload(clientTransmitted = clientTimeUs)
        )
        sendText(json.encodeToString(timeReq))
    }

    fun sendRequestFormat(codec: String, sampleRate: Int = 48000, bitDepth: Int = 16, channels: Int = 2) {
        val req = SendspinRequestFormat(
            payload = RequestFormatPayload(
                player = RequestFormatPlayerPayload(codec = codec, sampleRate = sampleRate, bitDepth = bitDepth, channels = channels)
            )
        )
        sendText(json.encodeToString(req))
    }

    fun sendGoodbye(reason: String = "user_request") {
        val goodbye = SendspinGoodbye(payload = GoodbyePayload(reason = reason))
        sendText(json.encodeToString(goodbye), force = true)
    }

    /** `client/hello` on the Noise transport, the answer to `server/hello`. */
    fun sendNoiseHello(payload: NoiseClientHelloPayload) {
        sendText(json.encodeToString(SendspinNoiseClientHello(payload = payload)), force = true)
    }

    /** `client/hello` on the rc1 wire, the answer to `server/hello`. */
    fun sendRc1Hello(payload: Rc1ClientHelloPayload) {
        sendText(json.encodeToString(SendspinRc1ClientHello(payload = payload)), force = true)
    }

    /** `client/pair-init`, which precedes `client/pair-finalize` on the rc1 wire. */
    fun sendPairInit(pairingIndex: Int) {
        sendText(json.encodeToString(SendspinClientPairInit(payload = ClientPairInitPayload(pairingIndex))), force = true)
    }

    fun sendPairFinalize(longTermPsk: ByteArray) {
        val finalize = SendspinClientPairFinalize(
            payload = ClientPairFinalizePayload(longTermPsk = SendspinNoise.b64urlEncode(longTermPsk))
        )
        sendText(json.encodeToString(finalize), force = true)
    }

    fun sendPairAbort(reason: String) {
        sendText(json.encodeToString(SendspinPairAbort(payload = PairAbortPayload(reason))), force = true)
    }

    /** The PSK of the current Noise connection, or null on the legacy transport or before the handshake. */
    fun noisePsk(): ResolvedPsk? = link?.noise?.psk

    /** The server id of the current Noise connection. */
    fun noiseServerId(): String? = link?.noise?.serverId

    /**
     * Sends a control message on the published link, encrypted when the link is
     * Noise. On Noise only a [force]d message passes while a hello exchange is
     * open (see [NoiseClientSession]); the others are dropped, which is harmless
     * because the manager reports its full state once the exchange ends.
     */
    private fun sendText(text: String, force: Boolean = false) {
        val current = link ?: return
        val noise = current.noise
        if (noise == null) {
            current.socket.send(text)
        } else {
            noise.sendText(text, force) { frame -> current.socket.send(frame.toByteString()) }
        }
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
