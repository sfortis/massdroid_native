package net.asksakis.massdroidv2.ui.screens.queue

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.domain.model.Chapter
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.model.QueueItemsSnapshot
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

private const val TAG = "QueueVM"
private const val PAGE_SIZE = 500

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

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** True once the user (or save flow) has loaded pages beyond the coordinator snapshot. */
    private var paginatedBeyond = false

    /**
     * Serializes page fetches so the on-scroll [loadMore] and the
     * save-all [fetchAllRemainingPages] paths can't read the same
     * `offset` concurrently and waste a duplicate RPC.
     */
    private val pageMutex = Mutex()

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
    private val queueId: String?
        get() = selectedPlayerId

    init {
        // Clear the local list whenever the selected player changes so
        // the screen briefly shows the loading state instead of the
        // previous player's queue while the coordinator catches up.
        viewModelScope.launch {
            playerRepository.selectedPlayer
                .map { it?.playerId }
                .distinctUntilChanged()
                .collect {
                    _queueItems.value = emptyList()
                    _hasMore.value = false
                    paginatedBeyond = false
                    _isLoading.value = true
                }
        }
        // First page comes from the shared coordinator snapshot; additional
        // pages are fetched on demand via [loadMore] when the user scrolls.
        viewModelScope.launch {
            playerRepository.queueItems.collect { snapshot ->
                val currentQueueId = queueId
                if (snapshot == null || currentQueueId == null) {
                    if (currentQueueId == null) {
                        _queueItems.value = emptyList()
                        _hasMore.value = false
                        paginatedBeyond = false
                        _isLoading.value = false
                    }
                    return@collect
                }
                if (snapshot.queueId != currentQueueId) return@collect
                applySnapshot(snapshot)
                _isLoading.value = false
            }
        }
    }

    /**
     * Merge a coordinator refresh into the locally paginated list. When the
     * user has scrolled beyond the first page, keep the tail as long as the
     * first-page item ids still match; otherwise reset to the snapshot only.
     */
    private fun applySnapshot(snapshot: QueueItemsSnapshot) {
        val firstPage = snapshot.items
        val current = _queueItems.value
        if (!paginatedBeyond || current.size <= firstPage.size) {
            _queueItems.value = firstPage
            paginatedBeyond = false
        } else {
            val snapshotPrefix = firstPage.map { it.queueItemId }
            val currentPrefix = current.take(firstPage.size).map { it.queueItemId }
            if (snapshotPrefix == currentPrefix) {
                _queueItems.value = firstPage + current.drop(firstPage.size)
            } else {
                _queueItems.value = firstPage
                paginatedBeyond = false
            }
        }
        _hasMore.value = firstPage.size == PAGE_SIZE
    }

    /** Load the next page when the user scrolls near the end of the list. */
    fun loadMore() {
        val id = queueId ?: return
        if (_isLoadingMore.value || !_hasMore.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                appendNextPage(id)
            } catch (e: Exception) {
                Log.w(TAG, "loadMore failed: ${e.message}")
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private suspend fun appendNextPage(queueId: String) {
        pageMutex.withLock {
            // Read offset and append under the lock so a concurrent caller
            // never fetches the same page twice.
            val offset = _queueItems.value.size
            val page = musicRepository.getQueueItems(queueId, limit = PAGE_SIZE, offset = offset)
            if (page.isEmpty()) {
                _hasMore.value = false
                return
            }
            val existingIds = _queueItems.value.map { it.queueItemId }.toSet()
            val newItems = page.filter { it.queueItemId !in existingIds }
            if (newItems.isNotEmpty()) {
                paginatedBeyond = true
                _queueItems.value = _queueItems.value + newItems
            }
            _hasMore.value = page.size == PAGE_SIZE
        }
    }

    private suspend fun fetchAllRemainingPages() {
        val id = queueId ?: return
        while (_hasMore.value) {
            appendNextPage(id)
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

    /**
     * Move an item that is already in the queue so it plays right after the
     * currently playing track.
     *
     * The server's `move_item` takes a *relative* `pos_shift`, so the target
     * slot is `currentPlayingIndex + 1`, not absolute index 1: in a queue where
     * tracks have already played the current item sits at `current_index > 0`,
     * and shifting to index 1 would bury the item in the already-played region
     * above the cursor (where it never plays next). This mirrors the same
     * `toIndex - fromIndex` convention used by drag-reorder ([moveItem]).
     */
    fun playNext(queueItemId: String) {
        val id = queueId ?: return
        val items = _queueItems.value
        val sourceIndex = items.indexOfFirst { it.queueItemId == queueItemId }
        if (sourceIndex < 0) return

        val currentItemId = playerRepository.queueState.value?.currentItem?.queueItemId
        val currentIndex = items.indexOfFirst { it.queueItemId == currentItemId }
            .takeIf { it >= 0 }
            ?: playerRepository.queueState.value?.currentIndex
            ?: 0
        val targetIndex = (currentIndex + 1).coerceAtMost(items.size - 1)
        val posShift = targetIndex - sourceIndex
        if (posShift == 0) return

        viewModelScope.launch {
            try {
                musicRepository.moveQueueItem(id, queueItemId, posShift)
                // Optimistic reorder so the row jumps immediately; the
                // coordinator refresh below reconciles with the server.
                val list = _queueItems.value.toMutableList()
                val from = list.indexOfFirst { it.queueItemId == queueItemId }
                if (from >= 0) {
                    val moved = list.removeAt(from)
                    list.add(targetIndex.coerceIn(0, list.size), moved)
                    _queueItems.value = list
                }
                loadQueue()
            } catch (e: Exception) {
                Log.w(TAG, "playNext failed: ${e.message}", e)
                _error.tryEmit(parseQueueError(e))
                loadQueue()
            }
        }
    }

    fun clearQueue() {
        val id = queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.clearQueue(id)
                _queueItems.value = emptyList()
                _hasMore.value = false
                paginatedBeyond = false
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
        if (_queueItems.value.isEmpty() && !_hasMore.value) return
        viewModelScope.launch {
            var added = 0
            try {
                fetchAllRemainingPages()
                val trackUris = _queueItems.value.mapNotNull { it.track?.uri }.distinct()
                if (trackUris.isEmpty()) return@launch
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
                val trackCount = _queueItems.value.mapNotNull { it.track?.uri }.distinct().size
                _error.tryEmit(
                    if (added > 0) {
                        "Added $added of $trackCount tracks to ${playlist.name}, then failed"
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
