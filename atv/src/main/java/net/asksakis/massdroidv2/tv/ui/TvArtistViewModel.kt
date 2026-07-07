package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * One artist's albums, so the user can pick which to play (artists are not played
 * directly from the home — they drill into their releases first). Plays the chosen
 * album on the currently selected player.
 */
@HiltViewModel
class TvArtistViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])
    private val provider: String = checkNotNull(savedStateHandle["provider"])

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { musicRepository.getArtist(itemId, provider) }.getOrNull()
                ?.let { _name.value = it.name }
            // Discography (provider catalogue): a library artist's artist_albums(library) is
            // ~empty on MA 2.9+, so the TV artist screen would otherwise show no albums.
            runCatching { musicRepository.getArtistDiscography(itemId, provider) }
                .onSuccess { list -> _albums.value = list.sortedBy { it.name.lowercase() } }
        }
    }

    /** Play an album on the selected player (or the first available one). */
    fun playMedia(uri: String) {
        viewModelScope.launch {
            val target = playerRepository.selectedPlayer.value?.playerId
                ?: playerRepository.players.value.firstOrNull()?.playerId
                ?: return@launch
            playerRepository.selectPlayer(target)
            runCatching { musicRepository.playMedia(target, uri, option = "replace") }
        }
    }
}
