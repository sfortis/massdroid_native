package net.asksakis.massdroidv2.tv.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.util.LibraryPager
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.Radio
import net.asksakis.massdroidv2.domain.model.LibraryTabKey
import net.asksakis.massdroidv2.domain.model.SortOption
import net.asksakis.massdroidv2.domain.model.sortOptionFor
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

enum class BrowseCategory(val label: String, val libraryTab: LibraryTabKey) {
    ARTISTS("Artists", LibraryTabKey.ARTISTS),
    ALBUMS("Albums", LibraryTabKey.ALBUMS),
    TRACKS("Tracks", LibraryTabKey.TRACKS),
    PLAYLISTS("Playlists", LibraryTabKey.PLAYLISTS),
    RADIOS("Radios", LibraryTabKey.RADIOS),
    AUDIOBOOKS("Audiobooks", LibraryTabKey.AUDIOBOOKS),
}

/** A library item rendered in the Browse grid, plus how a click should act. */
data class BrowseItem(
    val uri: String,
    val imageUrl: String?,
    val title: String,
    val subtitle: String?,
    val circular: Boolean = false,
    val isArtist: Boolean = false,
    val itemId: String = "",
    val provider: String = "",
)

/**
 * Full server library browse, ATV style: pick a category and scroll a paginated
 * grid of everything in it (no fixed cap). Albums/tracks/playlists play on the
 * selected player; artists drill into their albums. Each category remembers its
 * own sort across restarts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TvBrowseViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val sortPrefs = context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE)

    private val _category = MutableStateFlow(BrowseCategory.ARTISTS)
    val category: StateFlow<BrowseCategory> = _category.asStateFlow()

    // Per-category sort (+direction), persisted so every view keeps its own order across restarts.
    private val _sortOptions = MutableStateFlow(loadSortOptions())
    val sortOption: StateFlow<SortOption> = combine(_category, _sortOptions) { cat, sorts ->
        sorts[cat] ?: SortOption.NAME
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOption.NAME)

    private val _sortDescending = MutableStateFlow(loadSortDescending())
    val sortDescending: StateFlow<Boolean> = combine(_category, _sortDescending) { cat, descs ->
        descs[cat] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val artistsPager = LibraryPager(viewModelScope, { a: Artist -> a.uri }) { l, o ->
        musicRepository.getArtists(limit = l, offset = o, orderBy = orderByFor(BrowseCategory.ARTISTS))
    }
    private val albumsPager = LibraryPager(viewModelScope, { a: Album -> a.uri }) { l, o ->
        musicRepository.getAlbums(limit = l, offset = o, orderBy = orderByFor(BrowseCategory.ALBUMS))
    }
    private val tracksPager = LibraryPager(viewModelScope, { t: Track -> t.uri }) { l, o ->
        musicRepository.getTracks(limit = l, offset = o, orderBy = orderByFor(BrowseCategory.TRACKS))
    }
    private val playlistsPager = LibraryPager(viewModelScope, { p: Playlist -> p.uri }) { l, o ->
        musicRepository.getPlaylists(limit = l, offset = o, orderBy = orderByFor(BrowseCategory.PLAYLISTS))
    }
    private val radiosPager = LibraryPager(viewModelScope, { r: Radio -> r.uri }) { l, o ->
        musicRepository.getRadios(limit = l, offset = o, orderBy = orderByFor(BrowseCategory.RADIOS))
    }
    private val audiobooksPager = LibraryPager(viewModelScope, { t: Track -> t.uri }) { l, o ->
        musicRepository.getAudiobooks(limit = l, offset = o, orderBy = orderByFor(BrowseCategory.AUDIOBOOKS))
    }

    private val artistItems = artistsPager.items.map { list ->
        list.map { BrowseItem(it.uri, it.imageUrl, it.name, null, circular = true, isArtist = true, itemId = it.itemId, provider = it.provider) }
    }
    private val albumItems = albumsPager.items.map { list ->
        list.map { BrowseItem(it.uri, it.imageUrl, it.name, it.artistNames) }
    }
    private val trackItems = tracksPager.items.map { list ->
        list.map { BrowseItem(it.uri, it.imageUrl, it.name, it.artistNames) }
    }
    private val playlistItems = playlistsPager.items.map { list ->
        list.map { BrowseItem(it.uri, it.imageUrl, it.name, null) }
    }
    private val radioItems = radiosPager.items.map { list ->
        list.map { BrowseItem(it.uri, it.imageUrl, it.name, null) }
    }
    private val audiobookItems = audiobooksPager.items.map { list ->
        list.map { BrowseItem(it.uri, it.imageUrl, it.name, it.artistNames.takeIf { n -> n.isNotBlank() }) }
    }

    val items: StateFlow<List<BrowseItem>> = _category
        .flatMapLatest { cat ->
            when (cat) {
                BrowseCategory.ARTISTS -> artistItems
                BrowseCategory.ALBUMS -> albumItems
                BrowseCategory.TRACKS -> trackItems
                BrowseCategory.PLAYLISTS -> playlistItems
                BrowseCategory.RADIOS -> radioItems
                BrowseCategory.AUDIOBOOKS -> audiobookItems
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        artistsPager.loadMore()
    }

    fun selectCategory(cat: BrowseCategory) {
        _category.value = cat
        pagerFor(cat).reloadIfEmpty()
    }

    /** Fetch the next page of the current category as the grid nears its end. */
    fun loadMore() = pagerFor(_category.value).loadMore()

    /**
     * Set + persist the current category's sort and refetch it (pager params are live).
     * Picking a field resets the direction to its natural default: names ascending,
     * time/count fields newest/most first.
     */
    fun setSort(option: SortOption) {
        val cat = _category.value
        val defaultDesc = option.defaultDescending
        if ((_sortOptions.value[cat] ?: SortOption.NAME) == option &&
            (_sortDescending.value[cat] ?: false) == defaultDesc
        ) {
            return
        }
        _sortOptions.value = _sortOptions.value + (cat to option)
        _sortDescending.value = _sortDescending.value + (cat to defaultDesc)
        sortPrefs.edit()
            .putString(sortKey(cat), option.name)
            .putBoolean(descKey(cat), defaultDesc)
            .apply()
        pagerFor(cat).reload()
    }

    fun setSortDescending(descending: Boolean) {
        val cat = _category.value
        if ((_sortDescending.value[cat] ?: false) == descending) return
        _sortDescending.value = _sortDescending.value + (cat to descending)
        sortPrefs.edit().putBoolean(descKey(cat), descending).apply()
        pagerFor(cat).reload()
    }

    fun play(uri: String) {
        viewModelScope.launch {
            val target = playerRepository.selectedPlayer.value?.playerId
                ?: playerRepository.players.value.firstOrNull()?.playerId
                ?: return@launch
            playerRepository.selectPlayer(target)
            runCatching { musicRepository.playMedia(target, uri, option = "replace") }
        }
    }

    private fun pagerFor(cat: BrowseCategory): LibraryPager<*> = when (cat) {
        BrowseCategory.ARTISTS -> artistsPager
        BrowseCategory.ALBUMS -> albumsPager
        BrowseCategory.TRACKS -> tracksPager
        BrowseCategory.PLAYLISTS -> playlistsPager
        BrowseCategory.RADIOS -> radiosPager
        BrowseCategory.AUDIOBOOKS -> audiobooksPager
    }

    private fun loadSortOptions(): Map<BrowseCategory, SortOption> =
        BrowseCategory.entries.associateWith { cat ->
            val stored = sortPrefs.getString(sortKey(cat), null)
                ?.let { name -> SortOption.entries.firstOrNull { it.name == name } }
            sortOptionFor(cat.libraryTab, stored)
        }

    private fun loadSortDescending(): Map<BrowseCategory, Boolean> =
        BrowseCategory.entries.associateWith { cat ->
            sortPrefs.getBoolean(
                descKey(cat),
                (loadSortOptions()[cat] ?: SortOption.NAME).defaultDescending
            )
        }

    private fun sortKey(cat: BrowseCategory) = "browse_sort_" + cat.name.lowercase()
    private fun descKey(cat: BrowseCategory) = "browse_sort_desc_" + cat.name.lowercase()

    private fun orderByFor(cat: BrowseCategory): String {
        val option = sortOptionFor(cat.libraryTab, _sortOptions.value[cat])
        return option.orderBy(descending = _sortDescending.value[cat] ?: false)
    }

    private companion object {
        const val SORT_PREFS = "tv_prefs"
    }
}
