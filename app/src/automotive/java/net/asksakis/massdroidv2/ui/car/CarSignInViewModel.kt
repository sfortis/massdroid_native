package net.asksakis.massdroidv2.ui.car

import android.content.Context
import android.security.KeyChain
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Minimal car (AAOS) sign-in / server-config view model. Reuses the shared
 * :core [MaWebSocketClient] login flow exactly like the phone SettingsViewModel
 * and the TV onboarding screen, but stays intentionally small (username/password
 * + optional client certificate) so the parked car screen never pulls in the
 * full phone settings surface. OAuth is deliberately omitted (it needs a browser
 * round-trip that is not reliable in a car).
 */
@HiltViewModel
class CarSignInViewModel @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val authRepository: MaAuthRepository,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Currently selected mTLS client-certificate alias, or null. */
    val certAlias: StateFlow<String?> = settingsRepository.clientCertAlias
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Saved server URL + username, surfaced once to pre-fill the form. */
    val prefill: StateFlow<Prefill?> = combine(
        settingsRepository.serverUrl,
        settingsRepository.username,
    ) { url, username -> Prefill(url, username) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    data class Prefill(val url: String, val username: String)

    fun login(rawUrl: String, username: String, password: String) {
        val url = rawUrl.trim().trimEnd('/')
        val user = username.trim()
        val pass = password.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _error.value = "Add http:// or https:// to the URL"
            return
        }
        if (user.isBlank() || pass.isBlank()) {
            _error.value = "Enter username and password"
            return
        }
        _error.value = null
        viewModelScope.launch {
            settingsRepository.setServerUrl(url)
            settingsRepository.setUsername(user)
            settingsRepository.setPassword(pass)
        }
        wsClient.setSavedCredentials(user, pass)
        wsClient.connectWithLogin(url, user, pass) { token ->
            viewModelScope.launch { settingsRepository.setAuthToken(token) }
        }
    }

    /**
     * Apply a client certificate picked from the Android KeyChain. Mirrors the
     * phone settings cert flow: persist the alias, load the key/chain off-main,
     * and reconfigure the shared OkHttp/WebSocket client for mTLS.
     */
    fun onCertificateSelected(alias: String?, context: Context) {
        if (alias == null) return
        viewModelScope.launch {
            settingsRepository.setClientCertAlias(alias)
            loadCertificate(alias, context.applicationContext)
        }
    }

    fun clearCertificate() {
        viewModelScope.launch {
            settingsRepository.setClientCertAlias(null)
            wsClient.clearMtls()
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
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
                    settingsRepository.setClientCertAlias(null)
                    wsClient.clearMtls()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cert: ${e.message}")
                settingsRepository.setClientCertAlias(null)
                wsClient.clearMtls()
            }
        }
    }

    /** One-shot: load the saved cert (if any) so a re-opened screen keeps mTLS. */
    fun loadSavedCertificate(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val alias = settingsRepository.clientCertAlias.first() ?: return@launch
            loadCertificate(alias, appContext)
        }
    }

    companion object {
        private const val TAG = "CarSignIn"
    }
}
