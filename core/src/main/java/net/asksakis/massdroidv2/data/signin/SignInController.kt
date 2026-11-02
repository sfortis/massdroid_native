package net.asksakis.massdroidv2.data.signin

import android.content.Context
import android.security.KeyChain
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.domain.model.AuthProviderInfo
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Signing in to the server chosen in step 1: with a username and password, with the saved
 * token, or through Home Assistant, plus the client certificate for servers behind mutual
 * TLS. The phone's Settings screen and its onboarding share it, so both sign in the same way.
 * Each ViewModel creates one with its own scope through [Factory].
 */
class SignInController private constructor(
    private val scope: CoroutineScope,
    private val wsClient: MaWebSocketClient,
    private val settings: SettingsRepository,
    private val auth: MaAuthRepository
) {
    class Factory @Inject constructor(
        private val wsClient: MaWebSocketClient,
        private val settings: SettingsRepository,
        private val auth: MaAuthRepository
    ) {
        fun create(scope: CoroutineScope): SignInController = SignInController(scope, wsClient, settings, auth)
    }

    private val _error = MutableStateFlow<String?>(null)

    /** What went wrong with the form itself, such as an empty field or a failed Home Assistant sign-in. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _attemptedUrl = MutableStateFlow<String?>(null)

    /**
     * The server the last sign-in went to. The client's connection error belongs to that
     * attempt, so a screen shows it only while the same server is chosen.
     */
    val attemptedUrl: StateFlow<String?> = _attemptedUrl.asStateFlow()

    /** The sign-in providers of the server checked last. */
    val providers: StateFlow<List<AuthProviderInfo>> = auth.availableProviders
    val oauthInProgress: StateFlow<Boolean> = auth.oauthInProgress
    val oauthErrors: SharedFlow<String> = auth.oauthErrors

    val savedUsername: StateFlow<String> =
        settings.username.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")
    val savedPassword: StateFlow<String> =
        settings.password.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")
    val authToken: StateFlow<String> =
        settings.authToken.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")
    val clientCertAlias: StateFlow<String?> =
        settings.clientCertAlias.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun login(serverUrl: String, username: String, password: String) {
        val user = username.trim()
        val pass = password.trim()
        if (user.isBlank() || pass.isBlank()) {
            _error.value = "Enter your username and password."
            return
        }
        _error.value = null
        _attemptedUrl.value = serverUrl
        scope.launch {
            settings.setServerUrl(serverUrl)
            settings.setUsername(user)
            settings.setPassword(pass)
        }
        wsClient.setSavedCredentials(user, pass)
        wsClient.connectWithLogin(serverUrl, user, pass) { token ->
            scope.launch { settings.setAuthToken(token) }
        }
    }

    fun connectWithToken(serverUrl: String) {
        val token = authToken.value
        if (token.isBlank()) {
            _error.value = "Couldn't sign in with the saved token. Sign in with your username and password."
            return
        }
        _error.value = null
        _attemptedUrl.value = serverUrl
        scope.launch { settings.setServerUrl(serverUrl) }
        wsClient.connect(serverUrl, token, MaWebSocketClient.ConnectOrigin.SIGN_IN)
    }

    /**
     * Returns the URL to open in a browser tab to begin the Home Assistant sign-in, or null on
     * failure, which [oauthErrors] reports.
     */
    suspend fun startHomeAssistantOAuth(serverUrl: String): String? {
        _error.value = null
        _attemptedUrl.value = serverUrl
        settings.setServerUrl(serverUrl)
        return auth.startHomeAssistantOAuth(serverUrl)
    }

    /**
     * Abandons the sign-in in progress when the user leaves its server: a connection still being
     * set up is closed, so it cannot finish and sign in to the server the user moved away from.
     */
    fun cancelAttempt() {
        if (wsClient.connectionState.value is ConnectionState.Connecting) wsClient.disconnect()
        auth.cancelOAuth()
        _attemptedUrl.value = null
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun setError(message: String) {
        _error.value = message
    }

    /** Uses the certificate the user picked in the system KeyChain picker. A null alias is a cancel. */
    fun selectCertificate(alias: String?, context: Context) {
        if (alias == null) return
        val appContext = context.applicationContext
        scope.launch {
            settings.setClientCertAlias(alias)
            loadCertificate(alias, appContext)
        }
    }

    /** Loads the saved certificate again, for a screen that may have been opened after a change. */
    fun loadSavedCertificate(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            settings.clientCertAlias.first()?.let { loadCertificate(it, appContext) }
        }
    }

    fun clearCertificate() {
        scope.launch {
            settings.setClientCertAlias(null)
            wsClient.clearMtls()
        }
    }

    private suspend fun loadCertificate(alias: String, context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val privateKey = KeyChain.getPrivateKey(context, alias)
                val certChain = KeyChain.getCertificateChain(context, alias)
                if (privateKey != null && certChain != null) {
                    wsClient.configureMtls(privateKey, certChain)
                    Log.d(TAG, "mTLS loaded: $alias")
                } else {
                    Log.e(TAG, "Failed to load cert for alias: $alias")
                    forgetCertificate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cert: ${e.message}")
                forgetCertificate()
            }
        }
    }

    private suspend fun forgetCertificate() {
        settings.setClientCertAlias(null)
        wsClient.clearMtls()
    }

    private companion object {
        const val TAG = "SignIn"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
