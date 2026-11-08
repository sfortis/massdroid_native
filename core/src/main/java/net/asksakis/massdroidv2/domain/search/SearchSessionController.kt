package net.asksakis.massdroidv2.domain.search

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Items per category on the first page.
 *
 * Spotify answers 10 items per request, and without a custom client id Music Assistant lets it
 * make one request every 2 s. A page of 25 took three requests, so two searches in the queue
 * already ran past the 8 s Music Assistant waits for a provider (#76). Twenty is two requests.
 */
internal const val FIRST_PAGE_LIMIT = 20

/** Items fetched for one category when its chip is tapped: three Spotify requests, about 4 s. */
internal const val DEEP_SEARCH_LIMIT = 30

/**
 * An answer this slow may be missing a provider.
 *
 * Music Assistant gives up waiting for a provider after 8 s and answers without it, with
 * nothing in the answer to say so, while the provider's search goes on in the background
 * and fills the server's cache. Just under 8 s, so an answer cut off there is caught.
 */
internal const val SLOW_ANSWER_MS = 7_500L

/** The wait before a slow answer is asked again, for the background search to land in the cache. */
internal const val SLOW_RETRY_DELAY_MS = 3_000L

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
    val isSearching: Boolean = false,
    /** The categories whose chip is fetching more of them. */
    val deepening: Set<MediaType> = emptySet()
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
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic
) {

    private val _session = MutableStateFlow(SearchSession())

    /** What the screen shows. */
    val session: StateFlow<SearchSession> = _session.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** User facing failure messages, one per failed search. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var searchJob: Job? = null
    /** The running deepen of each category, so a new search stops all of them, not only the last. */
    private val deepenJobs = mutableMapOf<MediaType, Job>()

    /**
     * The search job while it waits on the server, else null.
     *
     * The server cannot be told to stop a search, so a request cancelled here still runs
     * there and holds a place in a slow provider's queue. While one is out, typing only
     * changes the text, and the running job asks for the latest text once its answer is in.
     */
    private var askingJob: Job? = null

    /** The text [askingJob] is waiting on. */
    private var askingQuery: String? = null

    /** The last keystroke, so a query held back behind a running request still waits out the debounce. */
    private var lastEdit: TimeMark? = null

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
        cancelDeepens()
        deepened.clear()
        recordedThisRun = null
        _session.value = SearchSession()
    }

    private fun search(newQuery: String, trigger: SearchTrigger) {
        cancelDeepens()
        deepened.clear()
        if (newQuery.isBlank() || newQuery.length < trigger.minLength) {
            searchJob?.cancel()
            // The job cancelled above is the one that would have cleared the progress
            // line, so a cleared field used to leave it running with nothing behind it
            // until some later search finished.
            _session.value = SearchSession(query = newQuery)
            // The field was emptied, so the next query starts a new run.
            recordedThisRun = null
            return
        }
        if (trigger == SearchTrigger.TYPING) lastEdit = timeSource.markNow()
        if (trigger == SearchTrigger.SUBMIT && askingJob?.isActive == true &&
            askingQuery == newQuery && _session.value.query == newQuery
        ) {
            // The search key for the text already on its way: asking again would only put a
            // second copy behind it on the server. Once it has answered, the key is a retry.
            return
        }
        if (trigger == SearchTrigger.TYPING && askingJob?.isActive == true) {
            // The running job sends this text once the server has answered it.
            _session.value = _session.value.copy(query = newQuery, isSearching = true, deepening = emptySet())
            return
        }
        searchJob?.cancel()
        _session.value = _session.value.copy(query = newQuery, deepening = emptySet())
        searchJob = scope.launch { run(newQuery, trigger.debounceMs) }
    }

    /** Searches [start], then whatever the field holds by the time each answer comes in. */
    private suspend fun run(start: String, debounceMs: Long) {
        var query = start
        var wait = debounceMs
        while (true) {
            if (wait > 0) delay(wait)
            _session.value = _session.value.copy(isSearching = true)
            if (answer(query)) break
            query = _session.value.query
            wait = debounceLeft()
        }
        _session.value = _session.value.copy(isSearching = false)
    }

    /** What is left of the debounce since the last keystroke. */
    private fun debounceLeft(): Long {
        val since = lastEdit?.elapsedNow() ?: return 0L
        return (SEARCH_DEBOUNCE_MS.milliseconds - since).inWholeMilliseconds
    }

    /** Whether the field moved on from [query] while its answer was on the way. */
    private fun movedOn(query: String) = _session.value.query != query

    /**
     * Asks the server for [query] and shows the answer. False when the text changed while it
     * waited: the answer is dropped and the caller asks for the new text.
     */
    private suspend fun answer(query: String): Boolean = try {
        val asked = timeSource.markNow()
        val first = askFor(query)
        when {
            first == null -> false
            asked.elapsedNow() < SLOW_ANSWER_MS.milliseconds -> {
                show(query, first)
                true
            }
            // Slow enough that a provider may be missing from it. One more ask, after its
            // background search has had time to fill the server's cache.
            first.isEmpty -> askAgainBeforeNothingFound(query)
            else -> {
                show(query, first)
                fillInMissing(query)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail(query, e)
        true
    }

    /** A slow empty answer is not yet "nothing found": that waits for the second ask. */
    private suspend fun askAgainBeforeNothingFound(query: String): Boolean {
        // Results of an earlier query do not stay under this text meanwhile.
        if (_session.value.resultsQuery != query) {
            _session.value = _session.value.copy(results = SearchResult(), resultsQuery = "")
        }
        delay(SLOW_RETRY_DELAY_MS)
        val retry = askFor(query) ?: return false
        show(query, retry)
        return true
    }

    /** A slow answer is already shown; the second ask only adds what was missing from it. */
    private suspend fun fillInMissing(query: String): Boolean {
        _session.value = _session.value.copy(isSearching = false)
        delay(SLOW_RETRY_DELAY_MS)
        val retry = try {
            askFor(query) ?: return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "second ask after a slow answer failed: ${e.message}")
            null
        }
        if (retry != null) {
            _session.value = _session.value.copy(results = _session.value.results.fillingFrom(retry))
        }
        return true
    }

    /** The answer for [query], or null when the field moved on while it was on the way. */
    private suspend fun askFor(query: String): SearchResult? {
        val result = try {
            Result.success(ask(query))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        return if (movedOn(query)) null else result.getOrThrow()
    }

    private suspend fun ask(query: String): SearchResult {
        val job = coroutineContext.job
        askingJob = job
        askingQuery = query
        try {
            return musicRepository.search(query, limit = FIRST_PAGE_LIMIT)
        } finally {
            // A cancelled job finishes after its successor has started asking.
            if (askingJob === job) askingJob = null
        }
    }

    private suspend fun show(query: String, result: SearchResult) {
        _session.value = _session.value.copy(results = result, resultsQuery = query)
        if (!result.isEmpty) {
            val superseded = recordedThisRun
                ?.takeIf { query.startsWith(it, ignoreCase = true) }
            settingsRepository.addRecentSearch(query, superseded)
            recordedThisRun = query
        }
    }

    private fun fail(query: String, e: Exception) {
        // Results for an earlier query are not an answer to this one. Leaving them
        // up put the field and the grid at odds: a reporter searching for one
        // episode was shown the previous one's albums under the new text, with
        // only the error message to explain it. Results that do answer this query
        // stay, because then the search key is a retry of something already shown.
        if (_session.value.resultsQuery != query) {
            _session.value = _session.value.copy(results = SearchResult(), resultsQuery = "")
        }
        Log.w(TAG, "search failed: ${e.message}")
        _errors.tryEmit("Search failed, try again")
    }

    /**
     * Fetch MORE of one category, triggered by selecting its filter chip.
     *
     * The broad search stays at [FIRST_PAGE_LIMIT] per category so five categories over
     * slow providers cannot make every keystroke expensive; the depth is bought only when
     * the listener narrows to one category, which is the moment they have said "this is
     * the kind of thing I am looking for". A category the first page did not fill has
     * nothing more to give, so it costs no request. The deeper page replaces the list only
     * when it is longer: a slow provider cut off from it would otherwise shrink the list.
     */
    fun deepen(mediaType: MediaType) {
        val current = _session.value
        val q = current.query
        // Only results that answer the text in the field say whether the category is full:
        // during the debounce they still belong to the previous text.
        if (q.isBlank() || current.isSearching || current.resultsQuery != q) return
        if (current.results.count(mediaType) < FIRST_PAGE_LIMIT) return
        if (!deepened.add(q to mediaType)) return
        _session.value = current.copy(deepening = current.deepening + mediaType)
        // Started once it is in the map, so even a failure before the first suspension
        // finds itself there and clears its own spinner.
        val job = scope.launch(start = CoroutineStart.LAZY) { deepenInto(q, mediaType) }
        deepenJobs[mediaType] = job
        job.start()
    }

    private suspend fun deepenInto(q: String, mediaType: MediaType) {
        val self = coroutineContext.job
        try {
            val more = musicRepository.search(q, listOf(mediaType), DEEP_SEARCH_LIMIT)
            // The field may have moved on to other text while the request was in flight.
            if (_session.value.resultsQuery != q) return
            val shown = _session.value.results
            if (more.count(mediaType) > shown.count(mediaType)) {
                _session.value = _session.value.copy(results = shown.replacing(mediaType, more))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retryable: the next tap should be allowed to ask again.
            if (deepenJobs[mediaType] === self) deepened.remove(q to mediaType)
            Log.w(TAG, "deepen search failed: ${e.message}")
        } finally {
            // A deepen that was replaced leaves the spinner to the one that replaced it.
            if (deepenJobs[mediaType] === self) {
                deepenJobs.remove(mediaType)
                _session.value = _session.value.copy(deepening = _session.value.deepening - mediaType)
            }
        }
    }

    private fun cancelDeepens() {
        deepenJobs.values.forEach { it.cancel() }
        deepenJobs.clear()
    }

    private fun SearchResult.count(mediaType: MediaType): Int = when (mediaType) {
        MediaType.ARTIST -> artists.size
        MediaType.ALBUM -> albums.size
        MediaType.TRACK -> tracks.size
        MediaType.PLAYLIST -> playlists.size
        MediaType.RADIO -> radios.size
        MediaType.AUDIOBOOK -> audiobooks.size
        MediaType.PODCAST -> podcasts.size
        MediaType.GENRE -> genres.size
        else -> 0
    }

    /** Each category from [from] where it holds more than this one does. */
    private fun SearchResult.fillingFrom(from: SearchResult): SearchResult = SearchResult(
        artists = from.artists.takeIf { it.size > artists.size } ?: artists,
        albums = from.albums.takeIf { it.size > albums.size } ?: albums,
        tracks = from.tracks.takeIf { it.size > tracks.size } ?: tracks,
        playlists = from.playlists.takeIf { it.size > playlists.size } ?: playlists,
        radios = from.radios.takeIf { it.size > radios.size } ?: radios,
        audiobooks = from.audiobooks.takeIf { it.size > audiobooks.size } ?: audiobooks,
        podcasts = from.podcasts.takeIf { it.size > podcasts.size } ?: podcasts,
        genres = from.genres.takeIf { it.size > genres.size } ?: genres
    )

    private fun SearchResult.replacing(mediaType: MediaType, from: SearchResult): SearchResult =
        when (mediaType) {
            MediaType.ARTIST -> copy(artists = from.artists)
            MediaType.ALBUM -> copy(albums = from.albums)
            MediaType.TRACK -> copy(tracks = from.tracks)
            MediaType.PLAYLIST -> copy(playlists = from.playlists)
            MediaType.RADIO -> copy(radios = from.radios)
            MediaType.AUDIOBOOK -> copy(audiobooks = from.audiobooks)
            MediaType.PODCAST -> copy(podcasts = from.podcasts)
            MediaType.GENRE -> copy(genres = from.genres)
            else -> this
        }
}
