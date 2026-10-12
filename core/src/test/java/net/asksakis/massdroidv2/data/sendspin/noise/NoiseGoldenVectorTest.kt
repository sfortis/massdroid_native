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
import java.security.MessageDigest

/**
 * Checks the Sendspin Noise client against vectors produced by the server's own code.
 *
 * `core/src/test/resources/sendspin/generate_noise_vectors.py` runs inside the Music Assistant container with aiosendspin
 * 9.1.1 and the noiseprotocol library it builds on, with fixed static and ephemeral keys. Matching
 * those bytes proves the handshake, the key schedule, the AES-GCM nonce layout and the framing
 * agree with the server, which a round trip through our own code alone could not show.
 */
class NoiseGoldenVectorTest {

    private val vectors: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("sendspin/noise_vectors.json")).readText()
    ).jsonObject

    private fun str(key: String) = vectors.getValue(key).jsonPrimitive.content
    private fun bytes(key: String) = SendspinNoise.b64urlDecode(str(key))
    private fun list(key: String) = vectors.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun clientStatic() = NoiseKeyPair.fromPrivateKey(bytes("client_static_private"))

    private fun responder(
        localStatic: NoiseKeyPair = clientStatic(),
        prologue: ByteArray = (str("client_init") + str("server_init")).toByteArray(Charsets.UTF_8),
    ) = NoiseResponderHandshake(
        localStatic = localStatic,
        remoteStaticPublic = bytes("server_static_public"),
        prologue = prologue,
        ephemeral = NoiseKeyPair.fromPrivateKey(bytes("client_ephemeral_private")),
    )

    private fun completedHandshake(): NoiseResponderHandshake.Result {
        val handshake = responder()
        handshake.readMessage1(bytes("msg1"))
        return handshake.writeMessage2(bytes("psk"), "{}".toByteArray())
    }

    @Test
    fun `psk ids and the sentinel psk match the server`() {
        assertThat(SendspinNoise.pskId(bytes("psk"))).isEqualTo(str("psk_id"))
        assertThat(SendspinNoise.b64urlEncode(SendspinNoise.SENTINEL_PSK)).isEqualTo(str("sentinel_psk"))
        assertThat(SendspinNoise.pskId(SendspinNoise.SENTINEL_PSK)).isEqualTo(str("sentinel_psk_id"))
    }

    @Test
    fun `the public key derived from the private key is the client id`() {
        assertThat(clientStatic().peerId).isEqualTo(str("client_static_public"))
        assertThat(clientStatic().peerId).hasLength(SendspinNoise.PEER_ID_LENGTH)
    }

    @Test
    fun `message 1 decrypts to the psk id the server named`() {
        val payload = responder().readMessage1(bytes("msg1"))

        assertThat(String(payload, Charsets.UTF_8)).isEqualTo(str("msg1_payload"))
    }

    @Test
    fun `message 2 and the handshake hash are byte for byte the server's`() {
        val result = completedHandshake()

        assertThat(SendspinNoise.b64urlEncode(result.message)).isEqualTo(str("msg2"))
        assertThat(SendspinNoise.b64urlEncode(result.handshakeHash)).isEqualTo(str("handshake_hash"))
    }

    @Test
    fun `message 1 for another client fails authentication`() {
        val stranger = NoiseKeyPair.fromPrivateKey(ByteArray(32) { 7 })

        assertThrows(NoiseProtocolException::class.java) {
            responder(localStatic = stranger).readMessage1(bytes("msg1"))
        }
    }

    @Test
    fun `a different prologue fails authentication`() {
        assertThrows(NoiseProtocolException::class.java) {
            responder(prologue = "tampered".toByteArray()).readMessage1(bytes("msg1"))
        }
    }

    @Test
    fun `server frames decode to a JSON message and a binary role message`() {
        val transport = completedHandshake().transport
        val frames = list("server_frames").map(SendspinNoise::b64urlDecode)
        val plaintexts = list("server_plaintexts").map(SendspinNoise::b64urlDecode)

        val text = transport.decode(frames[0]) as NoiseTransport.Incoming.Text
        val binary = transport.decode(frames[1]) as NoiseTransport.Incoming.Binary

        assertThat(text.text).isEqualTo(String(plaintexts[0], 1, plaintexts[0].size - 1, Charsets.UTF_8))
        assertThat(binary.data).isEqualTo(plaintexts[1])
    }

    @Test
    fun `a tampered server frame is rejected`() {
        val transport = completedHandshake().transport
        val frame = SendspinNoise.b64urlDecode(list("server_frames")[0])
        frame[frame.size / 2] = (frame[frame.size / 2].toInt() xor 1).toByte()

        assertThrows(NoiseProtocolException::class.java) { transport.decode(frame) }
    }

    @Test
    fun `the client's frames are byte for byte the server's expectation, fragments included`() {
        val transport = completedHandshake().transport
        val sent = mutableListOf<ByteArray>()
        val clientPlaintext = bytes("client_plaintext")
        // The script builds bytes([4]) + bytes(i % 251 for i in range(70000)).
        val big = ByteArray(vectors.getValue("fragment_plaintext_length").jsonPrimitive.int) {
            if (it == 0) 4 else ((it - 1) % 251).toByte()
        }

        transport.sendText(String(clientPlaintext, 1, clientPlaintext.size - 1, Charsets.UTF_8)) { sent += it; true }
        transport.sendBinary(big) { sent += it; true }

        assertThat(SendspinNoise.b64urlEncode(sent[0])).isEqualTo(str("client_frame"))
        val fragments = sent.drop(1)
        assertThat(fragments.map { it.size })
            .isEqualTo(vectors.getValue("fragment_frame_sizes").jsonArray.map { it.jsonPrimitive.int })
        assertThat(fragments.map { sha256Hex(it) }).isEqualTo(list("fragment_frame_sha256"))
    }

    @Test
    fun `fragments reassemble into the original binary message`() {
        val key = ByteArray(32) { it.toByte() }
        val sender = NoiseTransport(send = NoiseCipherState(key), receive = NoiseCipherState(null))
        val receiver = NoiseTransport(send = NoiseCipherState(null), receive = NoiseCipherState(key))
        val big = ByteArray(200_000) { (it % 253).toByte() }.also { it[0] = 4 }
        val frames = mutableListOf<ByteArray>()

        sender.sendBinary(big) { frames += it; true }
        val decoded = frames.map { receiver.decode(it) }

        assertThat(frames).hasSize(4)
        assertThat(decoded.dropLast(1).all { it == null }).isTrue()
        assertThat((decoded.last() as NoiseTransport.Incoming.Binary).data).isEqualTo(big)
    }

    @Test
    fun `a frame longer than a Noise message is refused before decrypting`() {
        val key = ByteArray(32) { it.toByte() }
        val receiver = NoiseTransport(send = NoiseCipherState(null), receive = NoiseCipherState(key))

        assertThrows(NoiseProtocolException::class.java) {
            receiver.decode(ByteArray(SendspinNoise.MAX_NOISE_MESSAGE + 1))
        }
    }

    @Test
    fun `a refused re-handshake reply keeps the old keys`() {
        val oldKey = ByteArray(32) { it.toByte() }
        val newKey = ByteArray(32) { (it + 1).toByte() }
        val transport = NoiseTransport(send = NoiseCipherState(oldKey), receive = NoiseCipherState(null))
        val next = NoiseTransport(send = NoiseCipherState(newKey), receive = NoiseCipherState(null))
        val peer = NoiseTransport(send = NoiseCipherState(null), receive = NoiseCipherState(oldKey))

        val sent = transport.sendTextThenSwap("{}", next) { false }
        val frames = mutableListOf<ByteArray>()
        transport.sendText("{\"type\":\"x\"}") { frames += it; true }

        assertThat(sent).isFalse()
        // Still the old key: the peer, which never got the reply, reads it. The refused reply
        // consumed nonce 0, so the peer skips that frame to stay in step.
        peer.decode(NoiseCipherState(oldKey).encryptWithAd(ByteArray(0), byteArrayOf(0, '{'.code.toByte(), '}'.code.toByte())))
        assertThat(peer.decode(frames.single())).isInstanceOf(NoiseTransport.Incoming.Text::class.java)
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
