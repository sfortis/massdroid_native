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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Player
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
) : ViewModel() {

    val players: StateFlow<List<Player>> = playerRepository.players
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedPlayer: StateFlow<Player?> = playerRepository.selectedPlayer

    /** MA player id of this device's own Sendspin player (the local "MassDroid TV"). */
    val localPlayerId: StateFlow<String?> = settingsRepository.sendspinClientId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectPlayer(playerId: String) = playerRepository.selectPlayer(playerId)

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

    /** A step on the selected player, through the same controller as the volume keys. */
    fun volumeUp() {
        volumeKeyController.step(up = true)
    }

    fun volumeDown() {
        volumeKeyController.step(up = false)
    }

    fun playPause() {
        val id = selectedPlayer.value?.playerId ?: return
        viewModelScope.launch { runCatching { playerRepository.playPause(id) } }
    }

    fun next() {
        val id = selectedPlayer.value?.playerId ?: return
        viewModelScope.launch { runCatching { playerRepository.next(id) } }
    }
}
