package net.asksakis.massdroidv2.tv.ui

import net.asksakis.massdroidv2.domain.player.VolumeKeyController
import net.asksakis.massdroidv2.domain.player.VolumeKeyTarget
import net.asksakis.massdroidv2.domain.player.volumeKeyTarget
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SyncState
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.playback.OfflinePlayPausePolicy
import net.asksakis.massdroidv2.domain.model.skipAvailability
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Backs the mini player bar: the selected player's live state, the full player list
 * for the switch dialog, and the transport buttons on the bar.
 */
@HiltViewModel
class TvMiniPlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    settingsRepository: SettingsRepository,
    private val volumeKeyController: VolumeKeyController,
    wsClient: MaWebSocketClient,
    sendspinManager: SendspinManager,
) : ViewModel() {

    val players: StateFlow<List<Player>> = playerRepository.players
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedPlayer: StateFlow<Player?> = playerRepository.selectedPlayer

    /** MA player id of this device's own Sendspin player (the local "MassDroid TV"). */
    val localPlayerId: StateFlow<String?> = settingsRepository.sendspinClientId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectPlayer(playerId: String) = playerRepository.selectPlayer(playerId)

    /**
     * The play/pause button. With MA down and this TV's own player selected it follows the
     * local output, which the last state MA reported does not; see [OfflinePlayPausePolicy].
     * Eagerly, because playPause() reads it for the direction of the press.
     */
    val playPauseButton: StateFlow<OfflinePlayPausePolicy.Button> = combine(
        wsClient.connectionState,
        selectedPlayer,
        localPlayerId,
        sendspinManager.streamActive,
        sendspinManager.syncState,
    ) { connection, player, localId, streamActive, syncState ->
        OfflinePlayPausePolicy.button(
            maConnected = connection is ConnectionState.Connected,
            serverPlaying = player?.state == PlaybackState.PLAYING,
            localPlayerId = localId,
            selectedPlayerId = player?.playerId,
            streamActive = streamActive,
            outputIdle = syncState == SyncState.IDLE,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OfflinePlayPausePolicy.Button(false, false))

    /**
     * Whether the volume buttons show: only for a Music Assistant player elsewhere, decided as
     * the remote's volume keys are ([volumeKeyTarget]). This TV's own output, a member of a
     * group included, is left at full level for the TV or the receiver to set: turning it down
     * here as well would attenuate twice. The keys rarely reach the app on a TV (a remote with
     * IR or CEC control sends them straight to the receiver), so these buttons are the way.
     */
    val volumeShown: StateFlow<Boolean> = combine(selectedPlayer, localPlayerId) { player, localId ->
        volumeKeyTarget(player, localId) is VolumeKeyTarget.RemotePlayer
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** False when next would do nothing, for example on a radio alone in the queue. */
    val canNext: StateFlow<Boolean> = playerRepository.queueState
        .map { it.skipAvailability().next }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** A step on the selected player, through the same controller as the volume keys. */
    fun volumeUp() {
        volumeKeyController.step(up = true)
    }

    fun volumeDown() {
        volumeKeyController.step(up = false)
    }

    fun playPause() {
        val id = selectedPlayer.value?.playerId ?: return
        // An offline pause is a pause whatever MA last reported, which the toggle would read.
        val pausedOffline = playPauseButton.value.pausableOffline
        viewModelScope.launch {
            runCatching {
                if (pausedOffline) playerRepository.pause(id) else playerRepository.playPause(id)
            }
        }
    }

    fun next() {
        val id = selectedPlayer.value?.playerId ?: return
        viewModelScope.launch { runCatching { playerRepository.next(id) } }
    }
}
