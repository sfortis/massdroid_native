package net.asksakis.massdroidv2.ui.screens.queue

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

private const val TAG = "QueueVM"

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository
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

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    val selectedPlayerId: String?
        get() = playerRepository.selectedPlayer.value?.playerId
    private val queueId: String?
        get() = selectedPlayerId
    private var queueLoadGeneration = 0L

    init {
        viewModelScope.launch {
            playerRepository.selectedPlayer
                .map { it?.playerId }
                .distinctUntilChanged()
                .collect {
                    _queueItems.value = emptyList()
                    loadQueue()
                }
        }
        viewModelScope.launch {
            playerRepository.queueItemsChanged.collect { changedQueueId ->
                if (changedQueueId == queueId) loadQueue()
            }
        }
    }

    private fun loadQueue() {
        val id = queueId ?: run {
            _queueItems.value = emptyList()
            _isLoading.value = false
            return
        }
        val generation = ++queueLoadGeneration
        val isInitialLoad = _queueItems.value.isEmpty()
        viewModelScope.launch {
            if (isInitialLoad) _isLoading.value = true
            try {
                val items = musicRepository.getQueueItems(id)
                if (generation != queueLoadGeneration || queueId != id) return@launch
                _queueItems.value = items
            } catch (e: Exception) {
                Log.w(TAG, "loadQueue failed: ${e.message}")
            } finally {
                if (generation == queueLoadGeneration && isInitialLoad) {
                    _isLoading.value = false
                }
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
            try {
                musicRepository.transferQueue(id, targetId)
            } catch (e: Exception) {
                Log.w(TAG, "transferQueue failed: ${e.message}")
                _error.tryEmit("Transfer failed")
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
}
