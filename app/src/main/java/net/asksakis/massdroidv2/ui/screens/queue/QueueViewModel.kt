package net.asksakis.massdroidv2.ui.screens.queue

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.domain.model.Chapter
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

private const val TAG = "QueueVM"

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _queueItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueItems: StateFlow<List<QueueItem>> = _queueItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val isPlaying: StateFlow<Boolean> = playerRepository.selectedPlayer
        .map { it?.state == PlaybackState.PLAYING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val currentQueueItemId: StateFlow<String?> = playerRepository.queueState
        .map { it?.currentItem?.queueItemId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val players: StateFlow<List<Player>> = playerRepository.players
    val sendspinClientId = settingsRepository.sendspinClientId

    /**
     * Audiobook chapter state. An audiobook is a single queue item whose chapters
     * are seek markers, so when the current item is an audiobook the queue surface
     * renders its chapters instead of the (single-row) item list.
     */
    val isAudiobook: StateFlow<Boolean> = playerRepository.queueState
        .map { it?.currentItem?.track?.mediaType == MediaType.AUDIOBOOK }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val chapters: StateFlow<List<Chapter>> = playerRepository.queueState
        .map { it?.currentItem?.track?.chapters ?: emptyList() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Index into [chapters] of the chapter containing the current position, or -1. */
    val currentChapterIndex: StateFlow<Int> =
        combine(playerRepository.elapsedTime, chapters) { elapsed, chs ->
            if (chs.isEmpty()) -1 else chs.indexOfLast { elapsed + 0.001 >= it.start }.coerceAtLeast(0)
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val audiobookTitle: StateFlow<String?> = playerRepository.queueState
        .map { it?.currentItem?.track?.takeIf { t -> t.mediaType == MediaType.AUDIOBOOK }?.name }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val audiobookDurationSec: StateFlow<Double> = playerRepository.queueState
        .map { it?.currentItem?.let { item -> item.track?.duration ?: item.duration } ?: 0.0 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    val selectedPlayerId: String?
        get() = playerRepository.selectedPlayer.value?.playerId

    /**
     * The active queue id to render and operate on. For a solo player this is
     * its own id, but for a synced group member it is the GROUP queue id (the
     * key the coordinator snapshot is built under). Matching/operating on the
     * raw [selectedPlayerId] showed the member's stale own queue (or spun
     * forever when the snapshot never matched). Falls back to the player id
     * before the first queue state arrives.
     */
    private val queueId: String?
        get() = playerRepository.queueState.value?.queueId ?: selectedPlayerId

    init {
        // Single source of truth for the displayed list + loading flag, derived
        // race-free from (canonical snapshot, active queue id). Two independent
        // collectors writing _isLoading could interleave so the loading reset ran
        // after the populate, leaving the spinner stuck on a warm snapshot.
        //
        // The active queue id is queueState.queueId (the group queue for a synced
        // member, the own id for a solo), NOT selectedPlayer.playerId: two members
        // of one group share a single queue, so switching between them keeps the
        // same queueId and must keep the list rather than flip to a never-clearing
        // loading state. [shownQueueId] tracks what the list currently represents;
        // when it changes we clear + show loading until a matching snapshot lands.
        viewModelScope.launch {
            var shownQueueId: String? = null
            combine(
                playerRepository.queueItems,
                playerRepository.queueState.map { it?.queueId }.distinctUntilChanged()
            ) { snapshot, qId -> qId to snapshot }
                .collect { (qId, snapshot) ->
                    if (qId == null) {
                        shownQueueId = null
                        _queueItems.value = emptyList()
                        _isLoading.value = false
                        return@collect
                    }
                    if (qId != shownQueueId) {
                        shownQueueId = qId
                        _queueItems.value = emptyList()
                        _isLoading.value = true
                    }
                    // Wait for the snapshot keyed by THIS active queue. Until then
                    // we stay in the loading state for the new queue.
                    if (snapshot == null || snapshot.queueId != qId) return@collect
                    // Replace only on a real change so an in-flight optimistic
                    // move/remove survives until the server echo reorders ids.
                    val newIds = snapshot.items.map { it.queueItemId }
                    if (_queueItems.value.map { it.queueItemId } != newIds) {
                        _queueItems.value = snapshot.items
                    }
                    _isLoading.value = false
                }
        }
        // Force the coordinator to (re)fetch on every active-queue change so a
        // missing or stale shared snapshot self-heals on open instead of leaving
        // the screen spinning. The coordinator single-flights + dedups, so this
        // collapses with its own debounced trigger into one RPC. Mirrors the TV
        // queue VM, which already followed this pattern.
        viewModelScope.launch {
            playerRepository.queueState
                .map { it?.queueId }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { qId -> runCatching { playerRepository.refreshQueueItems(qId) } }
        }
    }

    /**
     * Re-pull the queue snapshot from the coordinator. Used by recovery
     * paths (failed move, playNext) where the local optimistic state
     * may have drifted from the server. The coordinator runs at most
     * one in-flight fetch, so multiple recovery callers in quick
     * succession still produce a single RPC.
     */
    private fun loadQueue() {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                playerRepository.refreshQueueItems(id)
            } catch (e: Exception) {
                Log.w(TAG, "loadQueue refresh failed: ${e.message}")
            }
        }
    }

    fun playIndex(index: Int) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.playQueueIndex(id, index)
            } catch (e: Exception) {
                Log.w(TAG, "playIndex failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    /** Jump to a chapter by seeking to its start within the current audiobook item. */
    fun seekToChapter(chapter: Chapter) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                playerRepository.seek(id, chapter.start)
            } catch (e: Exception) {
                Log.w(TAG, "seekToChapter failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun removeItem(itemId: String) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.deleteQueueItem(id, itemId)
                _queueItems.value = _queueItems.value.filter { it.queueItemId != itemId }
            } catch (e: Exception) {
                Log.w(TAG, "removeItem failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun moveItemUp(queueItemId: String) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.moveQueueItem(id, queueItemId, -1)
                // Local swap
                val list = _queueItems.value.toMutableList()
                val idx = list.indexOfFirst { it.queueItemId == queueItemId }
                if (idx > 0) {
                    list[idx] = list[idx - 1].also { list[idx - 1] = list[idx] }
                    _queueItems.value = list
                }
            } catch (e: Exception) {
                Log.w(TAG, "moveItemUp failed: ${e.message}", e)
                _error.tryEmit(parseQueueError(e))
            }
        }
    }

    fun moveItemDown(queueItemId: String) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.moveQueueItem(id, queueItemId, 1)
                // Local swap
                val list = _queueItems.value.toMutableList()
                val idx = list.indexOfFirst { it.queueItemId == queueItemId }
                if (idx >= 0 && idx < list.size - 1) {
                    list[idx] = list[idx + 1].also { list[idx + 1] = list[idx] }
                    _queueItems.value = list
                }
            } catch (e: Exception) {
                Log.w(TAG, "moveItemDown failed: ${e.message}", e)
                _error.tryEmit(parseQueueError(e))
            }
        }
    }

    fun moveItem(queueItemId: String, fromIndex: Int, toIndex: Int) {
        val id = queueId ?: return
        if (fromIndex == toIndex) return
        viewModelScope.launch {
            try {
                musicRepository.moveQueueItem(id, queueItemId, toIndex - fromIndex)
                val list = _queueItems.value.toMutableList()
                if (fromIndex in list.indices && toIndex in list.indices) {
                    val item = list.removeAt(fromIndex)
                    list.add(toIndex, item)
                    _queueItems.value = list
                }
            } catch (e: Exception) {
                Log.w(TAG, "moveItem failed: ${e.message}", e)
                _error.tryEmit(parseQueueError(e))
                loadQueue()
            }
        }
    }

    fun playNext(queueItemId: String, currentIndex: Int) {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.moveQueueItem(id, queueItemId, -(currentIndex - 1))
                loadQueue()
            } catch (e: Exception) {
                Log.w(TAG, "playNext failed: ${e.message}", e)
                _error.tryEmit(parseQueueError(e))
            }
        }
    }

    fun clearQueue() {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.clearQueue(id)
                _queueItems.value = emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "clearQueue failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun transferQueue(targetId: String) {
        val id = queueId ?: return
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    musicRepository.transferQueue(id, targetId)
                    playerRepository.selectPlayer(targetId)
                } catch (e: Exception) {
                    Log.w(TAG, "transferQueue failed: ${e.message}")
                    _error.tryEmit("Transfer failed")
                }
            }
        }
    }

    private fun parseQueueError(e: Exception): String {
        val msg = e.message ?: return "Operation failed"
        if (msg.contains("already played/buffered", ignoreCase = true)) {
            return "Cannot move buffered track"
        }
        if (msg.contains("Timed out", ignoreCase = true)) {
            return "Server not responding"
        }
        return "Operation failed"
    }

    fun getPlaylists() = viewModelScope.launch {
        try { _playlists.value = musicRepository.getPlaylists() } catch (_: Exception) { }
    }

    private val _playlists = MutableStateFlow<List<net.asksakis.massdroidv2.domain.model.Playlist>>(emptyList())
    val playlists: StateFlow<List<net.asksakis.massdroidv2.domain.model.Playlist>> = _playlists.asStateFlow()

    fun saveQueueToPlaylist(playlist: net.asksakis.massdroidv2.domain.model.Playlist) {
        val trackUris = _queueItems.value.mapNotNull { it.track?.uri }.distinct()
        if (trackUris.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            try {
                // Get existing tracks to avoid duplicates
                val existing = try {
                    musicRepository.getPlaylistTracks(playlist.itemId, playlist.provider).map { it.uri }.toSet()
                } catch (_: Exception) { emptySet() }
                val newUris = trackUris.filter { it !in existing }
                Log.d(TAG, "Save queue: ${trackUris.size} queue tracks, ${existing.size} existing, ${newUris.size} new")
                for (uri in newUris) {
                    musicRepository.addTrackToPlaylist(playlist, uri)
                    added++
                }
                val msg = if (newUris.isEmpty()) "All ${trackUris.size} tracks already in ${playlist.name}"
                    else "Added $added tracks to ${playlist.name}" +
                        if (trackUris.size > newUris.size) " (${trackUris.size - newUris.size} already existed)" else ""
                _error.tryEmit(msg)
            } catch (e: Exception) {
                Log.w(TAG, "saveQueueToPlaylist failed: ${e.message}")
                _error.tryEmit(
                    if (added > 0) {
                        "Added $added of ${trackUris.size} tracks to ${playlist.name}, then failed"
                    } else {
                        "Failed to save queue: ${e.message}"
                    }
                )
            }
        }
    }

    fun saveQueueToNewPlaylist(name: String) {
        val id = queueId ?: return
        if (_queueItems.value.isEmpty()) return
        viewModelScope.launch {
            try {
                musicRepository.saveQueueAsPlaylist(id, name)
                _error.tryEmit("Created '$name' with ${_queueItems.value.size} tracks")
            } catch (e: Exception) {
                Log.w(TAG, "saveQueueToNewPlaylist failed: ${e.message}")
                _error.tryEmit("Failed to create playlist: ${e.message}")
            }
        }
    }

    fun suggestedPlaylistName(): String {
        val trackName = playerRepository.queueState.value?.currentItem?.track?.name
        return if (trackName != null) "$trackName's Playlist" else "My Queue"
    }
}
