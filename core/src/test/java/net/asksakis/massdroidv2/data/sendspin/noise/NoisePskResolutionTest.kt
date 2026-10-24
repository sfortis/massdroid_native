package net.asksakis.massdroidv2.data.sendspin.noise

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Which PSK answers handshake message 1 on each wire.
 *
 * On the legacy wire a miss fails the handshake. On the rc1 wire a PSK counts only under the
 * category the server declared, and a miss in the initial handshake is answered under the
 * Sentinel PSK, which the server accepts by replaying message 1 (`fork_at_message_2`). The
 * expected bytes come from `generate_noise_vectors_rc1.py`.
 */
class NoisePskResolutionTest {

    private val rc1 = load("sendspin/noise_vectors_rc1.json")
    private val legacy = load("sendspin/noise_vectors_9_1_1.json")

    private fun load(resource: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource(resource)).readText()
    ).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.bytes(key: String) = SendspinNoise.b64urlDecode(str(key))

    private fun session(v: JsonObject, ephemeral: ByteArray, held: List<ResolvedPsk>): NoiseClientSession {
        val byId = held.associateBy { it.pskId }
        val ephemerals = ArrayDeque(listOf(ephemeral, v.bytes("client_ephemeral2_private")))
        return NoiseClientSession(
            identity = NoiseKeyPair.fromPrivateKey(v.bytes("client_static_private")),
            pskResolver = PskResolver { byId[it] },
            ephemerals = { NoiseKeyPair.fromPrivateKey(ephemerals.removeFirst()) },
        ).also { it.onHandshakeText(v.str("server_init")) }
    }

    private fun dataOf(handshakeText: String): String =
        Json.parseToJsonElement(handshakeText).jsonObject.getValue("payload").jsonObject.getValue("data").jsonPrimitive.content

    private fun longTerm(v: JsonObject, serverId: String = v.str("server_static_public")) =
        ResolvedPsk(v.str("psk_id"), v.bytes("psk"), PskCategory.LONG_TERM, serverId)

    @Test
    fun `on rc1 a psk the client does not hold is answered under the Sentinel, as the server expects`() {
        val fallback = rc1.getValue("sentinel_fallback").jsonObject
        val session = session(rc1, fallback.bytes("client_ephemeral_private"), held = emptyList())

        val message2 = checkNotNull(session.onHandshakeText(NoiseClientSession.handshakeText(fallback.bytes("msg1"))))

        assertThat(dataOf(message2)).isEqualTo(fallback.str("msg2"))
        assertThat(session.psk?.category).isEqualTo(PskCategory.SENTINEL)
        assertThat(session.isEstablished).isTrue()
    }

    @Test
    fun `on the legacy wire a psk the client does not hold fails the handshake`() {
        val session = session(legacy, legacy.bytes("client_ephemeral_private"), held = emptyList())

        assertThrows(NoiseProtocolException::class.java) {
            session.onHandshakeText(NoiseClientSession.handshakeText(legacy.bytes("msg1")))
        }
    }

    @Test
    fun `on rc1 a psk held under another category is a miss`() {
        // The server declares lt; the client holds the same bytes as a pairing PSK.
        val asPairing = ResolvedPsk(rc1.str("psk_id"), rc1.bytes("psk"), PskCategory.PAIRING, null)
        val session = session(rc1, rc1.bytes("client_ephemeral_private"), held = listOf(asPairing))

        session.onHandshakeText(NoiseClientSession.handshakeText(rc1.bytes("msg1")))

        assertThat(session.psk?.category).isEqualTo(PskCategory.SENTINEL)
    }

    @Test
    fun `on rc1 a psk bound to another server is a miss, on the legacy wire a failure`() {
        val other = "x".repeat(SendspinNoise.PEER_ID_LENGTH)
        val rc1Session = session(rc1, rc1.bytes("client_ephemeral_private"), held = listOf(longTerm(rc1, other)))
        val legacySession = session(legacy, legacy.bytes("client_ephemeral_private"), held = listOf(longTerm(legacy, other)))

        rc1Session.onHandshakeText(NoiseClientSession.handshakeText(rc1.bytes("msg1")))

        assertThat(rc1Session.psk?.category).isEqualTo(PskCategory.SENTINEL)
        assertThrows(NoiseProtocolException::class.java) {
            legacySession.onHandshakeText(NoiseClientSession.handshakeText(legacy.bytes("msg1")))
        }
    }

    @Test
    fun `on rc1 the long-term psk the server names is used when the client holds it`() {
        val session = session(rc1, rc1.bytes("client_ephemeral_private"), held = listOf(longTerm(rc1)))

        val message2 = checkNotNull(session.onHandshakeText(NoiseClientSession.handshakeText(rc1.bytes("msg1"))))

        assertThat(dataOf(message2)).isEqualTo(rc1.str("msg2"))
        assertThat(session.psk?.category).isEqualTo(PskCategory.LONG_TERM)
    }

    /** Establishes the rc1 session and consumes every server frame sent before its re-handshake. */
    private fun rc1SessionAtRehandshake(held: List<ResolvedPsk>): NoiseClientSession {
        val session = session(rc1, rc1.bytes("client_ephemeral_private"), held)
        session.onHandshakeText(NoiseClientSession.handshakeText(rc1.bytes("msg1")))
        val frames = rc1.getValue("server_frames").jsonArray + rc1.getValue("server_fragment_frames").jsonArray
        for (frame in frames) session.receive(SendspinNoise.b64urlDecode(frame.jsonPrimitive.content)) { true }
        session.receive(rc1.bytes("single_fragment_frame")) { true }
        // The client traffic the server saw before it re-handshaked, so the send nonces line up:
        // the hello and one large binary message in two frames.
        val hello = rc1.bytes("client_plaintext")
        val big = ByteArray(rc1.getValue("fragment_plaintext_length").jsonPrimitive.content.toInt()) {
            if (it == 0) 4 else ((it - 1) % 251).toByte()
        }
        session.endExchange()
        session.sendText(String(hello, 1, hello.size - 1, Charsets.UTF_8)) { true }
        session.sendBinary(big) { true }
        return session
    }

    @Test
    fun `on rc1 a re-handshake onto the pairing psk finds it among the pairing psks`() {
        val pairing = ResolvedPsk(
            SendspinNoise.pskId(rc1.bytes("pairing_psk")), rc1.bytes("pairing_psk"), PskCategory.PAIRING, null
        )
        val session = rc1SessionAtRehandshake(held = listOf(longTerm(rc1), pairing))
        val sent = mutableListOf<ByteArray>()

        val received = session.receive(rc1.bytes("rehandshake_server_frame")) { sent += it; true }

        assertThat(received).isInstanceOf(NoiseClientSession.Received.Rehandshake::class.java)
        assertThat(session.psk?.category).isEqualTo(PskCategory.PAIRING)
        assertThat(SendspinNoise.b64urlEncode(sent.single())).isEqualTo(rc1.str("rehandshake_client_frame"))
    }

    @Test
    fun `on rc1 a miss in a re-handshake still fails, because the old keys stay valid there`() {
        val session = rc1SessionAtRehandshake(held = listOf(longTerm(rc1)))

        assertThrows(NoiseProtocolException::class.java) {
            session.receive(rc1.bytes("rehandshake_server_frame")) { true }
        }
    }
}
