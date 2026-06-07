package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Onboarding: log in to a Music Assistant server with credentials, persist the
 * server URL + credentials + returned token (so the next launch auto-connects),
 * exactly mirroring the phone SettingsViewModel login flow on the shared :core
 * MaWebSocketClient.
 */
@HiltViewModel
class TvOnboardingViewModel @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun login(rawUrl: String, username: String, password: String) {
        val url = rawUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _error.value = "Add http:// or https:// to the URL"
            return
        }
        if (username.isBlank() || password.isBlank()) {
            _error.value = "Enter username and password"
            return
        }
        _error.value = null
        viewModelScope.launch {
            settingsRepository.setServerUrl(url)
            settingsRepository.setUsername(username)
            settingsRepository.setPassword(password)
        }
        wsClient.setSavedCredentials(username, password)
        wsClient.connectWithLogin(url, username, password) { token ->
            viewModelScope.launch { settingsRepository.setAuthToken(token) }
        }
    }
}
