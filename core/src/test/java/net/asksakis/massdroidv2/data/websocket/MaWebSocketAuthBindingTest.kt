package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient.ConnectOrigin
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test

/**
 * The sign-in runs on the socket that greeted it, and a refusal counts only when it answers the
 * token itself. A sign-in that a newer socket replaced used to send its commands on whatever
 * socket was current, so a newer connect that completed between its last check and its send
 * carried one server's token to another machine at the same address, whose refusal then deleted
 * the token. A token that only timed out was deleted when the password after it was refused
 * (code check, 2026-11-02).
 *
 * Runs against real sockets on local MockWebServers.
 */
class MaWebSocketAuthBindingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fakes = mutableListOf<FakeServer>()
    private val rejected = CopyOnWriteArrayList<RejectedToken>()
    private var client = MaWebSocketClient(OkHttpClient(), json)

    @After
    fun tearDown() {
        client.disconnect()
        fakes.flatMap { it.sockets }.forEach { it.close(1000, null) }
        // MockWebServer can give up waiting for a WebSocket's queue to drain; the sockets are
        // closed above, and a server left behind only holds a local port until the run ends.
        fakes.forEach { runCatching { it.server.shutdown() } }
    }

    /**
     * A Music Assistant stand-in that greets as [serverId] and records every command. How it
     * answers `auth` and `auth/login` is up to the test; the last argument is the command's text.
     */
    private inner class FakeServer(
        private val serverId: String,
        private val answerAuth: (WebSocket, String, String) -> Unit = { ws, id, _ -> ws.send(ok(id)) },
        private val answerLogin: (WebSocket, String) -> Unit = { ws, id -> ws.send(ok(id)) },
    ) {
        val commands = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val authReceived = CompletableDeferred<Unit>()
        val server = MockWebServer()

        init {
            fakes += this
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets += webSocket
                    webSocket.send(
                        """{"server_id":"$serverId","server_version":"2.10.9","schema_version":28,"base_url":"x"}"""
                    )
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    commands += text
                    val obj = json.parseToJsonElement(text).jsonObject
                    val id = obj["message_id"]?.jsonPrimitive?.content ?: return
                    when (obj["command"]?.jsonPrimitive?.content) {
                        "auth" -> {
                            authReceived.complete(Unit)
                            answerAuth(webSocket, id, text)
                        }
                        "auth/login" -> answerLogin(webSocket, id)
                        else -> webSocket.send(ok(id))
                    }
                }
            }))
        }

        val url: String get() = server.url("/").toString().trimEnd('/')
        val text: String get() = commands.joinToString()
    }

    private fun ok(id: String) = """{"message_id":"$id","result":{}}"""

    private fun refusal(id: String) = """{"message_id":"$id","error_code":20,"details":"Invalid token"}"""

    private fun kotlinx.coroutines.CoroutineScope.watchRejections() =
        launch { client.rejectedTokens.collect { rejected += it } }

    @Test
    fun `a refused token is reported with the server that greeted its socket`() = runBlocking {
        val home = FakeServer("home", answerAuth = { ws, id, _ -> ws.send(refusal(id)) })
        val watcher = watchRejections()

        client.connect(home.url, "home token", ConnectOrigin.SAVED_CREDENTIALS)
        withTimeout(WAIT_MS) { client.connectionState.first { it is ConnectionState.Connected || it is ConnectionState.Error } }

        assertThat(rejected.map { it.serverId to it.token }).containsExactly("home" to "home token")
        watcher.cancel()
    }

    @Test
    fun `a newer connect that completes before an old sign-in sends does not get its token`() = runBlocking {
        // The old sign-in has passed its last generation check and is about to send; the newer
        // connect then runs to completion and its socket becomes the current one.
        val homeHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        client = MaWebSocketClient(OkHttpClient(), json, beforeAuthSend = {
            if (first.compareAndSet(true, false)) {
                homeHeld.complete(Unit)
                release.await()
            }
        })
        val home = FakeServer("home")
        val other = FakeServer("other", answerAuth = { ws, id, text ->
            ws.send(if ("other token" in text) ok(id) else refusal(id))
        })
        val watcher = watchRejections()

        client.connect(home.url, "home token", ConnectOrigin.SAVED_CREDENTIALS)
        withTimeout(WAIT_MS) { homeHeld.await() }
        client.connect(other.url, "other token", ConnectOrigin.SAVED_CREDENTIALS)
        withTimeout(WAIT_MS) { other.authReceived.await() }
        withTimeout(WAIT_MS) { client.connectionState.first { it is ConnectionState.Connected } }
        release.complete(Unit)

        // Bounded: with the binding nothing more ever reaches the other server.
        withTimeoutOrNull(SHORT_TIMEOUT_MS) { while ("home token" !in other.text) delay(POLL_MS) }
        assertThat(other.text).doesNotContain("home token")
        assertThat(rejected).isEmpty()
        watcher.cancel()
    }

    @Test
    fun `a token that timed out is not forgotten when the password after it is refused`() = runBlocking {
        client = MaWebSocketClient(OkHttpClient(), json, authCommandTimeoutMs = SHORT_TIMEOUT_MS)
        val home = FakeServer(
            "home",
            answerAuth = { _, _, _ -> },
            answerLogin = { ws, id ->
                ws.send("""{"message_id":"$id","result":{"success":false,"error":"Invalid password"}}""")
            }
        )
        val watcher = watchRejections()
        client.setSavedCredentials("alice", "changed password")

        client.connect(home.url, "home token", ConnectOrigin.SAVED_CREDENTIALS)
        withTimeout(WAIT_MS) { client.connectionState.first { it is ConnectionState.Error } }

        assertThat(home.text).contains("auth/login")
        assertThat(rejected).isEmpty()
        assertThat(client.authToken).isEqualTo("home token")
        watcher.cancel()
    }

    @Test
    fun `a refused token is not resent when the password after it times out`() = runBlocking {
        client = MaWebSocketClient(OkHttpClient(), json, authCommandTimeoutMs = SHORT_TIMEOUT_MS)
        val home = FakeServer("home", answerAuth = { ws, id, _ -> ws.send(refusal(id)) }, answerLogin = { _, _ -> })
        val watcher = watchRejections()
        client.setSavedCredentials("alice", "home password")

        client.connect(home.url, "home token", ConnectOrigin.SAVED_CREDENTIALS)
        // Not the Error state: after a sign-in with no answer the client closes the socket to
        // retry, and the state moves on at once, so a waiting collector can miss the Error.
        // The token is dropped only once the password after it has timed out.
        withTimeout(WAIT_MS) { while (rejected.isEmpty() || client.authToken != null) delay(POLL_MS) }

        assertThat(home.text).contains("auth/login")
        assertThat(rejected.map { it.token }).containsExactly("home token")
        assertThat(client.authToken).isNull()
        watcher.cancel()
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SHORT_TIMEOUT_MS = 500L
        const val POLL_MS = 20L
    }
}
