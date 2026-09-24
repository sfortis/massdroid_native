package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.playlist.PlaylistMembershipController
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "LibraryVM"

/** How long Play All stays busy when the queue never moves. See the playlist screen. */
private const val QUEUE_CHANGE_TIMEOUT_MS = 120_000L

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

    /**
     * Whether a whole-album action has been sent and the queue has not moved yet. Mirrors the
     * playlist screen, where the server can take long enough for Play All to read as dead.
     */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    /**
     * The artists the listener has blocked, served straight from the repository.
     *
     * Blocked items are shown faded rather than hidden, so that they can still be found and
     * unblocked. Ask [blocksArtist] rather than testing the set directly: the keys here are
     * canonical and a provider URI has to be normalised first.
     */
    val blockedArtistUris: StateFlow<Set<String>> = smartListeningRepository.blockedArtistUris
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val currentTrackUri: StateFlow<String?> = playerRepository.queueState
        .map { it?.currentItem?.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isPlaying: StateFlow<Boolean> = playerRepository.selectedPlayer
        .map { it?.state == PlaybackState.PLAYING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val players = playerRepository.players

    // Playlist management for the track action sheet. Declared above init because
    // init collects this controller's error flow, and a property declared after
    // init is still null while init runs.
    private val playlistMembership = PlaylistMembershipController(musicRepository, viewModelScope)
    val editablePlaylists: StateFlow<List<Playlist>> = playlistMembership.playlists
    val isLoadingEditablePlaylists: StateFlow<Boolean> = playlistMembership.isLoading
    val addingToPlaylistId: StateFlow<String?> = playlistMembership.pendingPlaylistId
    val playlistContainsTrack: StateFlow<Set<String>> = playlistMembership.containsTrack

    init {
        viewModelScope.launch { loadData(lazy = true) }
        viewModelScope.launch {
            playlistMembership.errors.collect { _error.tryEmit(it) }
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
        if (uris.isEmpty()) {
            // Every track was filtered out, which for a compilation credited to a blocked
            // artist means the whole album. Returning quietly read as a dead button.
            if (_tracks.value.isNotEmpty()) {
                _error.tryEmit("Everything here is by an artist you blocked")
            }
            return
        }
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        if (_sending.value) return
        val before = queueSignature()
        viewModelScope.launch {
            _sending.value = true
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uris, option = "replace")
                awaitQueueChange(before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "playAll failed: ${e.message}")
                _error.tryEmit("Could not play this album")
            } finally {
                _sending.value = false
            }
        }
    }

    /** What the queue looks like, to the detail that tells a replacement from no change. */
    private fun queueSignature(): Pair<String?, Int>? =
        playerRepository.queueState.value?.let { it.currentItem?.queueItemId to it.totalItems }

    /**
     * Wait until the server has acted on the queue, so the button can stay busy meanwhile.
     *
     * Same reasoning as the playlist screen: the command is sent without waiting for an answer,
     * and an album that the server is still resolving looks exactly like a button that did
     * nothing. The timeout only releases the button if the queue never moves at all.
     */
    private suspend fun awaitQueueChange(before: Pair<String?, Int>?) {
        val changed = withTimeoutOrNull(QUEUE_CHANGE_TIMEOUT_MS) {
            playerRepository.queueState.first { it != null && queueSignature() != before }
        }
        if (changed == null) {
            Log.w(TAG, "queue did not change within ${QUEUE_CHANGE_TIMEOUT_MS}ms of play")
            _error.tryEmit("The server did not start this album")
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
            val blocked = blockedArtistUris.value.contains(uri)
            smartListeningRepository.setArtistBlocked(uri, artistName, blocked = !blocked)
        }
    }

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
