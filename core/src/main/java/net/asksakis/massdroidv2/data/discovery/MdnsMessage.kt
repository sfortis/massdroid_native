package net.asksakis.massdroidv2.data.discovery

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * What one mDNS responder said about a service instance: the port and host from its SRV record,
 * its TXT properties, and the addresses it gave for that host. [hasTxt] tells a TXT record with
 * no properties from no TXT record at all.
 */
data class MdnsServiceRecord(
    val port: Int?,
    val target: String?,
    val txt: Map<String, String?>,
    val addresses: List<String>,
    val hasTxt: Boolean = txt.isNotEmpty()
) {
    /** Both the SRV and the TXT record are in: nothing more to wait for. */
    val isComplete: Boolean get() = port != null && hasTxt

    /**
     * This answer and a [later] one from the same responder as one record. A responder may send
     * the SRV and the TXT record in separate packets; reading only the first one ended the
     * resolve with a TXT record and no port.
     */
    fun mergedWith(later: MdnsServiceRecord): MdnsServiceRecord = MdnsServiceRecord(
        port = later.port ?: port,
        target = later.target ?: target,
        txt = if (later.hasTxt) later.txt else txt,
        addresses = (addresses + later.addresses).distinct(),
        hasTxt = hasTxt || later.hasTxt
    )
}

/**
 * The two DNS messages the app's own mDNS resolve needs: a query for a service instance's SRV
 * and TXT records, and the reading of the answer.
 *
 * The query is sent from an ordinary port, which RFC 6762 section 6.7 calls a legacy unicast
 * query: the responder answers with a unicast packet to that port, so the app needs neither port
 * 5353 nor the system's mDNS daemon. Each Music Assistant server answers only for itself, so the
 * address in its answer is its own, whatever host name the servers share.
 */
internal object MdnsMessage {

    private const val TYPE_A = 1
    private const val TYPE_TXT = 16
    private const val TYPE_AAAA = 28
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1
    private const val HEADER_SIZE = 12
    private const val MAX_POINTER_JUMPS = 32
    private const val IPV4_SIZE = 4
    private const val IPV6_SIZE = 16
    // Priority, weight and port, and a target of at least the root label.
    private const val MIN_SRV_SIZE = 7

    /** A query with id [id] for the SRV and TXT records of [instance], such as "x._mass._tcp.local". */
    fun query(instance: String, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeShort(id)
        out.writeShort(0) // flags: a standard query
        out.writeShort(2) // two questions
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(0)
        for (type in intArrayOf(TYPE_SRV, TYPE_TXT)) {
            out.writeName(instance)
            out.writeShort(type)
            out.writeShort(CLASS_IN)
        }
        return out.toByteArray()
    }

    /**
     * The records about [instance] in [packet], or null when the packet says nothing about it or
     * cannot be read. A packet from the network is untrusted, so a malformed one never throws.
     */
    fun parseAnswer(packet: ByteArray, length: Int, instance: String): MdnsServiceRecord? = try {
        parse(Reader(packet, length), instance)
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parse(reader: Reader, instance: String): MdnsServiceRecord? {
        reader.position = 4
        val questions = reader.short()
        val records = reader.short() + reader.short() + reader.short()
        reader.position = HEADER_SIZE
        repeat(questions) {
            reader.name()
            reader.position += 4
        }
        var port: Int? = null
        var target: String? = null
        var txt: Map<String, String?>? = null
        val hosts = mutableListOf<Pair<String, String>>()
        repeat(records) {
            val name = reader.name()
            val type = reader.short()
            reader.position += 2 + 4 // class, TTL
            val length = reader.short()
            val start = reader.position
            require(start + length <= reader.limit)
            when {
                type == TYPE_SRV && name.equals(instance, ignoreCase = true) -> {
                    require(length >= MIN_SRV_SIZE)
                    reader.position = start + 4 // priority, weight
                    port = reader.short()
                    target = reader.name()
                }
                type == TYPE_TXT && name.equals(instance, ignoreCase = true) ->
                    txt = readTxt(reader.bytes, start, length)
                type == TYPE_A && length == IPV4_SIZE ->
                    hosts += name to address(reader.bytes, start, IPV4_SIZE)
                type == TYPE_AAAA && length == IPV6_SIZE ->
                    hosts += name to address(reader.bytes, start, IPV6_SIZE)
            }
            reader.position = start + length
        }
        if (port == null && txt == null) return null
        val addresses = hosts.filter { (host, _) -> target != null && host.equals(target, ignoreCase = true) }
            .map { it.second }
        return MdnsServiceRecord(port, target, txt.orEmpty(), addresses, hasTxt = txt != null)
    }

    private fun readTxt(bytes: ByteArray, start: Int, length: Int): Map<String, String?> {
        val properties = LinkedHashMap<String, String?>()
        var i = start
        while (i < start + length) {
            val size = bytes[i].toInt() and 0xFF
            // An entry that runs past its record would read the next record, or bytes left in
            // the receive buffer by an earlier packet.
            require(i + 1 + size <= start + length)
            val entry = String(bytes, i + 1, size, Charsets.UTF_8)
            i += 1 + size
            if (entry.isEmpty()) continue
            val separator = entry.indexOf('=')
            if (separator < 0) {
                properties[entry] = null
            } else {
                properties[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
        }
        return properties
    }

    private fun address(bytes: ByteArray, start: Int, size: Int): String =
        InetAddress.getByAddress(bytes.copyOfRange(start, start + size)).hostAddress.orEmpty()

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeName(name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            write(bytes.size)
            write(bytes)
        }
        write(0)
    }

    private class Reader(val bytes: ByteArray, val limit: Int) {
        var position = 0

        fun short(): Int {
            require(position + 2 <= limit)
            val value = (bytes[position].toInt() and 0xFF shl 8) or (bytes[position + 1].toInt() and 0xFF)
            position += 2
            return value
        }

        /** A domain name, following compression pointers, with the position left after it. */
        fun name(): String {
            val labels = mutableListOf<String>()
            var at = position
            var resumeAt = -1
            var jumps = 0
            while (true) {
                require(at < limit)
                val length = bytes[at].toInt() and 0xFF
                when {
                    length == 0 -> {
                        at += 1
                        break
                    }
                    length and 0xC0 == 0xC0 -> {
                        require(at + 1 < limit && ++jumps <= MAX_POINTER_JUMPS)
                        if (resumeAt < 0) resumeAt = at + 2
                        at = (length and 0x3F shl 8) or (bytes[at + 1].toInt() and 0xFF)
                    }
                    else -> {
                        require(at + 1 + length <= limit)
                        labels += String(bytes, at + 1, length, Charsets.UTF_8)
                        at += 1 + length
                    }
                }
            }
            position = if (resumeAt >= 0) resumeAt else at
            return labels.joinToString(".")
        }
    }
}
