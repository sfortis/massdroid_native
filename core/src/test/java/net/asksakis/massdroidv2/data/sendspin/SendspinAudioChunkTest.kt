package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Reading the player audio header on each wire. The two reference frames were packed by the
 * server's own code in aiosendspin 10.2.1 (`aiosendspin.models.player.pack_player_audio_frame`
 * and `stamp_send_ahead` for rc1, `pack_binary_header_raw` for the legacy wire), with timestamp
 * 1234567890123 us, send_ahead 250000 us and the payload 01 02 03.
 */
class SendspinAudioChunkTest {

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val rc1Frame = hex("040000011f71fb04cb0003d090010203")
    private val legacyFrame = hex("040000011f71fb04cb010203")

    private fun SendspinAudioChunk.payload() = data.copyOfRange(payloadOffset, data.size)

    @Test
    fun `a legacy frame has a 9-byte header and no send_ahead`() {
        val chunk = checkNotNull(SendspinAudioChunk.parse(legacyFrame, SendspinWire.LEGACY))

        assertThat(chunk.timestampUs).isEqualTo(1_234_567_890_123L)
        assertThat(chunk.sendAheadUs).isNull()
        assertThat(chunk.payload()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `an rc1 frame has a 13-byte header with send_ahead`() {
        val chunk = checkNotNull(SendspinAudioChunk.parse(rc1Frame, SendspinWire.RC1))

        assertThat(chunk.timestampUs).isEqualTo(1_234_567_890_123L)
        assertThat(chunk.sendAheadUs).isEqualTo(250_000L)
        assertThat(chunk.payloadLength).isEqualTo(3)
        assertThat(chunk.payload()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `reading an rc1 frame with the legacy header would put send_ahead into the audio`() {
        // Why the header must follow the connection's wire: the four send_ahead bytes would
        // reach the decoder as the start of the payload.
        val wrong = checkNotNull(SendspinAudioChunk.parse(rc1Frame, SendspinWire.LEGACY))

        assertThat(wrong.payloadLength).isEqualTo(7)
    }

    @Test
    fun `the saturation values of send_ahead are no sample`() {
        val zero = rc1Frame.copyOf().also { for (i in 9..12) it[i] = 0 }
        val saturated = rc1Frame.copyOf().also { for (i in 9..12) it[i] = 0xFF.toByte() }

        assertThat(SendspinAudioChunk.parse(zero, SendspinWire.RC1)?.sendAheadUs).isNull()
        assertThat(SendspinAudioChunk.parse(saturated, SendspinWire.RC1)?.sendAheadUs).isNull()
    }

    @Test
    fun `an rc1 audio message shorter than its header is a protocol error`() {
        assertThrows(SendspinAudioChunk.MalformedException::class.java) {
            SendspinAudioChunk.parse(rc1Frame.copyOf(12), SendspinWire.RC1)
        }
    }

    @Test
    fun `a header with no payload carries no audio on either wire`() {
        assertThat(SendspinAudioChunk.parse(rc1Frame.copyOf(13), SendspinWire.RC1)).isNull()
        assertThat(SendspinAudioChunk.parse(legacyFrame.copyOf(9), SendspinWire.LEGACY)).isNull()
        assertThat(SendspinAudioChunk.parse(legacyFrame.copyOf(5), SendspinWire.LEGACY)).isNull()
    }

    @Test
    fun `a message of another type is not audio`() {
        val artwork = legacyFrame.copyOf().also { it[0] = 8 }

        assertThat(SendspinAudioChunk.parse(artwork, SendspinWire.LEGACY)).isNull()
        assertThat(SendspinAudioChunk.parse(ByteArray(0), SendspinWire.RC1)).isNull()
    }
}
