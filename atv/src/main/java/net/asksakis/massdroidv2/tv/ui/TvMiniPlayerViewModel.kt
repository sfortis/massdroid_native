package net.asksakis.massdroidv2.tv.ui

import net.asksakis.massdroidv2.domain.player.leadsVolumeGroup
import net.asksakis.massdroidv2.domain.player.remoteVolumeStep
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
) : ViewModel() {

    val players: StateFlow<List<Player>> = playerRepository.players
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedPlayer: StateFlow<Player?> = playerRepository.selectedPlayer

    /** MA player id of this device's own Sendspin player (the local "MassDroid TV"). */
    val localPlayerId: StateFlow<String?> = settingsRepository.sendspinClientId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectPlayer(playerId: String) = playerRepository.selectPlayer(playerId)

    fun playPause() {
        val id = selectedPlayer.value?.playerId ?: return
        viewModelScope.launch { runCatching { playerRepository.playPause(id) } }
    }

    fun next() {
        val id = selectedPlayer.value?.playerId ?: return
        viewModelScope.launch { runCatching { playerRepository.next(id) } }
    }

    fun volumeUp() = changeVolume(VOLUME_STEP)
    fun volumeDown() = changeVolume(-VOLUME_STEP)

    // A group leader takes the group's volume, as on the phone; its own level moved only itself.
    private fun changeVolume(delta: Int) {
        val player = selectedPlayer.value ?: return
        val isGroup = leadsVolumeGroup(player)
        val level = remoteVolumeStep(player, isGroup, delta)
        viewModelScope.launch {
            runCatching {
                if (isGroup) playerRepository.setGroupVolume(player.playerId, level)
                else playerRepository.setVolume(player.playerId, level)
            }
        }
    }

    private companion object {
        const val VOLUME_STEP = 5
    }
}
