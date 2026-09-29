package net.asksakis.massdroidv2.domain.search

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

private const val DEEP_SEARCH_LIMIT = 100

/**
 * How long the typing has to stop before a query goes to the server.
 *
 * Long enough that a pause between words does not spend a request, short enough
 * that the results feel like they answer the last letter typed.
 */
private const val SEARCH_DEBOUNCE_MS = 600L
private const val TAG = "SearchSession"

/** Below this the query is treated as unfinished: nothing is searched, the history stays up. */
const val MIN_SEARCH_QUERY_LENGTH = 2

/**
 * The two ways a search starts, which differ in how finished the text is.
 *
 * Typing is unfinished, so it waits out the debounce and ignores a query too
 * short to be worth a request. A submit is the listener saying the text is
 * final, so it goes to the server immediately and however short it is.
 */
enum class SearchTrigger(val debounceMs: Long, val minLength: Int) {
    TYPING(SEARCH_DEBOUNCE_MS, MIN_SEARCH_QUERY_LENGTH),
    SUBMIT(debounceMs = 0L, minLength = 1)
}

/**
 * Everything the search screen draws, in one value so the parts cannot disagree.
 *
 * They used to be four separate flows, and two of the field reports were exactly that
 * disagreement: a progress line still running with no search behind it, and results that
 * answered an older query sitting under new text.
 */
data class SearchSession(
    val query: String = "",
    val results: SearchResult = SearchResult(),
    /**
     * The query [results] answer, or empty while nothing has been answered yet.
     *
     * Empty results only mean "nothing matched" once the server has replied to this exact
     * query. During the debounce and the request they are simply the results that do not
     * exist yet, and the screen must not report them as an answer.
     */
    val resultsQuery: String = "",
    val isSearching: Boolean = false
)

/**
 * Runs the search behind the search screen: what is being asked, what is shown, and when a
 * query is worth sending to the server.
 *
 * This lives in the domain layer rather than in the ViewModel because the rules it holds are
 * the ones that went wrong in the field, and where they used to live nothing could test them:
 * the `:app` module has no unit tests, so a progress line that never stopped and results that
 * belonged to an earlier query were found by a reporter's screenshots instead.
 *
 * The recent-search history is written here as well, because what deserves to be remembered
 * is decided by how the search went: only a query the server actually matched is stored.
 */
class SearchSessionController(
    private val musicRepository: MusicRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope
) {

    private val _session = MutableStateFlow(SearchSession())

    /** What the screen shows. */
    val session: StateFlow<SearchSession> = _session.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** User facing failure messages, one per failed search. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var searchJob: Job? = null
    private var deepenJob: Job? = null

    /** (query, type) pairs already deepened, so re-tapping a chip costs nothing. */
    private val deepened = mutableSetOf<Pair<String, MediaType>>()

    /**
     * The entry this typing run has already stored, if any.
     *
     * A search fires while the listener is still typing, so "pink floyd" can be
     * preceded by a stored "pink". Remembering what this run stored lets the
     * longer query replace it, while an identical-looking search made earlier,
     * which this run knows nothing about, stays in the history.
     */
    private var recordedThisRun: String? = null

    /** The listener typed: unfinished text, so it waits out the debounce. */
    fun updateQuery(newQuery: String) = search(newQuery, SearchTrigger.TYPING)

    /**
     * The search key on the keyboard: search what is in the field now.
     *
     * The listener has said the text is final, so it goes to the server at once
     * and a query that has already been answered is asked again, which makes the
     * key a retry when a search failed.
     */
    fun submitQuery() = search(_session.value.query, SearchTrigger.SUBMIT)

    /** Re-run a remembered search. The text is final, so there is nothing to debounce. */
    fun searchAgain(query: String) {
        // Picked from the history rather than typed, so nothing it grows into
        // may replace it later.
        recordedThisRun = null
        search(query, SearchTrigger.SUBMIT)
    }

    /** Drop everything, for a server change that makes the current results meaningless. */
    fun reset() {
        searchJob?.cancel()
        deepenJob?.cancel()
        deepened.clear()
        recordedThisRun = null
        _session.value = SearchSession()
    }

    private fun search(newQuery: String, trigger: SearchTrigger) {
        searchJob?.cancel()
        deepenJob?.cancel()
        deepened.clear()
        if (newQuery.isBlank() || newQuery.length < trigger.minLength) {
            // The job cancelled above is the one that would have cleared the progress
            // line, so a cleared field used to leave it running with nothing behind it
            // until some later search finished.
            _session.value = SearchSession(query = newQuery)
            // The field was emptied, so the next query starts a new run.
            recordedThisRun = null
            return
        }
        _session.value = _session.value.copy(query = newQuery)
        searchJob = scope.launch {
            if (trigger.debounceMs > 0) delay(trigger.debounceMs)
            _session.value = _session.value.copy(isSearching = true)
            try {
                val result = musicRepository.search(newQuery)
                _session.value = _session.value.copy(results = result, resultsQuery = newQuery)
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
                // Results for an earlier query are not an answer to this one. Leaving them
                // up put the field and the grid at odds: a reporter searching for one
                // episode was shown the previous one's albums under the new text, with
                // only the error message to explain it. Results that do answer this query
                // stay, because then the search key is a retry of something already shown.
                if (_session.value.resultsQuery != newQuery) {
                    _session.value = _session.value.copy(results = SearchResult(), resultsQuery = "")
                }
                Log.w(TAG, "search failed: ${e.message}")
                _errors.tryEmit("Search failed, try again")
            }
            _session.value = _session.value.copy(isSearching = false)
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
    fun deepen(mediaType: MediaType) {
        val q = _session.value.query
        if (q.isBlank() || !deepened.add(q to mediaType)) return
        deepenJob = scope.launch {
            try {
                val more = musicRepository.search(q, listOf(mediaType), DEEP_SEARCH_LIMIT)
                // The query may have moved on while the request was in flight.
                if (_session.value.query != q) return@launch
                _session.value = _session.value.copy(
                    results = _session.value.results.replacing(mediaType, more)
                )
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
            MediaType.AUDIOBOOK -> copy(audiobooks = from.audiobooks)
            MediaType.PODCAST -> copy(podcasts = from.podcasts)
            else -> this
        }
}
