package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.search.SearchSession
import net.asksakis.massdroidv2.domain.search.SearchSessionController
import javax.inject.Inject

/**
 * The categories the TV search can narrow to. `null` is "everything the server matched",
 * the state the screen opens in; a type asks the server for more of that one category.
 */
enum class SearchFilter(val label: String, val mediaType: MediaType?) {
    ALL("All", null),
    ARTISTS("Artists", MediaType.ARTIST),
    ALBUMS("Albums", MediaType.ALBUM),
    TRACKS("Tracks", MediaType.TRACK),
    PLAYLISTS("Playlists", MediaType.PLAYLIST),
    RADIOS("Radios", MediaType.RADIO),
    AUDIOBOOKS("Audiobooks", MediaType.AUDIOBOOK),
}

/**
 * Search across the server and its providers, ATV style: one text field, filter chips, and
 * the results as a grid of the same cards Browse uses. The search itself is the shared
 * [SearchSessionController], so the TV debounces, deepens and remembers recent searches
 * exactly as the phone does; this class only maps its results to cards and plays them.
 */
@HiltViewModel
class TvSearchViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionEventBus: SessionEventBus,
) : ViewModel() {

    private val searchSession = SearchSessionController(musicRepository, settingsRepository, viewModelScope)

    /** What the screen draws: the query, the results, and whether a search is running. */
    val session: StateFlow<SearchSession> = searchSession.session

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One failure channel for the screen, whether the search or playing a result failed. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    /** The searches worth offering again, newest first; only queries the server matched. */
    val recentSearches: StateFlow<List<String>> = settingsRepository.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The results of the current filter as grid cards. */
    val items: StateFlow<List<BrowseItem>> = combine(session, _filter) { s, f -> s.results.toItems(f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { sessionEventBus.resets.collect { searchSession.reset() } }
        viewModelScope.launch { searchSession.errors.collect { _errors.tryEmit(it) } }
    }

    fun updateQuery(query: String) = searchSession.updateQuery(query)

    fun submitQuery() = searchSession.submitQuery()

    fun searchAgain(query: String) = searchSession.searchAgain(query)

    /** Narrow to one category and fetch more of it, as selecting a chip does on the phone. */
    fun selectFilter(filter: SearchFilter) {
        _filter.value = filter
        filter.mediaType?.let { searchSession.deepen(it) }
    }

    fun play(uri: String) {
        viewModelScope.launch {
            val target = playerRepository.selectedPlayer.value?.playerId
                ?: playerRepository.players.value.firstOrNull()?.playerId
                ?: return@launch
            playerRepository.selectPlayer(target)
            runCatching { musicRepository.playMedia(target, uri, option = "replace") }
                .onFailure { _errors.tryEmit("Couldn't play that") }
        }
    }

    private fun SearchResult.toItems(filter: SearchFilter): List<BrowseItem> {
        val all = filter == SearchFilter.ALL
        fun <T> List<T>.cards(type: SearchFilter, card: (T) -> BrowseItem): List<BrowseItem> =
            if (all || filter == type) map(card) else emptyList()
        // In "All" the subtitle names the category, so a track and the album it is on, which
        // share a title and an artist, can be told apart on the grid.
        fun sub(type: SearchFilter, detail: String? = null): String? {
            val d = detail?.takeIf { it.isNotBlank() }
            return if (all) listOfNotNull(type.label.dropLast(1), d).joinToString(" · ") else d
        }
        return artists.cards(SearchFilter.ARTISTS) {
            BrowseItem(
                it.uri, it.imageUrl, it.name, sub(SearchFilter.ARTISTS), circular = true, isArtist = true,
                itemId = it.itemId, provider = it.provider, key = "artist:" + it.uri
            )
        } + albums.cards(SearchFilter.ALBUMS) {
            BrowseItem(it.uri, it.imageUrl, it.name, sub(SearchFilter.ALBUMS, it.artistNames), key = "album:" + it.uri)
        } + tracks.cards(SearchFilter.TRACKS) {
            BrowseItem(it.uri, it.imageUrl, it.name, sub(SearchFilter.TRACKS, it.artistNames), key = "track:" + it.uri)
        } + playlists.cards(SearchFilter.PLAYLISTS) {
            BrowseItem(it.uri, it.imageUrl, it.name, sub(SearchFilter.PLAYLISTS), key = "playlist:" + it.uri)
        } + radios.cards(SearchFilter.RADIOS) {
            BrowseItem(it.uri, it.imageUrl, it.name, sub(SearchFilter.RADIOS), key = "radio:" + it.uri)
        } + audiobooks.cards(SearchFilter.AUDIOBOOKS) {
            BrowseItem(it.uri, it.imageUrl, it.name, sub(SearchFilter.AUDIOBOOKS, it.artistNames), key = "audiobook:" + it.uri)
        }
    }
}
