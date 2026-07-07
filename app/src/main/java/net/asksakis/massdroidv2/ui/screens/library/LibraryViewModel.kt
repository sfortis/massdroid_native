package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import net.asksakis.massdroidv2.data.lastfm.LastFmLibraryEnricher
import net.asksakis.massdroidv2.data.util.LibraryPager
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "LibraryVM"
private const val STATE_CURRENT_TAB = "library_current_tab"

// Page-0 search augmentation thresholds (see the pager construction below).
private const val GENRE_SEARCH_MIN_CHARS = 3
private const val RADIO_SEARCH_MIN_CHARS = 2
private const val RADIO_SEARCH_LIMIT = 25

// Folder playback (Browse): bound the recursive descent so a huge subtree can't hang or flood.
private const val FOLDER_MAX_TRACKS = 500
private const val FOLDER_MAX_FOLDERS = 250
private const val FOLDER_PLAY_TIMEOUT_MS = 30_000L

private fun defaultDisplayModeForTab(tab: Int): LibraryDisplayMode =
    LibraryTabKey.fromIndex(tab)?.defaultDisplayMode ?: LibraryDisplayMode.LIST

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository,
    private val lastFmLibraryEnricher: LastFmLibraryEnricher,
    val providerManifestCache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache,
    private val sessionEventBus: SessionEventBus
) : ViewModel() {

    private val _currentTab = MutableStateFlow(savedStateHandle[STATE_CURRENT_TAB] ?: 0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Provider filter per tab (persisted), so a selection survives restart and stays tab-scoped.
    private val _selectedProvidersMap = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())

    private val _sortOptions = MutableStateFlow<Map<Int, SortOption>>(emptyMap())
    val sortOption: StateFlow<SortOption> = combine(_sortOptions, _currentTab) { opts, tab ->
        opts[tab] ?: SortOption.NAME
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SortOption.NAME)

    private val _sortDescendings = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val sortDescending: StateFlow<Boolean> = combine(_sortDescendings, _currentTab) { descs, tab ->
        descs[tab] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _favoritesOnlyMap = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val favoritesOnly: StateFlow<Boolean> = combine(_favoritesOnlyMap, _currentTab) { favs, tab ->
        favs[tab] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val selectedProviders: StateFlow<Set<String>> = combine(_selectedProvidersMap, _currentTab) { map, tab ->
        map[tab] ?: emptySet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _displayModes = MutableStateFlow<Map<Int, LibraryDisplayMode>>(emptyMap())
    val displayMode: StateFlow<LibraryDisplayMode> = combine(_displayModes, _currentTab) { modes, tab ->
        modes[tab] ?: defaultDisplayModeForTab(tab)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryDisplayMode.GRID)

    // Per-tab display modes (user overrides only; defaults resolved per page). Each pager page must
    // render with its OWN mode so swiping doesn't briefly show the wrong layout then flip on settle.
    val displayModesByTab: StateFlow<Map<Int, LibraryDisplayMode>> = _displayModes.asStateFlow()

    val players = playerRepository.players
    val connectionState = wsClient.connectionState

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()
    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()

    private var searchJob: Job? = null
    private var pendingReload = false

    // ---- per-tab fetch parameters -------------------------------------------------------------

    private val currentSearch: String? get() = _searchQuery.value.ifBlank { null }

    /** Search applies only to the tab the user is on; preloads of other tabs fetch unfiltered. */
    private fun searchFor(tab: LibraryTabKey): String? =
        if (_currentTab.value == tab.index) currentSearch else null

    private fun orderByForTab(tab: Int): String {
        val base = (_sortOptions.value[tab] ?: SortOption.NAME).apiValue
        return if (_sortDescendings.value[tab] == true) "${base}_desc" else base
    }

    private fun favoriteOnlyForTab(tab: Int): Boolean = _favoritesOnlyMap.value[tab] ?: false

    // MA's library_items `provider` filter matches the provider INSTANCE id (e.g. deezer--GWnPbDSt),
    // not the bare domain. Sending the domain returns zero results, so the server filter must carry
    // the selected instance ids.
    private fun providerFilterFor(tab: LibraryTabKey): List<String>? =
        selectedProviderInstanceIds(tab.index).takeIf { it.isNotEmpty() }

    private fun selectedProviderInstanceIds(tab: Int): List<String> {
        val selected = _selectedProvidersMap.value[tab] ?: emptySet()
        if (selected.isEmpty()) return emptyList()
        val providersForTab = providerManifestCache.musicProvidersForTab(tab)
        return selected
            .mapNotNull { selectedId ->
                providersForTab.firstOrNull { it.instanceId == selectedId }?.instanceId
                    ?: providersForTab.firstOrNull { it.domain == selectedId }?.instanceId
            }
            .distinct()
    }

    private fun selectedProviderDomains(tab: Int): List<String> {
        val selected = _selectedProvidersMap.value[tab] ?: emptySet()
        if (selected.isEmpty()) return emptyList()
        val providersForTab = providerManifestCache.musicProvidersForTab(tab)
        return selected
            .mapNotNull { selectedId ->
                providersForTab.firstOrNull { it.instanceId == selectedId }?.domain
                    ?: providersForTab.firstOrNull { it.domain == selectedId }?.domain
            }
            .distinct()
    }

    /** Client-side safety net on top of the server instance-id filter. */
    private fun <T> filterBySelectedProviders(
        items: List<T>,
        tab: LibraryTabKey,
        providerDomains: (T) -> List<String>
    ): List<T> {
        val selectedDomains = selectedProviderDomains(tab.index).toSet()
        if (selectedDomains.isEmpty()) return items
        return items.filter { item -> providerDomains(item).any { it in selectedDomains } }
    }

    private fun parseMediaUri(uri: String): Pair<String, String>? {
        val sep = uri.indexOf("://")
        if (sep < 0) return null
        val provider = uri.substring(0, sep)
        val itemId = uri.substringAfterLast("/")
        return if (provider.isNotBlank() && itemId.isNotBlank()) provider to itemId else null
    }

    // ---- page-0 search augmentation -----------------------------------------------------------

    /**
     * When the user searches the library, also surface items whose ARTIST matches the query by
     * genre association (Last.fm-backed), merged after the API results. [excludeArtistUris] skips
     * resolution for artists already present (Artists tab); the Albums/Tracks tabs cannot pre-skip
     * because the genre match is the artist URI, not the row item.
     */
    private suspend fun <T> augmentWithGenreMatches(
        query: String?,
        apiResults: List<T>,
        excludeArtistUris: Set<String>,
        uriOf: (T) -> String,
        resolveArtistItems: suspend (provider: String, itemId: String) -> List<T>
    ): List<T> {
        if (query == null || query.length < GENRE_SEARCH_MIN_CHARS) return apiResults
        val genreUris = runCatching { genreRepository.searchArtistUris(query) }
            .getOrElse { emptyList() }
            .filterNot { it in excludeArtistUris }
        if (genreUris.isEmpty()) return apiResults
        val extras = supervisorScope {
            genreUris.map { uri ->
                async {
                    runCatching {
                        parseMediaUri(uri)?.let { (prov, id) -> resolveArtistItems(prov, id) }
                    }.getOrNull().orEmpty()
                }
            }.awaitAll().flatten()
        }
        val seen = apiResults.mapTo(HashSet(), uriOf)
        return apiResults + extras.filter { seen.add(uriOf(it)) }
    }

    /** Radios search also offers non-library stations, flagged by name-match against the library. */
    private suspend fun mergeRadioSearchResults(query: String?, apiResults: List<Radio>): List<Radio> {
        if (query == null || query.length < RADIO_SEARCH_MIN_CHARS) return apiResults
        val searchResults = runCatching {
            musicRepository.search(query, mediaTypes = listOf(MediaType.RADIO), limit = RADIO_SEARCH_LIMIT).radios
        }.getOrElse { emptyList() }
        if (searchResults.isEmpty()) return apiResults
        val seenUris = apiResults.mapTo(HashSet()) { it.uri }
        val libraryNames = apiResults.mapTo(HashSet()) { it.name.lowercase() }
        return apiResults + searchResults
            .filter { seenUris.add(it.uri) }
            .map { it.copy(inLibrary = it.name.lowercase() in libraryNames) }
    }

    // ---- tab pagers -----------------------------------------------------------------------------

    private val artistsPager = LibraryPager(
        scope = viewModelScope,
        key = { it: Artist -> it.uri },
        augmentFirstPage = { api ->
            augmentWithGenreMatches(
                query = searchFor(LibraryTabKey.ARTISTS),
                apiResults = api,
                excludeArtistUris = api.mapTo(HashSet()) { it.uri },
                uriOf = { it.uri }
            ) { provider, itemId -> listOfNotNull(musicRepository.getArtist(itemId, provider)) }
        },
        transformPage = { filterBySelectedProviders(it, LibraryTabKey.ARTISTS) { a -> a.providerDomains } },
        onPageLoaded = { lastFmLibraryEnricher.enrichInBackground(it) }
    ) { limit, offset ->
        musicRepository.getArtists(
            search = searchFor(LibraryTabKey.ARTISTS), limit = limit, offset = offset,
            orderBy = orderByForTab(LibraryTabKey.ARTISTS.index),
            favoriteOnly = favoriteOnlyForTab(LibraryTabKey.ARTISTS.index),
            providerFilter = providerFilterFor(LibraryTabKey.ARTISTS)
        )
    }

    private val albumsPager = LibraryPager(
        scope = viewModelScope,
        key = { it: Album -> it.uri },
        augmentFirstPage = { api ->
            augmentWithGenreMatches(
                query = searchFor(LibraryTabKey.ALBUMS),
                apiResults = api,
                excludeArtistUris = emptySet(),
                uriOf = { it.uri }
            ) { provider, itemId ->
                musicRepository.getArtistAlbums(itemId, provider).filter { it.uri.startsWith("library://") }
            }
        },
        transformPage = { filterBySelectedProviders(it, LibraryTabKey.ALBUMS) { a -> a.providerDomains } }
    ) { limit, offset ->
        musicRepository.getAlbums(
            search = searchFor(LibraryTabKey.ALBUMS), limit = limit, offset = offset,
            orderBy = orderByForTab(LibraryTabKey.ALBUMS.index),
            favoriteOnly = favoriteOnlyForTab(LibraryTabKey.ALBUMS.index),
            providerFilter = providerFilterFor(LibraryTabKey.ALBUMS)
        )
    }

    private val tracksPager = LibraryPager(
        scope = viewModelScope,
        key = { it: Track -> it.uri },
        augmentFirstPage = { api ->
            augmentWithGenreMatches(
                query = searchFor(LibraryTabKey.TRACKS),
                apiResults = api,
                excludeArtistUris = emptySet(),
                uriOf = { it.uri }
            ) { provider, itemId ->
                musicRepository.getArtistTracks(itemId, provider).filter { it.uri.startsWith("library://") }
            }
        },
        transformPage = { filterBySelectedProviders(it, LibraryTabKey.TRACKS) { t -> t.providerDomains } }
    ) { limit, offset ->
        musicRepository.getTracks(
            search = searchFor(LibraryTabKey.TRACKS), limit = limit, offset = offset,
            orderBy = orderByForTab(LibraryTabKey.TRACKS.index),
            favoriteOnly = favoriteOnlyForTab(LibraryTabKey.TRACKS.index),
            providerFilter = providerFilterFor(LibraryTabKey.TRACKS)
        )
    }

    private val playlistsPager = LibraryPager(
        scope = viewModelScope,
        key = { it: Playlist -> it.uri },
        transformPage = { filterBySelectedProviders(it, LibraryTabKey.PLAYLISTS) { p -> p.providerDomains } }
    ) { limit, offset ->
        musicRepository.getPlaylists(
            search = searchFor(LibraryTabKey.PLAYLISTS), limit = limit, offset = offset,
            orderBy = orderByForTab(LibraryTabKey.PLAYLISTS.index),
            favoriteOnly = favoriteOnlyForTab(LibraryTabKey.PLAYLISTS.index),
            providerFilter = providerFilterFor(LibraryTabKey.PLAYLISTS)
        )
    }

    private val radiosPager = LibraryPager(
        scope = viewModelScope,
        key = { it: Radio -> it.uri },
        augmentFirstPage = { api -> mergeRadioSearchResults(searchFor(LibraryTabKey.RADIOS), api) },
        transformPage = { filterBySelectedProviders(it, LibraryTabKey.RADIOS) { r -> r.providerDomains } }
    ) { limit, offset ->
        musicRepository.getRadios(
            search = searchFor(LibraryTabKey.RADIOS), limit = limit, offset = offset,
            orderBy = orderByForTab(LibraryTabKey.RADIOS.index),
            favoriteOnly = favoriteOnlyForTab(LibraryTabKey.RADIOS.index),
            providerFilter = providerFilterFor(LibraryTabKey.RADIOS)
        )
    }

    // Audiobooks are modeled as Track (single playable item; chapters arrive on play).
    private val audiobooksPager = LibraryPager(
        scope = viewModelScope,
        key = { it: Track -> it.uri },
        transformPage = { filterBySelectedProviders(it, LibraryTabKey.AUDIOBOOKS) { t -> t.providerDomains } }
    ) { limit, offset ->
        musicRepository.getAudiobooks(
            search = searchFor(LibraryTabKey.AUDIOBOOKS), limit = limit, offset = offset,
            orderBy = orderByForTab(LibraryTabKey.AUDIOBOOKS.index),
            favoriteOnly = favoriteOnlyForTab(LibraryTabKey.AUDIOBOOKS.index),
            providerFilter = providerFilterFor(LibraryTabKey.AUDIOBOOKS)
        )
    }

    private fun pagerFor(tab: LibraryTabKey): LibraryPager<*>? = when (tab) {
        LibraryTabKey.ARTISTS -> artistsPager
        LibraryTabKey.ALBUMS -> albumsPager
        LibraryTabKey.TRACKS -> tracksPager
        LibraryTabKey.PLAYLISTS -> playlistsPager
        LibraryTabKey.RADIOS -> radiosPager
        LibraryTabKey.AUDIOBOOKS -> audiobooksPager
        LibraryTabKey.BROWSE -> null // Browse is a folder tree, not a paged list.
    }

    private fun pagerForIndex(index: Int): LibraryPager<*>? =
        LibraryTabKey.fromIndex(index)?.let { pagerFor(it) }

    // Derived from pagerFor so a future tab can't be added to the dispatch but missed here
    // (resetForAccountSwitch would then leak that tab's list across an account switch).
    private val allPagers: List<LibraryPager<*>>
        get() = LibraryTabKey.entries.mapNotNull { pagerFor(it) }

    val artists: StateFlow<List<Artist>> = artistsPager.items
    val albums: StateFlow<List<Album>> = albumsPager.items
    val tracks: StateFlow<List<Track>> = tracksPager.items
    val playlists: StateFlow<List<Playlist>> = playlistsPager.items
    val radios: StateFlow<List<Radio>> = radiosPager.items
    val audiobooks: StateFlow<List<Track>> = audiobooksPager.items

    // ---- browse (folder tree) state ----------------------------------------------------------

    private val _browseItemsRaw = MutableStateFlow<List<BrowseItem>>(emptyList())
    private val _browseItems = MutableStateFlow<List<BrowseItem>>(emptyList())
    val browseItems: StateFlow<List<BrowseItem>> = _browseItems.asStateFlow()
    private val _browsePath = MutableStateFlow<String?>(null)
    val browsePath: StateFlow<String?> = _browsePath.asStateFlow()
    private val browsePathStack = mutableListOf<String?>()
    private val _browseLoading = MutableStateFlow(false)

    // ---- loading state (derived from the active tab's pager + browse) -------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val isLoading: StateFlow<Boolean> = _currentTab
        .flatMapLatest { tab ->
            val pagerLoading = pagerForIndex(tab)?.loading
            if (pagerLoading != null) {
                combine(pagerLoading, _browseLoading) { pager, browse -> pager || browse }
            } else {
                _browseLoading
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isLoadingMore: StateFlow<Boolean> = _currentTab
        .flatMapLatest { tab -> pagerForIndex(tab)?.loadingMore ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ---- playlist management state (declared before init: collectors may run synchronously) ----

    private val _editablePlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val editablePlaylists: StateFlow<List<Playlist>> = _editablePlaylists.asStateFlow()
    private val _isLoadingEditablePlaylists = MutableStateFlow(false)
    val isLoadingEditablePlaylists: StateFlow<Boolean> = _isLoadingEditablePlaylists.asStateFlow()
    private val _addingToPlaylistId = MutableStateFlow<String?>(null)
    val addingToPlaylistId: StateFlow<String?> = _addingToPlaylistId.asStateFlow()
    private val _playlistContainsTrack = MutableStateFlow<Set<String>>(emptySet())
    val playlistContainsTrack: StateFlow<Set<String>> = _playlistContainsTrack.asStateFlow()

    init {
        // Load all settings eagerly before any data loading
        viewModelScope.launch {
            _displayModes.value = settingsRepository.libraryDisplayModes.first()
                .mapKeys { it.key.index }
            _sortOptions.value = settingsRepository.librarySortOptions.first()
            _sortDescendings.value = settingsRepository.librarySortDescending.first()
            _favoritesOnlyMap.value = settingsRepository.libraryFavoritesOnly.first()
            _selectedProvidersMap.value = settingsRepository.libraryProviderFilters.first()
            _settingsLoaded.value = true
        }
        // Keep syncing after initial load
        viewModelScope.launch {
            settingsRepository.libraryDisplayModes.collect { _displayModes.value = it.mapKeys { entry -> entry.key.index } }
        }
        viewModelScope.launch {
            settingsRepository.librarySortOptions.collect { _sortOptions.value = it }
        }
        viewModelScope.launch {
            settingsRepository.librarySortDescending.collect { _sortDescendings.value = it }
        }
        viewModelScope.launch {
            settingsRepository.libraryFavoritesOnly.collect { _favoritesOnlyMap.value = it }
        }
        viewModelScope.launch {
            smartListeningRepository.blockedArtistUris.collect { _blockedArtistUris.value = it }
        }
        viewModelScope.launch {
            wsClient.connectionState
                .collect { state ->
                    if (state is ConnectionState.Connected && _settingsLoaded.value) {
                        val currentEmpty =
                            pagerForIndex(_currentTab.value)?.items?.value?.isEmpty() == true
                        if (currentEmpty) reloadCurrentTab()
                    }
                }
        }
        viewModelScope.launch {
            wsClient.events.collect { event ->
                when (event.event) {
                    EventType.MEDIA_ITEM_ADDED,
                    EventType.MEDIA_ITEM_DELETED -> {
                        pendingReload = true
                    }
                    EventType.MEDIA_ITEM_UPDATED -> {
                        // Handled below via mediaItemUpdates: patched in place (stable URI keys),
                        // never a structural reload, so no scroll reset.
                    }
                }
            }
        }
        // Metadata refreshes (e.g. artwork fetched when a detail screen triggers a server-side
        // lookup) reach already-loaded lists by swapping the matching row in place.
        viewModelScope.launch {
            musicRepository.mediaItemUpdates.collect { applyMediaItemUpdate(it) }
        }
        viewModelScope.launch {
            sessionEventBus.resets.collect { resetForAccountSwitch() }
        }
    }

    private fun applyMediaItemUpdate(update: MediaItemUpdate) {
        when (update) {
            is MediaItemUpdate.ArtistUpdated ->
                artistsPager.update { replaceByUri(it, update.item) { a -> a.uri } }
            is MediaItemUpdate.AlbumUpdated ->
                albumsPager.update { replaceByUri(it, update.item) { a -> a.uri } }
            is MediaItemUpdate.TrackUpdated ->
                tracksPager.update { replaceByUri(it, update.item) { t -> t.uri } }
            is MediaItemUpdate.AudiobookUpdated ->
                audiobooksPager.update { replaceByUri(it, update.item) { t -> t.uri } }
            is MediaItemUpdate.PlaylistUpdated ->
                playlistsPager.update { replaceByUri(it, update.item) { p -> p.uri } }
            is MediaItemUpdate.RadioUpdated ->
                // The server payload has no library-membership flag (toRadio defaults inLibrary
                // to true), so keep the flag of the row being replaced: a search-merged
                // non-library station must not flip to inLibrary=true on a metadata event.
                radiosPager.update { list ->
                    list.map { if (it.uri == update.item.uri) update.item.copy(inLibrary = it.inLibrary) else it }
                }
        }
    }

    /** Same list instance when the URI isn't present, so unrelated events don't recompose. */
    private fun <T> replaceByUri(list: List<T>, item: T, uriOf: (T) -> String): List<T> {
        val uri = uriOf(item)
        val idx = list.indexOfFirst { uriOf(it) == uri }
        if (idx < 0) return list
        return list.toMutableList().also { it[idx] = item }
    }

    fun onScreenVisible() {
        if (pendingReload) {
            pendingReload = false
            reloadCurrentTab()
        }
    }

    private fun resetForAccountSwitch() {
        Log.d(TAG, "Session reset: dropping cached library lists")
        searchJob?.cancel()
        _searchQuery.value = ""
        allPagers.forEach { it.clear() }
        _editablePlaylists.value = emptyList()
        _playlistContainsTrack.value = emptySet()
        _browseItemsRaw.value = emptyList()
        _browseItems.value = emptyList()
        _browsePath.value = null
        browsePathStack.clear()
        _selectedProvidersMap.value = emptyMap()
        pendingReload = false
        _browseLoading.value = false
        _isRefreshing.value = false
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                musicRepository.requestLibrarySync(force = true)
                reloadCurrentTab()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun setCurrentTab(tab: Int) {
        if (_currentTab.value != tab) {
            _searchQuery.value = ""
            searchJob?.cancel()
        }
        _currentTab.value = tab
        savedStateHandle[STATE_CURRENT_TAB] = tab
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            reloadCurrentTab()
        }
    }

    fun updateSort(option: SortOption) {
        val tab = _currentTab.value
        _sortOptions.value = _sortOptions.value + (tab to option)
        val defaultDesc = option != SortOption.NAME
        _sortDescendings.value = _sortDescendings.value + (tab to defaultDesc)
        viewModelScope.launch {
            settingsRepository.setLibrarySortOption(tab, option)
            settingsRepository.setLibrarySortDescending(tab, defaultDesc)
        }
        reloadCurrentTab()
    }

    fun toggleSortDirection() {
        val tab = _currentTab.value
        val newVal = _sortDescendings.value[tab] != true
        _sortDescendings.value = _sortDescendings.value + (tab to newVal)
        viewModelScope.launch { settingsRepository.setLibrarySortDescending(tab, newVal) }
        reloadCurrentTab()
    }

    fun toggleFavoritesFilter() {
        val tab = _currentTab.value
        val newVal = _favoritesOnlyMap.value[tab] != true
        _favoritesOnlyMap.value = _favoritesOnlyMap.value + (tab to newVal)
        viewModelScope.launch { settingsRepository.setLibraryFavoritesOnly(tab, newVal) }
        reloadCurrentTab()
    }

    fun toggleProviderFilter(instanceId: String) {
        val tab = _currentTab.value
        val current = _selectedProvidersMap.value[tab] ?: emptySet()
        val updated = if (instanceId in current) current - instanceId else current + instanceId
        _selectedProvidersMap.value = _selectedProvidersMap.value + (tab to updated)
        viewModelScope.launch { settingsRepository.setLibraryProviderFilters(tab, updated) }
        reloadCurrentTab()
    }

    fun clearProviderFilter() {
        val tab = _currentTab.value
        _selectedProvidersMap.value = _selectedProvidersMap.value + (tab to emptySet())
        viewModelScope.launch { settingsRepository.setLibraryProviderFilters(tab, emptySet()) }
        reloadCurrentTab()
    }

    fun toggleLibraryDisplayMode() {
        val tab = _currentTab.value
        val current = _displayModes.value[tab] ?: defaultDisplayModeForTab(tab)
        val newMode = when (current) {
            LibraryDisplayMode.LIST -> LibraryDisplayMode.GRID
            LibraryDisplayMode.GRID -> LibraryDisplayMode.LIST
        }
        _displayModes.value = _displayModes.value + (tab to newMode)
        viewModelScope.launch {
            LibraryTabKey.fromIndex(tab)?.let { tabKey ->
                settingsRepository.setLibraryDisplayMode(tabKey, newMode)
            }
        }
    }

    private fun reloadCurrentTab() {
        val tab = LibraryTabKey.fromIndex(_currentTab.value) ?: return
        if (tab == LibraryTabKey.BROWSE) applyBrowseFilterSort() else pagerFor(tab)?.reload()
    }

    // The screen calls these only when a tab is still empty (on-settle LaunchedEffect), so they
    // route through reloadIfEmpty: an in-flight neighbour preload is adopted instead of being
    // cancelled and refetched. Forced reloads (search/sort/filter/refresh) go through
    // reloadCurrentTab -> pager.reload().
    fun loadArtists() = artistsPager.reloadIfEmpty()
    fun loadMoreArtists() = artistsPager.loadMore()
    fun loadAlbums() = albumsPager.reloadIfEmpty()
    fun loadMoreAlbums() = albumsPager.loadMore()
    fun loadTracks() = tracksPager.reloadIfEmpty()
    fun loadMoreTracks() = tracksPager.loadMore()
    fun loadPlaylists() = playlistsPager.reloadIfEmpty()
    fun loadMorePlaylists() = playlistsPager.loadMore()
    fun loadRadios() = radiosPager.reloadIfEmpty()
    fun loadMoreRadios() = radiosPager.loadMore()
    fun loadAudiobooks() = audiobooksPager.reloadIfEmpty()
    fun loadMoreAudiobooks() = audiobooksPager.loadMore()

    /**
     * Warm the tabs on either side of [tab] so a swipe lands on populated content instead of an
     * empty page that fills in after settling. Fetches only an empty neighbour's first page, with
     * that tab's own sort/favourite/provider params and no search (the query belongs to the
     * active tab). The active-tab spinner is unaffected: [isLoading] only mirrors the CURRENT
     * tab's pager.
     */
    fun preloadAdjacentTabs(tab: Int) {
        pagerForIndex(tab - 1)?.reloadIfEmpty()
        pagerForIndex(tab + 1)?.reloadIfEmpty()
    }

    fun loadBrowse(path: String? = null) {
        viewModelScope.launch {
            _browseLoading.value = true
            try {
                when {
                    path == "genres://" -> {
                        val genres = genreRepository.libraryGenres()
                        _browseItemsRaw.value = genres.map { genre ->
                            BrowseItem(
                                itemId = genre, provider = "local", name = genre.replaceFirstChar { it.uppercase() },
                                uri = "genres://$genre", path = "genres://$genre", isFolder = true
                            )
                        }
                    }
                    path != null && path.startsWith("genres://") -> {
                        val genre = path.removePrefix("genres://")
                        val artists = genreRepository.libraryArtistsForGenre(genre)
                        _browseItemsRaw.value = artists.map { a ->
                            BrowseItem(
                                itemId = a.itemId, provider = a.provider, name = a.name,
                                uri = a.uri, mediaType = "artist"
                            )
                        }
                    }
                    else -> {
                        val serverItems = musicRepository.browse(path)
                            .filter { it.name != ".." }
                        _browseItemsRaw.value = if (path == null) {
                            val genresFolder = BrowseItem(
                                itemId = "genres", provider = "local", name = "Genres",
                                uri = "genres://", path = "genres://", isFolder = true
                            )
                            listOf(genresFolder) + serverItems
                        } else {
                            serverItems
                        }
                    }
                }
                _browsePath.value = path
                applyBrowseFilterSort()
            } catch (_: Exception) {}
            _browseLoading.value = false
        }
    }

    private fun applyBrowseFilterSort() {
        var items = _browseItemsRaw.value
        val query = _searchQuery.value.trim()
        if (query.isNotEmpty()) {
            items = items.filter { it.name.contains(query, ignoreCase = true) }
        }
        val sort = sortOption.value
        if (sort == SortOption.NAME) {
            items = if (sortDescending.value) items.sortedByDescending { it.name.lowercase() }
                    else items.sortedBy { it.name.lowercase() }
        }
        _browseItems.value = items
    }

    fun browseTo(path: String) {
        browsePathStack.add(_browsePath.value)
        loadBrowse(path)
    }

    fun browseBack(): Boolean {
        if (browsePathStack.isEmpty()) return false
        val prev = browsePathStack.removeAt(browsePathStack.lastIndex)
        loadBrowse(prev)
        return true
    }

    /**
     * Play (or enqueue) every playable item under a Browse folder, e.g. a whole album or artist
     * folder on the filesystem. Walks the subtree breadth-first so tracks come out in folder order,
     * bounded by [FOLDER_MAX_TRACKS]/[FOLDER_MAX_FOLDERS] so a large tree can't hang the UI.
     */
    fun playBrowseFolder(folder: BrowseItem, enqueue: Boolean = false) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        val rootPath = folder.path ?: folder.uri
        viewModelScope.launch {
            _browseLoading.value = true
            try {
                val uris = collectFolderTrackUris(rootPath)
                if (uris.isEmpty()) {
                    _error.tryEmit("No playable tracks in this folder")
                    return@launch
                }
                if (!enqueue) {
                    playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                }
                musicRepository.playMedia(
                    queueId, uris, option = if (enqueue) "add" else "replace", timeoutMs = FOLDER_PLAY_TIMEOUT_MS
                )
            } catch (e: Exception) {
                Log.w(TAG, "playBrowseFolder failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            } finally {
                _browseLoading.value = false
            }
        }
    }

    private suspend fun collectFolderTrackUris(rootPath: String): List<String> {
        val collected = mutableListOf<String>()
        val pending = ArrayDeque<String>().apply { add(rootPath) }
        var foldersVisited = 0
        while (pending.isNotEmpty() && collected.size < FOLDER_MAX_TRACKS && foldersVisited < FOLDER_MAX_FOLDERS) {
            val current = pending.removeFirst()
            foldersVisited++
            val children = runCatching { musicRepository.browse(current) }
                .getOrElse { Log.w(TAG, "browse failed for folder play ($current): ${it.message}"); emptyList() }
                .filter { it.name != ".." }
            for (child in children) {
                if (child.isFolder) {
                    pending.add(child.path ?: child.uri)
                } else if (child.uri.isNotBlank()) {
                    collected.add(child.uri)
                    if (collected.size >= FOLDER_MAX_TRACKS) break
                }
            }
        }
        return collected
    }

    // ---- playback actions ----------------------------------------------------------------------

    fun playTrack(track: Track) {
        playUri(track.uri)
    }

    fun playAlbum(album: Album) {
        playUri(album.uri)
    }

    fun playPlaylist(playlist: Playlist) {
        playUri(playlist.uri)
    }

    fun quickPlay(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uri, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "quickPlay failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun playUri(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uri)
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

    fun addTrackToQueue(track: Track) {
        enqueue(track.uri)
    }

    // ---- playlist management -------------------------------------------------------------------

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            try {
                val playlist = musicRepository.createPlaylist(name)
                playlistsPager.update { it + playlist }
                _editablePlaylists.value = _editablePlaylists.value + playlist
            } catch (e: Exception) {
                Log.w(TAG, "createPlaylist failed: ${e.message}")
                _error.tryEmit("Failed to create playlist")
            }
        }
    }

    fun loadEditablePlaylists(trackUri: String) {
        if (_isLoadingEditablePlaylists.value) return
        viewModelScope.launch {
            _isLoadingEditablePlaylists.value = true
            try {
                val loaded = musicRepository.getPlaylists(limit = 200).filter { it.isEditable }
                _editablePlaylists.value = loaded
                checkTrackInPlaylists(trackUri, loaded)
            } catch (e: Exception) {
                Log.w(TAG, "loadEditablePlaylists failed: ${e.message}")
            } finally {
                _isLoadingEditablePlaylists.value = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun checkTrackInPlaylists(trackUri: String, playlists: List<Playlist>) {
        val containing = mutableSetOf<String>()
        for (playlist in playlists) {
            try {
                val tracks = musicRepository.getPlaylistTracks(playlist.itemId, playlist.provider)
                if (tracks.any { it.uri == trackUri }) containing += playlist.uri
            } catch (_: Exception) { }
        }
        _playlistContainsTrack.value = containing
    }

    fun addTrackToPlaylist(playlist: Playlist, trackUri: String) {
        if (_addingToPlaylistId.value != null) return
        viewModelScope.launch {
            _addingToPlaylistId.value = playlist.itemId
            try {
                musicRepository.addTrackToPlaylist(playlist, trackUri)
                _playlistContainsTrack.value = _playlistContainsTrack.value + playlist.uri
            } catch (e: Exception) {
                Log.w(TAG, "addTrackToPlaylist failed: ${e.message}")
                _error.tryEmit("Failed to add track to playlist")
            } finally {
                _addingToPlaylistId.value = null
            }
        }
    }

    fun removeTrackFromPlaylist(playlist: Playlist, trackUri: String) {
        if (_addingToPlaylistId.value != null) return
        viewModelScope.launch {
            _addingToPlaylistId.value = playlist.itemId
            try {
                val tracks = musicRepository.getPlaylistTracks(playlist.itemId, playlist.provider)
                val position = tracks.indexOfFirst { it.uri == trackUri }
                if (position >= 0) {
                    musicRepository.removeTrackFromPlaylist(playlist, position)
                    _playlistContainsTrack.value = _playlistContainsTrack.value - playlist.uri
                }
            } catch (e: Exception) {
                Log.w(TAG, "removeTrackFromPlaylist failed: ${e.message}")
                _error.tryEmit("Failed to remove track from playlist")
            } finally {
                _addingToPlaylistId.value = null
            }
        }
    }

    fun createPlaylistAndAddTrack(name: String, trackUri: String) {
        viewModelScope.launch {
            try {
                val playlist = musicRepository.createPlaylist(name)
                musicRepository.addTrackToPlaylist(playlist, trackUri)
                playlistsPager.update { it + playlist }
                _editablePlaylists.value = _editablePlaylists.value + playlist
                _playlistContainsTrack.value = _playlistContainsTrack.value + playlist.uri
            } catch (e: Exception) {
                Log.w(TAG, "createPlaylistAndAddTrack failed: ${e.message}")
                _error.tryEmit("Failed to create playlist")
            }
        }
    }

    // ---- item state toggles ----------------------------------------------------------------------

    fun toggleFavorite(uri: String, mediaType: MediaType, itemId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(uri, mediaType, itemId, !currentFavorite)
                when (mediaType) {
                    MediaType.ARTIST -> artistsPager.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.ALBUM -> albumsPager.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.TRACK -> tracksPager.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.PLAYLIST -> playlistsPager.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.RADIO -> radiosPager.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.AUDIOBOOK -> audiobooksPager.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
            }
        }
    }

    fun removeFromLibrary(mediaType: MediaType, itemId: String, uri: String) {
        viewModelScope.launch {
            try {
                musicRepository.removeFromLibrary(mediaType, uri, itemId)
                when (mediaType) {
                    MediaType.ARTIST -> artistsPager.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.ALBUM -> albumsPager.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.TRACK -> tracksPager.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.PLAYLIST -> playlistsPager.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.RADIO -> radiosPager.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.AUDIOBOOK -> audiobooksPager.update { list -> list.filter { it.itemId != itemId } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "removeFromLibrary failed: ${e.message}")
            }
        }
    }

    fun addRadioToLibrary(radio: Radio) {
        viewModelScope.launch {
            try {
                musicRepository.addToLibrary(radio.uri)
                radiosPager.update { list ->
                    list.map { if (it.uri == radio.uri) it.copy(inLibrary = true) else it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "addRadioToLibrary failed: ${e.message}")
            }
        }
    }

    fun toggleArtistBlocked(artistUri: String?, artistName: String?) {
        val uri = MediaIdentity.canonicalArtistKey(uri = artistUri) ?: return
        viewModelScope.launch {
            val blocked = _blockedArtistUris.value.contains(uri)
            smartListeningRepository.setArtistBlocked(uri, artistName, blocked = !blocked)
        }
    }
}
