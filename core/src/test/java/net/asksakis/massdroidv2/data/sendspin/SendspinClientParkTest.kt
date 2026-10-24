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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the parked connection the phone keeps while MA has unpaired it and the user has not asked
 * to pair again. Left open with no player role, the connection kept a player in MA that accepted
 * play commands and played nothing (2026-10-24). Parked, it says goodbye and stays closed, and
 * only a refresh opens it again. A stopped client, unlike a parked one, cannot be refreshed.
 */
class SendspinClientParkTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private class Wire {
        val sent = mutableListOf<String>()
        val opened = AtomicInteger(0)

        @Volatile
        var listener: WebSocketListener? = null
        val socket: WebSocket = mockk(relaxed = true)
    }

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
            wire.opened.incrementAndGet()
            wire.socket
        }
        return SendspinClient({ http }, json).also { started += it }
    }

    /** The connect runs on the client's own IO scope, so wait for it, briefly. */
    private fun awaitOpened(wire: Wire, count: Int) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (wire.opened.get() >= count) return
            Thread.sleep(10)
        }
        throw AssertionError("the client opened ${wire.opened.get()} sockets, expected $count")
    }

    private fun connect(client: SendspinClient, wire: Wire) {
        client.start("client-1") {
            SendspinClient.Credentials(serverUrl = "https://ma.example", token = "tok")
        }
        awaitOpened(wire, 1)
        requireNotNull(wire.listener).onOpen(wire.socket, mockk<Response>(relaxed = true))
    }

    @Test
    fun `park says goodbye with its reason and leaves the client disconnected`() {
        val wire = Wire()
        val client = clientOn(wire)
        connect(client, wire)

        client.park(GOODBYE_REASON_USER_REQUEST)

        assertThat(wire.sent.last()).contains("client/goodbye")
        assertThat(wire.sent.last()).contains("\"$GOODBYE_REASON_USER_REQUEST\"")
        assertThat(client.state.value).isEqualTo(SendspinState.DISCONNECTED)
        assertThat(client.isConnected()).isFalse()
    }

    @Test
    fun `a parked client does not reconnect by itself`() {
        val wire = Wire()
        val client = clientOn(wire)
        connect(client, wire)
        val listener = requireNotNull(wire.listener)

        client.park(GOODBYE_REASON_USER_REQUEST)
        // The close the park started comes back as a callback; it must not schedule a reconnect.
        listener.onClosed(wire.socket, 1000, "Client disconnect")
        Thread.sleep(1_500)

        assertThat(wire.opened.get()).isEqualTo(1)
    }

    @Test
    fun `refresh opens a parked connection again`() {
        val wire = Wire()
        val client = clientOn(wire)
        connect(client, wire)

        client.park(GOODBYE_REASON_USER_REQUEST)
        client.refresh()

        awaitOpened(wire, 2)
    }

    @Test
    fun `refresh does not reopen a stopped client`() {
        val wire = Wire()
        val client = clientOn(wire)
        connect(client, wire)

        client.stop()
        client.refresh()
        Thread.sleep(500)

        assertThat(wire.opened.get()).isEqualTo(1)
    }
}
