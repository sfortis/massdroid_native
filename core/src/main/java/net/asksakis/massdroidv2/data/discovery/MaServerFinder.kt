package net.asksakis.massdroidv2.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.websocket.MaAuthProbe
import net.asksakis.massdroidv2.domain.connection.ServerAddress
import net.asksakis.massdroidv2.domain.connection.musicAssistantCandidate
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet6Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * A Music Assistant server announced on the local network. [name] is what the user recognises:
 * the server's own name without the "Music Assistant (...)" around it, or "Home Assistant
 * add-on" for the add-on, whose announced name is only its container hostname.
 */
data class DiscoveredServer(
    val serverId: String,
    val name: String,
    val url: String,
    val version: String?,
    val isHomeAssistantAddon: Boolean
) {
    /** The address without its scheme, for display. */
    val address: String get() = url.substringAfter("://")
}

/** The name to show for a server that announces [announced] as its name. */
internal fun displayNameOf(announced: String?, isHomeAssistantAddon: Boolean): String {
    if (isHomeAssistantAddon) return HOME_ASSISTANT_ADDON_NAME
    val name = announced?.trim().orEmpty()
    return ANNOUNCED_NAME.matchEntire(name)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        ?: name.ifEmpty { DEFAULT_SERVER_NAME }
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
    private val probe: MaAuthProbe
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
        // NsdManager before API 34 refuses a second resolve while one is running, so the
        // services found are resolved one at a time.
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
            for (service in toResolve) {
                val resolved = nsd.resolveWithRetry(service) ?: continue
                val server = resolved.toDiscoveredServer() ?: continue
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
     * Resolves [service], trying again while the system is busy. Before Android 14 NsdManager
     * runs one resolve at a time for the whole app and answers a second one with
     * FAILURE_ALREADY_ACTIVE, and a resolve that ran past a timeout cannot be stopped, so on the
     * Mi MIX (Android 10) every server failed with codes 3 and 101 (2026-11-01). The resolves of
     * every list therefore share one lock, and a failed one waits and goes again.
     */
    private suspend fun NsdManager.resolveWithRetry(service: NsdServiceInfo): NsdServiceInfo? {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            val resolved = resolveLock.withLock { withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(service) } }
            if (resolved != null) return resolved
            delay(RESOLVE_RETRY_DELAY_MS * (attempt + 1))
        }
        Log.d(TAG, "Gave up resolving ${service.serviceName}")
        return null
    }

    @Suppress("DEPRECATION") // resolveService is the call that exists on every supported API level.
    private suspend fun NsdManager.resolve(service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.d(TAG, "Could not resolve ${serviceInfo.serviceName}: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            })
        }

    private fun NsdServiceInfo.toDiscoveredServer(): DiscoveredServer? {
        val txt = attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8) }
        @Suppress("DEPRECATION") // getHost is replaced by getHostAddresses only from API 34.
        val address = host ?: return null
        val hostText = address.hostAddress?.substringBefore('%') ?: return null
        val hostPart = if (address is Inet6Address) "[$hostText]" else hostText
        // The server's base URL keeps its scheme (https when Music Assistant serves TLS itself),
        // but only while it points at the port the server announced. A base URL on another
        // port is a reverse proxy, which may not be reachable from home, so the address found
        // on this network is used instead.
        val baseUrl = txt["base_url"]?.toHttpUrlOrNull()
        val url = if (baseUrl != null && baseUrl.port == port) {
            baseUrl.toString().trimEnd('/')
        } else {
            "http://$hostPart:$port"
        }
        val isAddon = txt["homeassistant_addon"].toBoolean()
        return DiscoveredServer(
            serverId = txt["server_id"] ?: serviceName,
            name = displayNameOf(txt["name"], isAddon),
            url = url,
            version = txt["server_version"],
            isHomeAssistantAddon = isAddon
        )
    }

    private val resolveLock = Mutex()

    private companion object {
        const val TAG = "MaServerFinder"
        const val SERVICE_TYPE = "_mass._tcp."
        const val RESOLVE_TIMEOUT_MS = 10_000L
        const val RESOLVE_ATTEMPTS = 4
        const val RESOLVE_RETRY_DELAY_MS = 600L
    }
}
