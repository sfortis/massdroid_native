package net.asksakis.massdroidv2.data.websocket

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

/**
 * Opens the server connection with the saved credentials when nothing else has.
 *
 * The connection is normally opened by the first screen the listener looks at, so
 * anything that acts without a screen (the car media centre, a home screen widget
 * pressed while the app is dead) has to open it itself. Three copies of that logic
 * lived in two view models and the playback service; this is the one they share.
 */
@Singleton
class SavedCredentialsConnector @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository
) {

    /** True when saved credentials exist; nothing is connected until [connectIfNeeded]. */
    suspend fun hasSavedToken(): Boolean {
        val url = settingsRepository.serverUrl.first()
        val token = settingsRepository.authToken.first()
        return url.isNotBlank() && token.isNotBlank() && url.contains("://")
    }

    /**
     * Connect if the client needs it and the listener has not disconnected on purpose,
     * then wait up to [timeoutMs] for the connection. Returns whether the client is
     * connected when it comes back, whichever of the two paths got it there.
     */
    suspend fun connectIfNeeded(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        wsClient.startupReady.first { it }
        if (wsClient.connectionState.value is ConnectionState.Connected) return true
        if (wsClient.connectionState.value.needsConnect() && !wsClient.userDisconnected) {
            if (!hasSavedToken()) return false
            wsClient.connect(settingsRepository.serverUrl.first(), settingsRepository.authToken.first())
        }
        return withTimeoutOrNull(timeoutMs) {
            wsClient.connectionState.first { it is ConnectionState.Connected }
        } != null
    }

    companion object {
        /** Covers a token login plus the credential fallback the client runs when the token fails. */
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
