package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Chapter
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * Read + play view of the selected player's active queue. Items come from the
 * shared :core [PlayerRepository.queueItems] snapshot (one RPC per queue change,
 * shared with every other consumer); tapping a row plays that index.
 */
@HiltViewModel
class TvQueueViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
) : ViewModel() {

    // Only surface items whose snapshot matches the currently selected queue, so
    // a stale snapshot from a previous player never leaks in during a switch.
    val items: StateFlow<List<QueueItem>> =
        combine(playerRepository.queueItems, playerRepository.queueState) { snapshot, queue ->
            val queueId = queue?.queueId
            if (snapshot != null && queueId != null && snapshot.queueId == queueId) {
                snapshot.items
            } else {
                emptyList()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val fetchFailed = MutableStateFlow(false)

    /**
     * Whether [items] holds the selected queue yet. Until the snapshot for this queue arrives
     * the list is empty, and the screen used to say "Queue is empty" while it loaded. A failed
     * fetch keeps the old snapshot, so without [fetchFailed] the screen would load forever.
     */
    val load: StateFlow<TvLoad> =
        combine(playerRepository.queueItems, playerRepository.queueState, fetchFailed) { snapshot, queue, failed ->
            val queueId = queue?.queueId
            when {
                queueId == null || snapshot?.queueId == queueId -> TvLoad.READY
                failed -> TvLoad.FAILED
                else -> TvLoad.LOADING
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvLoad.LOADING)

    /** queueItemId of the currently playing item, for highlighting. */
    val currentItemId: StateFlow<String?> = playerRepository.queueState
        .map { it?.currentItem?.queueItemId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // An audiobook is one queue item; its "queue" is its chapters. Match the
    // mobile app: show the chapters and let a tap seek to one.
    val isAudiobook: StateFlow<Boolean> = playerRepository.queueState
        .map { it?.currentItem?.track?.mediaType == MediaType.AUDIOBOOK }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val chapters: StateFlow<List<Chapter>> = playerRepository.queueState
        .map { it?.currentItem?.track?.chapters ?: emptyList() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Index of the chapter containing the current position, or -1. */
    val currentChapterIndex: StateFlow<Int> =
        combine(playerRepository.elapsedTime, chapters) { elapsed, chs ->
            if (chs.isEmpty()) -1 else chs.indexOfLast { elapsed + 0.001 >= it.start }.coerceAtLeast(0)
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    init {
        // Force a fresh fetch whenever the selected queue id becomes available or
        // changes. The coordinator already fetches on player switch; this also
        // covers the case where the snapshot is stale/missing when we open.
        viewModelScope.launch {
            playerRepository.queueState
                .map { it?.queueId }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { queueId -> fetch(queueId) }
        }
    }

    fun retry() {
        val queueId = playerRepository.queueState.value?.queueId ?: return
        viewModelScope.launch { fetch(queueId) }
    }

    private suspend fun fetch(queueId: String) {
        fetchFailed.value = false
        runCatching { playerRepository.refreshQueueItems(queueId) }
        // refreshQueueItems reports nothing; a fetch that failed left no snapshot for this queue.
        // A queue that changed meanwhile has its own fetch coming, so it is not marked failed.
        fetchFailed.value = playerRepository.queueState.value?.queueId == queueId &&
            playerRepository.queueItems.value?.queueId != queueId
    }

    fun playIndex(index: Int) {
        val queueId = playerRepository.queueState.value?.queueId ?: return
        viewModelScope.launch { runCatching { musicRepository.playQueueIndex(queueId, index) } }
    }

    /** Seek the selected player to a chapter's start (audiobook navigation). */
    fun seekToChapter(chapter: Chapter) {
        val playerId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch { runCatching { playerRepository.seek(playerId, chapter.start) } }
    }
}
