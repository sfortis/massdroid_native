package net.asksakis.massdroidv2.domain.connection

import kotlin.math.abs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The port the Music Assistant webserver listens on unless its settings change it. */
const val MA_DEFAULT_PORT = 8095

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val HOME_ASSISTANT_PORT = 8123
private const val MAX_PORT = 65535
private const val NABU_CASA_SUFFIX = ".ui.nabu.casa"
private const val INGRESS_SEGMENT = "hassio_ingress"

// The Music Assistant add-on's ingress panel lives under a path such as
// "/d5369777_music_assistant", the add-on slug.
private const val ADDON_PANEL_SUFFIX = "_music_assistant"

private val EMBEDDED_PORT = Regex(":(\\d*)$")

// "h" up to "https://": a scheme still being typed, which is not an address yet.
private val PARTIAL_SCHEME = Regex("^h(t(t(p(s?(:(/(/)?)?)?)?)?)?)?$", RegexOption.IGNORE_CASE)

// "http:/host", "http//host" or "https:host": a scheme with a slip, not a host named "http".
private val MISTYPED_SCHEME = Regex("^https?(:/?|//)[^/]", RegexOption.IGNORE_CASE)

const val ADDRESS_HAS_SPACE_MESSAGE = "Remove the space. An address has no spaces."
const val ADDRESS_INVALID_MESSAGE = "This is not a valid server address."
const val ADDRESS_SCHEME_MESSAGE = "Start the address with http:// or https://, or leave the scheme out."
const val PORT_INVALID_MESSAGE = "The port is a number from 1 to 65535."

/** Where an address leads when it reaches Home Assistant instead of Music Assistant. */
enum class HomeAssistantAddress(val hint: String) {
    HOME_ASSISTANT(
        "This is your Home Assistant address. Music Assistant runs on the same machine, on port 8095."
    ),
    NABU_CASA(
        "Home Assistant Cloud reaches only Home Assistant. Use your home address, or a VPN when you are away."
    ),
    INGRESS(
        "This is the Music Assistant panel inside Home Assistant, which apps cannot open. Use your " +
            "Home Assistant address with port 8095."
    )
}

/** The outcome of reading what the user typed as a server address. */
sealed interface ServerAddress {
    data object Empty : ServerAddress
    data class Invalid(val message: String) : ServerAddress

    /**
     * An address the client can connect to. [homeAssistant] is set when the address leads to
     * Home Assistant, which never answers as Music Assistant.
     */
    data class Valid(val url: String, val homeAssistant: HomeAssistantAddress? = null) : ServerAddress
}

/** The connection chips under Other server. */
enum class PortChoice { MUSIC_ASSISTANT, HTTP, HTTPS, CUSTOM }

/**
 * What the sign-in form holds: the address without its port, the port, and whether the HTTPS
 * chip is chosen.
 *
 * The port follows the scheme until the user edits it: 443 for an https address and the Music
 * Assistant port otherwise. A port typed or pasted into the address takes precedence over the
 * port field, so a full URL works as typed. The chips never write a scheme into the address, so
 * the field shows what the user typed whichever chip is chosen; [https] carries the HTTPS chip,
 * and a scheme the user typed takes precedence over it.
 */
