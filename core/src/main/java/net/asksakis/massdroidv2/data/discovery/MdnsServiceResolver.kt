package net.asksakis.massdroidv2.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** A service instance resolved by the app itself: what its responder said, and who answered. */
data class MdnsReply(val record: MdnsServiceRecord, val responder: String)

/**
 * Resolves a service instance found by discovery without the system's mDNS daemon.
 *
 * Before Android 14 that daemon resolves one service at a time per app and cannot be stopped.
 * When Music Assistant servers share a host name (every server registers `mass.local.`), it stalls
 * on them: the Mi MIX (Android 10) gave up after 5 s each time, and the Shield (Android 11) never
 * answered at all, which left every other server unresolved behind it (2026-11-02). This sends
 * the query itself, from an ordinary port, over the Wi-Fi or Ethernet network, and reads the
 * unicast answer of the server, see [MdnsMessage].
 */
class MdnsServiceResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** What the server behind [instance] answers, or null when nothing answered in time. */
    suspend fun resolve(instance: String, timeoutMs: Long = RESOLVE_TIMEOUT_MS): MdnsReply? =
        withContext(Dispatchers.IO) {
            val network = localNetwork()
            try {
                MulticastSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(0))
                    network?.let { local ->
                        local.bindSocket(socket)
                        interfaceOf(local)?.let { socket.networkInterface = it }
                    }
                    socket.timeToLive = MULTICAST_TTL
                    socket.soTimeout = RECEIVE_SLICE_MS
                    exchange(socket, instance, timeoutMs)
                }
            } catch (e: IOException) {
                Log.d(TAG, "Own resolve of $instance failed: ${e.message}")
                null
            }
        }

    private suspend fun exchange(socket: MulticastSocket, instance: String, timeoutMs: Long): MdnsReply? {
        val query = MdnsMessage.query(instance, Random.nextInt(1, MAX_QUERY_ID))
        val group = InetAddress.getByName(MDNS_GROUP)
        val buffer = ByteArray(MAX_PACKET)
        val deadline = System.currentTimeMillis() + timeoutMs
        var nextSend = 0L
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            // A lost multicast packet is common on Wi-Fi, so the query goes out again until the
            // server answers or the time is up.
            if (System.currentTimeMillis() >= nextSend) {
                socket.send(DatagramPacket(query, query.size, group, MDNS_PORT))
                nextSend = System.currentTimeMillis() + RESEND_INTERVAL_MS
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val record = MdnsMessage.parseAnswer(packet.data, packet.length, instance) ?: continue
            return MdnsReply(record, packet.address.hostAddress.orEmpty())
        }
        return null
    }

    /** The Wi-Fi or Ethernet network, which carries the LAN even while a VPN is the default. */
    @Suppress("DEPRECATION") // allNetworks is the call that exists on every supported API level.
    private fun localNetwork(): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    private fun interfaceOf(network: Network): NetworkInterface? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val name = connectivity.getLinkProperties(network)?.interfaceName ?: return null
        return runCatching { NetworkInterface.getByName(name) }.getOrNull()
    }

    private companion object {
        const val TAG = "MdnsResolver"
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val MULTICAST_TTL = 255
        const val MAX_QUERY_ID = 0xFFFF
        const val MAX_PACKET = 9000
        const val RECEIVE_SLICE_MS = 250
        const val RESEND_INTERVAL_MS = 1_000L
        const val RESOLVE_TIMEOUT_MS = 3_500L
    }
}
