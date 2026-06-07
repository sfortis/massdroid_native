package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import net.asksakis.massdroidv2.domain.model.AuthProviderInfo

/**
 * Bridges Music Assistant's authentication flow to the UI. Owns the OAuth
 * state machine so the SettingsViewModel can stay focused on view state.
 */
interface MaAuthRepository {
    /** Login providers advertised by the most recently probed server. */
    val availableProviders: StateFlow<List<AuthProviderInfo>>

    /** True while an OAuth round-trip is in flight. */
    val oauthInProgress: StateFlow<Boolean>

    /** Human-readable errors raised during OAuth (one-shot). */
    val oauthErrors: SharedFlow<String>

    /**
     * Ask the server which login providers it exposes. Updates
     * [availableProviders]. Safe to call repeatedly; failure leaves the
     * previous list in place.
     */
    suspend fun probeProviders(serverUrl: String)

    /**
     * Begin the Home Assistant OAuth round-trip. Returns the authorization URL
     * the caller must open in a browser tab, or null on failure (which also
     * emits to [oauthErrors]).
     */
    suspend fun startHomeAssistantOAuth(serverUrl: String): String?

    /** Cancel a pending OAuth flow (user dismissed the browser tab). */
    fun cancelOAuth()

    /**
     * Notify the repo that the foreground activity has resumed. Lets the
     * repo detect when an OAuth flow was abandoned (user closed the browser
     * without completing the sign-in) and unstick the UI.
     */
    fun handleAppResumed()

    /**
     * Wipe everything tied to the current sign-in: token, saved credentials,
     * discover cache, and the live WebSocket connection. The server URL is
     * preserved so the user can sign back in to the same server.
     */
    suspend fun signOut()
}

/**
 * Lightweight identity slice the UI uses to render "Signed in as X via Y"
 * without needing to parse the JWT itself.
 */
data class CurrentUser(
    val username: String,
    /** "Home Assistant", "Username & password", or null if unknown. */
    val authMethod: String?
)
