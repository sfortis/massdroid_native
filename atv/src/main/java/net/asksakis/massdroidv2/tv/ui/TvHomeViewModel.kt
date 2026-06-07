package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * Connected home content. Pulls live players + a "recently played" album shelf
 * straight from the shared :core repositories. The screen renders these as
 * 10-foot, D-pad navigable card rows (ATV style).
 */
@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    playerRepository: PlayerRepository,
) : ViewModel() {

    val players: StateFlow<List<Player>> = playerRepository.players
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recentlyPlayed = MutableStateFlow<List<Album>>(emptyList())
    val recentlyPlayed: StateFlow<List<Album>> = _recentlyPlayed.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { musicRepository.getAlbums(limit = 20, orderBy = "last_played") }
                .onSuccess { _recentlyPlayed.value = it }
        }
    }
}
