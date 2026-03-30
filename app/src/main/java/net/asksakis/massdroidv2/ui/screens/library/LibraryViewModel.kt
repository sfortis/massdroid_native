package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.lastfm.LastFmAlbumInfoResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmArtistInfoResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmLibraryEnricher
import net.asksakis.massdroidv2.data.lastfm.LastFmSimilarResolver
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "LibraryVM"
private const val PAGE_SIZE = 50

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val playHistoryRepository: PlayHistoryRepository,
    private val genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository,
    private val lastFmLibraryEnricher: LastFmLibraryEnricher,
    val providerManifestCache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache
) : ViewModel() {

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _radios = MutableStateFlow<List<Radio>>(emptyList())
    val radios: StateFlow<List<Radio>> = _radios.asStateFlow()

    private val _editablePlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val editablePlaylists: StateFlow<List<Playlist>> = _editablePlaylists.asStateFlow()
    private val _isLoadingEditablePlaylists = MutableStateFlow(false)
    val isLoadingEditablePlaylists: StateFlow<Boolean> = _isLoadingEditablePlaylists.asStateFlow()
    private val _addingToPlaylistId = MutableStateFlow<String?>(null)
    val addingToPlaylistId: StateFlow<String?> = _addingToPlaylistId.asStateFlow()
    private val _playlistContainsTrack = MutableStateFlow<Set<String>>(emptySet())
    val playlistContainsTrack: StateFlow<Set<String>> = _playlistContainsTrack.asStateFlow()

    private val _browseItemsRaw = MutableStateFlow<List<BrowseItem>>(emptyList())
    private val _browseItems = MutableStateFlow<List<BrowseItem>>(emptyList())
    val browseItems: StateFlow<List<BrowseItem>> = _browseItems.asStateFlow()
    private val _browsePath = MutableStateFlow<String?>(null)
    val browsePath: StateFlow<String?> = _browsePath.asStateFlow()
    private val browsePathStack = mutableListOf<String?>()

    private val _selectedProviders = MutableStateFlow<Set<String>>(emptySet())
    val selectedProviders: StateFlow<Set<String>> = _selectedProviders.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val connectionState = wsClient.connectionState
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var searchJob: Job? = null
    private var mediaEventJob: Job? = null
    private var pendingReload = false

    fun onScreenVisible() {
        if (pendingReload) {
            pendingReload = false
            reloadCurrentTab()
        }
    }
    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab.asStateFlow()

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

    private val _displayModes = MutableStateFlow<Map<Int, LibraryDisplayMode>>(emptyMap())
    val displayMode: StateFlow<LibraryDisplayMode> = combine(_displayModes, _currentTab) { modes, tab ->
        modes[tab] ?: if (tab <= 1) LibraryDisplayMode.GRID else LibraryDisplayMode.LIST
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryDisplayMode.GRID)

    val players = playerRepository.players

    private var hasMoreArtists = true
    private var hasMoreAlbums = true
    private var hasMoreTracks = true
    private var hasMorePlaylists = true
    private var hasMoreRadios = true

    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()
    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()

    init {
        // Load all settings eagerly before any data loading
        viewModelScope.launch {
            _displayModes.value = settingsRepository.libraryDisplayModes.first()
            _sortOptions.value = settingsRepository.librarySortOptions.first()
            _sortDescendings.value = settingsRepository.librarySortDescending.first()
            _favoritesOnlyMap.value = settingsRepository.libraryFavoritesOnly.first()
            _settingsLoaded.value = true
        }
        // Keep syncing after initial load
        viewModelScope.launch {
            settingsRepository.libraryDisplayModes.collect { _displayModes.value = it }
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
                        val isEmpty = when (_currentTab.value) {
                            0 -> _artists.value.isEmpty()
                            1 -> _albums.value.isEmpty()
                            2 -> _tracks.value.isEmpty()
                            3 -> _playlists.value.isEmpty()
                            4 -> _radios.value.isEmpty()
                            else -> false
                        }
                        if (isEmpty) reloadCurrentTab()
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
                        // Skip: updates don't change list structure, avoid scroll reset
                    }
                }
            }
        }
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

    private val currentSearch: String? get() = _searchQuery.value.ifBlank { null }
    private val currentOrderBy: String get() {
        val tab = _currentTab.value
        val base = (_sortOptions.value[tab] ?: SortOption.NAME).apiValue
        return if (_sortDescendings.value[tab] == true) "${base}_desc" else base
    }

    private val currentFavoriteOnly: Boolean get() =
        _favoritesOnlyMap.value[_currentTab.value] ?: false

    fun setCurrentTab(tab: Int) {
        if (_currentTab.value != tab) {
            _searchQuery.value = ""
            searchJob?.cancel()
        }
        _currentTab.value = tab
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
        val current = _selectedProviders.value
        _selectedProviders.value = if (instanceId in current) current - instanceId else current + instanceId
        reloadCurrentTab()
    }

    fun clearProviderFilter() {
        _selectedProviders.value = emptySet()
        reloadCurrentTab()
    }

    private fun providerFilterArgs(): List<String>? =
        selectedProviderDomains().takeIf { it.isNotEmpty() }

    private fun selectedProviderDomains(): List<String> {
        if (_selectedProviders.value.isEmpty()) return emptyList()
        val providersForTab = providerManifestCache.musicProvidersForTab(_currentTab.value)
        return _selectedProviders.value
            .mapNotNull { selectedId ->
                providersForTab.firstOrNull { it.instanceId == selectedId }?.domain
                    ?: providersForTab.firstOrNull { it.domain == selectedId }?.domain
            }
            .distinct()
    }

    private fun <T> filterBySelectedProviders(
        items: List<T>,
        providerDomains: (T) -> List<String>
    ): List<T> {
        val selectedDomains = selectedProviderDomains().toSet()
        if (selectedDomains.isEmpty()) return items
        return items.filter { item ->
            providerDomains(item).any { it in selectedDomains }
        }
    }

    fun toggleLibraryDisplayMode() {
        val tab = _currentTab.value
        val current = _displayModes.value[tab] ?: if (tab <= 1) LibraryDisplayMode.GRID else LibraryDisplayMode.LIST
        val newMode = when (current) {
            LibraryDisplayMode.LIST -> LibraryDisplayMode.GRID
            LibraryDisplayMode.GRID -> LibraryDisplayMode.LIST
        }
        _displayModes.value = _displayModes.value + (_currentTab.value to newMode)
        viewModelScope.launch {
            settingsRepository.setLibraryDisplayMode(_currentTab.value, newMode)
        }
    }

    private fun reloadCurrentTab() {
        when (_currentTab.value) {
            0 -> loadArtists()
            1 -> loadAlbums()
            2 -> loadTracks()
            3 -> loadPlaylists()
            4 -> loadRadios()
            5 -> applyBrowseFilterSort()
        }
    }

    private fun parseMediaUri(uri: String): Pair<String, String>? {
        val sep = uri.indexOf("://")
        if (sep < 0) return null
        val provider = uri.substring(0, sep)
        val itemId = uri.substringAfterLast("/")
        return if (provider.isNotBlank() && itemId.isNotBlank()) provider to itemId else null
    }

    fun loadArtists() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val query = currentSearch
                val apiDeferred = async {
                    musicRepository.getArtists(
                        search = query, limit = PAGE_SIZE, offset = 0, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly,
                        providerFilter = providerFilterArgs()
                    )
                }
                val genreDeferred = if (query != null && query.length >= 3) {
                    async { runCatching { genreRepository.searchArtistUris(query) }.getOrElse { emptyList() } }
                } else null

                val apiResults = apiDeferred.await()
                val genreUris = genreDeferred?.await().orEmpty()

                val merged = if (genreUris.isNotEmpty()) {
                    val existingUris = apiResults.map { it.uri }.toSet()
                    val newUris = genreUris.filter { it !in existingUris }
                    val genreArtists = if (newUris.isNotEmpty()) {
                        supervisorScope {
                            newUris.map { uri -> async { runCatching { parseMediaUri(uri)?.let { (prov, id) -> musicRepository.getArtist(id, prov) } }.getOrNull() } }.awaitAll().filterNotNull()
                        }
                    } else emptyList()
                    val seenUris = existingUris.toMutableSet()
                    apiResults + genreArtists.filter { seenUris.add(it.uri) }
                } else apiResults

                _artists.value = filterBySelectedProviders(merged) { it.providerDomains }
                hasMoreArtists = apiResults.size >= PAGE_SIZE
                lastFmLibraryEnricher.enrichInBackground(apiResults)
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun loadMoreArtists() {
        if (_isLoadingMore.value || !hasMoreArtists) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            try {
                val items = musicRepository.getArtists(
                    search = currentSearch,
                    limit = PAGE_SIZE,
                    offset = _artists.value.size,
                    orderBy = currentOrderBy,
                    favoriteOnly = currentFavoriteOnly,
                    providerFilter = providerFilterArgs()
                )
                _artists.value = _artists.value + filterBySelectedProviders(items) { it.providerDomains }
                hasMoreArtists = items.size >= PAGE_SIZE
                lastFmLibraryEnricher.enrichInBackground(items)
            } catch (_: Exception) {
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadAlbums() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val query = currentSearch
                val apiDeferred = async {
                    musicRepository.getAlbums(
                        search = query, limit = PAGE_SIZE, offset = 0, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly,
                        providerFilter = providerFilterArgs()
                    )
                }
                val genreDeferred = if (query != null && query.length >= 3) {
                    async { runCatching { genreRepository.searchArtistUris(query) }.getOrElse { emptyList() } }
                } else null

                val apiResults = apiDeferred.await()
                val genreUris = genreDeferred?.await().orEmpty()

                val merged = if (genreUris.isNotEmpty()) {
                    val genreAlbums: List<Album> = supervisorScope {
                        genreUris.map { uri ->
                            async {
                                runCatching {
                                    parseMediaUri(uri)?.let { (prov, id) -> musicRepository.getArtistAlbums(id, prov) }
                                }.getOrNull().orEmpty()
                            }
                        }.awaitAll().flatten()
                    }.filter { it.uri.startsWith("library://") }
                    val seenUris = apiResults.map { it.uri }.toMutableSet()
                    apiResults + genreAlbums.filter { seenUris.add(it.uri) }
                } else apiResults

                _albums.value = filterBySelectedProviders(merged) { it.providerDomains }
                hasMoreAlbums = apiResults.size >= PAGE_SIZE
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun loadMoreAlbums() {
        if (_isLoadingMore.value || !hasMoreAlbums) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            try {
                val items = musicRepository.getAlbums(
                    search = currentSearch,
                    limit = PAGE_SIZE,
                    offset = _albums.value.size,
                    orderBy = currentOrderBy,
                    favoriteOnly = currentFavoriteOnly,
                    providerFilter = providerFilterArgs()
                )
                _albums.value = _albums.value + filterBySelectedProviders(items) { it.providerDomains }
                hasMoreAlbums = items.size >= PAGE_SIZE
            } catch (_: Exception) {
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadTracks() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val query = currentSearch
                val apiDeferred = async {
                    musicRepository.getTracks(
                        search = query, limit = PAGE_SIZE, offset = 0, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly,
                        providerFilter = providerFilterArgs()
                    )
                }
                val genreDeferred = if (query != null && query.length >= 3) {
                    async { runCatching { genreRepository.searchArtistUris(query) }.getOrElse { emptyList() } }
                } else null

                val apiResults = apiDeferred.await()
                val genreUris = genreDeferred?.await().orEmpty()

                val merged = if (genreUris.isNotEmpty()) {
                    val genreTracks: List<Track> = supervisorScope {
                        genreUris.map { uri ->
                            async {
                                runCatching {
                                    parseMediaUri(uri)?.let { (prov, id) -> musicRepository.getArtistTracks(id, prov) }
                                }.getOrNull().orEmpty()
                            }
                        }.awaitAll().flatten()
                    }.filter { it.uri.startsWith("library://") }
                    val seenUris = apiResults.map { it.uri }.toMutableSet()
                    apiResults + genreTracks.filter { seenUris.add(it.uri) }
                } else apiResults

                _tracks.value = filterBySelectedProviders(merged) { it.providerDomains }
                hasMoreTracks = apiResults.size >= PAGE_SIZE
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun loadMoreTracks() {
        if (_isLoadingMore.value || !hasMoreTracks) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            try {
                val items = musicRepository.getTracks(
                    search = currentSearch,
                    limit = PAGE_SIZE,
                    offset = _tracks.value.size,
                    orderBy = currentOrderBy,
                    favoriteOnly = currentFavoriteOnly,
                    providerFilter = providerFilterArgs()
                )
                _tracks.value = _tracks.value + filterBySelectedProviders(items) { it.providerDomains }
                hasMoreTracks = items.size >= PAGE_SIZE
            } catch (_: Exception) {
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val items = musicRepository.getPlaylists(
                    search = currentSearch,
                    limit = PAGE_SIZE,
                    offset = 0,
                    orderBy = currentOrderBy,
                    favoriteOnly = currentFavoriteOnly,
                    providerFilter = providerFilterArgs()
                )
                _playlists.value = filterBySelectedProviders(items) { it.providerDomains }
                hasMorePlaylists = items.size >= PAGE_SIZE
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun loadMorePlaylists() {
        if (_isLoadingMore.value || !hasMorePlaylists) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            try {
                val items = musicRepository.getPlaylists(
                    search = currentSearch,
                    limit = PAGE_SIZE,
                    offset = _playlists.value.size,
                    orderBy = currentOrderBy,
                    favoriteOnly = currentFavoriteOnly,
                    providerFilter = providerFilterArgs()
                )
                _playlists.value = _playlists.value + filterBySelectedProviders(items) { it.providerDomains }
                hasMorePlaylists = items.size >= PAGE_SIZE
            } catch (_: Exception) {
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadRadios() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val query = currentSearch
                val libraryDeferred = async {
                    musicRepository.getRadios(
                        search = query, limit = PAGE_SIZE, offset = 0, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly,
                        providerFilter = providerFilterArgs()
                    )
                }
                val searchDeferred = if (query != null && query.length >= 2) {
                    async {
                        runCatching {
                            musicRepository.search(query, mediaTypes = listOf(MediaType.RADIO), limit = 25).radios
                        }.getOrElse { emptyList() }
                    }
                } else null

                val libraryResults = libraryDeferred.await()
                val searchResults = searchDeferred?.await().orEmpty()

                val merged = if (searchResults.isNotEmpty()) {
                    val seenUris = libraryResults.map { it.uri }.toMutableSet()
                    val libraryNames = libraryResults.map { it.name.lowercase() }.toSet()
                    libraryResults + searchResults
                        .filter { seenUris.add(it.uri) }
                        .map { radio ->
                            val alreadyInLibrary = radio.name.lowercase() in libraryNames
                            radio.copy(inLibrary = alreadyInLibrary)
                        }
                } else libraryResults

                _radios.value = filterBySelectedProviders(merged) { it.providerDomains }
                hasMoreRadios = libraryResults.size >= PAGE_SIZE
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun loadMoreRadios() {
        if (_isLoadingMore.value || !hasMoreRadios) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            try {
                val items = musicRepository.getRadios(
                    search = currentSearch,
                    limit = PAGE_SIZE,
                    offset = _radios.value.size,
                    orderBy = currentOrderBy,
                    favoriteOnly = currentFavoriteOnly,
                    providerFilter = providerFilterArgs()
                )
                _radios.value = _radios.value + filterBySelectedProviders(items) { it.providerDomains }
                hasMoreRadios = items.size >= PAGE_SIZE
            } catch (_: Exception) {
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadBrowse(path: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
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
            _isLoading.value = false
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

    fun playTrack(track: Track) {
        playUri(track.uri)
    }

    fun playAlbum(album: Album) {
        playUri(album.uri)
    }

    fun playPlaylist(playlist: Playlist) {
        playUri(playlist.uri)
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            try {
                val playlist = musicRepository.createPlaylist(name)
                _playlists.value = _playlists.value + playlist
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
                _playlists.value = _playlists.value + playlist
                _editablePlaylists.value = _editablePlaylists.value + playlist
                _playlistContainsTrack.value = _playlistContainsTrack.value + playlist.uri
            } catch (e: Exception) {
                Log.w(TAG, "createPlaylistAndAddTrack failed: ${e.message}")
                _error.tryEmit("Failed to create playlist")
            }
        }
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

    fun toggleFavorite(uri: String, mediaType: MediaType, itemId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(uri, mediaType, itemId, !currentFavorite)
                when (mediaType) {
                    MediaType.ARTIST -> _artists.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.ALBUM -> _albums.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.TRACK -> _tracks.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.PLAYLIST -> _playlists.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.RADIO -> _radios.update { list ->
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
                val libraryItemId = Regex("^library://\\w+/(.+)$").find(uri)?.groupValues?.get(1) ?: itemId
                musicRepository.removeFromLibrary(mediaType, libraryItemId)
                when (mediaType) {
                    MediaType.ARTIST -> _artists.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.ALBUM -> _albums.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.TRACK -> _tracks.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.PLAYLIST -> _playlists.update { list -> list.filter { it.itemId != itemId } }
                    MediaType.RADIO -> _radios.update { list -> list.filter { it.itemId != itemId } }
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
                _radios.update { list ->
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


@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val lastFmSimilarResolver: LastFmSimilarResolver,
    private val lastFmArtistInfoResolver: LastFmArtistInfoResolver,
    private val lastFmGenreResolver: LastFmGenreResolver,
    private val dao: PlayHistoryDao
) : ViewModel() {

    val itemId: String = savedStateHandle["itemId"] ?: ""
    val provider: String = savedStateHandle["provider"] ?: ""

    private val _artist = MutableStateFlow<Artist?>(null)
    val artist: StateFlow<Artist?> = _artist.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _artistName = MutableStateFlow(savedStateHandle.get<String>("name") ?: "Artist")
    val artistName: StateFlow<String> = _artistName.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()

    private val _similarArtists = MutableStateFlow<List<Artist>>(emptyList())
    val similarArtists: StateFlow<List<Artist>> = _similarArtists.asStateFlow()

    val players = playerRepository.players

    init {
        viewModelScope.launch { loadData(lazy = true) }
        viewModelScope.launch {
            smartListeningRepository.blockedArtistUris.collect { _blockedArtistUris.value = it }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                dao.clearSimilarArtistResolved(_artistName.value.lowercase())
                _artist.value?.uri?.let { musicRepository.refreshItemByUri(it) }
                    ?: musicRepository.requestLibrarySync(force = true)
                loadData(lazy = false)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun loadData(lazy: Boolean) {
        try {
            var artist = musicRepository.getArtist(itemId, provider, lazy = lazy)
            // Immediately replace genres with cached Last.fm tags if available
            if (artist != null) {
                val cached = dao.getLastFmTags(artist.name)
                if (cached != null) {
                    val tags = cached.tags.split(",").filter { it.isNotBlank() }
                    if (tags.isNotEmpty()) artist = artist.copy(genres = tags)
                }
            }
            _artist.value = artist
            _albums.value = musicRepository.getArtistAlbums(itemId, provider)
            _tracks.value = musicRepository.getArtistTracks(itemId, provider)

            _artist.value?.let { a ->
                if (a.name.isNotBlank()) _artistName.value = a.name
            } ?: run {
                if (_artistName.value == "Artist") {
                    _tracks.value.firstOrNull()?.artistNames?.let { _artistName.value = it }
                }
            }

            // Auto-refresh if artist has no real image (only imageproxy fallback)
            val hasRealImage = _artist.value?.imageUrl?.let {
                !it.contains("imageproxy") || it.contains("path=http")
            } ?: false
            if (lazy && !hasRealImage) {
                kotlinx.coroutines.delay(500)
                val refreshed = musicRepository.getArtist(itemId, provider, lazy = false)
                if (refreshed != null) {
                    _artist.value = refreshed
                    if (refreshed.name.isNotBlank()) _artistName.value = refreshed.name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Load artist detail failed: ${e.message}")
        }

        val name = _artistName.value
        if (name.isNotBlank()) {
            viewModelScope.launch { loadSimilarArtists(name) }
            if (_artist.value?.description.isNullOrBlank()) {
                viewModelScope.launch { loadLastFmBio(name) }
            }
            viewModelScope.launch { enrichArtistGenresFromLastFm(name) }
        }
    }

    private suspend fun loadSimilarArtists(artistName: String) {
        try {
            val similar = lastFmSimilarResolver.resolve(artistName, limit = 8)
            if (similar.isEmpty()) return
            val key = artistName.lowercase()
            val cached = dao.getSimilarArtists(key)
            val now = System.currentTimeMillis()
            val resolveTtl = 7 * 86_400_000L
            val sourceGenres = lastFmGenreResolver.resolve(artistName).toSet()

            val resolved = similar.mapNotNull { sim ->
                val cachedRow = cached.firstOrNull { it.similarArtist == sim.name }
                val resolvedAt = cachedRow?.resolvedAt
                if (resolvedAt != null && now - resolvedAt < resolveTtl) {
                    return@mapNotNull cachedRow.resolvedUri?.let { uri ->
                        Artist(
                            itemId = cachedRow.resolvedItemId.orEmpty(),
                            provider = cachedRow.resolvedProvider.orEmpty(),
                            name = cachedRow.resolvedName.orEmpty(),
                            uri = uri,
                            imageUrl = cachedRow.resolvedImageUrl
                        )
                    }
                }
                val searchResult = musicRepository.search(sim.name, listOf(MediaType.ARTIST), limit = 5)
                val candidates = searchResult.artists.filter { it.name.equals(sim.name, ignoreCase = true) }
                val matched = if (candidates.isEmpty()) {
                    null
                } else if (sourceGenres.isEmpty()) {
                    candidates.firstOrNull()
                } else {
                    candidates.firstNotNullOfOrNull { c ->
                        val detail = musicRepository.getArtist(c.itemId, c.provider)
                        val cGenres = detail?.genres?.map { normalizeGenre(it) }?.toSet().orEmpty()
                        when {
                            detail == null -> null
                            cGenres.isEmpty() -> detail
                            cGenres.any { it in sourceGenres } -> detail
                            else -> null
                        }
                    }
                }
                dao.updateSimilarArtistResolved(
                    sourceArtist = key, similarArtist = sim.name,
                    itemId = matched?.itemId, provider = matched?.provider,
                    name = matched?.name, imageUrl = matched?.imageUrl, uri = matched?.uri,
                    resolvedAt = now
                )
                matched
            }
            _similarArtists.value = resolved
        } catch (e: Exception) {
            Log.w(TAG, "Load similar artists failed: ${e.message}")
        }
    }

    private suspend fun enrichArtistGenresFromLastFm(artistName: String) {
        try {
            val lastFmGenres = lastFmGenreResolver.resolve(artistName)
            if (lastFmGenres.isNotEmpty()) {
                _artist.update { current ->
                    current?.copy(genres = lastFmGenres)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Enrich artist genres failed: ${e.message}")
        }
    }

    private suspend fun loadLastFmBio(artistName: String) {
        try {
            val bio = lastFmArtistInfoResolver.resolve(artistName) ?: return
            _artist.update { it?.copy(description = bio) }
        } catch (e: Exception) {
            Log.w(TAG, "Load Last.fm bio failed: ${e.message}")
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

    fun playTrack(track: Track) = playUri(track.uri)

    fun playAllTracks(option: String = "replace") {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        val uris = _tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (option == "replace") {
                    playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                }
                musicRepository.playMedia(queueId, uris, option = option)
            } catch (e: Exception) {
                Log.w(TAG, "playAllTracks failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun toggleArtistFavorite() {
        val a = _artist.value ?: return
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(a.uri, MediaType.ARTIST, a.itemId, !a.favorite)
                _artist.update { it?.copy(favorite = !a.favorite) }
            } catch (e: Exception) {
                Log.w(TAG, "toggleArtistFavorite failed: ${e.message}")
            }
        }
    }

    fun toggleFavorite(uri: String, mediaType: MediaType, itemId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(uri, mediaType, itemId, !currentFavorite)
                when (mediaType) {
                    MediaType.ALBUM -> _albums.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.TRACK -> _tracks.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
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

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val lastFmAlbumInfoResolver: LastFmAlbumInfoResolver,
    private val lastFmGenreResolver: LastFmGenreResolver
) : ViewModel() {

    val itemId: String = savedStateHandle["itemId"] ?: ""
    val provider: String = savedStateHandle["provider"] ?: ""

    private val _album = MutableStateFlow<Album?>(null)
    val album: StateFlow<Album?> = _album.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _albumName = MutableStateFlow(savedStateHandle.get<String>("name") ?: "Album")
    val albumName: StateFlow<String> = _albumName.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()

    val currentTrackUri: StateFlow<String?> = playerRepository.queueState
        .map { it?.currentItem?.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isPlaying: StateFlow<Boolean> = playerRepository.selectedPlayer
        .map { it?.state == PlaybackState.PLAYING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val players = playerRepository.players

    init {
        viewModelScope.launch { loadData(lazy = true) }
        viewModelScope.launch {
            smartListeningRepository.blockedArtistUris.collect { _blockedArtistUris.value = it }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                _album.value?.uri?.let { musicRepository.refreshItemByUri(it) }
                    ?: musicRepository.requestLibrarySync(force = true)
                loadData(lazy = false)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun loadData(lazy: Boolean) {
        try {
            _album.value = musicRepository.getAlbum(itemId, provider, lazy = lazy)
            _tracks.value = musicRepository.getAlbumTracks(itemId, provider)

            _album.value?.let { a ->
                if (a.name.isNotBlank()) _albumName.value = a.name
            } ?: run {
                if (_albumName.value == "Album") {
                    _tracks.value.firstOrNull()?.albumName?.let {
                        if (it.isNotBlank()) _albumName.value = it
                    }
                }
            }

            // Auto-refresh if album has no real image (only imageproxy fallback)
            val hasRealImage = _album.value?.imageUrl?.let {
                !it.contains("imageproxy") || it.contains("path=http")
            } ?: false
            if (lazy && !hasRealImage) {
                val refreshed = musicRepository.getAlbum(itemId, provider, lazy = false)
                if (refreshed != null) {
                    _album.value = refreshed
                    if (refreshed.name.isNotBlank()) _albumName.value = refreshed.name
                    _tracks.value = musicRepository.getAlbumTracks(itemId, provider)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Load album detail failed: ${e.message}")
        }

        val album = _album.value ?: return
        val artistName = album.artistNames.split(",").firstOrNull()?.trim().orEmpty()
        if (artistName.isNotBlank()) {
            val needsBio = album.description.isNullOrBlank()
            val needsYear = album.year == null
            if (needsBio || needsYear) {
                viewModelScope.launch { loadLastFmAlbumInfo(artistName, album.name, needsBio, needsYear) }
            }
            viewModelScope.launch { enrichGenresFromLastFm(artistName) }
        }
    }

    private suspend fun enrichGenresFromLastFm(artistName: String) {
        try {
            val lastFmGenres = lastFmGenreResolver.resolve(artistName)
            if (lastFmGenres.isNotEmpty()) {
                _album.update { current ->
                    val merged = (current?.genres.orEmpty() + lastFmGenres).distinctBy { it.lowercase() }
                    current?.copy(genres = merged)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Enrich genres failed: ${e.message}")
        }
    }

    private suspend fun loadLastFmAlbumInfo(
        artistName: String,
        albumName: String,
        needsBio: Boolean,
        needsYear: Boolean
    ) {
        try {
            val info = lastFmAlbumInfoResolver.resolve(artistName, albumName) ?: return
            _album.update { current ->
                current?.copy(
                    description = if (needsBio && info.summary != null) info.summary else current.description,
                    year = if (needsYear && info.year != null) info.year else current.year
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Load Last.fm album info failed: ${e.message}")
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

    fun playTrack(track: Track) = playUri(track.uri)

    fun toggleAlbumFavorite() {
        val a = _album.value ?: return
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(a.uri, MediaType.ALBUM, a.itemId, !a.favorite)
                _album.update { it?.copy(favorite = !a.favorite) }
            } catch (e: Exception) {
                Log.w(TAG, "toggleAlbumFavorite failed: ${e.message}")
            }
        }
    }

    fun playAll() {
        val uris = _tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uris, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "playAll failed: ${e.message}")
            }
        }
    }

    fun addAllToQueue() {
        val uris = _tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uris, option = "add")
            } catch (e: Exception) {
                Log.w(TAG, "addAllToQueue failed: ${e.message}")
            }
        }
    }

    fun playAllNext() {
        val uris = _tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uris, option = "next")
            } catch (e: Exception) {
                Log.w(TAG, "playAllNext failed: ${e.message}")
            }
        }
    }

    fun replaceQueue() {
        val uris = _tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uris, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "replaceQueue failed: ${e.message}")
            }
        }
    }

    fun startRadioAll() {
        val first = _tracks.value.firstOrNull()?.uri ?: return
        startRadio(first)
    }

    fun toggleFavorite(uri: String, mediaType: MediaType, itemId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(uri, mediaType, itemId, !currentFavorite)
                if (mediaType == MediaType.TRACK) {
                    _tracks.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
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

    // Playlist management for track action sheet
    private val _editablePlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val editablePlaylists: StateFlow<List<Playlist>> = _editablePlaylists.asStateFlow()
    private val _isLoadingEditablePlaylists = MutableStateFlow(false)
    val isLoadingEditablePlaylists: StateFlow<Boolean> = _isLoadingEditablePlaylists.asStateFlow()
    private val _addingToPlaylistId = MutableStateFlow<String?>(null)
    val addingToPlaylistId: StateFlow<String?> = _addingToPlaylistId.asStateFlow()
    private val _playlistContainsTrack = MutableStateFlow<Set<String>>(emptySet())
    val playlistContainsTrack: StateFlow<Set<String>> = _playlistContainsTrack.asStateFlow()

    fun loadEditablePlaylists(trackUri: String) {
        if (_isLoadingEditablePlaylists.value) return
        viewModelScope.launch {
            _isLoadingEditablePlaylists.value = true
            try {
                val loaded = musicRepository.getPlaylists(limit = 200).filter { it.isEditable }
                _editablePlaylists.value = loaded
                val containing = mutableSetOf<String>()
                for (pl in loaded) {
                    try {
                        val t = musicRepository.getPlaylistTracks(pl.itemId, pl.provider)
                        if (t.any { it.uri == trackUri }) containing += pl.uri
                    } catch (_: Exception) { }
                }
                _playlistContainsTrack.value = containing
            } catch (e: Exception) {
                Log.w("AlbumDetailVM", "loadEditablePlaylists failed: ${e.message}")
            } finally {
                _isLoadingEditablePlaylists.value = false
            }
        }
    }

    fun addTrackToPlaylist(playlist: Playlist, trackUri: String) {
        if (_addingToPlaylistId.value != null) return
        viewModelScope.launch {
            _addingToPlaylistId.value = playlist.itemId
            try {
                musicRepository.addTrackToPlaylist(playlist, trackUri)
                _playlistContainsTrack.value = _playlistContainsTrack.value + playlist.uri
            } catch (e: Exception) {
                Log.w("AlbumDetailVM", "addTrackToPlaylist failed: ${e.message}")
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
                val pos = tracks.indexOfFirst { it.uri == trackUri }
                if (pos >= 0) {
                    musicRepository.removeTrackFromPlaylist(playlist, pos)
                    _playlistContainsTrack.value = _playlistContainsTrack.value - playlist.uri
                }
            } catch (e: Exception) {
                Log.w("AlbumDetailVM", "removeTrackFromPlaylist failed: ${e.message}")
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
                _editablePlaylists.value = _editablePlaylists.value + playlist
                _playlistContainsTrack.value = _playlistContainsTrack.value + playlist.uri
            } catch (e: Exception) {
                Log.w("AlbumDetailVM", "createPlaylistAndAddTrack failed: ${e.message}")
            }
        }
    }
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val smartListeningRepository: SmartListeningRepository
) : ViewModel() {

    val itemId: String = savedStateHandle["itemId"] ?: ""
    val provider: String = savedStateHandle["provider"] ?: ""
    private val playlistUri: String = savedStateHandle["uri"] ?: ""

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _playlistName = MutableStateFlow(savedStateHandle.get<String>("name") ?: "Playlist")
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

    private val _favorite = MutableStateFlow(savedStateHandle.get<Boolean>("favorite") ?: false)
    val favorite: StateFlow<Boolean> = _favorite.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _busyTrackUri = MutableStateFlow<String?>(null)
    val busyTrackUri: StateFlow<String?> = _busyTrackUri.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()
    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()

    val players = playerRepository.players

    init {
        viewModelScope.launch {
            try {
                _tracks.value = musicRepository.getPlaylistTracks(itemId, provider)
            } catch (e: Exception) {
                Log.w(TAG, "Load playlist tracks failed: ${e.message}")
            }
        }
        viewModelScope.launch {
            try {
                _playlists.value = musicRepository.getPlaylists(limit = 200)
            } catch (e: Exception) {
                Log.w(TAG, "Load playlists failed: ${e.message}")
            }
        }
        viewModelScope.launch {
            smartListeningRepository.blockedArtistUris.collect { _blockedArtistUris.value = it }
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

    fun playTrack(track: Track) = playUri(track.uri)

    fun removeTrackFromPlaylist(track: Track, fallbackPosition: Int) {
        val playlist = currentPlaylist() ?: return
        val position = track.position ?: fallbackPosition
        viewModelScope.launch {
            _busyTrackUri.value = track.uri
            try {
                musicRepository.removeTrackFromPlaylist(playlist, position)
                _tracks.update { list -> list.filterNot { it.uri == track.uri && (it.position ?: fallbackPosition) == position } }
            } catch (e: Exception) {
                Log.w(TAG, "removeTrackFromPlaylist failed: ${e.message}")
                _error.tryEmit("Failed to remove track from playlist")
            } finally {
                _busyTrackUri.value = null
            }
        }
    }

    fun moveTrackToPlaylist(track: Track, fallbackPosition: Int, destination: Playlist) {
        val source = currentPlaylist() ?: return
        if (destination.uri == source.uri) return
        val position = track.position ?: fallbackPosition
        viewModelScope.launch {
            _busyTrackUri.value = track.uri
            try {
                musicRepository.addTrackToPlaylist(destination, track.uri)
                musicRepository.removeTrackFromPlaylist(source, position)
                _tracks.update { list -> list.filterNot { it.uri == track.uri && (it.position ?: fallbackPosition) == position } }
            } catch (e: Exception) {
                Log.w(TAG, "moveTrackToPlaylist failed: ${e.message}")
                _error.tryEmit("Failed to move track to playlist")
            } finally {
                _busyTrackUri.value = null
            }
        }
    }

    fun togglePlaylistFavorite() {
        val current = _favorite.value
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(playlistUri, MediaType.PLAYLIST, itemId, !current)
                _favorite.value = !current
            } catch (e: Exception) {
                Log.w(TAG, "togglePlaylistFavorite failed: ${e.message}")
            }
        }
    }

    fun toggleFavorite(uri: String, mediaType: MediaType, itemId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(uri, mediaType, itemId, !currentFavorite)
                if (mediaType == MediaType.TRACK) {
                    _tracks.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
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

    private fun currentPlaylist(): Playlist? {
        if (itemId.isBlank()) return null
        return Playlist(
            itemId = itemId,
            provider = provider,
            name = _playlistName.value,
            uri = playlistUri,
            favorite = _favorite.value
        )
    }
}
