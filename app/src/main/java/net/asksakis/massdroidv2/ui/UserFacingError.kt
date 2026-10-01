package net.asksakis.massdroidv2.ui

import net.asksakis.massdroidv2.data.websocket.MaApiException
import java.io.IOException

/**
 * What to put on screen when something did not go through.
 *
 * The shape is what failed, then why in a few words: "Couldn't add that to the queue. The
 * server did not answer". The first half is the only part the caller knows and the second
 * is the only part the exception knows, so neither can write the message alone.
 *
 * Every one of these used to read "Not connected to server", whatever had happened. That
 * was wrong far more often than it was right: a refusal from the server, a command the
 * server does not accept in its current state, and a request that timed out all arrived
 * here, and all of them reached the listener as a claim about their network. Somebody
 * pressing shuffle on a queue the server had locked was told to check their connection
 * while the music kept playing.
 */
fun Throwable.failureMessage(what: String): String =
    failureReason()?.let { "$what. $it" } ?: what

/**
 * Why it failed, or null when there is nothing worth adding.
 *
 * The server's own words are used when it refused, because they say what is actually in
 * the way, and only a transport failure is described as a connection problem. An exception
 * the app cannot read says nothing rather than guessing, which leaves the caller's own
 * sentence to stand on its own.
 */
private fun Throwable.failureReason(): String? = when {
    this is MaApiException && isConnectionLost -> NOT_CONNECTED
    this is MaApiException && isTimeout -> "The server did not answer"
    this is MaApiException -> message?.takeIf { it.isNotBlank() }
    this is IOException -> NOT_CONNECTED
    else -> null
}

private const val NOT_CONNECTED = "Not connected to the server"
