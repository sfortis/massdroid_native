package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.discovery.ServerPicker
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
    serverPickerFactory: ServerPicker.Factory,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    /** Step 1 and step 2 of the sign-in, shared with the phone and the car. */
    val serverPicker: ServerPicker = serverPickerFactory.create(viewModelScope)

    /** The saved username, to fill the field in once. */
    val savedUsername: StateFlow<String?> = settingsRepository.username
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    private val _attemptedUrl = MutableStateFlow<String?>(null)

    /** The server the last login went to; the client's connection error belongs to it. */
    val attemptedUrl: StateFlow<String?> = _attemptedUrl.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /**
     * Abandons a login still in progress when the user leaves its server, so it cannot finish
     * and sign in to the server the user moved away from, and Sign in is usable again at once.
     */
    fun cancelAttempt() {
        attemptWatchdog?.cancel()
        attemptWatchdog = null
        if (_attempting.value && connectionState.value is ConnectionState.Connecting) wsClient.disconnect()
        _attempting.value = false
        _attemptedUrl.value = null
        _error.value = null
    }

    fun login(url: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _error.value = "Enter your username and password."
            return
        }
        _error.value = null
        _attemptedUrl.value = url
        _attempting.value = true
        // Everything is saved only when the server hands out a token, as on the phone. Saved
        // before, a failed sign-in to another server left its URL next to the old server's
        // token, and BACK out of Change server then made TvRootViewModel retry that pair for good.
        wsClient.connectWithLogin(url, username, password) { token ->
            // The URL is written last, so the server a later start connects to changes only once
            // the sign-in for it is saved. The four values live in two DataStores, so one edit
            // cannot hold them all.
            viewModelScope.launch {
                settingsRepository.setAuthToken(token)
                settingsRepository.setUsername(username)
                settingsRepository.setPassword(password)
                settingsRepository.setServerUrl(url)
            }
            wsClient.setSavedCredentials(username, password)
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
