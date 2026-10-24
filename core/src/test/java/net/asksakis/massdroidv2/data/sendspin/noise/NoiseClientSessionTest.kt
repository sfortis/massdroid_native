package net.asksakis.massdroidv2.data.sendspin.noise

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Drives a whole client session through the frames the server's own code produced: the init
 * exchange, the handshake, transport traffic, and an in-band re-handshake onto a pairing PSK.
 * See `generate_noise_vectors_9_1_1.py` for how the vectors were made.
 */
class NoiseClientSessionTest {

    private val vectors: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("sendspin/noise_vectors_9_1_1.json")).readText()
    ).jsonObject

    private fun str(key: String) = vectors.getValue(key).jsonPrimitive.content
    private fun bytes(key: String) = SendspinNoise.b64urlDecode(str(key))
    private fun list(key: String) = vectors.getValue(key).jsonArray.map { SendspinNoise.b64urlDecode(it.jsonPrimitive.content) }

    private val serverId get() = str("server_static_public")

    private val storedPsks: Map<String, ResolvedPsk> by lazy {
        listOf(
            ResolvedPsk(SendspinNoise.pskId(bytes("psk")), bytes("psk"), PskCategory.LONG_TERM, serverId),
            ResolvedPsk(SendspinNoise.pskId(bytes("pairing_psk")), bytes("pairing_psk"), PskCategory.PAIRING, null),
        ).associateBy { it.pskId }
    }

    private fun session(resolver: PskResolver = PskResolver { storedPsks[it] }): NoiseClientSession {
        val ephemerals = ArrayDeque(
            listOf(bytes("client_ephemeral_private"), bytes("client_ephemeral2_private"))
                .map(NoiseKeyPair::fromPrivateKey)
        )
        return NoiseClientSession(
            identity = NoiseKeyPair.fromPrivateKey(bytes("client_static_private")),
            pskResolver = resolver,
            ephemerals = { ephemerals.removeFirst() },
        )
    }

    private fun message1Text() = NoiseClientSession.handshakeText(bytes("msg1"))

    private fun dataOf(handshakeText: String): String =
        Json.parseToJsonElement(handshakeText).jsonObject.getValue("payload").jsonObject.getValue("data").jsonPrimitive.content

    @Test
    fun `the session speaks the server's protocol from client init to a re-handshake`() {
        val session = session()

        assertThat(session.clientInitText).isEqualTo(str("client_init"))
        assertThat(session.onHandshakeText(str("server_init"))).isNull()
        val message2 = checkNotNull(session.onHandshakeText(message1Text()))

        assertThat(dataOf(message2)).isEqualTo(str("msg2"))
        assertThat(session.isEstablished).isTrue()
        assertThat(session.serverId).isEqualTo(serverId)
        assertThat(session.psk?.category).isEqualTo(PskCategory.LONG_TERM)

        val noSink: (ByteArray) -> Boolean = { error("nothing should be sent") }
        val serverFrames = list("server_frames")
        assertThat(session.receive(serverFrames[0], noSink)).isInstanceOf(NoiseClientSession.Received.Text::class.java)
        assertThat(session.receive(serverFrames[1], noSink)).isInstanceOf(NoiseClientSession.Received.Binary::class.java)

        // The same traffic the server saw before it re-handshakes, so both send nonces line up.
        val clientHello = bytes("client_plaintext")
        val helloText = String(clientHello, 1, clientHello.size - 1, Charsets.UTF_8)
        val big = ByteArray(vectors.getValue("fragment_plaintext_length").jsonPrimitive.int) {
            if (it == 0) 4 else ((it - 1) % 251).toByte()
        }
        session.endExchange()
        session.sendText(helloText) { true }
        session.sendBinary(big) { true }

        val sent = mutableListOf<ByteArray>()
        val rehandshake = session.receive(bytes("rehandshake_server_frame")) { sent += it; true }

        assertThat(rehandshake).isInstanceOf(NoiseClientSession.Received.Rehandshake::class.java)
        assertThat(session.psk?.category).isEqualTo(PskCategory.PAIRING)
        assertThat(sent.map(SendspinNoise::b64urlEncode)).containsExactly(str("rehandshake_client_frame"))

        // After the swap both directions use the new keys, and only the hello may go out.
        val serverHello = session.receive(bytes("post_rehandshake_server_frame"), noSink) as NoiseClientSession.Received.Text
        assertThat(serverHello.text).contains("server/hello")
        assertThat(session.sendText("{\"type\":\"client/time\"}", sink = noSink)).isFalse()
        val after = mutableListOf<ByteArray>()
        session.sendText(helloText, force = true) { after += it; true }
        assertThat(after.map(SendspinNoise::b64urlEncode)).containsExactly(str("post_rehandshake_client_frame"))
    }

    @Test
    fun `until the exchange ends only forced sends leave`() {
        val session = session()
        session.onHandshakeText(str("server_init"))
        session.onHandshakeText(message1Text())
        val sent = mutableListOf<ByteArray>()

        assertThat(session.sendText("{\"type\":\"client/time\"}") { sent += it; true }).isFalse()
        assertThat(session.sendText("{\"type\":\"client/hello\"}", force = true) { sent += it; true }).isTrue()
        session.endExchange()
        assertThat(session.sendText("{\"type\":\"client/time\"}") { sent += it; true }).isTrue()
        assertThat(sent).hasSize(2)
    }

    @Test
    fun `an unknown psk id aborts the handshake`() {
        val session = session(resolver = { null })
        session.onHandshakeText(str("server_init"))

        assertThrows(NoiseProtocolException::class.java) { session.onHandshakeText(message1Text()) }
        assertThat(session.isEstablished).isFalse()
    }

    @Test
    fun `a long-term psk bound to another server is refused`() {
        val psk = bytes("psk")
        val session = session(resolver = { ResolvedPsk(it, psk, PskCategory.LONG_TERM, serverId = "another-server") })
        session.onHandshakeText(str("server_init"))

        assertThrows(NoiseProtocolException::class.java) { session.onHandshakeText(message1Text()) }
    }

    @Test
    fun `server init with another protocol version is refused`() {
        val session = session()
        val serverInit = str("server_init").replace("\"version\":1", "\"version\":2")

        assertThrows(NoiseProtocolException::class.java) { session.onHandshakeText(serverInit) }
    }

    @Test
    fun `a frame that is not server init is refused`() {
        assertThrows(NoiseProtocolException::class.java) { session().onHandshakeText("{\"type\":\"server/hello\"}") }
        assertThrows(NoiseProtocolException::class.java) { session().onHandshakeText("not json") }
    }

    @Test
    fun `transport frames before the handshake are refused`() {
        assertThrows(NoiseProtocolException::class.java) {
            session().receive(list("server_frames")[0]) { true }
        }
    }
}
