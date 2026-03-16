package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

private const val TAG = "MiniPlayerVM"

data class MiniPlayerUiState(
    val connected: Boolean = false,
    val hasPlayer: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val imageUrl: String? = null,
    val isPlaying: Boolean = false
)

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    suspend fun isServerConfigured(): Boolean =
        settingsRepository.serverUrl.first().isNotBlank()

    val noPlayerSelectedEvent: SharedFlow<Unit> = playerRepository.noPlayerSelectedEvent

    val miniPlayerUiState: StateFlow<MiniPlayerUiState> = combine(
        wsClient.connectionState,
        playerRepository.selectedPlayer,
        playerRepository.queueState
    ) { connectionState, selectedPlayer, queueState ->
        val currentTrack = queueState?.currentItem?.track
        MiniPlayerUiState(
            connected = connectionState is ConnectionState.Connected,
            hasPlayer = selectedPlayer != null,
            title = currentTrack?.name ?: selectedPlayer?.currentMedia?.title
            ?: selectedPlayer?.displayName.orEmpty(),
            artist = currentTrack?.artistNames ?: selectedPlayer?.currentMedia?.artist.orEmpty(),
            imageUrl = currentTrack?.imageUrl ?: selectedPlayer?.currentMedia?.imageUrl,
            isPlaying = selectedPlayer?.state == PlaybackState.PLAYING
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MiniPlayerUiState()
        )

    fun playPause() {
        val player = playerRepository.selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.playPause(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "playPause failed: ${e.message}")
            }
        }
    }

    fun next() {
        val player = playerRepository.selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.next(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "next failed: ${e.message}")
            }
        }
    }
}
