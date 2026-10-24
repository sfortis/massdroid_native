package net.asksakis.massdroidv2.tv.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.SortOption
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * One artist's albums, so the user can pick which to play (artists are not played
 * directly from the home — they drill into their releases first). Plays the chosen
 * album on the currently selected player.
 *
 * The albums come from the provider's discography in one answer, with no play counts or
 * dates added, so they are sorted here and only by what every album carries: name and year.
 * One order serves every artist and is kept across restarts, like the Browse categories.
 */
@HiltViewModel
class TvArtistViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val sortPrefs = context.getSharedPreferences(SORT_PREFS, Context.MODE_PRIVATE)

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])
    private val provider: String = checkNotNull(savedStateHandle["provider"])

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    // The albums as loaded; [albums] is this list in the chosen order.
    private var loadedAlbums: List<Album> = emptyList()
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _sortOption = MutableStateFlow(
        sortPrefs.getString(KEY_SORT, null)
            ?.let { stored -> SORT_OPTIONS.firstOrNull { it.name == stored } }
            ?: SortOption.NAME
    )
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _sortDescending = MutableStateFlow(
        sortPrefs.getBoolean(KEY_SORT_DESC, _sortOption.value.defaultDescending)
    )
    val sortDescending: StateFlow<Boolean> = _sortDescending.asStateFlow()

    // Without it the screen said "No albums" while a large discography was still loading,
    // and for good when the load failed.
    private val _load = MutableStateFlow(TvLoad.LOADING)
    val load: StateFlow<TvLoad> = _load.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { musicRepository.getArtist(itemId, provider) }.getOrNull()
                ?.let { _name.value = it.name }
        }
        loadAlbums()
    }

    /** Load the albums again, after a failure. */
    fun retry() = loadAlbums()

    private fun loadAlbums() {
        _load.value = TvLoad.LOADING
        viewModelScope.launch {
            // Discography (provider catalogue): a library artist's artist_albums(library) is
            // ~empty on MA 2.9+, so the TV artist screen would otherwise show no albums.
            runCatching { musicRepository.getArtistDiscography(itemId, provider) }
                .onSuccess { list ->
                    loadedAlbums = list
                    applySort()
                    _load.value = TvLoad.READY
                }
                .onFailure { _load.value = TvLoad.FAILED }
        }
    }

    /**
     * Picking a field resets the direction to its natural default, as in Browse: names
     * ascending, years newest first.
     */
    fun setSort(option: SortOption) {
        if (_sortOption.value == option && _sortDescending.value == option.defaultDescending) return
        _sortOption.value = option
        _sortDescending.value = option.defaultDescending
        sortPrefs.edit()
            .putString(KEY_SORT, option.name)
            .putBoolean(KEY_SORT_DESC, option.defaultDescending)
            .apply()
        applySort()
    }

    fun setSortDescending(descending: Boolean) {
        if (_sortDescending.value == descending) return
        _sortDescending.value = descending
        sortPrefs.edit().putBoolean(KEY_SORT_DESC, descending).apply()
        applySort()
    }

    private fun applySort() {
        val byName = compareBy<Album> { it.name.lowercase() }
        val comparator = when (_sortOption.value) {
            // Albums without a year go last in either direction, ordered by name.
            SortOption.YEAR -> compareBy<Album> { it.year == null }
                .then(if (_sortDescending.value) compareByDescending { it.year } else compareBy { it.year })
                .then(byName)
            else -> if (_sortDescending.value) byName.reversed() else byName
        }
        _albums.value = loadedAlbums.sortedWith(comparator)
    }

    /** Play an album on the selected player (or the first available one). */
    fun playMedia(uri: String) {
        viewModelScope.launch {
            val target = playerRepository.selectedPlayer.value?.playerId
                ?: playerRepository.players.value.firstOrNull()?.playerId
                ?: return@launch
            playerRepository.selectPlayer(target)
            runCatching { musicRepository.playMedia(target, uri, option = "replace") }
        }
    }

    companion object {
        /** What every album of a discography carries; see the class comment. */
        val SORT_OPTIONS = listOf(SortOption.NAME, SortOption.YEAR)

        // The same file as the Browse sorts.
        private const val SORT_PREFS = "tv_prefs"
        private const val KEY_SORT = "artist_sort"
        private const val KEY_SORT_DESC = "artist_sort_desc"
    }
}
