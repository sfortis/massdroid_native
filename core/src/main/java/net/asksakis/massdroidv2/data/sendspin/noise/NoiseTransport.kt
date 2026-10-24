package net.asksakis.massdroidv2.data.sendspin.noise

import net.asksakis.massdroidv2.data.sendspin.SendspinWire
import java.io.ByteArrayOutputStream

/**
 * The Sendspin transport after the Noise handshake: every WebSocket frame is binary and holds one
 * Noise transport message.
 *
 * The first plaintext byte gives the message type. Type 0 is a JSON control message, and any other
 * value is a binary role message (audio chunks, artwork) passed on with its type byte, exactly as
 * the legacy unencrypted transport delivered it. A message longer than one Noise message allows is
 * split into fragments, framed per [wire]:
 *
 * - Legacy wire: type 2 for a fragment with more to come and type 3 for the last one. The first
 *   fragment carries the original type in its second byte.
 * - rc1 wire: type 1 followed by a flags byte, bit 1 for the first fragment and bit 0 for the last.
 *   The first fragment carries the original type after the flags. A single frame with both bits is
 *   a complete message. Types 2 and 3 are reserved and ignored. Any malformed sequence closes the
 *   connection (spec `messaging.md`, binary message fragmentation).
 *
 * Each direction counts its own nonces, so frames must leave in the order they were encrypted.
 * [send] therefore encrypts and hands the frames to the socket under one lock. Receiving happens on
 * the socket's single reader thread.
 */
