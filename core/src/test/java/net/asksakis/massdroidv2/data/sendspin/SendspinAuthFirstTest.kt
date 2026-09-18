package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Test

/**
 * Pins that the auth frame is the first thing the server sees.
 *
 * Music Assistant closes a Sendspin socket with 4001 "First message must be
 * auth" when the first frame carries any other type, and OkHttp hands back the
 * socket handle before the handshake completes while queueing whatever is sent
 * in the meantime. A periodic sender, such as the clock's time request, could
 * therefore overtake the auth frame. It did on 2026-09-17: three consecutive
 * reconnect attempts were rejected and the phone was silent for thirteen
 * seconds until the fourth won the race.
 */
class SendspinAuthFirstTest {

    // The same configuration the app injects (AppModule). `encodeDefaults` is
    // what puts "type":"auth" on the wire, since the field carries a default.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private class Wire {
        val sent = mutableListOf<String>()

        // Written from the client's IO coroutine and read by the test thread, so
        // the test needs the visibility guarantee to see it at all.
        @Volatile
        var listener: WebSocketListener? = null
        val socket: WebSocket = mockk(relaxed = true)
    }

    /** Every client owns a live IO scope, so none may outlive its test. */
    private val started = mutableListOf<SendspinClient>()

    @After
    fun stopClients() {
        started.forEach { it.stop() }
        started.clear()
    }

    private fun clientOn(wire: Wire): SendspinClient {
        every { wire.socket.send(any<String>()) } answers {
            wire.sent += firstArg<String>()
            true
        }
        val listenerSlot = slot<WebSocketListener>()
        val http = mockk<OkHttpClient>()
        every { http.newWebSocket(any(), capture(listenerSlot)) } answers {
            wire.listener = listenerSlot.captured
            wire.socket
        }
        return SendspinClient({ http }, json).also { started += it }
    }

    /** The connect runs on the client's own IO scope, so wait for it, briefly. */
    private fun awaitConnect(wire: Wire) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (wire.listener != null) return
            Thread.sleep(10)
        }
        throw AssertionError("the client never opened a socket")
    }

    private fun openSocket(wire: Wire) {
        val response = mockk<Response>(relaxed = true)
        requireNotNull(wire.listener).onOpen(wire.socket, response)
    }

    private fun start(client: SendspinClient) = client.start("client-1") {
        SendspinClient.Credentials(serverUrl = "https://ma.example", token = "tok")
    }

    @Test
    fun `nothing is written to the wire before the auth frame`() {
        val wire = Wire()
        val client = clientOn(wire)
        start(client)
        awaitConnect(wire)

        // The socket handle exists but the handshake has not completed. A frame
        // sent now would be queued ahead of the auth frame.
        client.sendClientState(volume = 30)
        client.sendTimeRequest(clientTimeUs = 1_000)

        assertThat(wire.sent).isEmpty()
    }

    @Test
    fun `auth goes first, and the rest follows once it is written`() {
        val wire = Wire()
        val client = clientOn(wire)
        start(client)
        awaitConnect(wire)
        client.sendTimeRequest(clientTimeUs = 1_000)

        openSocket(wire)
        client.sendTimeRequest(clientTimeUs = 2_000)

        assertThat(wire.sent).hasSize(2)
        assertThat(wire.sent.first()).contains("\"auth\"")
        assertThat(wire.sent.last()).contains("client/time")
    }

    @Test
    fun `a lost transport closes the gate again`() {
        val wire = Wire()
        val client = clientOn(wire)
        start(client)
        awaitConnect(wire)
        openSocket(wire)
        wire.sent.clear()

        requireNotNull(wire.listener).onFailure(wire.socket, RuntimeException("dropped"), null)
        client.sendClientState(volume = 30)

        assertThat(wire.sent).isEmpty()
    }
}
