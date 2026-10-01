package net.asksakis.massdroidv2.ui.screens.library

import net.asksakis.massdroidv2.ui.failureMessage
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
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.EverythingBlockedException
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "LibraryVM"

/**
 * How long Play All stays busy when the queue never moves at all.
 *
 * Generous on purpose. It is not a deadline for the server, only the point at which the
 * button stops claiming to be working: a queue that arrives later still plays. The measured
 * worst case, a 260-track Deezer playlist sent as an expanded track list, took 100 seconds.
 */
private const val QUEUE_CHANGE_TIMEOUT_MS = 120_000L

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
    val playlistUri: String = savedStateHandle["uri"] ?: ""

    /**
     * Whether the server rebuilds this playlist every time it is played, which the
     * Smart Playlist and Endless Mix providers do.
     *
     * The rebuild happens when Music Assistant resolves a PLAYLIST into a queue
     * (`media_resolver.get_playlist_tracks` sets `force_refresh = playlist.is_dynamic`).
     * Handing it the track list instead, which is what the buttons below did, gives it
     * nothing to rebuild and plays the snapshot that happened to be on screen. Reported
     * as issue #67.
     */
    private val isDynamic: Boolean = savedStateHandle["dynamic"] ?: false

    /**
     * Exposed so the screen can say that what it lists is a preview: the server draws a
     * fresh set when the playlist is played, so the queue will not be these tracks.
     */
    val isDynamicPlaylist: Boolean get() = isDynamic

    private val _rawTracks = MutableStateFlow<List<Track>>(emptyList())

    /**
     * The order every playlist is listed in.
     *
     * One global preference, persisted, rather than one per playlist. Music Assistant's own
     * web UI keeps it per playlist; this does not.
     *
     * The stored form still has the separate descending flag the app used before the orders
     * were aligned with the server's, so a preference written by an older build is folded back
     * into a single key here. See [playlistSortKeyOf].
     */
    val sortKey: StateFlow<PlaylistSortKey> = combine(
        settingsRepository.playlistSortKey,
        settingsRepository.playlistSortDescending
    ) { stored, descending -> playlistSortKeyOf(stored, descending) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistSortKey.POSITION)

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    val tracks: StateFlow<List<Track>> = combine(
        _rawTracks, sortKey, _favoritesOnly
    ) { raw, key, favsOnly ->
        val filtered = if (favsOnly) raw.filter { it.favorite } else raw
        // Ordered exactly as Music Assistant would order it, so that what plays matches what
        // is listed even when the server does the sorting. See [sortedForListing].
        filtered.sortedForListing(key)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentTrackUri: StateFlow<String?> = playerRepository.queueState
        .map { it?.currentItem?.track?.uri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isPlaying: StateFlow<Boolean> = playerRepository.selectedPlayer
        .map { it?.state == PlaybackState.PLAYING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setSortKey(key: PlaylistSortKey) {
        viewModelScope.launch {
            settingsRepository.setPlaylistSortKey(key.name)
            // Reversal is part of the key now. Clearing the old flag stops a preference
            // written by an older build from reversing the new key on the next read.
            settingsRepository.setPlaylistSortDescending(false)
        }
    }

    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    /**
     * Re-read the listing from the provider, as the refresh button in the Music Assistant
     * web UI does.
     *
     * The server serves playlist tracks from a cache, so without asking for a refresh a
     * playlist the provider generates can show the same day-old sample. That is why this
     * passes `force_refresh` rather than simply calling the load again.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                loadTracks(forceRefresh = true)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private enum class ListingState { PENDING, LOADED, FAILED }

    /**
     * How the last track listing ended, which is what tells an empty list apart: an empty
     * playlist ([ListingState.LOADED]), a listing that failed ([ListingState.FAILED]), or
     * one still on its way ([ListingState.PENDING]). See [playWhole].
     */
    private var listingState = ListingState.PENDING

    private suspend fun loadTracks(forceRefresh: Boolean) {
        try {
            _rawTracks.value = musicRepository.getPlaylistTracks(itemId, provider, forceRefresh)
            listingState = ListingState.LOADED
        } catch (e: Exception) {
            Log.w(TAG, "Load playlist tracks failed: ${e.message}")
            listingState = ListingState.FAILED
        }
    }

    private val _playlistName = MutableStateFlow(savedStateHandle.get<String>("name") ?: "Playlist")
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

    private val _favorite = MutableStateFlow(savedStateHandle.get<Boolean>("favorite") ?: false)
    val favorite: StateFlow<Boolean> = _favorite.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _busyTrackUri = MutableStateFlow<String?>(null)
    val busyTrackUri: StateFlow<String?> = _busyTrackUri.asStateFlow()

    /**
     * Whether a whole-playlist action has been sent and the queue has not moved yet.
     *
     * The screen shows it on the Play All button, which otherwise looks like it did nothing
     * for as long as the server takes. See [awaitQueueChange].
     */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()
    /**
     * The artists the listener has blocked, served straight from the repository.
     *
     * Blocked items are shown faded rather than hidden, so that they can still be found and
     * unblocked. Ask [blocksArtist] rather than testing the set directly: the keys here are
     * canonical and a provider URI has to be normalised first.
     */
    val blockedArtistUris: StateFlow<Set<String>> = smartListeningRepository.blockedArtistUris
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val players = playerRepository.players

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch { loadTracks(forceRefresh = false) }
        viewModelScope.launch {
            try {
                _playlists.value = musicRepository.getPlaylists(limit = 200)
                    .filter { it.acceptsManualTracks }
            } catch (e: Exception) {
                Log.w(TAG, "Load playlists failed: ${e.message}")
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
                _error.tryEmit(e.failureMessage("Couldn't start playback"))
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
                _error.tryEmit(e.failureMessage("Couldn't play that"))
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
                _error.tryEmit(e.failureMessage("Couldn't add that to the queue"))
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
                _error.tryEmit(e.failureMessage("Couldn't play that next"))
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
                _error.tryEmit(e.failureMessage("Couldn't start the radio"))
            }
        }
    }

    fun playTrack(track: Track) = playUri(track.uri)

    fun playAll() = playWhole(option = "replace", what = "playAll")

    fun addAllToQueue() = playWhole(option = "add", what = "addAllToQueue")

    fun playAllNext() = playWhole(option = "next", what = "playAllNext")

    fun replaceQueue() = playWhole(option = "replace", what = "replaceQueue")

    /**
     * Send the whole playlist to the queue.
     *
     * The playlist goes as its own container URI whenever the server can produce the order
     * on screen, which it can for every ascending order plus descending position and
     * duration. The server then resolves the playlist in one paged pass and sorts it itself,
     * and playback starts almost at once. A dynamic playlist always goes this way so the
     * server rebuilds it.
     *
     * The remaining orders, a favourites-filtered listing and a server too old for `sort_by`
     * fall back to sending the explicit track list, so that what plays still matches what is
     * on screen. That path makes the server resolve every track URI one by one: a 260-track
     * Deezer playlist measured 100 seconds before the first track played.
     */
    private fun playWhole(option: String, what: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        val uris = tracks.value.map { it.uri }
        val order = sortKey.value
        // A favourites-filtered listing is a subset, which no sort order can express, so it
        // can only be played as the list of what is actually shown.
        val serverCanSort = !_favoritesOnly.value && musicRepository.supportsServerSideSort(order)
        // The URI is a navigation argument and can be absent; without it there is no
        // container to hand over and the track list is all there is, rebuild or not.
        val useContainer = playlistUri.isNotBlank() && (isDynamic || serverCanSort)
        // An empty list only goes over as the container while the listing is still on its
        // way. Once it has arrived empty there is nothing to play, and once it has failed
        // the screen shows nothing to play; either way the queue must not be replaced with
        // a playlist the user cannot see. A dynamic playlist is drawn fresh by the server
        // regardless, as it always was.
        if (uris.isEmpty() && !isDynamic && listingState != ListingState.PENDING) return
        if (!useContainer && uris.isEmpty()) return
        if (_sending.value) {
            Log.d(TAG, "$what ignored: a previous one is still being applied")
            return
        }
        // Read before the command goes out. Taking it afterwards would race a queue that
        // arrives quickly: the new queue would be recorded as the old one, and the button
        // would then sit busy waiting for a second change that never comes.
        val queueBefore = queueSignature()
        viewModelScope.launch {
            _sending.value = true
            try {
                if (useContainer) {
                    Log.d(TAG, "$what: container $playlistUri sorted by $order")
                    // The sort order is what stops the container from playing in a different
                    // order than the screen shows; a dynamic playlist is drawn fresh by the
                    // server and has no order of ours to keep.
                    musicRepository.playMedia(
                        queueId,
                        playlistUri,
                        option = option,
                        sortKey = if (isDynamic) null else order
                    )
                } else {
                    Log.d(TAG, "$what: ${uris.size} track URIs, $order is not a server order")
                    musicRepository.playMedia(queueId, uris, option = option)
                }
                awaitQueueChange(queueBefore)
            } catch (e: CancellationException) {
                // The screen was left. Nothing failed, and there is no one to tell.
                throw e
            } catch (e: EverythingBlockedException) {
                _error.tryEmit("Everything here is by an artist you blocked")
            } catch (e: Exception) {
                Log.w(TAG, "$what failed: ${e.message}")
                _error.tryEmit(e.failureMessage("Couldn't play that playlist"))
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * What the queue looks like, to the detail that tells a replacement from no change.
     *
     * The queue item id rather than the track URI, because a replace builds fresh queue items
     * even when the same track ends up playing first. Keying on the URI meant that replaying a
     * playlist already sitting on its first track produced an identical reading, and the
     * button then stayed busy for the full timeout over a server that had answered at once.
     */
    private fun QueueState?.signature(): Pair<String?, Int>? =
        this?.let { it.currentItem?.queueItemId to it.totalItems }

    private fun queueSignature(): Pair<String?, Int>? = playerRepository.queueState.value.signature()

    /**
     * Wait until the server has actually acted on the queue.
     *
     * Nothing on screen changes while Music Assistant resolves a playlist, and resolving an
     * expanded track list is slow enough that Play All reads as a dead button: the measured
     * case sat silent for 100 seconds, and the natural response, pressing again, sent the
     * server another copy of the same work and made it slower still. So the button stays
     * busy until the queue moves.
     *
     * The queue is watched rather than the command's own answer, because `play_media` is
     * sent without waiting for one. Its answer would also arrive on a 30 s timeout, well
     * inside the time a large playlist legitimately takes, and reporting a failure there
     * would be wrong.
     *
     * [QUEUE_CHANGE_TIMEOUT_MS] only releases the button if the queue never moves at all. It
     * is not a deadline for the server: playback is unaffected either way, and a queue that
     * arrives later still plays.
     */
    private suspend fun awaitQueueChange(before: Pair<String?, Int>?) {
        val changed = withTimeoutOrNull(QUEUE_CHANGE_TIMEOUT_MS) {
            playerRepository.queueState.first { state -> state != null && state.signature() != before }
        }
        if (changed == null) {
            // Say so rather than just releasing the button. The command is sent without
            // waiting for an answer, so a refusal by the server (the 2026-09-23 incident
            // answered "There is nothing to play here") never reaches the catch below and
            // this silence is the only thing the listener would otherwise see.
            Log.w(TAG, "queue did not change within ${QUEUE_CHANGE_TIMEOUT_MS}ms of play")
            _error.tryEmit("Couldn't play that playlist. The server started nothing")
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
                _error.tryEmit(e.failureMessage("Couldn't remove that track"))
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
                _error.tryEmit(e.failureMessage("Couldn't move that track"))
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
            val blocked = blockedArtistUris.value.contains(uri)
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
