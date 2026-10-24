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
import net.asksakis.massdroidv2.data.sendspin.SendspinWire

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
 * **Hello exchange.** Right after the handshake, and again after every re-handshake, the server
 * reads the next frame as `client/hello`, and during pairing as `client/pair-finalize`. Any other
 * frame there breaks the connection. So from the handshake until the caller calls [endExchange],
 * only sends marked `force` go out; the others are dropped, as aiosendspin's client suppresses
 * them. The re-handshake opens the exchange under the same lock as its key swap, so a periodic
 * send on another thread cannot slip in between.
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
    private val gateLock = Any()
    private var exchangeOpen = true

    /** The server's peer id, known once `server/init` has arrived. */
    @Volatile var serverId: String? = null
        private set

    /** The PSK of the current handshake. A re-handshake replaces it. */
    @Volatile var psk: ResolvedPsk? = null
        private set

    /**
     * The wire the server speaks, known once handshake message 1 has been read. A re-handshake
     * must keep it.
     */
    @Volatile var wire: SendspinWire? = null
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

    /**
     * Encrypts and sends a control message. Returns false when the socket refused it, or when a
     * hello exchange is open and [force] is false, in which case nothing is sent.
     */
    fun sendText(text: String, force: Boolean = false, sink: (ByteArray) -> Boolean): Boolean =
        synchronized(gateLock) {
            if (exchangeOpen && !force) return false
            established().sendText(text, sink)
        }

    fun sendBinary(data: ByteArray, sink: (ByteArray) -> Boolean): Boolean = synchronized(gateLock) {
        if (exchangeOpen) return false
        established().sendBinary(data, sink)
    }

    /** Ends the hello exchange: the server has activated the connection for something other than pairing. */
    fun endExchange() {
        synchronized(gateLock) { exchangeOpen = false }
    }

    val isExchangeOpen: Boolean get() = synchronized(gateLock) { exchangeOpen }

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
        val (resolved, result) = respond(checkNotNull(handshake), text, initialHandshake = true) { wire = it }
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
        val (resolved, result) = respond(next, text, initialHandshake = false) { declared ->
            if (declared != wire) throw NoiseProtocolException("re-handshake on the $declared wire of a $wire connection")
        }
        val sent = synchronized(gateLock) {
            exchangeOpen = true
            current.sendTextThenSwap(handshakeText(result.message), result.transport, sink)
        }
        if (!sent) throw NoiseProtocolException("socket refused re-handshake message 2")
        handshakeHash = result.handshakeHash
        psk = resolved
        return Received.Rehandshake(resolved)
    }

    /** Reads message 1, reports the wire it declares to [onWire], and answers with message 2. */
    private inline fun respond(
        handshake: NoiseResponderHandshake,
        text: String,
        initialHandshake: Boolean,
        onWire: (SendspinWire) -> Unit,
    ): Pair<ResolvedPsk, NoiseResponderHandshake.Result> {
        val message1 = protocolGuard("noise/handshake") {
            val data = parseMessage(text, HANDSHAKE_TYPE)["data"]?.jsonPrimitive?.contentOrNull
                ?: throw NoiseProtocolException("noise/handshake without data")
            SendspinNoise.b64urlDecode(data)
        }
        val plaintext = handshake.readMessage1(message1)
        val payload = protocolGuard("message 1 payload") {
            Message1Payload.parse(String(plaintext, Charsets.UTF_8))
        }
        onWire(payload.wire)
        val resolved = resolvePsk(payload, initialHandshake)
        return resolved to handshake.writeMessage2(resolved.psk, MESSAGE_2_PAYLOAD)
    }

    /**
     * Finds the PSK message 1 names.
     *
     * On the legacy wire any PSK the client holds under that id is used, and a miss fails the
     * handshake, as aiosendspin 9.1.1's client does.
     *
     * On the rc1 wire a PSK counts only under the category the server declared it as; held under
     * another category, or bound to another server, it is a miss (spec `connection.md`). A miss in
     * the initial handshake is answered under the Sentinel PSK, which the server accepts by
     * replaying message 1 under it ("Sentinel Fallback"). The phone then comes up unpaired and
     * pairs again, instead of failing every handshake until someone unpairs it in Music
     * Assistant. A miss in a re-handshake still fails, because the old keys stay valid there.
     */
    private fun resolvePsk(payload: Message1Payload, initialHandshake: Boolean): ResolvedPsk {
        val declared = payload.category
            ?: return findPsk(payload.pskId, missIsFatal = true)
                ?: throw NoiseProtocolException("no PSK matches psk_id ${payload.pskId}")
        findPsk(payload.pskId, missIsFatal = false)?.takeIf { it.category == declared }?.let { return it }
        if (initialHandshake) return SENTINEL
        throw NoiseProtocolException("no ${declared.code} PSK matches psk_id ${payload.pskId} in a re-handshake")
    }

    /**
     * The PSK held under [pskId], or null when none is. A stored PSK is bound to the server it was
     * paired with; for any other server it is a fatal error when [missIsFatal], and a miss
     * otherwise.
     */
    private fun findPsk(pskId: String, missIsFatal: Boolean): ResolvedPsk? {
        val resolved = if (pskId == SENTINEL_PSK_ID) SENTINEL else pskResolver.resolve(pskId) ?: return null
        if (resolved.serverId != null && resolved.serverId != serverId) {
            if (missIsFatal) throw NoiseProtocolException("PSK $pskId is bound to another server")
            return null
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

    /**
     * The plaintext of handshake message 1. [category] is null on the legacy wire, which has no
     * such field, and that absence is what tells the two wires apart.
     */
    internal class Message1Payload(val pskId: String, val category: PskCategory?) {
        val wire: SendspinWire get() = if (category == null) SendspinWire.LEGACY else SendspinWire.RC1

        companion object {
            /**
             * @throws NoiseProtocolException without a `psk_id`, or with a `psk_category` the spec
             *     does not define, which the spec treats as a failed handshake.
             * @throws IllegalArgumentException when the text is not a JSON object.
             */
            fun parse(text: String): Message1Payload {
                val obj = Json.parseToJsonElement(text).jsonObject
                val pskId = obj["psk_id"]?.jsonPrimitive?.contentOrNull
                    ?: throw NoiseProtocolException("message 1 payload without psk_id")
                val category = obj["psk_category"]?.let { element ->
                    val code = element.jsonPrimitive.contentOrNull
                    code?.let(PskCategory::fromCode) ?: throw NoiseProtocolException("unknown psk_category $code")
                }
                return Message1Payload(pskId, category)
            }
        }
    }

    companion object {
        const val CLIENT_INIT_TYPE = "client/init"
        const val SERVER_INIT_TYPE = "server/init"
        const val HANDSHAKE_TYPE = "noise/handshake"

        private val SENTINEL_PSK_ID = SendspinNoise.pskId(SendspinNoise.SENTINEL_PSK)
        private val SENTINEL = ResolvedPsk(SENTINEL_PSK_ID, SendspinNoise.SENTINEL_PSK, PskCategory.SENTINEL, serverId = null)
        private val MESSAGE_2_PAYLOAD = "{}".toByteArray(Charsets.UTF_8)

        internal fun handshakeText(message: ByteArray): String = buildJsonObject {
            put("type", HANDSHAKE_TYPE)
            putJsonObject("payload") { put("data", SendspinNoise.b64urlEncode(message)) }
        }.toString()
    }
}
