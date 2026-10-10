package net.asksakis.massdroidv2.data.sendspin.noise

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The client side of one Sendspin Noise connection: the cleartext init exchange, the KKpsk2
 * handshake, the encrypted transport and the in-band re-handshakes.
 *
 * It follows aiosendspin's `run_handshake_client` and `run_rehandshake_client`. The session does
 * no I/O itself. Once the MA proxy has accepted the auth frame, the caller sends [clientInitText],
 * passes each text frame to [onHandshakeText] and sends back what it returns, until
 * [isEstablished] is true. From then on every frame is binary: the caller passes each one to
 * [receive] and sends through [sendText] and [sendBinary].
 *
 * Receiving must happen on one thread, the socket's reader. Sending is safe from any thread.
 *
 * @param ephemerals the source of ephemeral key pairs, one per handshake; fixed ones for tests only.
 */
class NoiseClientSession(
    private val identity: NoiseKeyPair,
    private val pskResolver: PskResolver,
    private val ephemerals: () -> NoiseKeyPair = { NoiseKeyPair.generate() },
) {
    val clientInitText: String = buildJsonObject {
        put("type", CLIENT_INIT_TYPE)
        putJsonObject("payload") {
            put("client_id", identity.peerId)
            put("version", SendspinNoise.PROTOCOL_VERSION)
            put("suite", SendspinNoise.SUITE)
        }
    }.toString()

    private var step = Step.AWAIT_SERVER_INIT
    private var handshake: NoiseResponderHandshake? = null
    private var serverStatic: ByteArray? = null
    private var handshakeHash = ByteArray(0)
    @Volatile private var transport: NoiseTransport? = null

    /** The server's peer id, known once `server/init` has arrived. */
    @Volatile var serverId: String? = null
        private set

    /** The PSK of the current handshake. A re-handshake replaces it. */
    @Volatile var psk: ResolvedPsk? = null
        private set

    val isEstablished: Boolean get() = transport != null

    sealed class Received {
        class Text(val text: String) : Received()

        /** A binary role message, including its leading type byte. */
        class Binary(val data: ByteArray) : Received()

        /** The server moved the connection onto another PSK. The hellos are exchanged again. */
        class Rehandshake(val psk: ResolvedPsk) : Received()
    }

    /**
     * Consumes one cleartext frame of the bring-up and returns the frame to send in reply, or null.
     *
     * @throws NoiseProtocolException when the frame is not the expected one or fails authentication.
     */
    fun onHandshakeText(text: String): String? = when (step) {
        Step.AWAIT_SERVER_INIT -> {
            readServerInit(text)
            null
        }
        Step.AWAIT_MESSAGE_1 -> completeHandshake(text)
        Step.ESTABLISHED -> throw NoiseProtocolException("cleartext frame after the handshake")
    }

    /**
     * Decrypts one transport frame. Returns null while a fragmented message is incomplete. An
     * in-band `noise/handshake` is answered through [sink] and reported as [Received.Rehandshake].
     *
     * @throws NoiseProtocolException when the frame fails authentication or breaks the protocol.
     */
    fun receive(frame: ByteArray, sink: (ByteArray) -> Boolean): Received? {
        val current = transport ?: throw NoiseProtocolException("transport frame before the handshake completed")
        return when (val incoming = current.decode(frame)) {
            null -> null
            is NoiseTransport.Incoming.Binary -> Received.Binary(incoming.data)
            is NoiseTransport.Incoming.Text ->
                if (isHandshakeMessage(incoming.text)) {
                    rehandshake(incoming.text, current, sink)
                } else {
                    Received.Text(incoming.text)
                }
        }
    }

    fun sendText(text: String, sink: (ByteArray) -> Boolean): Boolean = established().sendText(text, sink)

    fun sendBinary(data: ByteArray, sink: (ByteArray) -> Boolean): Boolean = established().sendBinary(data, sink)

    private fun established(): NoiseTransport = checkNotNull(transport) { "Noise handshake has not completed" }

    private fun readServerInit(text: String) = protocolGuard("server/init") {
        val payload = parseMessage(text, SERVER_INIT_TYPE)
        val version = payload["version"]?.jsonPrimitive?.intOrNull
        if (version != SendspinNoise.PROTOCOL_VERSION) {
            throw NoiseProtocolException("unsupported protocol version $version")
        }
        val id = payload["server_id"]?.jsonPrimitive?.contentOrNull
            ?: throw NoiseProtocolException("server/init without server_id")
        val key = peerKey(id)
        serverId = id
        serverStatic = key
        // The prologue is the exact text of both init frames, as each side saw it on the wire.
        handshake = NoiseResponderHandshake(
            localStatic = identity,
            remoteStaticPublic = key,
            prologue = (clientInitText + text).toByteArray(Charsets.UTF_8),
            ephemeral = ephemerals(),
        )
        step = Step.AWAIT_MESSAGE_1
    }

    private fun completeHandshake(text: String): String {
        val (resolved, result) = respond(checkNotNull(handshake), text)
        handshake = null
        handshakeHash = result.handshakeHash
        psk = resolved
        step = Step.ESTABLISHED
        transport = result.transport
        return handshakeText(result.message)
    }

    /**
     * Answers an in-band re-handshake. Message 2 still travels under the old keys, and the swap
     * to the new ones happens under the transport's send lock, so no other frame can slip between.
     */
    private fun rehandshake(text: String, current: NoiseTransport, sink: (ByteArray) -> Boolean): Received {
        val next = NoiseResponderHandshake(
            localStatic = identity,
            remoteStaticPublic = checkNotNull(serverStatic),
            prologue = handshakeHash,
            ephemeral = ephemerals(),
        )
        val (resolved, result) = respond(next, text)
        if (!current.sendTextThenSwap(handshakeText(result.message), result.transport, sink)) {
            throw NoiseProtocolException("socket refused re-handshake message 2")
        }
        handshakeHash = result.handshakeHash
        psk = resolved
        return Received.Rehandshake(resolved)
    }

    private fun respond(
        handshake: NoiseResponderHandshake,
        text: String,
    ): Pair<ResolvedPsk, NoiseResponderHandshake.Result> {
        val message1 = protocolGuard("noise/handshake") {
            val data = parseMessage(text, HANDSHAKE_TYPE)["data"]?.jsonPrimitive?.contentOrNull
                ?: throw NoiseProtocolException("noise/handshake without data")
            SendspinNoise.b64urlDecode(data)
        }
        val payload = handshake.readMessage1(message1)
        val pskId = protocolGuard("message 1 payload") {
            Json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject["psk_id"]
                ?.jsonPrimitive?.contentOrNull
                ?: throw NoiseProtocolException("message 1 payload without psk_id")
        }
        val resolved = resolvePsk(pskId)
        return resolved to handshake.writeMessage2(resolved.psk, MESSAGE_2_PAYLOAD)
    }

    private fun resolvePsk(pskId: String): ResolvedPsk {
        val resolved = if (pskId == SENTINEL_PSK_ID) {
            ResolvedPsk(pskId, SendspinNoise.SENTINEL_PSK, PskCategory.SENTINEL, serverId = null)
        } else {
            pskResolver.resolve(pskId) ?: throw NoiseProtocolException("no PSK matches psk_id $pskId")
        }
        // A stored PSK is bound to the server it was paired with; any other server must not use it.
        if (resolved.serverId != null && resolved.serverId != serverId) {
            throw NoiseProtocolException("PSK $pskId is bound to another server")
        }
        return resolved
    }

    private fun isHandshakeMessage(text: String): Boolean =
        text.contains(HANDSHAKE_TYPE) && protocolGuard("control message") {
            Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.contentOrNull == HANDSHAKE_TYPE
        }

    /** Returns the payload of a message after checking its type. */
    private fun parseMessage(text: String, expectedType: String): JsonObject {
        val obj = Json.parseToJsonElement(text).jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type != expectedType) throw NoiseProtocolException("expected $expectedType, got $type")
        return obj["payload"] as? JsonObject ?: throw NoiseProtocolException("$expectedType without payload")
    }

    private fun peerKey(peerId: String): ByteArray {
        if (peerId.length != SendspinNoise.PEER_ID_LENGTH) {
            throw NoiseProtocolException("invalid server_id length ${peerId.length}")
        }
        val key = SendspinNoise.b64urlDecode(peerId)
        if (key.size != SendspinNoise.KEY_SIZE) throw NoiseProtocolException("invalid server_id")
        return key
    }

    /** Turns malformed JSON and base64 (both [IllegalArgumentException]) into a protocol failure. */
    private inline fun <T> protocolGuard(what: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw NoiseProtocolException("malformed $what", e)
    }

    private enum class Step { AWAIT_SERVER_INIT, AWAIT_MESSAGE_1, ESTABLISHED }

    companion object {
        const val CLIENT_INIT_TYPE = "client/init"
        const val SERVER_INIT_TYPE = "server/init"
        const val HANDSHAKE_TYPE = "noise/handshake"

        private val SENTINEL_PSK_ID = SendspinNoise.pskId(SendspinNoise.SENTINEL_PSK)
        private val MESSAGE_2_PAYLOAD = "{}".toByteArray(Charsets.UTF_8)

        internal fun handshakeText(message: ByteArray): String = buildJsonObject {
            put("type", HANDSHAKE_TYPE)
            putJsonObject("payload") { put("data", SendspinNoise.b64urlEncode(message)) }
        }.toString()
    }
}
