package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
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
    private val smartListeningRepository: SmartListeningRepository
) : ViewModel() {

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
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
        modes[tab] ?: LibraryDisplayMode.LIST
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryDisplayMode.LIST)

    val players = playerRepository.players

    private var hasMoreArtists = true
    private var hasMoreAlbums = true
    private var hasMoreTracks = true
    private var hasMorePlaylists = true

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
                    EventType.MEDIA_ITEM_UPDATED,
                    EventType.MEDIA_ITEM_DELETED -> {
                        mediaEventJob?.cancel()
                        mediaEventJob = launch {
                            delay(500)
                            reloadCurrentTab()
                        }
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

    fun toggleLibraryDisplayMode() {
        val current = _displayModes.value[_currentTab.value] ?: LibraryDisplayMode.LIST
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
        }
    }

    fun loadArtists() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val items = musicRepository.getArtists(
                    search = currentSearch, limit = PAGE_SIZE, offset = 0, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly
                )
                _artists.value = items
                hasMoreArtists = items.size >= PAGE_SIZE
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
                    search = currentSearch, limit = PAGE_SIZE, offset = _artists.value.size, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly
                )
                _artists.value = _artists.value + items
                hasMoreArtists = items.size >= PAGE_SIZE
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
                val items = musicRepository.getAlbums(
                    search = currentSearch, limit = PAGE_SIZE, offset = 0, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly
                )
                _albums.value = items
                hasMoreAlbums = items.size >= PAGE_SIZE
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
                    search = currentSearch, limit = PAGE_SIZE, offset = _albums.value.size, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly
                )
                _albums.value = _albums.value + items
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
                val items = musicRepository.getTracks(
                    search = currentSearch, limit = PAGE_SIZE, offset = 0, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly
                )
                _tracks.value = items
                hasMoreTracks = items.size >= PAGE_SIZE
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
                    search = currentSearch, limit = PAGE_SIZE, offset = _tracks.value.size, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly
                )
                _tracks.value = _tracks.value + items
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
                    search = currentSearch, limit = PAGE_SIZE, offset = 0, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly
                )
                _playlists.value = items
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
                    search = currentSearch, limit = PAGE_SIZE, offset = _playlists.value.size, orderBy = currentOrderBy, favoriteOnly = currentFavoriteOnly
                )
                _playlists.value = _playlists.value + items
                hasMorePlaylists = items.size >= PAGE_SIZE
            } catch (_: Exception) {
            } finally {
                _isLoadingMore.value = false
            }
        }
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

    fun quickPlay(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uri, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "quickPlay failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun playUri(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
                musicRepository.playMedia(playerId, uri)
            } catch (e: Exception) {
                Log.w(TAG, "playOnPlayer failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun enqueue(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
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
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val smartListeningRepository: SmartListeningRepository
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
            _artist.value = musicRepository.getArtist(itemId, provider, lazy = lazy)
            _albums.value = musicRepository.getArtistAlbums(itemId, provider)
            _tracks.value = musicRepository.getArtistTracks(itemId, provider)

            _artist.value?.let { a ->
                if (a.name.isNotBlank()) _artistName.value = a.name
            } ?: run {
                if (_artistName.value == "Artist") {
                    _tracks.value.firstOrNull()?.artistNames?.let { _artistName.value = it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Load artist detail failed: ${e.message}")
        }
    }

    fun playUri(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
                musicRepository.playMedia(playerId, uri)
            } catch (e: Exception) {
                Log.w(TAG, "playOnPlayer failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun enqueue(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
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
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uri, radioMode = true)
            } catch (e: Exception) {
                Log.w(TAG, "startRadio failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun quickPlay(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uri, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "quickPlay failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun playTrack(track: Track) = playUri(track.uri)

    fun playAllTracks(option: String = "replace") {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        val uris = _tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
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
    private val smartListeningRepository: SmartListeningRepository
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
        } catch (e: Exception) {
            Log.w(TAG, "Load album detail failed: ${e.message}")
        }
    }

    fun playUri(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
                musicRepository.playMedia(playerId, uri)
            } catch (e: Exception) {
                Log.w(TAG, "playOnPlayer failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun enqueue(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
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
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uris, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "playAll failed: ${e.message}")
            }
        }
    }

    fun addAllToQueue() {
        val uris = _tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
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
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
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
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
                musicRepository.playMedia(playerId, uri)
            } catch (e: Exception) {
                Log.w(TAG, "playOnPlayer failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun enqueue(uri: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
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
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
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
