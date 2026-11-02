package net.asksakis.massdroidv2.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
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
    /**
     * What the server behind [instance] answers, or null when nothing answered in time. The query
     * goes out on [network], the network discovery found the service on when Android says so
     * (API 33 and later), else on the first Wi-Fi or Ethernet network.
     */
    suspend fun resolve(instance: String, network: Network? = null): MdnsReply? =
        withContext(Dispatchers.IO) {
            val local = network ?: localNetwork()
            try {
                MulticastSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(0))
                    if (local != null) pinTo(socket, local)
                    socket.timeToLive = MULTICAST_TTL
                    socket.soTimeout = RECEIVE_SLICE_MS
                    exchange(socket, instance)
                }
            } catch (e: IOException) {
                Log.d(TAG, "Own resolve of $instance failed: ${e.message}")
                null
            }
        }

    /**
     * Keeps the query on [network]'s link. Both steps are best effort: a lockdown VPN can refuse
     * to bind the socket to a network outside it, and the query is then still sent on the
     * default route instead of not at all.
     */
    private fun pinTo(socket: MulticastSocket, network: Network) {
        try {
            network.bindSocket(socket)
        } catch (e: IOException) {
            Log.d(TAG, "Could not bind the query to the local network: ${e.message}")
        }
        try {
            interfaceOf(network)?.let { socket.networkInterface = it }
        } catch (e: IOException) {
            Log.d(TAG, "Could not pin the query to the local interface: ${e.message}")
        }
    }

    private suspend fun exchange(socket: MulticastSocket, instance: String): MdnsReply? {
        val query = MdnsMessage.query(instance, Random.nextInt(1, MAX_QUERY_ID))
        val group = InetAddress.getByName(MDNS_GROUP)
        val buffer = ByteArray(MAX_PACKET)
        // What each responder said so far, by address. Its SRV and TXT records can come in
        // separate packets, so the resolve waits until one responder has sent both.
        val replies = HashMap<String, MdnsServiceRecord>()
        // The elapsed clock, so a change of the wall clock cannot stretch or end the wait.
        val deadline = SystemClock.elapsedRealtime() + RESOLVE_TIMEOUT_MS
        var nextSend = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            // A lost multicast packet is common on Wi-Fi, so the query goes out again until the
            // server answers or the time is up.
            if (SystemClock.elapsedRealtime() >= nextSend) {
                socket.send(DatagramPacket(query, query.size, group, MDNS_PORT))
                nextSend = SystemClock.elapsedRealtime() + RESEND_INTERVAL_MS
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val record = MdnsMessage.parseAnswer(packet.data, packet.length, instance) ?: continue
            val responder = packet.address.hostAddress.orEmpty()
            val merged = replies[responder]?.mergedWith(record) ?: record
            replies[responder] = merged
            if (merged.isComplete) return MdnsReply(merged, responder)
        }
        // A responder that sent its SRV record but never a TXT one still gives the port.
        return replies.entries.firstOrNull { it.value.port != null }?.let { MdnsReply(it.value, it.key) }
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
