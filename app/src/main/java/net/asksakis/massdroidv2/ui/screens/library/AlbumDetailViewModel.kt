package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.playlist.PlaylistMembershipController
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
    private val musicBrainzGenreResolver: net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
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
            playlistMembership.errors.collect { _error.tryEmit(it) }
        }
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
            // Album descriptions used to come from Last.fm when Music Assistant
            // had none. They are the one thing dropping the API key genuinely
            // costs (measured: Music Assistant carries one for 6% of albums),
            // and an absent paragraph is a quieter failure than asking every
            // listener to go and create an API key. The year is unaffected -
            // Music Assistant reports it for every album measured.
            viewModelScope.launch { enrichGenres(artistName) }
        }
    }

    private suspend fun enrichGenres(artistName: String) {
        try {
            val musicBrainzGenres = musicBrainzGenreResolver.resolve(artistName, null)
            if (musicBrainzGenres.isNotEmpty()) {
                _album.update { current ->
                    val merged = (current?.genres.orEmpty() + musicBrainzGenres).distinctBy { it.lowercase() }
                    current?.copy(genres = merged)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Enrich genres failed: ${e.message}")
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
    private val playlistMembership = PlaylistMembershipController(musicRepository, viewModelScope)
    val editablePlaylists: StateFlow<List<Playlist>> = playlistMembership.playlists
    val isLoadingEditablePlaylists: StateFlow<Boolean> = playlistMembership.isLoading
    val addingToPlaylistId: StateFlow<String?> = playlistMembership.pendingPlaylistId
    val playlistContainsTrack: StateFlow<Set<String>> = playlistMembership.containsTrack

    /** Point the add-to-playlist dialog at [trackUri] and load the list. */
    fun loadEditablePlaylists(trackUri: String) {
        playlistMembership.open(trackUri, reload = true)
    }

    /** Resolve the tick marks for the playlist rows currently on screen. */
    fun onPlaylistsVisible(playlistUris: List<String>) {
        playlistMembership.onPlaylistsVisible(playlistUris)
    }

    fun reloadEditablePlaylists() {
        playlistMembership.reload()
    }

    fun addTrackToPlaylist(playlist: Playlist, trackUri: String) {
        playlistMembership.open(trackUri)
        playlistMembership.add(playlist)
    }

    fun removeTrackFromPlaylist(playlist: Playlist, trackUri: String) {
        playlistMembership.open(trackUri)
        playlistMembership.remove(playlist)
    }

    fun createPlaylistAndAddTrack(name: String, trackUri: String) {
        playlistMembership.open(trackUri)
        playlistMembership.createAndAdd(name)
    }
}
