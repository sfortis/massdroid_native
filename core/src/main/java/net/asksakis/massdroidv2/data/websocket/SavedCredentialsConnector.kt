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

    /**
     * True when the app can sign in by itself: a server address and either a token or a
     * username with its password. Nothing is connected until [connectIfNeeded].
     */
    suspend fun hasSavedCredentials(): Boolean = canSignInUnattended(
        url = settingsRepository.serverUrl.first(),
        token = settingsRepository.authToken.first(),
        username = settingsRepository.username.first(),
        password = settingsRepository.password.first(),
    )

    /**
     * Connect if the client needs it and the listener has not disconnected on purpose,
     * then wait up to [timeoutMs] for the connection. Returns whether the client is
     * connected when it comes back, whichever of the two paths got it there.
     */
    suspend fun connectIfNeeded(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        wsClient.startupReady.first { it }
        if (wsClient.connectionState.value is ConnectionState.Connected) return true
        if (wsClient.connectionState.value.needsConnect() && !wsClient.userDisconnected) {
            if (!hasSavedCredentials()) return false
            // A blank token is fine here: the client then signs in with the saved password.
            wsClient.connect(
                settingsRepository.serverUrl.first(),
                settingsRepository.authToken.first(),
                MaWebSocketClient.ConnectOrigin.SAVED_CREDENTIALS,
            )
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

/**
 * Whether saved settings are enough to sign in with no screen open. The token alone used to be
 * required, while the car's playback service already counted a saved password: with the token
 * gone the car showed its tabs as signed in and never connected (issue #80).
 */
internal fun canSignInUnattended(url: String, token: String, username: String, password: String): Boolean =
    url.isNotBlank() && url.contains("://") &&
        (token.isNotBlank() || (username.isNotBlank() && password.isNotBlank()))
