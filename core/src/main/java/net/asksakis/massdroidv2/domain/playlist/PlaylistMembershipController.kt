package net.asksakis.massdroidv2.domain.playlist

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.acceptsManualTracks
import net.asksakis.massdroidv2.domain.repository.MusicRepository

/**
 * The state and the server calls behind the "add to playlist" dialog, shared by every
 * screen that offers it.
 *
 * Membership is resolved lazily. The server has no command that answers "which playlists
 * hold this track", so the only way to draw the tick marks is to download the track list
 * of a playlist and look. Doing that for the whole library on open cost 64 seconds and
 * 4847 track objects against a real account of 104 playlists, and the dialog showed a
 * spinner for all of it. Callers therefore report which rows the listener can actually
 * see, through [onPlaylistsVisible], and only those are fetched, at most
 * [maxConcurrentChecks] at a time. Each playlist is fetched once per target track.
 *
 * A tick that has not arrived yet looks the same as a track that is not in the playlist,
 * so adding a duplicate is possible in the gap. That is harmless: the server drops a track
 * that the playlist already holds.
 */
class PlaylistMembershipController(
    private val musicRepository: MusicRepository,
    private val scope: CoroutineScope,
    private val maxConcurrentChecks: Int = DEFAULT_CONCURRENT_CHECKS
) {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /** The playlists a track may be added to, already filtered by [acceptsManualTracks]. */
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    /** True while the playlist list itself is being fetched, never during a tick check. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pendingPlaylistId = MutableStateFlow<String?>(null)

    /** The item id of the playlist currently being written to, or null. */
    val pendingPlaylistId: StateFlow<String?> = _pendingPlaylistId.asStateFlow()

    private val _containsTrack = MutableStateFlow<Set<String>>(emptySet())

    /** Uris of the playlists confirmed to hold the target track. */
    val containsTrack: StateFlow<Set<String>> = _containsTrack.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = ERROR_BUFFER)

    /** User facing failure messages, one per failed write. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val mutex = Mutex()
    private val checkPermits = Semaphore(maxConcurrentChecks)

    /** Playlist uris already fetched for the current [targetTrackUri]. */
    private val resolvedPlaylistUris = mutableSetOf<String>()

    private var targetTrackUri: String? = null

    /**
     * Bumped whenever the target track changes, so ticks still in flight from the
     * previous track are dropped rather than marking the wrong rows.
     */
    private var generation = 0

    /**
     * Bumped when the account behind the playlists changes, which is the only thing
     * that invalidates the list itself.
     *
     * It has to be separate from [generation]. Sharing one counter meant a track
     * change during a load discarded that load's result, while the reload it asked
     * for was refused because a load was still in flight, and nothing rescheduled
     * either: the dialog was left with no playlists at all. Which track is selected
     * has no bearing on which playlists exist.
     */
    private var listGeneration = 0

    /** A reload asked for while one was already running, run when that one ends. */
    private var reloadWhenIdle = false

    /**
     * Point the dialog at [trackUri] and make sure the playlist list is loaded.
     *
     * Changing the track throws away every tick and every record of what was already
     * fetched, because membership is a fact about one track. Pass [reload] to fetch the
     * playlist list again rather than keep the one already held, which is what opening
     * the dialog does so a playlist created elsewhere shows up.
     */
    fun open(trackUri: String?, reload: Boolean = false) {
        if (trackUri != targetTrackUri) {
            targetTrackUri = trackUri
            generation++
            _containsTrack.value = emptySet()
            scope.launch { mutex.withLock { resolvedPlaylistUris.clear() } }
        }
        loadPlaylists(force = reload)
    }


    /** Re-fetch the playlist list from the server, discarding the cached one. */
    fun reload() {
        loadPlaylists(force = true)
    }

    /** Drop every cached playlist and tick, for when the account behind them changes. */
    fun reset() {
        targetTrackUri = null
        generation++
        listGeneration++
        _playlists.value = emptyList()
        _containsTrack.value = emptySet()
        scope.launch { mutex.withLock { resolvedPlaylistUris.clear() } }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadPlaylists(force: Boolean) {
        if (_isLoading.value) {
            // Remember it rather than drop it, so a load asked for while another
            // was running still happens and the dialog cannot be left empty.
            if (force) reloadWhenIdle = true
            return
        }
        if (!force && _playlists.value.isNotEmpty()) return
        val currentGeneration = listGeneration
        scope.launch {
            _isLoading.value = true
            try {
                val loaded = musicRepository.getPlaylists(limit = PLAYLIST_LIMIT)
                    .filter { it.acceptsManualTracks }
                if (currentGeneration == listGeneration) _playlists.value = loaded
            } catch (e: Exception) {
                Log.w(TAG, "loadPlaylists failed: ${e.message}")
                _errors.tryEmit("Failed to load playlists")
            } finally {
                _isLoading.value = false
                val queued = reloadWhenIdle || currentGeneration != listGeneration
                reloadWhenIdle = false
                if (queued) loadPlaylists(force = true)
            }
        }
    }

    /**
     * Report the playlists on screen so their tick marks can be resolved.
     *
     * Safe to call on every scroll frame: a playlist already fetched for the current
     * track is skipped, and a fetch that fails is released so a later pass retries it.
     */
    fun onPlaylistsVisible(playlistUris: Collection<String>) {
        val track = targetTrackUri ?: return
        if (playlistUris.isEmpty()) return
        val byUri = _playlists.value.associateBy { it.uri }
        val candidates = playlistUris.mapNotNull { byUri[it] }
        if (candidates.isEmpty()) return
        val currentGeneration = generation
        scope.launch {
            val pending = claim(candidates, currentGeneration)
            if (pending.isEmpty()) return@launch
            coroutineScope {
                pending.forEach { playlist ->
                    launch {
                        checkPermits.withPermit {
                            resolveMembership(playlist, track, currentGeneration)
                        }
                    }
                }
            }
        }
    }

    private suspend fun claim(candidates: List<Playlist>, currentGeneration: Int): List<Playlist> =
        mutex.withLock {
            if (currentGeneration != generation) return@withLock emptyList()
            candidates.filter { resolvedPlaylistUris.add(it.uri) }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveMembership(playlist: Playlist, trackUri: String, currentGeneration: Int) {
        val holdsTrack = try {
            musicRepository.getPlaylistTracks(playlist.itemId, playlist.provider)
                .any { it.uri == trackUri }
        } catch (e: Exception) {
            Log.w(TAG, "membership check failed for ${playlist.uri}: ${e.message}")
            mutex.withLock {
                if (currentGeneration == generation) resolvedPlaylistUris.remove(playlist.uri)
            }
            return
        }
        if (!holdsTrack) return
        mutex.withLock {
            if (currentGeneration != generation) return@withLock
            _containsTrack.value = _containsTrack.value + playlist.uri
        }
    }

    /** Add the target track to [playlist]. */
    @Suppress("TooGenericExceptionCaught")
    fun add(playlist: Playlist, onDone: () -> Unit = {}) {
        val track = targetTrackUri ?: return
        if (_pendingPlaylistId.value != null) return
        val currentGeneration = generation
        scope.launch {
            _pendingPlaylistId.value = playlist.itemId
            try {
                musicRepository.addTrackToPlaylist(playlist, track)
                if (markContains(playlist.uri, holdsTrack = true, currentGeneration)) onDone()
            } catch (e: Exception) {
                Log.w(TAG, "add to ${playlist.uri} failed: ${e.message}")
                if (isPlaylistWriteUnsupported(e)) {
                    _playlists.value = _playlists.value.filterNot { it.uri == playlist.uri }
                    _errors.tryEmit("This playlist is read-only")
                } else {
                    _errors.tryEmit("Failed to add track to playlist")
                }
            } finally {
                _pendingPlaylistId.value = null
            }
        }
    }

    /** Remove the target track from [playlist]. */
    @Suppress("TooGenericExceptionCaught")
    fun remove(playlist: Playlist, onDone: () -> Unit = {}) {
        val track = targetTrackUri ?: return
        if (_pendingPlaylistId.value != null) return
        val currentGeneration = generation
        scope.launch {
            _pendingPlaylistId.value = playlist.itemId
            try {
                val tracks = musicRepository.getPlaylistTracks(playlist.itemId, playlist.provider)
                val position = tracks.indexOfFirst { it.uri == track }
                if (position >= 0) {
                    musicRepository.removeTrackFromPlaylist(playlist, position)
                }
                if (markContains(playlist.uri, holdsTrack = false, currentGeneration)) onDone()
            } catch (e: Exception) {
                Log.w(TAG, "remove from ${playlist.uri} failed: ${e.message}")
                _errors.tryEmit("Failed to remove track from playlist")
            } finally {
                _pendingPlaylistId.value = null
            }
        }
    }

    /**
     * Create a playlist named [name], put the target track in it, and hand the new
     * playlist to [onCreated] so callers can also show it wherever else they list playlists.
     */
    @Suppress("TooGenericExceptionCaught")
    fun createAndAdd(name: String, onCreated: (Playlist) -> Unit = {}) {
        val track = targetTrackUri ?: return
        val currentGeneration = generation
        scope.launch {
            try {
                val playlist = musicRepository.createPlaylist(name)
                musicRepository.addTrackToPlaylist(playlist, track)
                _playlists.value = _playlists.value + playlist
                markContains(playlist.uri, holdsTrack = true, currentGeneration)
                // Unconditional, unlike the tick above: the playlist now exists
                // whatever the dialog is pointed at, and callers use this to show
                // it wherever else they list playlists.
                onCreated(playlist)
            } catch (e: Exception) {
                Log.w(TAG, "createAndAdd failed: ${e.message}")
                _errors.tryEmit("Failed to create playlist")
            }
        }
    }

    /**
     * Record that [playlistUri] does or does not hold the track, unless the dialog
     * has moved on.
     *
     * A write that lands after the target track changed is answering a question
     * nobody is asking any more, and applying it would tick a row for the wrong
     * track.
     */
    private suspend fun markContains(
        playlistUri: String,
        holdsTrack: Boolean,
        currentGeneration: Int
    ): Boolean = mutex.withLock {
        if (currentGeneration != generation) return@withLock false
        resolvedPlaylistUris.add(playlistUri)
        _containsTrack.value = if (holdsTrack) {
            _containsTrack.value + playlistUri
        } else {
            _containsTrack.value - playlistUri
        }
        true
    }

    /**
     * Whether the server refused the write because the playlist cannot take tracks at all.
     *
     * The server reports this as a plain message, so the text has to be matched. It is a
     * safety net for a playlist that slipped past [acceptsManualTracks]: the row is dropped
     * from the list rather than left there to fail again.
     */
    private fun isPlaylistWriteUnsupported(error: Exception): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return error is MaApiException && READ_ONLY_MARKERS.any { it in message }
    }

    companion object {
        private const val TAG = "PlaylistMembership"
        private const val PLAYLIST_LIMIT = 200
        private const val DEFAULT_CONCURRENT_CHECKS = 6
        private const val ERROR_BUFFER = 4
        private val READ_ONLY_MARKERS = listOf(
            "read-only",
            "readonly",
            "not supported",
            "unsupported",
            "cannot add",
            "auto",
            "generated"
        )
    }
}
