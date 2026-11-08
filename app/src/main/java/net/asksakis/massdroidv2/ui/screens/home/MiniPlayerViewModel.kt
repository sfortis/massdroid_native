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
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SyncState
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.skipAvailability
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.playback.OfflinePlayPausePolicy
import kotlinx.coroutines.flow.first
import javax.inject.Inject

private const val TAG = "MiniPlayerVM"

data class MiniPlayerUiState(
    val connected: Boolean = false,
    val hasPlayer: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val imageUrl: String? = null,
    val isPlaying: Boolean = false,
    /**
     * True while MA is not connected and this phone is playing its own buffered audio:
     * the play/pause button may pause it then, see [OfflinePlayPausePolicy].
     */
    val pausableOffline: Boolean = false,
    val playerName: String = "",
    /** False when next would do nothing, for example on a radio alone in the queue. */
    val canNext: Boolean = true
)

@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val sendspinManager: SendspinManager
) : ViewModel() {

    /**
     * Whether the app can sign in without the user: a server is saved, and with it a token or a
     * saved password. A sign-in saves the server before it succeeds, so a server alone is not
     * enough; a wrong password followed by closing the app must bring the onboarding back.
     * A saved password counts, because the client signs in again with it when the token is
     * gone.
     */
    suspend fun isServerConfigured(): Boolean =
        settingsRepository.serverUrl.first().isNotBlank() &&
            (settingsRepository.authToken.first().isNotBlank() || settingsRepository.password.first().isNotBlank())

    val noPlayerSelectedEvent: SharedFlow<Unit> = playerRepository.noPlayerSelectedEvent

    /** Queue exhausted (play/next rejected with "no more tracks"); UI shows a notice. */
    val queueEndedEvent: SharedFlow<Unit> = playerRepository.queueEndedEvent

    private val localOutput = combine(
        settingsRepository.sendspinClientId,
        sendspinManager.streamActive,
        sendspinManager.syncState
    ) { localPlayerId, streamActive, syncState -> Triple(localPlayerId, streamActive, syncState == SyncState.IDLE) }

    val miniPlayerUiState: StateFlow<MiniPlayerUiState> = combine(
        wsClient.connectionState,
        playerRepository.selectedPlayer,
        playerRepository.queueState,
        localOutput
    ) { connectionState, selectedPlayer, queueState, (localPlayerId, streamActive, outputIdle) ->
        val currentTrack = queueState?.currentItem?.track
        val connected = connectionState is ConnectionState.Connected
        val button = OfflinePlayPausePolicy.button(
            maConnected = connected,
            serverPlaying = selectedPlayer?.state == PlaybackState.PLAYING,
            localPlayerId = localPlayerId,
            selectedPlayerId = selectedPlayer?.playerId,
            streamActive = streamActive,
            outputIdle = outputIdle,
        )
        MiniPlayerUiState(
            connected = connected,
            hasPlayer = selectedPlayer != null,
            title = currentTrack?.name ?: selectedPlayer?.currentMedia?.title
            ?: selectedPlayer?.displayName.orEmpty(),
            artist = currentTrack?.artistNames ?: selectedPlayer?.currentMedia?.artist.orEmpty(),
            imageUrl = currentTrack?.imageUrl ?: selectedPlayer?.currentMedia?.imageUrl,
            isPlaying = button.shownPlaying,
            pausableOffline = button.pausableOffline,
            playerName = selectedPlayer?.displayName.orEmpty(),
            canNext = queueState.skipAvailability().next
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
        // An offline pause is a pause whatever MA last reported, which the toggle would read.
        val pausedOffline = miniPlayerUiState.value.pausableOffline
        val willPlay = !pausedOffline && player.state != PlaybackState.PLAYING
        Log.i(TAG, "Play/pause from the mini player: ${if (willPlay) "play" else "pause"}")
        viewModelScope.launch {
            try {
                if (pausedOffline) {
                    playerRepository.pause(player.playerId)
                } else {
                    playerRepository.playPause(player.playerId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "playPause failed: ${e.message}")
            }
        }
    }

    fun switchPlayer(direction: Int) {
        val current = playerRepository.selectedPlayer.value ?: return
        val available = playerRepository.players.value.filter { it.available }
            .sortedBy { it.displayName.lowercase() }
        if (available.size <= 1) return
        val currentIdx = available.indexOfFirst { it.playerId == current.playerId }
        if (currentIdx < 0) return
        val nextIdx = (currentIdx + direction).mod(available.size)
        val target = available[nextIdx]
        playerRepository.selectPlayer(target.playerId)
        viewModelScope.launch {
            try { settingsRepository.setSelectedPlayerId(target.playerId) } catch (_: Exception) {}
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
