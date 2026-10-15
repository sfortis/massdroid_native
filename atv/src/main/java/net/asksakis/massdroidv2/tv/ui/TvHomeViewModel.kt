package net.asksakis.massdroidv2.tv.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.discover.DiscoverFeedStore
import net.asksakis.massdroidv2.domain.discover.MixLauncher
import net.asksakis.massdroidv2.presentation.discover.userMessage
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

private const val TAG = "TvHomeVM"

/**
 * The TV's Home: the same Discover feed as the phone, from the shared [DiscoverFeedStore], with
 * Smart Mix and Genre Radio started through the shared [MixLauncher]. The library itself is
 * reached through Browse.
 */
@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val feedStore: DiscoverFeedStore,
    private val mixLauncher: MixLauncher,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    val sections: StateFlow<List<DiscoverSection>> = feedStore.state.map { it.sections }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), feedStore.state.value.sections)
    val isLoading: StateFlow<Boolean> = feedStore.state.map { it.isLoading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), feedStore.state.value.isLoading)
    val mixBuilding: StateFlow<MixLauncher.Mix?> = mixLauncher.activity

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        feedStore.activate()
        viewModelScope.launch {
            mixLauncher.outcomes.collect { outcome -> outcome.userMessage()?.let { _message.value = it } }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    /** Plays an album, a playlist or a track on the target player, replacing its queue. */
    fun playMedia(uri: String) {
        val target = targetPlayerId() ?: return
        viewModelScope.launch {
            playerRepository.selectPlayer(target)
            runCatching { musicRepository.playMedia(target, uri, option = "replace") }
                .onFailure { Log.w(TAG, "playMedia failed for $uri: ${it.message}") }
        }
    }

    fun startSmartMix() {
        val target = targetPlayerId()
        if (target == null) {
            _message.value = "Select a player first"
            return
        }
        playerRepository.selectPlayer(target)
        mixLauncher.startSmartMix(target)
    }

    fun startGenreRadio(genre: String) {
        val target = targetPlayerId()
        if (target == null) {
            _message.value = "Select a player first"
            return
        }
        playerRepository.selectPlayer(target)
        mixLauncher.startGenreRadio(target, genre)
    }

    // The selected player, or the first one while nothing is selected yet.
    private fun targetPlayerId(): String? =
        playerRepository.selectedPlayer.value?.playerId
            ?: playerRepository.players.value.firstOrNull()?.playerId
}
