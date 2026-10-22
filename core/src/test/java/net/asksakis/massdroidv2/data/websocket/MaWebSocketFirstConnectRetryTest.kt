package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient.ConnectOrigin
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.net.ServerSocket

/**
 * Whether a first connection that fails is retried.
 *
 * Issue #78: Android Auto started the app in the background on mobile data, the connection was
 * made before Tailscale was up, and nothing ever retried it, so the car showed an empty library
 * until the app was opened by hand. A connection with saved credentials is retried like a lost
 * one. A sign-in the user has just typed is not, because a failure there is most likely a wrong
 * address and the user is looking at the error.
 *
 * Runs against a real socket: the port of a closed local server refuses the connection at once.
 */
class MaWebSocketFirstConnectRetryTest {

    private val client = MaWebSocketClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

    private val refusedUrl: String = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }

    @After
    fun tearDown() = client.disconnect()

    @Test
    fun `a failed first connect with saved credentials is retried`() = runBlocking {
        client.connect(refusedUrl, "token", ConnectOrigin.SAVED_CREDENTIALS)

        val retrying = withTimeout(WAIT_MS) { client.isReconnecting.first { it } }
        assertThat(retrying).isTrue()
    }

    @Test
    fun `a failed first sign-in is reported and not retried`() = runBlocking {
        client.connect(refusedUrl, "token", ConnectOrigin.SIGN_IN)

        withTimeout(WAIT_MS) { client.connectionState.first { it is ConnectionState.Error } }
        // The retry would be scheduled in the same failure callback that set the error.
        delay(SETTLE_MS)
        assertThat(client.isReconnecting.value).isFalse()
    }

    @Test
    fun `a sign-in after an earlier session is not retried either`() = runBlocking {
        // What a session that once authenticated leaves behind.
        client.retriesEnabled = true

        client.connect(refusedUrl, "token", ConnectOrigin.SIGN_IN)

        withTimeout(WAIT_MS) { client.connectionState.first { it is ConnectionState.Error } }
        delay(SETTLE_MS)
        assertThat(client.isReconnecting.value).isFalse()
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 300L
    }
}
