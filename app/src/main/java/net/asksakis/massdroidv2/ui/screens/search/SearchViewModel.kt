package net.asksakis.massdroidv2.ui.screens.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import javax.inject.Inject

private const val DEEP_SEARCH_LIMIT = 100
/**
 * How long the typing has to stop before a query goes to the server.
 *
 * Long enough that a pause between words does not spend a request, short enough
 * that the results feel like they answer the last letter typed.
 */
private const val SEARCH_DEBOUNCE_MS = 600L
private const val TAG = "SearchVM"

/** Below this the query is treated as unfinished: nothing is searched, the history stays up. */
const val MIN_SEARCH_QUERY_LENGTH = 2

/**
 * The two ways a search starts, which differ in how finished the text is.
 *
 * Typing is unfinished, so it waits out the debounce and ignores a query too
 * short to be worth a request. A submit is the listener saying the text is
 * final, so it goes to the server immediately and however short it is.
 */
private enum class SearchTrigger(val debounceMs: Long, val minLength: Int) {
    TYPING(SEARCH_DEBOUNCE_MS, MIN_SEARCH_QUERY_LENGTH),
    SUBMIT(debounceMs = 0L, minLength = 1)
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val sessionEventBus: SessionEventBus,
    private val settingsRepository: net.asksakis.massdroidv2.domain.repository.SettingsRepository
) : ViewModel() {

    init {
        viewModelScope.launch {
            sessionEventBus.resets.collect {
                searchJob?.cancel()
                _query.value = ""
                _results.value = SearchResult()
                _resultsQuery.value = ""
                _isSearching.value = false
                recordedThisRun = null
            }
        }
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResult())
    val results: StateFlow<SearchResult> = _results.asStateFlow()

    /**
     * The query [results] answer, or empty while nothing has been answered yet.
     *
     * Empty results only mean "nothing matched" once the server has replied to
     * this exact query. During the debounce and the request they are simply the
     * results that do not exist yet, and the screen must not report them as an
     * answer.
     */
    private val _resultsQuery = MutableStateFlow("")
    val resultsQuery: StateFlow<String> = _resultsQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Grid is the default and the choice is persisted: re-picking the layout on
    // every visit was the papercut, not the layout itself.
    private val _gridMode = MutableStateFlow(true)
    val gridMode: StateFlow<Boolean> = _gridMode.asStateFlow()

    init {
        viewModelScope.launch { _gridMode.value = settingsRepository.searchGridMode.first() }
    }

    fun toggleGridMode() {
        val grid = !_gridMode.value
        _gridMode.value = grid
        viewModelScope.launch { settingsRepository.setSearchGridMode(grid) }
    }

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private var searchJob: Job? = null
    private var deepenJob: Job? = null

    /** (query, type) pairs already deepened, so re-tapping a chip costs nothing. */
    private val deepened = mutableSetOf<Pair<String, MediaType>>()

    /**
     * The searches worth offering again, newest first. Only queries the server
     * actually matched get in, so a half-typed word never becomes an entry.
     */
    val recentSearches: StateFlow<List<String>> = settingsRepository.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateQuery(newQuery: String) = search(newQuery, SearchTrigger.TYPING)

    /**
     * The search key on the keyboard: search what is in the field now.
     *
     * The listener has said the text is final, so it goes to the server at once
     * and a query that has already been answered is asked again, which makes the
     * key a retry when a search failed.
     */
    fun submitQuery() = search(_query.value, SearchTrigger.SUBMIT)

    /** Re-run a remembered search. The text is final, so there is nothing to debounce. */
    fun searchAgain(query: String) {
        // Picked from the history rather than typed, so nothing it grows into
        // may replace it later.
        recordedThisRun = null
        search(query, SearchTrigger.SUBMIT)
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { settingsRepository.removeRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { settingsRepository.clearRecentSearches() }
    }

    /**
     * The entry this typing run has already stored, if any.
     *
     * A search fires while the listener is still typing, so "pink floyd" can be
     * preceded by a stored "pink". Remembering what this run stored lets the
     * longer query replace it, while an identical-looking search made earlier,
     * which this run knows nothing about, stays in the history.
     */
    private var recordedThisRun: String? = null

    private fun search(newQuery: String, trigger: SearchTrigger) {
        _query.value = newQuery
        searchJob?.cancel()
        deepenJob?.cancel()
        deepened.clear()
        if (newQuery.isBlank() || newQuery.length < trigger.minLength) {
            _results.value = SearchResult()
            _resultsQuery.value = ""
            // The field was emptied, so the next query starts a new run.
            recordedThisRun = null
            return
        }
        searchJob = viewModelScope.launch {
            if (trigger.debounceMs > 0) delay(trigger.debounceMs)
            _isSearching.value = true
            try {
                val result = musicRepository.search(newQuery)
                _results.value = result
                _resultsQuery.value = newQuery
                if (!result.isEmpty) {
                    val superseded = recordedThisRun
                        ?.takeIf { newQuery.startsWith(it, ignoreCase = true) }
                    settingsRepository.addRecentSearch(newQuery, superseded)
                    recordedThisRun = newQuery
                }
            } catch (e: CancellationException) {
                // The next keystroke already owns the state. Falling through here
                // would clear its spinner and leave the stale results on screen.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "search failed: ${e.message}")
            }
            _isSearching.value = false
        }
    }

    /**
     * Fetch MORE of one category, triggered by selecting its filter chip.
     *
     * The broad search stays at the default 25 per category so five categories
     * over slow providers cannot make every keystroke expensive; the depth is
     * bought only when the listener narrows to one category, which is the moment
     * they have said "this is the kind of thing I am looking for". The deeper
     * page replaces that category's list (same query, same server ordering, so
     * it is a superset of what is already shown).
     */
    fun deepenSearch(mediaType: MediaType) {
        val q = _query.value
        if (q.isBlank() || !deepened.add(q to mediaType)) return
        deepenJob = viewModelScope.launch {
            try {
                val more = musicRepository.search(q, listOf(mediaType), DEEP_SEARCH_LIMIT)
                // The query may have moved on while the request was in flight.
                if (_query.value != q) return@launch
                _results.value = _results.value.replacing(mediaType, more)
            } catch (e: Exception) {
                // Retryable: the next tap should be allowed to ask again.
                deepened.remove(q to mediaType)
                Log.w(TAG, "deepen search failed: ${e.message}")
            }
        }
    }

    private fun SearchResult.replacing(mediaType: MediaType, from: SearchResult): SearchResult =
        when (mediaType) {
            MediaType.ARTIST -> copy(artists = from.artists)
            MediaType.ALBUM -> copy(albums = from.albums)
            MediaType.TRACK -> copy(tracks = from.tracks)
            MediaType.PLAYLIST -> copy(playlists = from.playlists)
            MediaType.RADIO -> copy(radios = from.radios)
            else -> this
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
