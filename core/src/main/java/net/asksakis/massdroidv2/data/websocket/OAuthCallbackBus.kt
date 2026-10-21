package net.asksakis.massdroidv2.data.websocket

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

/**
 * Singleton handoff between the deep-link receiver in the Activity and the
 * auth repository that completes the OAuth login. The Activity emits what the
 * link carried; the repository accepts it only for the sign-in in progress.
 */
@Singleton
class OAuthCallbackBus @Inject constructor() {
    private val _callbacks = MutableSharedFlow<OAuthCallback>(extraBufferCapacity = 1)
    val callbacks: SharedFlow<OAuthCallback> = _callbacks.asSharedFlow()

    fun publish(callback: OAuthCallback) {
        _callbacks.tryEmit(callback)
    }
}
