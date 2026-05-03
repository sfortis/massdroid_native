package net.asksakis.massdroidv2.data.websocket

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton handoff between the deep-link receiver in the Activity and the
 * SettingsViewModel that completes the OAuth login. The Activity emits the
 * extracted access token; whichever screen is currently observing picks it
 * up and finalises the session.
 */
@Singleton
class OAuthCallbackBus @Inject constructor() {
    private val _tokens = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val tokens: SharedFlow<String> = _tokens.asSharedFlow()

    fun publish(token: String) {
        _tokens.tryEmit(token)
    }
}
