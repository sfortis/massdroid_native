package net.asksakis.massdroidv2.data.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The app's own mDNS resolve, used before Android 14 where the system's daemon stalls on Music
 * Assistant servers that all register the host name mass.local. The answers below have the shape
 * the three servers sent to a legacy unicast query on 2026-11-02: TXT with base_url, SRV pointing
 * at mass.local, and an A record with the server's own address.
 */
class MdnsMessageTest {

    private val instance = "21c3b9c33bfa4cda9e9a3172a3504928._mass._tcp.local"

    @Test
    fun `the query asks for SRV and TXT of the instance`() {
        val query = MdnsMessage.query("a._mass._tcp.local", id = 0x1234)
        val expectedName = byteArrayOf(1, 'a'.code.toByte()) + label("_mass") + label("_tcp") + label("local") + byteArrayOf(0)
        val expected = byteArrayOf(0x12, 0x34, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0) +
            expectedName + byteArrayOf(0, 33, 0, 1) +
            expectedName + byteArrayOf(0, 16, 0, 1)
        assertThat(query).isEqualTo(expected)
    }

    @Test
    fun `an answer gives the port, the TXT properties and the server's own address`() {
        val packet = answer(instance, address = byteArrayOf(192.toByte(), 168.toByte(), 1, 14))
        val record = MdnsMessage.parseAnswer(packet, packet.size, instance)!!
        assertThat(record.port).isEqualTo(8095)
        assertThat(record.target).isEqualTo("mass.local")
        assertThat(record.txt["base_url"]).isEqualTo("http://192.168.1.14:8095")
        assertThat(record.txt["server_version"]).isEqualTo("2.10.9")
        assertThat(record.addresses).containsExactly("192.168.1.14")
    }

    @Test
    fun `an answer about another instance is not taken`() {
        val packet = answer("4f82ad5b72a84044bd2379c1e36508fd._mass._tcp.local", address = byteArrayOf(192.toByte(), 168.toByte(), 1, 35))
        assertThat(MdnsMessage.parseAnswer(packet, packet.size, instance)).isNull()
    }

    @Test
    fun `a cut off or looping packet is refused without throwing`() {
        val packet = answer(instance, address = byteArrayOf(192.toByte(), 168.toByte(), 1, 14))
        for (length in listOf(5, 20, packet.size / 2, packet.size - 3)) {
            assertThat(MdnsMessage.parseAnswer(packet, length, instance)).isNull()
        }
        // A name pointer that points at itself.
        val looping = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0.toByte(), 12)
        assertThat(MdnsMessage.parseAnswer(looping, looping.size, instance)).isNull()
    }

    @Test
    fun `a server is built from the answer's base URL`() {
        val server = discoveredServerOf(
            serviceName = "21c3",
            txt = mapOf("base_url" to "http://192.168.1.14:8095", "name" to "Music Assistant (mass)", "server_id" to "21c3"),
            hostAddress = "192.168.1.14",
            port = 8095
        )!!
        assertThat(server.url).isEqualTo("http://192.168.1.14:8095")
        assertThat(server.name).isEqualTo("mass")
    }

    @Test
    fun `a base URL on another port gives way to the address on this network`() {
        val server = discoveredServerOf(
            serviceName = "21c3",
            txt = mapOf("base_url" to "https://music.example.com"),
            hostAddress = "192.168.1.14",
            port = 8095
        )!!
        assertThat(server.url).isEqualTo("http://192.168.1.14:8095")
    }

    private fun label(text: String) = byteArrayOf(text.length.toByte()) + text.toByteArray()

    /** A response like the servers send: TXT, then SRV with a compressed target, then the A record. */
    private fun answer(name: String, address: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun short(value: Int) {
            out.write(value shr 8 and 0xFF)
            out.write(value and 0xFF)
        }
        fun name(text: String) {
            for (part in text.split('.')) out.write(label(part))
            out.write(0)
        }
        short(0x1234)
        short(0x8400)
        short(0)
        short(3)
        short(0)
        short(0)
        val instanceAt = out.size()
        name(name)
        short(16)
        short(0x8001)
        out.write(byteArrayOf(0, 0, 0x11, 0x94.toByte()))
        val txt = listOf("server_id=21c3", "server_version=2.10.9", "base_url=http://192.168.1.14:8095")
            .fold(ByteArray(0)) { acc, entry -> acc + label(entry) }
        short(txt.size)
        out.write(txt)
        // SRV, its owner name compressed to the instance above.
        short(0xC000 or instanceAt)
        short(33)
        short(0x8001)
        out.write(byteArrayOf(0, 0, 0, 120))
        val targetAt = out.size() + 2 + 6
        val target = label("mass") + label("local") + byteArrayOf(0)
        short(6 + target.size)
        short(0)
        short(0)
        short(8095)
        out.write(target)
        // A for the target, its name compressed to the SRV target.
        short(0xC000 or targetAt)
        short(1)
        short(0x8001)
        out.write(byteArrayOf(0, 0, 0, 120))
        short(4)
        out.write(address)
        return out.toByteArray()
    }
}
