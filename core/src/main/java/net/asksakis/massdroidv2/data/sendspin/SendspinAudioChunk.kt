package net.asksakis.massdroidv2.data.sendspin

/**
 * One player audio chunk (binary message type 4), with its header already read for the wire it
 * arrived on.
 *
 * The legacy header is the type byte and an 8-byte big-endian server timestamp in microseconds,
 * 9 bytes in all. The rc1 header adds `send_ahead`, a big-endian uint32 of microseconds from
 * transmission to that timestamp, so the payload starts at byte 13. `send_ahead` has no
 * scheduling meaning: a player may only use it to measure how late chunks arrive.
 *
 * The chunk keeps the whole message and the offset of its payload, so the engine never copies it.
 */
class SendspinAudioChunk private constructor(
    val timestampUs: Long,
    /**
     * Microseconds from transmission to [timestampUs] as the server reported it, or null on the
     * legacy wire and for the saturation values 0 and 4294967295, which the spec says are no sample.
     */
    val sendAheadUs: Long?,
    val data: ByteArray,
    val payloadOffset: Int,
) {
    val payloadLength: Int get() = data.size - payloadOffset

    /** An audio message too short for its header. On the rc1 wire this is a protocol error. */
    class MalformedException(message: String) : Exception(message)

    companion object {
        const val TYPE_PLAYER_AUDIO: Byte = 4
        const val LEGACY_HEADER_SIZE = 9
        const val RC1_HEADER_SIZE = 13
        private const val TIMESTAMP_BYTES = 8
        private const val SEND_AHEAD_BYTES = 4
        private const val SEND_AHEAD_SATURATED = 0xFFFFFFFFL
        private const val BYTE_MASK = 0xFFL
        private const val BITS_PER_BYTE = 8

        /**
         * Reads an audio message. Returns null for a message that carries no audio: another type,
         * or a header with no payload after it.
         *
         * @throws MalformedException on the rc1 wire for a message shorter than its 13-byte
         *     header, which the spec makes a protocol error that closes the connection.
         */
        fun parse(data: ByteArray, wire: SendspinWire): SendspinAudioChunk? {
            if (data.isEmpty() || data[0] != TYPE_PLAYER_AUDIO) return null
            val headerSize = when (wire) {
                SendspinWire.LEGACY -> LEGACY_HEADER_SIZE
                SendspinWire.RC1 -> RC1_HEADER_SIZE
            }
            if (wire == SendspinWire.RC1 && data.size < RC1_HEADER_SIZE) {
                throw MalformedException("audio message of ${data.size} bytes, shorter than its $RC1_HEADER_SIZE-byte header")
            }
            if (data.size <= headerSize) return null
            val sendAhead = when (wire) {
                SendspinWire.LEGACY -> null
                SendspinWire.RC1 -> readUnsigned(data, 1 + TIMESTAMP_BYTES, SEND_AHEAD_BYTES)
                    .takeIf { it != 0L && it != SEND_AHEAD_SATURATED }
            }
            return SendspinAudioChunk(readUnsigned(data, 1, TIMESTAMP_BYTES), sendAhead, data, headerSize)
        }

        private fun readUnsigned(data: ByteArray, offset: Int, length: Int): Long {
            var value = 0L
            for (i in offset until offset + length) value = (value shl BITS_PER_BYTE) or (data[i].toLong() and BYTE_MASK)
            return value
        }
    }
}
