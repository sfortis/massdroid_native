package net.asksakis.massdroidv2.ui.screens.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import javax.inject.Inject

private const val TAG = "SearchVM"

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResult())
    val results: StateFlow<SearchResult> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private var searchJob: Job? = null

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        if (newQuery.length < 2) {
            _results.value = SearchResult()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce
            _isSearching.value = true
            try {
                _results.value = musicRepository.search(newQuery)
            } catch (e: Exception) {
                Log.w(TAG, "search failed: ${e.message}")
            }
            _isSearching.value = false
        }
    }

    fun playTrack(track: Track) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, track.uri)
            } catch (e: Exception) {
                Log.w(TAG, "playTrack failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }
}
