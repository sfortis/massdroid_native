package net.asksakis.massdroidv2.data.party

import net.asksakis.massdroidv2.data.websocket.isPrivateHost
import java.net.URI

/**
 * Whether the Party join link at [url] opens only on the server's own network, so that a
 * guest on mobile data cannot join.
 *
 * The plugin falls back to the server's local address when neither an external URL nor remote
 * access is set up, and it copies that address as it is. Beyond the private IPv4 ranges this
 * therefore counts a name without a dot (a bare hostname), a local IPv6 address, and the
 * local-only name suffixes. A Tailscale `.ts.net` name is not counted, because a Funnel URL
 * there is public.
 */
internal fun opensOnlyAtHome(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()
        ?.lowercase()
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?: return false
    return when {
        ':' in host -> isLocalIpv6(host)
        '.' !in host -> true
        LOCAL_SUFFIXES.any { host.endsWith(it) } -> true
        host.endsWith(".ts.net") -> false
        host.startsWith(LINK_LOCAL_IPV4) -> true
        else -> isPrivateHost(host)
    }
}

/** Loopback, unique local (fc00::/7) or link-local (fe80::/10). */
private fun isLocalIpv6(host: String): Boolean =
    host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
        LINK_LOCAL_IPV6_PREFIXES.any { host.startsWith(it) }

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")
private val LINK_LOCAL_IPV6_PREFIXES = listOf("fe8", "fe9", "fea", "feb")
private const val LINK_LOCAL_IPV4 = "169.254."
