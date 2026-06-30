package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.lastfm.LastFmAlbumInfoResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "LibraryVM"

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

    private val _albumInLibrary = MutableStateFlow(false)
    val albumInLibrary: StateFlow<Boolean> = _albumInLibrary.asStateFlow()

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
            _albumInLibrary.value = _album.value.isInLibrary()
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

    fun toggleAlbumLibrary() {
        val a = _album.value ?: return
        val inLibrary = _albumInLibrary.value
        viewModelScope.launch {
            try {
                if (inLibrary) {
                    musicRepository.removeFromLibrary(MediaType.ALBUM, a.uri, a.itemId)
                } else {
                    musicRepository.addToLibrary(a.uri)
                }
                _albumInLibrary.value = !inLibrary
            } catch (e: Exception) {
                Log.w(TAG, "toggleAlbumLibrary failed: ${e.message}")
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

    fun playAll() {
        val uris = _tracks.value
            .filter { t ->
                val uri = t.artistUri ?: return@filter true
                val name = t.artistNames.split(",").firstOrNull()?.trim().orEmpty()
                !playerRepository.isArtistBlocked(name, uri)
            }
            .map { it.uri }
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
