package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * True from the moment we fire a login until it resolves (connected, errored,
     * or the [attemptWatchdog] gives up). Drives the button's disabled/"Connecting"
     * state on our own terms rather than the raw connection state, so a stalled
     * attempt always re-enables the button for a retry instead of freezing it.
     */
    private val _attempting = MutableStateFlow(false)
    val attempting: StateFlow<Boolean> = _attempting.asStateFlow()

    private var attemptWatchdog: Job? = null

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
        _attempting.value = true
        wsClient.connectWithLogin(url, username, password) { token ->
            viewModelScope.launch { settingsRepository.setAuthToken(token) }
        }

        // Backstop the UI: the core client now has its own handshake watchdog, but
        // this keeps the button honest and turns any non-resolution into a plain
        // user-facing message instead of an indefinite "Connecting...".
        attemptWatchdog?.cancel()
        attemptWatchdog = viewModelScope.launch {
            val resolved = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                connectionState.first {
                    it is ConnectionState.Connected || it is ConnectionState.Error
                }
            }
            _attempting.value = false
            if (resolved == null) {
                // Tear down the stalled attempt so a fresh Connect isn't ignored
                // as a duplicate of the still-"connecting" endpoint.
                wsClient.disconnect()
                _error.value = "Could not reach $url. Check the address and that this device " +
                    "is on the same network as Music Assistant."
            } else if (resolved is ConnectionState.Error) {
                _error.value = resolved.message
            }
        }
    }

    private companion object {
        // A little longer than the core client's HANDSHAKE_TIMEOUT_MS (20 s) plus
        // the auth command timeout, so the UI backstop only fires when the client
        // genuinely never resolves.
        private const val ATTEMPT_TIMEOUT_MS = 35_000L
    }
}
