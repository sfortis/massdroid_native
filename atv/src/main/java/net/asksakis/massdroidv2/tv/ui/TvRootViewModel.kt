package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Decides the top-level TV destination and owns connection lifecycle.
 *
 * With a saved server it auto-connects and KEEPS RETRYING on transient failures
 * (e.g. a local/split-horizon hostname that briefly fails to resolve on the TV
 * right after wake), showing a "connecting" state instead of dumping the user
 * back to the login form. The login form only appears when there is no saved
 * server, or when the user explicitly chooses to change it.
 */
@HiltViewModel
class TvRootViewModel @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    private val _hasSavedServer = MutableStateFlow(false)
    val hasSavedServer: StateFlow<Boolean> = _hasSavedServer.asStateFlow()

    private val _changeServerRequested = MutableStateFlow(false)
    val changeServerRequested: StateFlow<Boolean> = _changeServerRequested.asStateFlow()

    // False until saved config is read; prevents flashing the login form during
    // the brief cold-launch window before we know whether a server is saved.
    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    private var savedUrl: String = ""

    init {
        viewModelScope.launch {
            wsClient.startupReady.first { it }
            savedUrl = settingsRepository.serverUrl.first()
            val token = settingsRepository.authToken.first()
            _hasSavedServer.value = savedUrl.isNotBlank() && token.isNotBlank()
            _initialized.value = true
            if (!_hasSavedServer.value) return@launch

            // Connect, then retry on Error/Disconnected with a bounded backoff
            // until connected (or the user disconnects / changes server).
            while (isActive) {
                if (wsClient.userDisconnected || _changeServerRequested.value) break
                when (wsClient.connectionState.value) {
                    is ConnectionState.Connected -> {
                        // Stay connected; the WS client handles drops itself.
                        wsClient.connectionState.first { it !is ConnectionState.Connected }
                    }
                    is ConnectionState.Connecting -> delay(RETRY_DELAY_MS)
                    else -> {
                        wsClient.connect(savedUrl, token, MaWebSocketClient.ConnectOrigin.SAVED_CREDENTIALS)
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    fun serverLabel(): String = savedUrl

    /** User chose to enter a different server: stop retrying and show onboarding. */
    fun changeServer() {
        _changeServerRequested.value = true
        wsClient.disconnect()
    }

    private companion object {
        const val RETRY_DELAY_MS = 3_000L
    }
}