data class ServerAddressInput(
    val address: String = "",
    val port: String = MA_DEFAULT_PORT.toString(),
    val portEdited: Boolean = false,
    val https: Boolean = false
) {
    /** The port that will be used: one written in the address, else the port field. */
    val portInAddress: String? get() = embeddedPort(address)

    fun withAddress(newAddress: String): ServerAddressInput {
        val grown = newAddress.length - address.length
        // A paste either adds more than one character, or replaces the text with another of a
        // different length (select all, then paste a shorter URL).
        val replaced = abs(grown) > 1 && !newAddress.startsWith(address) && !address.startsWith(newAddress)
        val pasted = grown > 1 || replaced
        val parts = splitAddress(newAddress.trim())
        val typedPort = parts?.port
        // A pasted URL carries its port into the port field. While typing, the port stays in
        // the address, because moving it would break the next keystroke.
        if (pasted && parts != null && !typedPort.isNullOrEmpty()) {
            return copy(
                address = parts.schemePrefix + parts.host + parts.path,
                port = typedPort,
                portEdited = true
            )
        }
        // A pasted URL without a port gets the port the form suggests for its scheme (443 for
        // https, Music Assistant's 8095 for http), whatever port an earlier paste brought in.
        // A saved URL is different: it leaves out only the scheme's own port, so fromUrl reads
        // a saved http URL without a port as 80.
        if (pasted && newAddress.contains("://")) {
            return copy(address = newAddress, port = defaultPortFor(newAddress).toString(), portEdited = false)
        }
        // A scheme typed, changed or removed decides the port again, whatever a chip set
        // before: http:// after the HTTP chip means Music Assistant's port, not 80.
        val schemeChanged = schemePrefixOf(newAddress) != schemePrefixOf(address)
        if (schemeChanged) {
            val secureNow = schemePrefixOf(newAddress)?.let { it == "https://" } ?: https
            val schemePort = if (secureNow) HTTPS_DEFAULT_PORT else MA_DEFAULT_PORT
            return copy(address = newAddress, port = schemePort.toString(), portEdited = false)
        }
        val nextPort = if (portEdited) port else defaultPortFor(newAddress).toString()
        return copy(address = newAddress, port = nextPort)
    }

    /**
     * The input with [newPort] as its port. A port written in the address is taken out, because
     * the port field now says which port is used.
     */
    fun withPort(newPort: String): ServerAddressInput {
        val parts = splitAddress(address.trim())
        val bare = if (parts?.port != null) parts.schemePrefix + parts.host + parts.path else address
        return copy(address = bare, port = newPort, portEdited = true)
    }

    /** The port the URL will use: one written in the address, else the port field. */
    val effectivePort: String get() = portInAddress ?: port

    /** Whether the URL will use a secure connection: a typed scheme decides, else the HTTPS chip. */
    val secure: Boolean
        get() {
            val typed = schemePrefixOf(address) ?: return https
            return typed == "https://"
        }

    /**
     * Which connection chip the input matches: Music Assistant's own port over http, http or
     * https on the scheme's standard port, or anything else.
     */
    val portChoice: PortChoice
        get() = when {
            !secure && effectivePort == MA_DEFAULT_PORT.toString() -> PortChoice.MUSIC_ASSISTANT
            !secure && effectivePort == HTTP_DEFAULT_PORT.toString() -> PortChoice.HTTP
            secure && effectivePort == HTTPS_DEFAULT_PORT.toString() -> PortChoice.HTTPS
            else -> PortChoice.CUSTOM
        }

    /**
     * The input with a connection chip applied. The first three set the scheme and the port
     * together; Custom keeps both, because it only opens the port field.
     */
    fun withPortChoice(choice: PortChoice): ServerAddressInput = when (choice) {
        PortChoice.MUSIC_ASSISTANT -> withoutScheme(https = false).withPort(MA_DEFAULT_PORT.toString())
        PortChoice.HTTP -> withoutScheme(https = false).withPort(HTTP_DEFAULT_PORT.toString())
        PortChoice.HTTPS -> withoutScheme(https = true).withPort(HTTPS_DEFAULT_PORT.toString())
        PortChoice.CUSTOM -> this
    }

    /** The input with a typed scheme taken out of the address, and [https] saying which one applies. */
    private fun withoutScheme(https: Boolean): ServerAddressInput {
        val parts = splitAddress(address.trim()) ?: return copy(https = https)
        val bare = parts.host + (parts.port?.let { ":$it" } ?: "") + parts.path
        return copy(address = bare, https = https)
    }

    fun toServerAddress(): ServerAddress {
        // The address is read as typed first, so a half-typed or mistyped scheme ("http",
        // "http:/host") is caught before the HTTPS chip puts https:// in front of it.
        val asTyped = parseServerAddress(address, port)
        if (!https || asTyped !is ServerAddress.Valid || schemePrefixOf(address) != null) return asTyped
        return parseServerAddress("https://${address.trim()}", port)
    }

    companion object {
        /** Splits a saved server URL into the two fields of the form. */
        fun fromUrl(url: String): ServerAddressInput {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return ServerAddressInput()
            val parts = splitAddress(trimmed) ?: return ServerAddressInput(address = trimmed)
            val explicit = parts.port
            val address = parts.schemePrefix + parts.host + parts.path
            return when {
                !explicit.isNullOrEmpty() -> ServerAddressInput(address, explicit, portEdited = true)
                // A saved URL without a port means the scheme's own port, which for http is not
                // the Music Assistant port.
                parts.scheme == "https" -> ServerAddressInput(address, HTTPS_DEFAULT_PORT.toString())
                else -> ServerAddressInput(address, HTTP_DEFAULT_PORT.toString(), portEdited = true)
            }
        }
    }
}

/** The scheme the user typed in [address], such as "https://", or null when none was typed. */
private fun schemePrefixOf(address: String): String? =
    splitAddress(address.trim())?.schemePrefix?.takeIf { it.isNotEmpty() }

/** The port the form suggests for [address]: 443 for https, the Music Assistant port otherwise. */
fun defaultPortFor(address: String): Int =
    if (address.trim().startsWith("https://", ignoreCase = true)) HTTPS_DEFAULT_PORT else MA_DEFAULT_PORT

/**
 * Reads an address and a port into a URL the client can connect to.
 *
 * An address without a scheme gets http://, because that is how Music Assistant answers out of
 * the box. A port written in the address takes precedence over [port]. The URL omits the port
 * when it is the scheme's own, so a saved URL keeps the form it had before the port field.
 */
