package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Decides the top-level TV destination: onboarding vs connected home. Also runs
 * the one-shot auto-connect with the saved server + token (same policy as the
 * phone HomeViewModel) so a returning user lands straight on the home screen.
 */
@HiltViewModel
class TvRootViewModel @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    init {
        viewModelScope.launch {
            wsClient.startupReady.first { it }
            val state = wsClient.connectionState.value
            if (state is ConnectionState.Disconnected && !wsClient.userDisconnected) {
                val url = settingsRepository.serverUrl.first()
                val token = settingsRepository.authToken.first()
                if (url.isNotBlank() && token.isNotBlank()) {
                    wsClient.connect(url, token)
                }
            }
        }
    }
}
