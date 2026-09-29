package net.asksakis.massdroidv2.ui.screens.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.search.SearchSession
import net.asksakis.massdroidv2.domain.search.SearchSessionController
import javax.inject.Inject

private const val TAG = "SearchVM"

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val sessionEventBus: SessionEventBus,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /**
     * The search itself. It lives in the domain layer so its rules can be tested; this
     * class keeps what belongs to the screen around it, such as the layout choice and
     * the actions on a result.
     */
    private val searchSession = SearchSessionController(musicRepository, settingsRepository, viewModelScope)

    /** What the screen draws: the query, the results, and whether a search is running. */
    val session: StateFlow<SearchSession> = searchSession.session

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    // Grid is the default and the choice is persisted: re-picking the layout on
    // every visit was the papercut, not the layout itself.
    private val _gridMode = MutableStateFlow(true)
    val gridMode: StateFlow<Boolean> = _gridMode.asStateFlow()

    // Declared after everything the collectors below touch: viewModelScope is
    // Main.immediate, so each launch runs inside the constructor and a property
    // declared under this block would still be null when it is read.
    init {
        viewModelScope.launch {
            sessionEventBus.resets.collect { searchSession.reset() }
        }
        // One failure channel reaches the screen, whether the search or an action on a
        // result is what failed.
        viewModelScope.launch {
            searchSession.errors.collect { _error.tryEmit(it) }
        }
        viewModelScope.launch { _gridMode.value = settingsRepository.searchGridMode.first() }
    }

    fun toggleGridMode() {
        val grid = !_gridMode.value
        _gridMode.value = grid
        viewModelScope.launch { settingsRepository.setSearchGridMode(grid) }
    }

    /**
     * The searches worth offering again, newest first. Only queries the server
     * actually matched get in, so a half-typed word never becomes an entry.
     */
    val recentSearches: StateFlow<List<String>> = settingsRepository.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateQuery(newQuery: String) = searchSession.updateQuery(newQuery)

    fun submitQuery() = searchSession.submitQuery()

    fun searchAgain(query: String) = searchSession.searchAgain(query)

    fun deepenSearch(mediaType: MediaType) = searchSession.deepen(mediaType)

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { settingsRepository.removeRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { settingsRepository.clearRecentSearches() }
    }

    fun playRadio(radio: net.asksakis.massdroidv2.domain.model.Radio) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, radio.uri)
            } catch (e: Exception) {
                Log.w(TAG, "playRadio failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
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

    // --- Long-press action sheet support (favorite, library, play, queue, radio) ---

    val players = playerRepository.players

    fun playUri(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uri)
            } catch (e: Exception) {
                Log.w(TAG, "playUri failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun playOnPlayer(uri: String, playerId: String) {
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(playerId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(playerId, uri)
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

    fun startRadio(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.RADIO_SMART)
                musicRepository.playMedia(queueId, uri, radioMode = true)
            } catch (e: Exception) {
                Log.w(TAG, "startRadio failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun toggleFavorite(uri: String, mediaType: MediaType, itemId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(uri, mediaType, itemId, !currentFavorite)
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
            }
        }
    }

    fun toggleLibrary(uri: String, mediaType: MediaType, itemId: String, currentlyInLibrary: Boolean) {
        viewModelScope.launch {
            try {
                if (currentlyInLibrary) {
                    musicRepository.removeFromLibrary(mediaType, uri, itemId)
                } else {
                    musicRepository.addToLibrary(uri)
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleLibrary failed: ${e.message}")
            }
        }
    }
}
