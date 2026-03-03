package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

private const val TAG = "HomeVM"

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val wsClient: MaWebSocketClient
) : ViewModel() {

    val players = playerRepository.players
    val selectedPlayer = playerRepository.selectedPlayer
    val connectionState = wsClient.connectionState
    val elapsedTime = playerRepository.elapsedTime
    val queueState = playerRepository.queueState

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    init {
        // Auto-connect on startup if we have saved credentials
        viewModelScope.launch {
            val state = wsClient.connectionState.value
            if (state is ConnectionState.Disconnected) {
                val url = settingsRepository.serverUrl.first()
                val token = settingsRepository.authToken.first()
                if (url.isNotBlank() && token.isNotBlank()) {
                    wsClient.connect(url, token)
                    // Wait for successful connection (covers token-fail + credential-fallback cycle)
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        wsClient.connectionState.first { it is ConnectionState.Connected }
                    }
                }
            }
            _isInitializing.value = false
        }

        // PlayerRepositoryImpl handles refreshPlayers + restore saved player on Connected
    }

    fun connectIfNeeded() {
        viewModelScope.launch {
            val state = wsClient.connectionState.value
            if (state is ConnectionState.Disconnected) {
                val url = settingsRepository.serverUrl.first()
                val token = settingsRepository.authToken.first()
                if (url.isNotBlank() && token.isNotBlank()) {
                    wsClient.connect(url, token)
                }
            }
        }
    }

    fun selectPlayer(player: Player) {
        playerRepository.selectPlayer(player.playerId)
    }

    fun setVolume(playerId: String, volume: Int) {
        viewModelScope.launch {
            try {
                playerRepository.setVolume(playerId, volume)
            } catch (_: Exception) {}
        }
    }

    fun playPause() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.playPause(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "playPause failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun next() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.next(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "next failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun updatePlayerIcon(playerId: String, icon: String) {
        viewModelScope.launch {
            try {
                playerRepository.updatePlayerIcon(playerId, icon)
            } catch (e: Exception) {
                Log.w(TAG, "updatePlayerIcon failed: ${e.message}")
            }
        }
    }

    suspend fun getPlayerConfig(playerId: String): PlayerConfig? {
        return try {
            playerRepository.getPlayerConfig(playerId)
        } catch (e: Exception) {
            Log.w(TAG, "getPlayerConfig failed: ${e.message}")
            null
        }
    }

    fun savePlayerConfig(playerId: String, values: Map<String, Any>) {
        viewModelScope.launch {
            try {
                playerRepository.savePlayerConfig(playerId, values)
                // Update local display name if changed
                val newName = values["name"] as? String
                if (newName != null) {
                    playerRepository.renamePlayer(playerId, newName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "savePlayerConfig failed: ${e.message}")
            }
        }
    }

    fun previous() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.previous(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "previous failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }
}
