package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "LibraryVM"

enum class PlaylistSortKey(val label: String) {
    POSITION("Position"),
    NAME("Name"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration"),
    RECENTLY_ADDED("Recently Added")
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val itemId: String = savedStateHandle["itemId"] ?: ""
    val provider: String = savedStateHandle["provider"] ?: ""
    private val playlistUri: String = savedStateHandle["uri"] ?: ""

    private val _rawTracks = MutableStateFlow<List<Track>>(emptyList())
    // Sort is a single global preference (persisted), applied to every playlist and surviving
    // navigation/restart — not per-playlist. Backed by DataStore via [SettingsRepository].
    val sortKey: StateFlow<PlaylistSortKey> = settingsRepository.playlistSortKey
        .map { stored -> runCatching { PlaylistSortKey.valueOf(stored) }.getOrDefault(PlaylistSortKey.POSITION) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistSortKey.POSITION)
    val sortDescending: StateFlow<Boolean> = settingsRepository.playlistSortDescending
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    val tracks: StateFlow<List<Track>> = combine(
        _rawTracks, sortKey, sortDescending, _favoritesOnly
    ) { raw, key, desc, favsOnly ->
        val filtered = if (favsOnly) raw.filter { it.favorite } else raw
        val sorted = when (key) {
            // Default (ascending) is the playlist's own order as the server returns it (matches
            // MA web and other players); descending reverses it. This was inverted, so every
            // playlist showed bottom-to-top until the user manually toggled descending.
            PlaylistSortKey.POSITION -> if (desc) filtered.reversed() else filtered
            PlaylistSortKey.NAME -> filtered.sortedBy { it.name.lowercase() }
            PlaylistSortKey.ARTIST -> filtered.sortedBy { it.artistNames.lowercase() }
            PlaylistSortKey.ALBUM -> filtered.sortedBy { it.albumName.lowercase() }
            PlaylistSortKey.DURATION -> filtered.sortedBy { it.duration ?: 0.0 }
            PlaylistSortKey.RECENTLY_ADDED -> filtered.sortedByDescending { it.dateAdded ?: "" }
        }
        if (desc && key != PlaylistSortKey.POSITION && key != PlaylistSortKey.RECENTLY_ADDED) sorted.reversed() else sorted
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentTrackUri: StateFlow<String?> = playerRepository.queueState
        .map { it?.currentItem?.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isPlaying: StateFlow<Boolean> = playerRepository.selectedPlayer
        .map { it?.state == PlaybackState.PLAYING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setSortKey(key: PlaylistSortKey) {
        viewModelScope.launch {
            if (sortKey.value == key) {
                settingsRepository.setPlaylistSortDescending(!sortDescending.value)
            } else {
                settingsRepository.setPlaylistSortKey(key.name)
                settingsRepository.setPlaylistSortDescending(false)
            }
        }
    }

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

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
                _rawTracks.value = musicRepository.getPlaylistTracks(itemId, provider)
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

    fun playAll() {
        val uris = tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uris, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "playAll failed: ${e.message}")
            }
        }
    }

    fun addAllToQueue() {
        val uris = tracks.value.map { it.uri }
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
        val uris = tracks.value.map { it.uri }
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
        val uris = tracks.value.map { it.uri }
        if (uris.isEmpty()) return
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uris, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "replaceQueue failed: ${e.message}")
            }
        }
    }

    fun startRadioAll() {
        val first = tracks.value.firstOrNull()?.uri ?: return
        startRadio(first)
    }

    fun removeTrackFromPlaylist(track: Track, fallbackPosition: Int) {
        val playlist = currentPlaylist() ?: return
        val position = track.position ?: fallbackPosition
        viewModelScope.launch {
            _busyTrackUri.value = track.uri
            try {
                musicRepository.removeTrackFromPlaylist(playlist, position)
                _rawTracks.update { list -> list.filterNot { it.uri == track.uri && (it.position ?: fallbackPosition) == position } }
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
                _rawTracks.update { list -> list.filterNot { it.uri == track.uri && (it.position ?: fallbackPosition) == position } }
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
                    _rawTracks.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
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
