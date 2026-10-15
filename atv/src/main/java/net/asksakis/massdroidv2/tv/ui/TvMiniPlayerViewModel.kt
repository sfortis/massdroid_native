package net.asksakis.massdroidv2.tv.ui

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

    private fun changeVolume(delta: Int) {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            runCatching {
                playerRepository.setVolume(player.playerId, (player.volumeLevel + delta).coerceIn(0, 100))
            }
        }
    }

    private companion object {
        const val VOLUME_STEP = 5
    }
}
