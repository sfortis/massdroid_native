package net.asksakis.massdroidv2.domain.playlist

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.SeedAddOutcome
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeed
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSummary
import net.asksakis.massdroidv2.domain.repository.SmartPlaylistRepository

/**
 * The state and the server calls behind the "add to smart playlist" picker, for any screen
 * that offers it. The picker is open while [seed] is set. Every add or create ends it, and
 * the outcome arrives on [events] for the screen to word.
 */
class SmartPlaylistSeedController(
    private val repository: SmartPlaylistRepository,
    private val scope: CoroutineScope
) {
    sealed interface Event {
        data class Added(val playlistName: String) : Event
        data class AlreadyPresent(val itemName: String, val playlistName: String) : Event
        data class Full(val playlistName: String) : Event
        data class Created(val playlistName: String) : Event
        data class LoadFailed(val error: Exception) : Event
        data class AddFailed(val error: Exception) : Event
        data class CreateFailed(val error: Exception) : Event
    }

    /** True while the server can take seeds, so the action is worth offering. */
    val available: Flow<Boolean> = repository.available

    private val _seed = MutableStateFlow<SmartPlaylistSeed?>(null)

    /** The track or artist being added; null while the picker is closed. */
    val seed: StateFlow<SmartPlaylistSeed?> = _seed.asStateFlow()

    private val _playlists = MutableStateFlow<List<SmartPlaylistSummary>>(emptyList())
    val playlists: StateFlow<List<SmartPlaylistSummary>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pendingPlaylistId = MutableStateFlow<String?>(null)

    /** The playlist being written to, [NEW_PLAYLIST_ID] while creating, or null. */
    val pendingPlaylistId: StateFlow<String?> = _pendingPlaylistId.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var loadJob: Job? = null

    fun open(seed: SmartPlaylistSeed) {
        _seed.value = seed
        reload()
    }

    fun close() {
        loadJob?.cancel()
        _seed.value = null
        _isLoading.value = false
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _isLoading.value = true
            try {
                _playlists.value = repository.seedPlaylists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "seedPlaylists failed: ${e.message}")
                _events.tryEmit(Event.LoadFailed(e))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun add(playlist: SmartPlaylistSummary) {
        val seed = _seed.value ?: return
        if (_pendingPlaylistId.value != null) return
        // Claimed before the launch, so a second tap is refused whatever the dispatcher.
        _pendingPlaylistId.value = playlist.playlistId
        scope.launch {
            try {
                val event = when (repository.addSeed(playlist.playlistId, seed)) {
                    SeedAddOutcome.ADDED -> Event.Added(playlist.name)
                    SeedAddOutcome.ALREADY_PRESENT -> Event.AlreadyPresent(seed.name, playlist.name)
                    SeedAddOutcome.FULL -> Event.Full(playlist.name)
                }
                _events.tryEmit(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "addSeed to ${playlist.playlistId} failed: ${e.message}")
                _events.tryEmit(Event.AddFailed(e))
            } finally {
                _pendingPlaylistId.value = null
                closeIfStillFor(seed)
            }
        }
    }

    fun create(name: String) {
        val seed = _seed.value ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || _pendingPlaylistId.value != null) return
        _pendingPlaylistId.value = NEW_PLAYLIST_ID
        scope.launch {
            try {
                repository.createWithSeed(trimmed, seed)
                _events.tryEmit(Event.Created(trimmed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "createWithSeed failed: ${e.message}")
                _events.tryEmit(Event.CreateFailed(e))
            } finally {
                _pendingPlaylistId.value = null
                closeIfStillFor(seed)
            }
        }
    }

    /** A late answer must not close a picker reopened meanwhile for another item. */
    private fun closeIfStillFor(seed: SmartPlaylistSeed) {
        if (_seed.value === seed) close()
    }

    companion object {
        private const val TAG = "SmartPlaylistSeeds"
        private const val EVENT_BUFFER = 4

        /** [pendingPlaylistId] while a new playlist is being created. */
        const val NEW_PLAYLIST_ID = "new"
    }
}
