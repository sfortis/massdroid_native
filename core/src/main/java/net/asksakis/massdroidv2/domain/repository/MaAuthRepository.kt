package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import net.asksakis.massdroidv2.domain.connection.ServerCheck
import net.asksakis.massdroidv2.domain.model.AuthProviderInfo

/**
 * Bridges Music Assistant's authentication flow to the UI. Owns the OAuth
 * state machine so the SettingsViewModel can stay focused on view state.
 */
interface MaAuthRepository {
    /** Login providers advertised by the most recently checked server. */
    val availableProviders: StateFlow<List<AuthProviderInfo>>

    /** True while an OAuth round-trip is in flight. */
    val oauthInProgress: StateFlow<Boolean>

    /** Human-readable errors raised during OAuth (one-shot). */
    val oauthErrors: SharedFlow<String>

    /**
     * A Home Assistant sign-in that came back with a token. Nothing is saved here: the phone's
     * sign-in session connects with it and saves it only once the server accepted it.
     */
    val completedOAuthSignIns: SharedFlow<OAuthSignIn>

    /**
     * Checks that Music Assistant answers at [serverUrl] and loads its login providers into
     * [availableProviders], which empties when it does not answer.
     */
    suspend fun checkServer(serverUrl: String): ServerCheck

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
     * Wipe everything tied to the current sign-in: the current server's token and
     * password, the discover cache, and the live WebSocket connection. The server URL
     * and the username are preserved so the user can sign back in to the same server,
     * and the accounts saved for other servers are left alone.
     */
    suspend fun signOut()

    /**
     * Drops the per-account state (the Discover cache and everything held in memory) before a
     * sign-in to another server, as Sign out and the Home Assistant sign-in do.
     */
    suspend fun prepareServerSwitch()
}

/** The server a Home Assistant sign-in was for, and the token it returned. */
class OAuthSignIn(val url: String, val token: String) {
    override fun toString(): String = "OAuthSignIn(${url.substringAfter("://")})"
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
