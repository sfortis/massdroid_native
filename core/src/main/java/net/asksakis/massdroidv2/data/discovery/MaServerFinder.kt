package net.asksakis.massdroidv2.data.discovery

import android.content.Context
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.websocket.MaAuthProbe
import net.asksakis.massdroidv2.domain.connection.ServerAddress
import net.asksakis.massdroidv2.domain.connection.musicAssistantCandidate
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * A Music Assistant server announced on the local network. [name] is what the user recognises:
 * the server's own name without the "Music Assistant (...)" around it, or "Home Assistant
 * add-on" for the add-on, whose announced name is only its container hostname.
 */
data class DiscoveredServer(
    val name: String,
    val url: String,
    val version: String?,
    val isHomeAssistantAddon: Boolean
)

/** The name to show for a server that announces [announced] as its name. */
internal fun displayNameOf(announced: String?, isHomeAssistantAddon: Boolean): String {
    if (isHomeAssistantAddon) return HOME_ASSISTANT_ADDON_NAME
    val name = announced?.trim().orEmpty()
    return ANNOUNCED_NAME.matchEntire(name)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        ?: name.ifEmpty { DEFAULT_SERVER_NAME }
}

/**
 * The server a resolved service describes. The server's base URL keeps its scheme (https when
 * Music Assistant serves TLS itself), but only while it points at the port the service
 * announced. A base URL on another port is a reverse proxy, which may not be reachable from
 * home, so the address found on this network is used instead.
 */
internal fun discoveredServerOf(
    txt: Map<String, String?>,
    hostAddress: String?,
    port: Int
): DiscoveredServer? {
    val baseUrl = txt["base_url"]?.toHttpUrlOrNull()
    val url = if (baseUrl != null && baseUrl.port == port) {
        baseUrl.toString().trimEnd('/')
    } else {
        val host = hostAddress?.substringBefore('%')?.takeIf { it.isNotEmpty() } ?: return null
        val hostPart = if (host.contains(':')) "[$host]" else host
        "http://$hostPart:$port"
    }
    val isAddon = txt["homeassistant_addon"].toBoolean()
    return DiscoveredServer(
        name = displayNameOf(txt["name"], isAddon),
        url = url,
        version = txt["server_version"],
        isHomeAssistantAddon = isAddon
    )
}

private const val HOME_ASSISTANT_ADDON_NAME = "Home Assistant add-on"
private const val DEFAULT_SERVER_NAME = "Music Assistant"
private val ANNOUNCED_NAME = Regex("^Music Assistant \\((.+)\\)$")

/**
 * Finds Music Assistant servers for the sign-in screens.
 *
 * Every server announces itself over mDNS as `_mass._tcp`, with its webserver port and its
 * server info (server_id, server_version, base_url) as TXT properties; Home Assistant's
 * integration discovers it the same way. The finder also checks whether Music Assistant answers
 * next to a Home Assistant address, for the user who typed the Home Assistant URL.
 */
