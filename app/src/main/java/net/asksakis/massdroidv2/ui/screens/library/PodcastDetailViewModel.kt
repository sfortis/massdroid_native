package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

private const val TAG = "PodcastDetailVM"

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository
) : ViewModel() {

    val itemId: String = savedStateHandle["itemId"] ?: ""
    val provider: String = savedStateHandle["provider"] ?: ""

    private val _podcast = MutableStateFlow<Podcast?>(null)
    val podcast: StateFlow<Podcast?> = _podcast.asStateFlow()

    private val _episodes = MutableStateFlow<List<PodcastEpisode>>(emptyList())
    val episodes: StateFlow<List<PodcastEpisode>> = _episodes.asStateFlow()

    private val _podcastName = MutableStateFlow(savedStateHandle.get<String>("name") ?: "Podcast")
    val podcastName: StateFlow<String> = _podcastName.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val currentTrackUri: StateFlow<String?> = playerRepository.queueState
        .map { it?.currentItem?.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isPlaying: StateFlow<Boolean> = playerRepository.selectedPlayer
        .map { it?.state == PlaybackState.PLAYING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val players = playerRepository.players

    /** Live playback position of the selected player; drives the current episode's progress. */
    val elapsedTime: StateFlow<Double> = playerRepository.elapsedTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    init {
        viewModelScope.launch { loadData() }
        // Re-pull episode state when the playing episode changes so an episode
        // left mid-play reflects its new server-side resume/played state without a
        // manual pull-to-refresh (the currently-playing one updates live from
        // elapsedTime; this catches the one you moved off). Skips the initial value.
        viewModelScope.launch {
            currentTrackUri.drop(1).distinctUntilChanged().collect { loadData() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                _podcast.value?.uri?.let { musicRepository.refreshItemByUri(it) }
                loadData()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun loadData() {
        try {
            _podcast.value = musicRepository.getPodcast(itemId, provider)
            _podcast.value?.let { p ->
                if (p.name.isNotBlank()) _podcastName.value = p.name
            }
            _episodes.value = musicRepository.getPodcastEpisodes(itemId, provider)
        } catch (e: Exception) {
            Log.w(TAG, "Load podcast detail failed: ${e.message}")
        }
    }

    fun playEpisode(episode: PodcastEpisode) = playUri(episode.uri)

    fun playUri(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uri, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "play failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun playOnPlayer(uri: String, playerId: String) {
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(playerId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(playerId, uri, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "playOnPlayer failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun enqueue(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uri, option = "add")
            } catch (e: Exception) {
                Log.w(TAG, "enqueue failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun enqueueNext(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uri, option = "next")
            } catch (e: Exception) {
                Log.w(TAG, "enqueueNext failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun togglePodcastFavorite() {
        val p = _podcast.value ?: return
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(p.uri, MediaType.PODCAST, p.itemId, !p.favorite)
                _podcast.update { it?.copy(favorite = !p.favorite) }
            } catch (e: Exception) {
                Log.w(TAG, "togglePodcastFavorite failed: ${e.message}")
            }
        }
    }

    /** Mark an episode fully played or unplayed (server + optimistic local update). */
    fun setEpisodePlayed(episode: PodcastEpisode, played: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setPodcastEpisodePlayed(episode.itemId, episode.provider, played)
                _episodes.update { list ->
                    list.map {
                        if (it.itemId == episode.itemId) {
                            it.copy(fullyPlayed = played, resumePositionMs = 0L)
                        } else {
                            it
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "setEpisodePlayed failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun toggleEpisodeFavorite(uri: String, itemId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(uri, MediaType.PODCAST_EPISODE, itemId, !currentFavorite)
                _episodes.update { list ->
                    list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleEpisodeFavorite failed: ${e.message}")
            }
        }
    }
}
