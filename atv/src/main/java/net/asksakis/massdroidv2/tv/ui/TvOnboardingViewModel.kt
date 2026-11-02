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
 * The TV sign-in: a username and password for the server picked in step 1. The token, the
 * account and the server are saved once the server hands out a token, so the next launch
 * connects by itself.
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
        // Everything is saved only when the server hands out a token, as on the phone, so a
        // failed sign-in never leaves one server's address next to another server's token.
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

        // Bounds what the screen shows: an attempt that neither connects nor fails in time is
        // given up with a plain message instead of an endless "Connecting...".
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
        // The longest the TV screen waits for a sign-in to resolve, as SignInSession waits on
        // the phone and the car. A slow server can still be answering then, and the attempt
        // is given up rather than leaving the user near a minute on "Connecting".
        const val ATTEMPT_TIMEOUT_MS = 35_000L
    }
}
