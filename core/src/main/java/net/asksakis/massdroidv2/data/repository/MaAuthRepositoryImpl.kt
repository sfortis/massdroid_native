package net.asksakis.massdroidv2.data.repository

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.websocket.MaAuthProbe
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.OAuthCallback
import net.asksakis.massdroidv2.data.websocket.OAuthCallbackBus
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.AuthProviderInfo
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

@Singleton
class MaAuthRepositoryImpl @Inject constructor(
    private val probe: MaAuthProbe,
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val callbackBus: OAuthCallbackBus,
    private val discoverCache: DiscoverCache,
    private val sessionEventBus: SessionEventBus
) : MaAuthRepository {

    companion object {
        private const val TAG = "MaAuthRepo"
        // The return URL is this prefix plus "/<nonce>". MA accepts any return URL that
        // starts with "musicassistant://" and appends the token as ?code=, so the nonce
        // comes back in the path, where a server that only concatenates cannot break it.
        const val OAUTH_RETURN_URL = "musicassistant://auth/callback"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _availableProviders = MutableStateFlow<List<AuthProviderInfo>>(emptyList())
    override val availableProviders: StateFlow<List<AuthProviderInfo>> =
        _availableProviders.asStateFlow()

    private val _oauthInProgress = MutableStateFlow(false)
    override val oauthInProgress: StateFlow<Boolean> = _oauthInProgress.asStateFlow()

    private val _oauthErrors = MutableSharedFlow<String>(extraBufferCapacity = 2)
    override val oauthErrors: SharedFlow<String> = _oauthErrors.asSharedFlow()

    /** Pending OAuth target URL; needed when the callback resolves. */
    @Volatile private var pendingServerUrl: String? = null
    /** Nonce of the sign-in in progress; a callback without it is not ours. */
    @Volatile private var pendingNonce: String? = null

    init {
        scope.launch {
            callbackBus.callbacks.collect { callback -> onOAuthCallback(callback) }
        }
    }

    override suspend fun probeProviders(serverUrl: String) {
        if (serverUrl.isBlank()) {
            _availableProviders.value = emptyList()
            return
        }
        val list = probe.fetchProviders(serverUrl)
        if (list.isNotEmpty()) {
            _availableProviders.value = list
            Log.d(TAG, "providers for $serverUrl: ${list.map { it.providerId }}")
        } else {
            Log.d(TAG, "providers probe returned empty for $serverUrl")
            _availableProviders.value = emptyList()
        }
    }

    override suspend fun startHomeAssistantOAuth(serverUrl: String): String? {
        val provider = _availableProviders.value.firstOrNull { it.isHomeAssistant }
            ?: run {
                _oauthErrors.tryEmit("Home Assistant sign-in is not available on this server.")
                return null
            }
        val nonce = newOAuthNonce()
        val authUrl = probe.fetchAuthorizationUrl(
            serverUrl = serverUrl,
            providerId = provider.providerId,
            returnUrl = "$OAUTH_RETURN_URL/$nonce"
        )
        if (authUrl.isNullOrBlank()) {
            _oauthErrors.tryEmit("Could not start sign-in. Check the server URL and try again.")
            return null
        }
        pendingServerUrl = serverUrl
        pendingNonce = nonce
        oauthStartedAtMs = System.currentTimeMillis()
        _oauthInProgress.value = true
        return authUrl
    }

    override fun cancelOAuth() {
        if (_oauthInProgress.value) {
            _oauthInProgress.value = false
            pendingServerUrl = null
            pendingNonce = null
            oauthStartedAtMs = 0L
        }
    }

    override suspend fun signOut() {
        Log.d(TAG, "signOut(): wiping token, credentials, discover cache, and disconnecting")
        runCatching { wsClient.disconnect() }
        wsClient.clearSavedCredentials()
        settingsRepository.setAuthToken("")
        settingsRepository.setUsername("")
        settingsRepository.setPassword("")
        // The next sign-in is explicit and trusts whichever server answers it.
        runCatching { settingsRepository.setTrustedServerId(null) }
        // Drop the saved player choice too, otherwise reconnecting against a
        // server that happens to expose a same-id player would silently latch
        // back onto something the user did not choose.
        runCatching { settingsRepository.setSelectedPlayerId(null) }
        runCatching { discoverCache.clear() }
        sessionEventBus.emitReset()
        // Leave the server URL alone, the user is signing OUT and not switching
        // servers. They can edit the URL field if they want to move on.
    }

    @Volatile private var oauthStartedAtMs: Long = 0L

    override fun handleAppResumed() {
        // If the user returns to the app while we're still in OAuth-progress
        // state, give the deep link a brief grace window to fire. If nothing
        // arrives, treat it as a cancellation so the UI unsticks.
        if (!_oauthInProgress.value) return
        val started = oauthStartedAtMs
        if (started == 0L) return
        scope.launch {
            kotlinx.coroutines.delay(1_500)
            // Re-check: a successful callback would have flipped oauthInProgress to false
            // and reset oauthStartedAtMs already, so this only fires on real cancellation.
            if (_oauthInProgress.value && oauthStartedAtMs == started) {
                Log.d(TAG, "OAuth canceled by user (returned to app without callback)")
                _oauthInProgress.value = false
                pendingServerUrl = null
                pendingNonce = null
                oauthStartedAtMs = 0L
                _oauthErrors.tryEmit("Sign-in canceled.")
            }
        }
    }

    /**
     * Accept a deep link only for the sign-in this app started. Any app or web page can
     * open a musicassistant://auth link, and accepting one with no sign-in in progress
     * wiped the saved token and credentials and signed in with whatever code it carried.
     * A callback that arrives after the process was killed during the browser step is
     * refused the same way, because its nonce died with the process; the user signs in
     * again.
     */
    private fun onOAuthCallback(callback: OAuthCallback) {
        val url = pendingServerUrl
        if (!_oauthInProgress.value || url == null ||
            !oauthCallbackMatches(expected = pendingNonce, received = callback.state)
        ) {
            Log.w(TAG, "OAuth callback ignored: no matching sign-in in progress")
            if (!_oauthInProgress.value) _oauthErrors.tryEmit("Sign-in expired. Please try again.")
            return
        }
        pendingNonce = null
        finishOAuth(url, callback.code)
    }

    private fun finishOAuth(url: String, token: String) {
        scope.launch {
            try {
                settingsRepository.setAuthToken(token)
                // Clear any built-in credentials saved from a previous attempt
                // so the persisted auth state is unambiguous: OAuth-token only.
                settingsRepository.setUsername("")
                settingsRepository.setPassword("")
                wsClient.clearSavedCredentials()
                // The previous account's selected player almost certainly does
                // not exist on the new server; drop it so we don't try to
                // restore a phantom selection.
                settingsRepository.setSelectedPlayerId(null)
                // Discover content is per-account; wipe the on-disk cache so the
                // home screen doesn't briefly show items the new user hasn't seen.
                discoverCache.clear()
                // Notify everyone holding per-account in-memory state to drop it
                // before the new connection brings fresh data in.
                sessionEventBus.emitReset()
                wsClient.connect(url, token, MaWebSocketClient.ConnectOrigin.SIGN_IN)
                Log.d(TAG, "OAuth completed, connecting to $url with new token")
            } catch (e: Exception) {
                Log.w(TAG, "OAuth completion failed: ${e.message}")
                _oauthErrors.tryEmit("Sign-in completed but connection failed: ${e.message}")
            } finally {
                _oauthInProgress.value = false
                pendingServerUrl = null
                oauthStartedAtMs = 0L
            }
        }
    }
}

private const val OAUTH_NONCE_BYTES = 16

private fun newOAuthNonce(): String {
    val bytes = ByteArray(OAUTH_NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Whether a callback's nonce is the one the sign-in in progress sent; compared in constant time. */
internal fun oauthCallbackMatches(expected: String?, received: String?): Boolean {
    if (expected.isNullOrEmpty() || received.isNullOrEmpty()) return false
    return java.security.MessageDigest.isEqual(expected.toByteArray(), received.toByteArray())
}
