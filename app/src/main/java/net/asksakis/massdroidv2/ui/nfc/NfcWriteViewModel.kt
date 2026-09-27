package net.asksakis.massdroidv2.ui.nfc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.nfc.NfcTagRecord
import net.asksakis.massdroidv2.data.nfc.NfcTagStore
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * What the write sheet needs, wherever it is opened from.
 *
 * It owns this rather than each calling screen, because the sheet is offered from the
 * library, from an album, from a playlist and from the player, and every one of them would
 * otherwise carry the same three dependencies for a panel that is not theirs.
 */
@HiltViewModel
class NfcWriteViewModel @Inject constructor(
    playerRepository: PlayerRepository,
    private val store: NfcTagStore
) : ViewModel() {

    val players: StateFlow<List<Player>> = playerRepository.players
    val selectedPlayer: StateFlow<Player?> = playerRepository.selectedPlayer

    init {
        viewModelScope.launch { store.load() }
    }

    /**
     * Keep a note of a tag that was just written. The tag itself already holds everything
     * a tap needs, so this only feeds the list in settings.
     */
    fun remember(record: NfcTagRecord) {
        viewModelScope.launch { store.remember(record) }
    }
}