fun parseServerAddress(address: String, port: String = ""): ServerAddress {
    val trimmed = address.trim()
    if (trimmed.isEmpty() || PARTIAL_SCHEME.matches(trimmed)) return ServerAddress.Empty
    if (!trimmed.contains("://") && MISTYPED_SCHEME.containsMatchIn(trimmed)) {
        return ServerAddress.Invalid(ADDRESS_SCHEME_MESSAGE)
    }
    if (trimmed.any { it.isWhitespace() } || port.trim().any { it.isWhitespace() }) {
        return ServerAddress.Invalid(ADDRESS_HAS_SPACE_MESSAGE)
    }
    val parts = splitAddress(trimmed) ?: return ServerAddress.Invalid(ADDRESS_SCHEME_MESSAGE)
    if (parts.host.isEmpty()) return ServerAddress.Invalid(ADDRESS_INVALID_MESSAGE)

    val portText = parts.port?.takeIf { it.isNotEmpty() } ?: port.trim()
    val portNumber = if (portText.isEmpty()) {
        if (parts.scheme == "https") HTTPS_DEFAULT_PORT else MA_DEFAULT_PORT
    } else {
        portText.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
            ?: return ServerAddress.Invalid(PORT_INVALID_MESSAGE)
    }
    val schemePort = if (parts.scheme == "https") HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
    val portSuffix = if (portNumber == schemePort) "" else ":$portNumber"
    val url = "${parts.scheme}://${parts.host}$portSuffix${parts.path}"

    // OkHttp parses the WebSocket URL the same way, so an address it refuses here would only
    // fail later, at the connection.
    val parsed = url.toHttpUrlOrNull() ?: return ServerAddress.Invalid(ADDRESS_INVALID_MESSAGE)
    return ServerAddress.Valid(url, homeAssistantKind(parsed))
}

/**
 * The address where Music Assistant usually answers when [address] is Home Assistant's own:
 * the add-on runs on the same host, on the Music Assistant port, over http.
 */
fun musicAssistantCandidate(address: ServerAddress.Valid): String? {
    if (address.homeAssistant != HomeAssistantAddress.HOME_ASSISTANT) return null
    val url = address.url.toHttpUrlOrNull() ?: return null
    // Only from a plain http address: offering http for an https one would send the
    // password in the clear to a host the user reached over https.
    if (url.scheme != "http") return null
    val host = url.host
    val hostPart = if (host.contains(':')) "[$host]" else host
    return "http://$hostPart:$MA_DEFAULT_PORT"
}

private fun homeAssistantKind(url: HttpUrl): HomeAssistantAddress? = when {
    url.host.endsWith(NABU_CASA_SUFFIX, ignoreCase = true) -> HomeAssistantAddress.NABU_CASA
    url.pathSegments.any { it == INGRESS_SEGMENT || it.endsWith(ADDON_PANEL_SUFFIX) } ->
        HomeAssistantAddress.INGRESS
    url.port == HOME_ASSISTANT_PORT -> HomeAssistantAddress.HOME_ASSISTANT
    else -> null
}

private fun embeddedPort(address: String): String? =
    splitAddress(address.trim())?.port?.takeIf { it.isNotEmpty() }

private class AddressParts(
    val scheme: String,
    val schemePrefix: String,
    val host: String,
    val port: String?,
    val path: String
)

/**
 * Splits an address into its scheme, host, port and path. Returns null for a scheme other than
 * http or https. The port is null when none is written, and empty when only the colon is.
 */
private fun splitAddress(address: String): AddressParts? {
    val schemeEnd = address.indexOf("://")
    // A ws:// or wss:// URL from an older version is read as the http form it stands for.
    val scheme = when (val typed = if (schemeEnd >= 0) address.substring(0, schemeEnd).lowercase() else "http") {
        "ws" -> "http"
        "wss" -> "https"
        else -> typed
    }
    if (scheme != "http" && scheme != "https") return null
    val schemePrefix = if (schemeEnd >= 0) "$scheme://" else ""
    val rest = if (schemeEnd >= 0) address.substring(schemeEnd + 3) else address
    val pathStart = rest.indexOf('/').let { if (it < 0) rest.length else it }
    val hostAndPort = rest.substring(0, pathStart)
    val path = rest.substring(pathStart).trimEnd('/')
    // A bracketed IPv6 host keeps its colons; only a colon after the closing bracket starts a port.
    val match = EMBEDDED_PORT.find(hostAndPort)?.takeIf {
        !hostAndPort.startsWith("[") || hostAndPort.lastIndexOf(']') in 0 until it.range.first
    }
    val host = match?.let { hostAndPort.substring(0, it.range.first) } ?: hostAndPort
    return AddressParts(scheme, schemePrefix, host, match?.groupValues?.get(1), path)
}