@Singleton
class MaServerFinder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: MaAuthProbe,
    private val ownResolver: MdnsServiceResolver
) {
    /**
     * The servers on this network, updated as they appear and disappear. Discovery runs only
     * while the flow is collected, so a screen that stops collecting stops the multicast.
     */
    fun servers(): Flow<List<DiscoveredServer>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            send(emptyList())
            close()
            return@callbackFlow
        }
        val found = LinkedHashMap<String, DiscoveredServer>()
        // The services found are resolved one at a time, which is all NsdManager allows an app
        // before API 34.
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        send(emptyList())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                toResolve.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(found) {
                    if (found.remove(serviceInfo.serviceName) != null) trySend(found.values.toList())
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery did not start: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
        }

        val resolver = launch {
            val fallback = SystemFallbackGate()
            for (service in toResolve) {
                val server = resolveServer(nsd, service, fallback) ?: continue
                synchronized(found) {
                    found[service.serviceName] = server
                    trySend(found.values.toList())
                }
            }
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "mDNS discovery refused: ${e.message}")
            close()
        }
        awaitClose {
            resolver.cancel()
            toResolve.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    /**
     * The Music Assistant address next to a Home Assistant address, when Music Assistant answers
     * there; null otherwise.
     */
    suspend fun musicAssistantInsteadOf(address: ServerAddress.Valid): String? {
        val candidate = musicAssistantCandidate(address) ?: return null
        return candidate.takeIf { probe.answersAsMusicAssistant(it) }
    }

    /**
     * Turns a service found by discovery into a server. From Android 14 the system resolves it,
     * and the app's own query covers a resolve that fails. Before Android 14 the app's own query
     * goes first, and the system resolve stands in only while [fallback] allows it (a VPN that
     * keeps the query off the local network, for example). The system resolve there runs one at
     * a time per app, cannot be stopped, and stalls on servers that share a host name (every
     * Music Assistant server registers `mass.local.`), which on the Shield left every other
     * server unresolved behind it, so it is never the first try.
     */
    private suspend fun resolveServer(
        nsd: NsdManager,
        service: NsdServiceInfo,
        fallback: SystemFallbackGate
    ): DiscoveredServer? {
        val systemFirst = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        if (systemFirst) systemResolve(nsd, service)?.let { return it }
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) service.network else null
        val reply = ownResolver.resolve("${service.serviceName}.$SERVICE_DOMAIN", network) ?: run {
            Log.d(TAG, "No answer for ${service.serviceName}")
            if (systemFirst || !fallback.open) return null
            return systemResolve(nsd, service).also { if (it == null) fallback.close("the system resolve failed too") }
        }
        if (!systemFirst) fallback.close("the app's own query works on this network")
        val record = reply.record
        val port = record.port ?: return null
        return discoveredServerOf(
            txt = record.txt,
            hostAddress = record.addresses.firstOrNull { !it.contains(':') } ?: reply.responder,
            port = port
        )
    }

    private suspend fun systemResolve(nsd: NsdManager, service: NsdServiceInfo): DiscoveredServer? =
        withTimeoutOrNull(SYSTEM_RESOLVE_TIMEOUT_MS) { nsd.resolve(service) }?.toDiscoveredServer()

    @Suppress("DEPRECATION") // resolveService is the call that exists on every supported API level.
    private suspend fun NsdManager.resolve(service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "Could not resolve ${serviceInfo.serviceName}: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            }
            resolveService(service, listener)
            // A resolve that timed out keeps running in the daemon unless it is stopped, which
            // Android allows from API 34; before that it cannot be stopped at all.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                cont.invokeOnCancellation { runCatching { stopServiceResolution(listener) } }
            }
        }

    private fun NsdServiceInfo.toDiscoveredServer(): DiscoveredServer? {
        @Suppress("DEPRECATION") // getHost is replaced by getHostAddresses only from API 34.
        val address = host ?: return null
        return discoveredServerOf(
            txt = attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8) },
            hostAddress = address.hostAddress,
            port = port
        )
    }

    /**
     * Whether the system resolve may still stand in for the app's own query in this discovery
     * session, before Android 14. It stays open only while it can help: the own query has not
     * worked yet (it is kept from the local network, by a VPN for example), and the system
     * resolve has not failed. Each failed one costs 5 s that cannot be stopped, and a stopped
     * server the system still lists made every later fallback fail with "already active".
     */
    private class SystemFallbackGate {
        var open = true
            private set

        fun close(reason: String) {
            if (!open) return
            open = false
            Log.d(TAG, "System resolve fallback off for this discovery: $reason")
        }
    }

    private companion object {
        const val TAG = "MaServerFinder"
        const val SERVICE_TYPE = "_mass._tcp."
        const val SERVICE_DOMAIN = "_mass._tcp.local"
        const val SYSTEM_RESOLVE_TIMEOUT_MS = 5_000L
    }
}
