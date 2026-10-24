package net.asksakis.massdroidv2.data.sendspin.noise

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.sendspin.SendspinWire
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The rc1 fragment framing (spec `messaging.md`, binary message fragmentation): type 1, a flags
 * byte with bit 1 for the first fragment and bit 0 for the last, and the original type after the
 * flags of the first fragment. Every malformed sequence the spec lists closes the connection, and
 * the legacy types 2 and 3 are reserved and ignored.
 */
class NoiseRc1FramingTest {

    private val key = ByteArray(32) { it.toByte() }
    private val sender = NoiseCipherState(key)
    private val receiver = NoiseTransport(
        send = NoiseCipherState(null),
        receive = NoiseCipherState(key),
        wire = SendspinWire.RC1,
    )

    /** Encrypts [plaintext] as one frame and hands it to the receiver. */
    private fun deliver(vararg plaintext: Int): NoiseTransport.Incoming? =
        receiver.decode(sender.encryptWithAd(ByteArray(0), ByteArray(plaintext.size) { plaintext[it].toByte() }))

    private fun assertClosed(vararg plaintext: Int) {
        assertThrows(NoiseProtocolException::class.java) { deliver(*plaintext) }
    }

    @Test
    fun `a message split over a first, a middle and a last fragment is reassembled`() {
        assertThat(deliver(1, 0x02, 4, 10, 11)).isNull()
        assertThat(deliver(1, 0x00, 12)).isNull()
        val message = deliver(1, 0x01, 13) as NoiseTransport.Incoming.Binary

        assertThat(message.data).isEqualTo(byteArrayOf(4, 10, 11, 12, 13))
    }

    @Test
    fun `a single frame flagged first and last is a complete message`() {
        val message = deliver(1, 0x03, 0, '{'.code, '}'.code) as NoiseTransport.Incoming.Text

        assertThat(message.text).isEqualTo("{}")
    }

    @Test
    fun `a first fragment while another is in flight closes the connection`() {
        deliver(1, 0x02, 4, 10)

        assertClosed(1, 0x02, 4, 11)
    }

    @Test
    fun `a continuation with no message in flight closes the connection`() {
        assertClosed(1, 0x01, 10)
    }

    @Test
    fun `a non-fragment frame while a message is in flight closes the connection`() {
        deliver(1, 0x02, 4, 10)

        assertClosed(4, 10)
    }

    @Test
    fun `reserved flag bits close the connection`() {
        assertClosed(1, 0x07, 4, 10)
    }

    @Test
    fun `a fragment claiming to carry a fragment closes the connection`() {
        assertClosed(1, 0x03, 1, 10)
    }

    @Test
    fun `a fragment without its flags byte closes the connection`() {
        assertClosed(1)
    }

    @Test
    fun `a first fragment without its original type closes the connection`() {
        assertClosed(1, 0x02)
    }

    @Test
    fun `the legacy fragment types are reserved on rc1 and ignored`() {
        assertThat(deliver(2, 4, 10)).isNull()
        assertThat(deliver(3, 10)).isNull()
        assertThat(deliver(4, 10)).isInstanceOf(NoiseTransport.Incoming.Binary::class.java)
    }

    @Test
    fun `a large message survives a round trip through the rc1 framing`() {
        val rc1Sender = NoiseTransport(send = NoiseCipherState(key), receive = NoiseCipherState(null), wire = SendspinWire.RC1)
        val big = ByteArray(200_000) { (it % 253).toByte() }.also { it[0] = 4 }
        val frames = mutableListOf<ByteArray>()

        rc1Sender.sendBinary(big) { frames += it; true }
        val decoded = frames.map { receiver.decode(it) }

        assertThat(frames).hasSize(4)
        assertThat(decoded.dropLast(1).all { it == null }).isTrue()
        assertThat((decoded.last() as NoiseTransport.Incoming.Binary).data).isEqualTo(big)
    }

    @Test
    fun `a message that fits one Noise message is sent unfragmented`() {
        val rc1Sender = NoiseTransport(send = NoiseCipherState(key), receive = NoiseCipherState(null), wire = SendspinWire.RC1)
        val frames = mutableListOf<ByteArray>()

        rc1Sender.sendText("{}") { frames += it; true }

        assertThat((receiver.decode(frames.single()) as NoiseTransport.Incoming.Text).text).isEqualTo("{}")
    }
}
