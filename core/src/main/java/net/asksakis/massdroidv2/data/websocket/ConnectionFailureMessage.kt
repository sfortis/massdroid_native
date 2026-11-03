package net.asksakis.massdroidv2.data.websocket

import net.asksakis.massdroidv2.domain.connection.MA_DEFAULT_PORT
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private val UPGRADE_STATUS = Regex("Expected HTTP 101 response but was '(\\d{3})")
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_GATEWAY_TIMEOUT = 504

internal const val NOT_MUSIC_ASSISTANT_MESSAGE =
    "This address answered, but not as Music Assistant. If Music Assistant runs in Home Assistant, " +
        "use the Home Assistant host with port 8095, not the Home Assistant address."
internal const val NOT_HTTPS_MESSAGE = "This server does not use https. Change https:// to http:// in the address."

/**
 * Turns a failed WebSocket connection into a sentence the user can act on.
 *
 * OkHttp's own messages, such as "Expected HTTP 101 response but was '404 Not Found'", are
 * what a user sees after entering the Home Assistant address, and they say nothing about the
 * cause. A failure this does not recognise keeps OkHttp's message.
 */
internal fun describeConnectionFailure(error: Throwable, serverUrl: String?, signingIn: Boolean = true): String {
    val url = serverUrl?.toHttpUrlOrNull()
    val target = url?.let { "${it.host}:${it.port}" } ?: "the server"
    for (cause in generateSequence(error) { it.cause }) {
        // After a working session a failed reconnect is usually the network or the server
        // being down, so the address advice is kept for a sign-in.
        if (!signingIn && cause is IOException && cause !is ProtocolException && cause !is SSLException) {
            return "Can't reach $target. Retrying."
        }
        when (cause) {
            is ProtocolException -> UPGRADE_STATUS.find(cause.message.orEmpty())?.let {
                return describeUpgradeStatus(it.groupValues[1].toInt())
            }
            is SSLException -> if (isPlainHttpAnswer(cause)) return NOT_HTTPS_MESSAGE
            is UnknownHostException -> return "Can't find ${url?.host ?: "the server"}. Check the " +
                "address. If the name ends in .local, try the server's IP address instead."
            is NoRouteToHostException -> return "This device can't reach $target. Check that it is " +
                "on the same network as Music Assistant, or connected through your VPN."
            is ConnectException -> return if (isRefused(error)) {
                "Nothing answers at $target. Check the address and the port. Music Assistant " +
                    "normally uses port 8095."
            } else {
                "This device can't reach $target. Check that it is on the same network as Music " +
                    "Assistant, or connected through your VPN."
            }
            is SocketTimeoutException -> return "The server did not answer in time. Check the address " +
                "and that Music Assistant is reachable on this network."
        }
    }
    return error.message ?: "Connection failed"
}

internal const val NO_MUSIC_ASSISTANT_MESSAGE = "No Music Assistant answers at this address. Check the " +
    "address, and that this device is on your home network or VPN."
internal const val NOT_SECURE_MESSAGE =
    "This server does not use a secure connection. Choose MA port or HTTP instead of HTTPS, or remove https:// from the address."

/**
 * The sentence the sign-in screens show when a server address did not answer as Music
 * Assistant. [error] is the connection failure, or null when the address answered without
 * greeting as Music Assistant or did not answer in time.
 *
 * The sign-in screens set https with a switch rather than with the scheme, so a secure
 * connection to a plain server names the switch. A name that cannot be looked up keeps its
 * own advice about .local names. A connection refused on a port other than Music Assistant's
 * names the port, because a wrong port is the usual cause. Everything else that never reached
 * a server is said in one sentence, because the user's next step is the same for all of it.
 */
internal fun describeServerCheckFailure(error: Throwable?, serverUrl: String): String {
    if (error == null) return NO_MUSIC_ASSISTANT_MESSAGE
    // The whole chain is read for a known cause first, because a wrapper around it is an
    // IOException too and would hide it behind the general sentence.
    val causes = generateSequence(error) { it.cause }.toList()
    for (cause in causes) {
        when (cause) {
            is SSLException -> if (isPlainHttpAnswer(cause)) return NOT_SECURE_MESSAGE
            is ProtocolException -> if (UPGRADE_STATUS.containsMatchIn(cause.message.orEmpty())) {
                return describeConnectionFailure(error, serverUrl)
            }
            is UnknownHostException -> return describeConnectionFailure(error, serverUrl)
            is ConnectException -> {
                val port = serverUrl.toHttpUrlOrNull()?.port
                if (port != null && port != MA_DEFAULT_PORT && isRefused(error)) {
                    return describeConnectionFailure(cause, serverUrl)
                }
            }
        }
    }
    if (causes.any { it is IOException && it !is SSLException && it !is ProtocolException }) {
        return NO_MUSIC_ASSISTANT_MESSAGE
    }
    return describeConnectionFailure(error, serverUrl)
}

private fun describeUpgradeStatus(status: Int): String = when (status) {
    HTTP_NOT_FOUND, in 200..399 -> NOT_MUSIC_ASSISTANT_MESSAGE
    HTTP_BAD_REQUEST -> "The server or a proxy in front of it did not accept the connection (HTTP 400). " +
        "A reverse proxy must pass WebSocket connections on /ws."
    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> "The server or a proxy in front of it refused access (HTTP $status)."
    in HTTP_BAD_GATEWAY..HTTP_GATEWAY_TIMEOUT -> "The server's proxy could not reach Music Assistant (HTTP $status)."
    else -> "The server refused the connection (HTTP $status)."
}

// Android raises ConnectException for most connect errors, an unreachable network or host
// included, so only the errno text tells a refused connection apart: "ECONNREFUSED (Connection
// refused)" on Android, "Connection refused" on the JVM.
private fun isRefused(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
    val message = cause.message.orEmpty()
    message.contains("ECONNREFUSED") || message.contains("Connection refused", ignoreCase = true)
}

// A TLS handshake sent to a plain http port reads the http answer as a TLS record, which
// BoringSSL reports as a wrong version number.
private fun isPlainHttpAnswer(error: SSLException): Boolean {
    val message = error.message.orEmpty()
    return message.contains("WRONG_VERSION_NUMBER", ignoreCase = true) ||
        message.contains("wrong version number", ignoreCase = true) ||
        message.contains("Unable to parse TLS packet header", ignoreCase = true)
}
