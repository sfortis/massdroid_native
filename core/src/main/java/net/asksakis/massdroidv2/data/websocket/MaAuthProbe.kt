package net.asksakis.massdroidv2.data.websocket

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.asksakis.massdroidv2.domain.model.AuthProviderInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot WebSocket helper for Music Assistant's unauthenticated commands.
 *
 * Connects, sends a single command, returns the parsed response, then closes.
 * Used by the Settings flow to discover available login providers and to start
 * an OAuth authorization without disturbing the long-lived [MaWebSocketClient]
 * connection (which may not exist yet at first-time setup).
 */
class MaAuthProbe(
    /**
     * The client for a URL, the same one the long-lived connection would use, so a server
     * behind a client certificate or a private host with a certificate for another name
     * answers the check the way it answers the connection.
     */
    private val clientFor: (String) -> OkHttpClient,
    private val json: Json
) {
    companion object {
        private const val TAG = "MaAuthProbe"
        private const val DEFAULT_TIMEOUT_MS = 6_000L
        private const val REACH_TIMEOUT_MS = 2_500L
    }

    /**
     * Whether anything answers HTTP at [origin] within [timeoutMs]. Any status counts, because
     * the question is only whether this phone can reach the address at all.
     */
    suspend fun answersHttp(origin: String, timeoutMs: Long = REACH_TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(origin).head().build()
        } catch (_: IllegalArgumentException) {
            return@withContext false
        }
        val client = clientFor(origin).newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        try {
            client.newCall(request).execute().use { true }
        } catch (e: IOException) {
            Log.d(TAG, "No answer from $origin: ${e.message}")
            false
        }
    }

    /** Whether a Music Assistant server answers at [serverUrl]. */
    suspend fun answersAsMusicAssistant(serverUrl: String): Boolean =
        sendUnauthenticated(serverUrl = serverUrl, command = "auth/providers") != null

    /**
     * Asks [serverUrl] who it is. Music Assistant greets every new connection with its server
     * info before any command, so the version comes from that greeting and the login providers
     * from `auth/providers` on the same connection. An address that does not greet with a
     * server version is not Music Assistant, whatever else it answers.
     */
    suspend fun identify(serverUrl: String): ServerAnswer {
        val exchange = exchange(serverUrl = serverUrl, command = "auth/providers")
        // A safe cast: another service at /ws may greet with an object here, and jsonPrimitive throws.
        val version = (exchange.hello?.get("server_version") as? JsonPrimitive)?.contentOrNull
            ?: return ServerAnswer.Failed(exchange.error)
        val providers = exchange.result?.let { raw ->
            runCatching {
                json.decodeFromJsonElement(ListSerializer(MaAuthProvider.serializer()), raw)
            }.getOrNull()
        }.orEmpty().map { it.toInfo() }
        val serverId = (exchange.hello?.get("server_id") as? JsonPrimitive)?.contentOrNull
        return ServerAnswer.MusicAssistant(version, providers, serverId)
    }

    /**
     * Asks the server to start an OAuth flow for [providerId] and returns the
     * authorization URL the user must visit (typically the Home Assistant login).
     * The MA server tracks state internally, keyed by [returnUrl].
     */
    suspend fun fetchAuthorizationUrl(
        serverUrl: String,
        providerId: String,
        returnUrl: String
    ): String? {
        val raw = sendUnauthenticated(
            serverUrl = serverUrl,
            command = "auth/authorization_url",
            args = buildJsonObject {
                put("provider_id", providerId)
                put("return_url", returnUrl)
            }
        ) ?: return null
        val obj = raw as? JsonObject ?: return null
        return obj["authorization_url"]?.jsonPrimitive?.contentOrNull
    }

    private suspend fun sendUnauthenticated(
        serverUrl: String,
        command: String,
        args: JsonObject? = null
    ): JsonElement? = exchange(serverUrl, command, args).result

    /** What one connection brought back: the server's greeting, the command's result, the failure. */
    private class Exchange(val hello: JsonObject?, val result: JsonElement?, val error: Throwable?)

    private suspend fun exchange(
        serverUrl: String,
        command: String,
        args: JsonObject? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Exchange = withContext(Dispatchers.IO) {
        val wsUrl = toWsUrl(serverUrl) ?: return@withContext Exchange(null, null, null)
        val messageId = UUID.randomUUID().toString().take(12)
        val deferred = CompletableDeferred<JsonElement?>()
        val socketRef = AtomicReference<WebSocket?>()
        val helloRef = AtomicReference<JsonObject?>()
        val errorRef = AtomicReference<Throwable?>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return
                // The server's greeting carries no message id; any other unrelated frame is ignored.
                val mid = (msg["message_id"] as? JsonPrimitive)?.contentOrNull ?: run {
                    if (msg.containsKey("server_version")) helloRef.compareAndSet(null, msg)
                    return
                }
                if (mid != messageId) return
                deferred.complete(msg["result"])
                webSocket.close(1000, "probe done")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure for $command: ${t.message}")
                errorRef.set(t)
                deferred.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }

        // The probe runs while the user types, so the address is often half written. OkHttp
        // throws on a host it cannot parse (a space from autocorrect, a lone "http://"), and
        // that exception used to escape the ViewModel and end the app.
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Not probing an invalid URL: ${e.message}")
            return@withContext Exchange(null, null, e)
        }
        val socket = clientFor(serverUrl).newWebSocket(request, listener)
        // Closed on every way out, cancellation included: the check is cancelled at each
        // keystroke, and an abandoned socket stays open until the server drops it.
        try {
            val payload = buildJsonObject {
                put("command", command)
                put("message_id", messageId)
                if (args != null) put("args", args)
            }.toString()

            // Brief wait for OPEN, then send. If we never opened, the failure will
            // resolve the deferred with null below.
            val opened = withTimeoutOrNull(timeoutMs) {
                while (socketRef.get() == null && !deferred.isCompleted) {
                    kotlinx.coroutines.delay(50)
                }
                socketRef.get()
            }
            opened?.send(payload)

            val result = try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                null
            }
            Exchange(helloRef.get(), result, errorRef.get())
        } finally {
            runCatching { socket.close(1000, "probe done") }
        }
    }

    private fun toWsUrl(serverUrl: String): String? {
        val trimmed = serverUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            // Refuse to guess a scheme: an unscoped URL would point at an
            // arbitrary host. Caller is expected to validate first.
            else -> return null
        }
        return "$withScheme/ws"
    }
}

/** What an address said when asked whether it is Music Assistant. */
sealed interface ServerAnswer {
    data class MusicAssistant(
        val version: String,
        val providers: List<AuthProviderInfo>,
        val serverId: String? = null
    ) : ServerAnswer

    /** No Music Assistant answered. [error] is the connection failure, null when there was none to name. */
    data class Failed(val error: Throwable?) : ServerAnswer
}

@kotlinx.serialization.Serializable
private data class MaAuthProvider(
    @kotlinx.serialization.SerialName("provider_id") val providerId: String,
    @kotlinx.serialization.SerialName("provider_type") val providerType: String,
    @kotlinx.serialization.SerialName("requires_redirect") val requiresRedirect: Boolean = false
)

private fun MaAuthProvider.toInfo() = AuthProviderInfo(
    providerId = providerId,
    providerType = providerType,
    requiresRedirect = requiresRedirect
)
