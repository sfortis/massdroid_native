package net.asksakis.massdroidv2.data.websocket

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What the OAuth deep link carried: the access token MA appended as `code`, and the
 * nonce the sign-in put in the return URL's path (null when the link had none).
 */
data class OAuthCallback(val state: String?, val code: String)

/** The scheme of the link MA opens when a Home Assistant sign-in ends; see [parseOAuthCallback]. */
const val OAUTH_CALLBACK_SCHEME = "musicassistant"

/**
 * Reads the link a Home Assistant sign-in ends on, `musicassistant://auth/callback/<nonce>?code=<token>`,
 * or returns null when [link] is not such a link or carries no token. The phone receives it as
 * a deep link from the browser, and the TV and car catch it inside their sign-in page.
 */
fun parseOAuthCallback(link: String): OAuthCallback? {
    val uri = try {
        URI(link)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!uri.scheme.equals(OAUTH_CALLBACK_SCHEME, ignoreCase = true) || uri.host != "auth") return null
    val code = uri.rawQuery.orEmpty().split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == "code" }
        ?.let { URLDecoder.decode(it[1], Charsets.UTF_8.name()) }
    if (code.isNullOrBlank()) return null
    // The sign-in's nonce is the path segment after "callback"; see MaAuthRepositoryImpl.OAUTH_RETURN_URL.
    val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    val state = segments.takeIf { it.size == 2 && it[0] == "callback" }?.get(1)
    return OAuthCallback(state, code)
}

/**
 * Singleton handoff between whatever receives the sign-in's last link (the phone's deep link,
 * the TV and car sign-in page) and the auth repository that completes the OAuth login. The
 * repository accepts a callback only for the sign-in in progress.
 */
@Singleton
class OAuthCallbackBus @Inject constructor() {
    private val _callbacks = MutableSharedFlow<OAuthCallback>(extraBufferCapacity = 1)
    val callbacks: SharedFlow<OAuthCallback> = _callbacks.asSharedFlow()

    fun publish(callback: OAuthCallback) {
        _callbacks.tryEmit(callback)
    }

    /** Publishes the callback [link] carries; false when it is not a sign-in callback. */
    fun publishLink(link: String): Boolean {
        val callback = parseOAuthCallback(link) ?: return false
        publish(callback)
        return true
    }
}
