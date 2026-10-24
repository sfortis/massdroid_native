package net.asksakis.massdroidv2.data.sendspin.noise

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.sendspin.SendspinWire
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * How a Noise session learns which wire the server speaks: from `psk_category` in handshake
 * message 1, which aiosendspin 10 always sends and aiosendspin 9.1.1 never does.
 */
class NoiseWireTest {

    private fun vectors(resource: String): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource(resource)).readText()
    ).jsonObject

    /** Runs the init exchange and the handshake of [resource] and returns the session. */
    private fun establish(resource: String): Pair<NoiseClientSession, JsonObject> {
        val v = vectors(resource)
        fun str(key: String) = v.getValue(key).jsonPrimitive.content
        fun bytes(key: String) = SendspinNoise.b64urlDecode(str(key))
        val psk = ResolvedPsk(str("psk_id"), bytes("psk"), PskCategory.LONG_TERM, str("server_static_public"))
        val session = NoiseClientSession(
            identity = NoiseKeyPair.fromPrivateKey(bytes("client_static_private")),
            pskResolver = PskResolver { if (it == psk.pskId) psk else null },
            ephemerals = { NoiseKeyPair.fromPrivateKey(bytes("client_ephemeral_private")) },
        )
        session.onHandshakeText(str("server_init"))
        val reply = checkNotNull(session.onHandshakeText(NoiseClientSession.handshakeText(bytes("msg1"))))
        val data = Json.parseToJsonElement(reply).jsonObject.getValue("payload").jsonObject.getValue("data")
        assertThat(data.jsonPrimitive.content).isEqualTo(str("msg2"))
        return session to v
    }

    @Test
    fun `aiosendspin 9_1_1 sends no psk_category, so the connection is on the legacy wire`() {
        val (session, _) = establish("sendspin/noise_vectors_9_1_1.json")

        assertThat(session.wire).isEqualTo(SendspinWire.LEGACY)
    }

    @Test
    fun `aiosendspin 10 names the psk_category, so the connection is on the rc1 wire`() {
        val (session, _) = establish("sendspin/noise_vectors_rc1.json")

        assertThat(session.wire).isEqualTo(SendspinWire.RC1)
        assertThat(session.psk?.category).isEqualTo(PskCategory.LONG_TERM)
    }

    @Test
    fun `the wire is unknown until message 1 has been read`() {
        val v = vectors("sendspin/noise_vectors_rc1.json")
        val session = NoiseClientSession(
            identity = NoiseKeyPair.fromPrivateKey(
                SendspinNoise.b64urlDecode(v.getValue("client_static_private").jsonPrimitive.content)
            ),
            pskResolver = PskResolver { null },
        )
        session.onHandshakeText(v.getValue("server_init").jsonPrimitive.content)

        assertThat(session.wire).isNull()
    }

    @Test
    fun `every category code the server uses maps to the app's category`() {
        val codes = vectors("sendspin/noise_vectors_rc1.json").getValue("psk_category_codes").jsonObject

        for ((name, code) in codes) {
            assertThat(PskCategory.fromCode(code.jsonPrimitive.content)?.name).isEqualTo(name)
        }
    }

    @Test
    fun `the message 1 payload names the psk and, on rc1, its category`() {
        val rc1 = NoiseClientSession.Message1Payload.parse("""{"psk_id":"abc","psk_category":"pr"}""")
        val legacy = NoiseClientSession.Message1Payload.parse("""{"psk_id":"abc"}""")

        assertThat(rc1.pskId).isEqualTo("abc")
        assertThat(rc1.category).isEqualTo(PskCategory.PAIRING)
        assertThat(rc1.wire).isEqualTo(SendspinWire.RC1)
        assertThat(legacy.category).isNull()
        assertThat(legacy.wire).isEqualTo(SendspinWire.LEGACY)
    }

    @Test
    fun `a category the spec does not define fails the handshake`() {
        // The spec: a psk_category outside lt, pr and sn is a silent failure that closes the
        // connection. Guessing a wire here would risk speaking the wrong one.
        assertThrows(NoiseProtocolException::class.java) {
            NoiseClientSession.Message1Payload.parse("""{"psk_id":"abc","psk_category":"zz"}""")
        }
        assertThrows(NoiseProtocolException::class.java) {
            NoiseClientSession.Message1Payload.parse("""{"psk_id":"abc","psk_category":null}""")
        }
    }

    @Test
    fun `a payload without a psk id fails the handshake`() {
        assertThrows(NoiseProtocolException::class.java) {
            NoiseClientSession.Message1Payload.parse("""{"psk_category":"lt"}""")
        }
    }
}
