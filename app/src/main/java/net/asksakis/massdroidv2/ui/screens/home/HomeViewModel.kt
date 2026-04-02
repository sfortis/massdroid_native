package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.proximity.RoomDetector
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

private const val TAG = "HomeVM"

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val settingsRepository: SettingsRepository,
    private val wsClient: MaWebSocketClient,
    private val proximityConfigStore: net.asksakis.massdroidv2.data.proximity.ProximityConfigStore,
    private val roomDetector: RoomDetector
) : ViewModel() {

    val players = playerRepository.players
    val selectedPlayer = playerRepository.selectedPlayer
    val connectionState = wsClient.connectionState
    val elapsedTime = playerRepository.elapsedTime
    val queueState = playerRepository.queueState
    val sendspinClientId = settingsRepository.sendspinClientId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sendspinAudioFormat = settingsRepository.sendspinAudioFormat
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sendspinStaticDelayMs = settingsRepository.sendspinStaticDelayMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val proximityConfig = proximityConfigStore.config
    val currentDetectedRoom = roomDetector.currentRoom

    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()
    private val _suppressConnectionPrompt = MutableStateFlow(false)
    val suppressConnectionPrompt: StateFlow<Boolean> = _suppressConnectionPrompt.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()
    private var hasConnectedOnce = false
    private var reconnectUiJob: Job? = null

    init {
        // Auto-connect on startup if we have saved credentials
        viewModelScope.launch {
            wsClient.startupReady.first { it }
            val state = wsClient.connectionState.value
            if (state is ConnectionState.Disconnected && !wsClient.userDisconnected) {
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
        viewModelScope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        hasConnectedOnce = true
                        reconnectUiJob?.cancel()
                        _suppressConnectionPrompt.value = false
                    }
                    is ConnectionState.Connecting -> {
                        if (hasConnectedOnce) {
                            _suppressConnectionPrompt.value = true
                        }
                    }
                    is ConnectionState.Disconnected, is ConnectionState.Error -> {
                        if (!hasConnectedOnce) {
                            reconnectUiJob?.cancel()
                            _suppressConnectionPrompt.value = false
                            return@collect
                        }
                        reconnectUiJob?.cancel()
                        _suppressConnectionPrompt.value = true
                        reconnectUiJob = viewModelScope.launch {
                            delay(8_000)
                            val latest = wsClient.connectionState.value
                            if (latest is ConnectionState.Disconnected || latest is ConnectionState.Error) {
                                _suppressConnectionPrompt.value = false
                            }
                        }
                    }
                }
            }
        }
    }

    fun connectIfNeeded() {
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
                if (player.state == net.asksakis.massdroidv2.domain.model.PlaybackState.PLAYING) {
                    playerRepository.pause(player.playerId)
                } else {
                    playerRepository.play(player.playerId)
                }
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

    fun setDontStopTheMusic(queueId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setDontStopTheMusic(queueId, enabled)
            } catch (e: Exception) {
                Log.w(TAG, "setDontStopTheMusic failed: ${e.message}")
            }
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

    fun setAudioFormat(format: net.asksakis.massdroidv2.domain.model.SendspinAudioFormat) {
        viewModelScope.launch {
            settingsRepository.setSendspinAudioFormat(format.name)
        }
    }

    fun setSendspinStaticDelayMs(delayMs: Int) {
        viewModelScope.launch {
            settingsRepository.setSendspinStaticDelayMs(delayMs)
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

    fun clearQueue(playerId: String) {
        viewModelScope.launch {
            try {
                musicRepository.clearQueue(playerId)
            } catch (e: Exception) {
                Log.w(TAG, "clearQueue failed: ${e.message}")
            }
        }
    }

    fun startSongRadio(playerId: String, trackUri: String) {
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(playerId, PlayerRepository.QueueFilterMode.RADIO_SMART)
                musicRepository.playMedia(playerId, trackUri, radioMode = true)
            } catch (e: Exception) {
                Log.w(TAG, "startSongRadio failed: ${e.message}")
            }
        }
    }

    fun transferQueue(sourceId: String, targetId: String) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    musicRepository.transferQueue(sourceId, targetId)
                    playerRepository.selectPlayer(targetId)
                } catch (e: Exception) {
                    Log.w(TAG, "transferQueue failed: ${e.message}")
                }
            }
        }
    }
}
