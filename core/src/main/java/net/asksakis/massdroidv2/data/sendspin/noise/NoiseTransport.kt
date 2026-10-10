package net.asksakis.massdroidv2.data.sendspin.noise

import java.io.ByteArrayOutputStream

/**
 * The Sendspin transport after the Noise handshake: every WebSocket frame is binary and holds one
 * Noise transport message.
 *
 * The first plaintext byte gives the message type. Type 0 is a JSON control message, types 2 and 3
 * are fragments of a message longer than one Noise message allows, and any other value is a binary
 * role message (audio chunks, artwork) passed on with its type byte, exactly as the legacy
 * unencrypted transport delivered it.
 *
 * Each direction counts its own nonces, so frames must leave in the order they were encrypted.
 * [send] therefore encrypts and hands the frames to the socket under one lock. Receiving happens on
 * the socket's single reader thread.
 */
class NoiseTransport internal constructor(
    private var send: NoiseCipherState,
    private var receive: NoiseCipherState,
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
        fragment(plaintext).all { frame -> sink(send.encryptWithAd(EMPTY, frame)) }
    }

    /**
     * Decrypts one received frame. Returns null while a fragmented message is still incomplete.
     *
     * @throws NoiseProtocolException when the frame fails authentication or breaks the framing.
     *   The connection cannot recover from either, because the receive nonce is then out of step.
     */
    fun decode(frame: ByteArray): Incoming? {
        val plaintext = receive.decryptWithAd(EMPTY, frame)
        if (plaintext.isEmpty()) throw NoiseProtocolException("empty plaintext after decrypt")
        return when (plaintext[0]) {
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
    }

    /**
     * Replaces both ciphers after an in-band re-handshake. The caller must hold back other sends
     * between sending handshake message 2 and this swap.
     */
    fun swapCiphers(other: NoiseTransport) {
        synchronized(sendLock) {
            send = other.send
            receive = other.receive
        }
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
        reassembly = null
        return byteArrayOf(reassemblyType) + buffer.toByteArray()
    }

    private fun append(buffer: ByteArrayOutputStream, plaintext: ByteArray) {
        if (buffer.size() + plaintext.size - 1 > MAX_REASSEMBLED_BYTES) {
            reassembly = null
            throw NoiseProtocolException("fragmented message exceeds $MAX_REASSEMBLED_BYTES bytes")
        }
        buffer.write(plaintext, 1, plaintext.size - 1)
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

        /** Splits an oversized plaintext the way aiosendspin `wire._fragment` does. */
        fun fragment(plaintext: ByteArray): List<ByteArray> {
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
