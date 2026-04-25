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
import kotlinx.serialization.json.boolean
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
import java.util.UUID
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
    private val httpClient: OkHttpClient,
    private val json: Json
) {
    companion object {
        private const val TAG = "MaAuthProbe"
        private const val DEFAULT_TIMEOUT_MS = 6_000L
    }

    /** List of providers the server supports (empty on failure). */
    suspend fun fetchProviders(serverUrl: String): List<AuthProviderInfo> {
        val raw = sendUnauthenticated(
            serverUrl = serverUrl,
            command = "auth/providers"
        ) ?: return emptyList()
        return runCatching {
            json.decodeFromJsonElement(
                ListSerializer(MaAuthProvider.serializer()),
                raw
            )
        }.getOrElse {
            Log.w(TAG, "fetchProviders parse failed: ${it.message}")
            emptyList()
        }.map {
            AuthProviderInfo(
                providerId = it.providerId,
                providerType = it.providerType,
                requiresRedirect = it.requiresRedirect
            )
        }
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
        args: JsonObject? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): JsonElement? = withContext(Dispatchers.IO) {
        val wsUrl = toWsUrl(serverUrl) ?: return@withContext null
        val messageId = UUID.randomUUID().toString().take(12)
        val deferred = CompletableDeferred<JsonElement?>()
        val socketRef = AtomicReference<WebSocket?>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef.set(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return
                // Ignore the initial server hello and any unrelated frames.
                val mid = msg["message_id"]?.jsonPrimitive?.contentOrNull ?: return
                if (mid != messageId) return
                deferred.complete(msg["result"])
                webSocket.close(1000, "probe done")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure for $command: ${t.message}")
                deferred.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }

        val request = Request.Builder().url(wsUrl).build()
        val socket = httpClient.newWebSocket(request, listener)

        // Wait until the socket is open before sending the command.
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

        runCatching { socket.close(1000, "probe done") }
        result
    }

    private fun toWsUrl(serverUrl: String): String? {
        val trimmed = serverUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> "ws://$trimmed"
        }
        return "$withScheme/ws"
    }
}

@kotlinx.serialization.Serializable
private data class MaAuthProvider(
    @kotlinx.serialization.SerialName("provider_id") val providerId: String,
    @kotlinx.serialization.SerialName("provider_type") val providerType: String,
    @kotlinx.serialization.SerialName("requires_redirect") val requiresRedirect: Boolean = false
)