class NoiseTransport internal constructor(
    private var send: NoiseCipherState,
    private var receive: NoiseCipherState,
    private val wire: SendspinWire = SendspinWire.LEGACY,
) {
    private val sendLock = Any()
    private var reassembly: ByteArrayOutputStream? = null
    private var reassemblyType: Byte = 0

    sealed class Incoming {
        class Text(val text: String) : Incoming()

        /** A binary role message, including its leading type byte. */
        class Binary(val data: ByteArray) : Incoming()
    }

    /** Encrypts a JSON control message and passes the frame(s) to [sink]; false when the sink refused one. */
    fun sendText(text: String, sink: (ByteArray) -> Boolean): Boolean =
        sendPlaintext(byteArrayOf(SendspinNoise.TYPE_JSON) + text.toByteArray(Charsets.UTF_8), sink)

    /** Encrypts a binary role message whose first byte is its type. */
    fun sendBinary(data: ByteArray, sink: (ByteArray) -> Boolean): Boolean {
        require(data.isNotEmpty()) { "binary payload must include a leading type byte" }
        return sendPlaintext(data, sink)
    }

    private fun sendPlaintext(plaintext: ByteArray, sink: (ByteArray) -> Boolean): Boolean = synchronized(sendLock) {
        val frames = when (wire) {
            SendspinWire.LEGACY -> fragmentLegacy(plaintext)
            SendspinWire.RC1 -> fragmentRc1(plaintext)
        }
        frames.all { frame -> sink(send.encryptWithAd(EMPTY, frame)) }
    }

    /**
     * Decrypts one received frame. Returns null while a fragmented message is still incomplete.
     *
     * @throws NoiseProtocolException when the frame fails authentication or breaks the framing.
     *   The connection cannot recover from either, because the receive nonce is then out of step.
     */
    fun decode(frame: ByteArray): Incoming? {
        // A Noise message is never longer; a larger frame is not from a conforming peer.
        if (frame.size > SendspinNoise.MAX_NOISE_MESSAGE) {
            throw NoiseProtocolException("frame of ${frame.size} bytes exceeds the Noise message limit")
        }
        val plaintext = receive.decryptWithAd(EMPTY, frame)
        if (plaintext.isEmpty()) throw NoiseProtocolException("empty plaintext after decrypt")
        return when (wire) {
            SendspinWire.LEGACY -> decodeLegacy(plaintext)
            SendspinWire.RC1 -> decodeRc1(plaintext)
        }
    }

    private fun decodeLegacy(plaintext: ByteArray): Incoming? =
        when (plaintext[0]) {
            SendspinNoise.TYPE_FRAGMENT_MORE -> {
                onFragmentMore(plaintext)
                null
            }
            SendspinNoise.TYPE_FRAGMENT_END -> dispatch(onFragmentEnd(plaintext))
            else -> {
                if (reassembly != null) {
                    throw NoiseProtocolException("non-fragment frame while a fragmented message is in flight")
                }
                dispatch(plaintext)
            }
        }

    private fun decodeRc1(plaintext: ByteArray): Incoming? {
        val type = plaintext[0]
        if (type == SendspinNoise.TYPE_FRAGMENT) return onRc1Fragment(plaintext)
        frameCheck(reassembly == null) { "non-fragment frame while a fragmented message is in flight" }
        // Reserved on this wire. A binary message with an ID the client does not implement is ignored.
        val reserved = type == SendspinNoise.TYPE_FRAGMENT_MORE || type == SendspinNoise.TYPE_FRAGMENT_END
        return if (reserved) null else dispatch(plaintext)
    }

    private fun onRc1Fragment(plaintext: ByteArray): Incoming? {
        frameCheck(plaintext.size >= RC1_FRAGMENT_HEADER) { "fragment without its flags byte" }
        val flags = plaintext[1].toInt() and BYTE_MASK
        frameCheck(flags and SendspinNoise.FRAGMENT_FLAGS_RESERVED == 0) { "fragment with reserved flag bits $flags" }
        val first = flags and SendspinNoise.FRAGMENT_FLAG_FIRST != 0
        val dataStart = if (first) {
            frameCheck(reassembly == null) { "first fragment while another is in flight" }
            frameCheck(plaintext.size > RC1_FRAGMENT_HEADER) { "first fragment without its type" }
            val originalType = plaintext[RC1_FRAGMENT_HEADER]
            frameCheck(originalType != SendspinNoise.TYPE_FRAGMENT) { "fragment of type 1" }
            reassemblyType = originalType
            reassembly = ByteArrayOutputStream()
            RC1_FRAGMENT_HEADER + 1
        } else {
            frameCheck(reassembly != null) { "continuation fragment with no message in flight" }
            RC1_FRAGMENT_HEADER
        }
        append(checkNotNull(reassembly), plaintext, dataStart)
        val last = flags and SendspinNoise.FRAGMENT_FLAG_LAST != 0
        return if (last) dispatch(finishReassembly()) else null
    }

    /** A malformed fragment sequence closes the connection; the receive state cannot recover from it. */
    private inline fun frameCheck(valid: Boolean, message: () -> String) {
        if (!valid) throw NoiseProtocolException(message())
    }

    /**
     * Sends re-handshake message 2 under the current keys, then takes over the ciphers of [next].
     * Both happen under the send lock, so no frame from another thread can be encrypted with the
     * old keys after message 2 or with the new keys before it. Must be called on the receiving
     * thread, which owns [receive]. When the socket refused message 2 the keys stay as they were,
     * since the server never received it; the caller closes the connection then.
     */
    fun sendTextThenSwap(text: String, next: NoiseTransport, sink: (ByteArray) -> Boolean): Boolean =
        synchronized(sendLock) {
            val sent = sendText(text, sink)
            if (sent) {
                send = next.send
                receive = next.receive
            }
            sent
        }

    private fun onFragmentMore(plaintext: ByteArray) {
        val buffer = reassembly
        if (buffer == null) {
            if (plaintext.size < 2) throw NoiseProtocolException("fragment start frame missing its type")
            reassemblyType = plaintext[1]
            reassembly = ByteArrayOutputStream().apply { write(plaintext, 2, plaintext.size - 2) }
        } else {
            append(buffer, plaintext)
        }
    }

    private fun onFragmentEnd(plaintext: ByteArray): ByteArray {
        val buffer = reassembly ?: throw NoiseProtocolException("fragment end frame with no message in flight")
        append(buffer, plaintext)
        return finishReassembly()
    }

    /** Ends the message in flight and returns it with its original type byte in front. */
    private fun finishReassembly(): ByteArray {
        val buffer = checkNotNull(reassembly)
        reassembly = null
        // One copy into the result, not a second one through concatenation.
        val body = buffer.toByteArray()
        return ByteArray(body.size + 1).also {
            it[0] = reassemblyType
            body.copyInto(it, destinationOffset = 1)
        }
    }

    /** Appends the fragment data of [plaintext] from [start], the bytes after its header. */
    private fun append(buffer: ByteArrayOutputStream, plaintext: ByteArray, start: Int = 1) {
        if (buffer.size() + plaintext.size - start > MAX_REASSEMBLED_BYTES) {
            reassembly = null
            throw NoiseProtocolException("fragmented message exceeds $MAX_REASSEMBLED_BYTES bytes")
        }
        buffer.write(plaintext, start, plaintext.size - start)
    }

    private fun dispatch(plaintext: ByteArray): Incoming =
        if (plaintext[0] == SendspinNoise.TYPE_JSON) {
            Incoming.Text(String(plaintext, 1, plaintext.size - 1, Charsets.UTF_8))
        } else {
            Incoming.Binary(plaintext)
        }

    private companion object {
        val EMPTY = ByteArray(0)

        /** The server's own bound on one reassembled message (aiosendspin `wire.py`). */
        const val MAX_REASSEMBLED_BYTES = 64 * 1024 * 1024

        /** rc1 fragment header: the type byte and the flags byte. */
        const val RC1_FRAGMENT_HEADER = 2
        const val BYTE_MASK = 0xFF

        /** Splits an oversized plaintext the way aiosendspin 10 `wire._fragment` does. */
        fun fragmentRc1(plaintext: ByteArray): List<ByteArray> {
            if (plaintext.size <= SendspinNoise.MAX_TRANSPORT_PLAINTEXT) return listOf(plaintext)
            val originalType = plaintext[0]
            val firstCapacity = SendspinNoise.MAX_TRANSPORT_PLAINTEXT - RC1_FRAGMENT_HEADER - 1
            val nextCapacity = SendspinNoise.MAX_TRANSPORT_PLAINTEXT - RC1_FRAGMENT_HEADER
            val frames = ArrayList<ByteArray>()
            var offset = 1
            while (offset < plaintext.size) {
                val first = offset == 1
                val end = minOf(offset + if (first) firstCapacity else nextCapacity, plaintext.size)
                var flags = if (end == plaintext.size) SendspinNoise.FRAGMENT_FLAG_LAST else 0
                if (first) flags = flags or SendspinNoise.FRAGMENT_FLAG_FIRST
                val header = if (first) {
                    byteArrayOf(SendspinNoise.TYPE_FRAGMENT, flags.toByte(), originalType)
                } else {
                    byteArrayOf(SendspinNoise.TYPE_FRAGMENT, flags.toByte())
                }
                frames += header + plaintext.copyOfRange(offset, end)
                offset = end
            }
            return frames
        }

        /** Splits an oversized plaintext the way aiosendspin 9.1.1 `wire._fragment` does. */
        fun fragmentLegacy(plaintext: ByteArray): List<ByteArray> {
            if (plaintext.size <= SendspinNoise.MAX_TRANSPORT_PLAINTEXT) return listOf(plaintext)
            val originalType = plaintext[0]
            val firstCapacity = SendspinNoise.MAX_TRANSPORT_PLAINTEXT - 2
            val nextCapacity = SendspinNoise.MAX_TRANSPORT_PLAINTEXT - 1
            val frames = ArrayList<ByteArray>()
            frames += byteArrayOf(SendspinNoise.TYPE_FRAGMENT_MORE, originalType) +
                plaintext.copyOfRange(1, 1 + firstCapacity)
            var offset = 1 + firstCapacity
            while (offset < plaintext.size) {
                val end = minOf(offset + nextCapacity, plaintext.size)
                val tag = if (end == plaintext.size) SendspinNoise.TYPE_FRAGMENT_END else SendspinNoise.TYPE_FRAGMENT_MORE
                frames += byteArrayOf(tag) + plaintext.copyOfRange(offset, end)
                offset = end
            }
            return frames
        }
    }
}
