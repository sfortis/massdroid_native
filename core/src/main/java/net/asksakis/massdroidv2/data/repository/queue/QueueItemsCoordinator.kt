package net.asksakis.massdroidv2.data.repository.queue

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.asksakis.massdroidv2.domain.model.QueueItemsSnapshot
import net.asksakis.massdroidv2.domain.model.QueueState
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Canonical owner of "items in the currently-selected queue". Replaces
 * the historic pattern where the AA controller, NowPlaying adjacent
 * artwork lookup, the Queue screen, and the blocked-artist cleanup
 * each issued their own `player_queues/items` RPC for the same
 * `queue_id` within milliseconds of each other (three to five
 * duplicates per queue change in production logs).
 *
 * Lifecycle: the coordinator is a `@Singleton`. `bind()` is called once
 * by `PlayerRepositoryImpl` after its own flows are constructed, which
 * starts the trigger collectors. After bind, the coordinator runs a
 * single mutex-guarded fetch per debounced trigger and fans the result
 * out via [queueItems].
 *
 * Two trigger sources merged:
 *  - `queueItemsChanged` (SharedFlow<String>): emitted on every
 *    server-side QUEUE_ITEMS_UPDATED / QUEUE_UPDATED for the selected
 *    queue. Lots of these arrive on cold-start and after `play_media`.
 *  - `queueState.queueId` (distinct): emitted when the user switches
 *    players or the saved selection is restored on Connected.
 *
 * The 200 ms debounce collapses the cold-start burst into one fetch
 * without dropping legitimate later updates (a follow-up
 * QUEUE_ITEMS_UPDATED >200 ms after the last one fires its own
 * fetch). The [fetchMutex] guarantees that even if two collectors
 * race past the debounce, only one RPC is in flight at a time.
 *
 * Consumers that need more bytes than [FETCH_LIMIT] should fall back
 * to direct `MusicRepository.getQueueItems()` calls; for current use
 * cases (AA list, NowPlaying adjacent, blocked-artist cleanup) 500
 * items is well above what we render.
 */
@Singleton
class QueueItemsCoordinator @Inject constructor(
    private val musicRepository: dagger.Lazy<MusicRepository>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueItems = MutableStateFlow<QueueItemsSnapshot?>(null)
    val queueItems: StateFlow<QueueItemsSnapshot?> = _queueItems.asStateFlow()

    private val fetchMutex = Mutex()

    @Volatile
    private var bound = false

    /**
     * Wire the coordinator to the owning repository's flows. Must be
     * called exactly once from `PlayerRepositoryImpl.init`; subsequent
     * calls are ignored. Kept as a post-construction hook rather than
     * a constructor parameter so the coordinator does not need a
     * `dagger.Lazy<PlayerRepository>` reference (which would form a
     * dependency cycle with the repository, since the repository
     * already injects this coordinator).
     */
    @OptIn(FlowPreview::class)
    fun bind(
        queueState: StateFlow<QueueState?>,
        queueItemsChanged: SharedFlow<String>,
    ) {
        if (bound) return
        bound = true

        scope.launch {
            merge(
                queueItemsChanged,
                queueState
                    .map { it?.queueId }
                    .filterNotNull()
                    .distinctUntilChanged(),
            )
                .debounce(DEBOUNCE_MS)
                .collectLatest { queueId -> fetchSnapshot(queueId, reason = "trigger") }
        }

        // Drop the snapshot when the queue clears so consumers don't
        // keep showing stale items for a queue that no longer exists.
        scope.launch {
            queueState
                .map { it?.queueId }
                .distinctUntilChanged()
                .collect { queueId ->
                    if (queueId == null) _queueItems.value = null
                }
        }
    }

    /**
     * Force an immediate refresh, bypassing the debounce. Used by code
     * paths that explicitly need a fresh snapshot (e.g. blocked-artist
     * cleanup after `select_player`) and cannot wait for the next
     * server event.
     */
    suspend fun refresh(queueId: String): QueueItemsSnapshot? {
        fetchSnapshot(queueId, reason = "refresh")
        return _queueItems.value?.takeIf { it.queueId == queueId }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchSnapshot(queueId: String, reason: String) {
        fetchMutex.withLock {
            // Coalesce within the dedup window: if we already have a
            // fresh snapshot for the same queueId from a recent fetch
            // (e.g. the blocked-cleanup path called refresh(), and now
            // the debounced merge trigger lands for the same queueId
            // change), skip the second RPC. The first fetch's data is
            // still authoritative for this window.
            val now = System.currentTimeMillis()
            val current = _queueItems.value
            if (current != null && current.queueId == queueId &&
                now - current.fetchedAt < DEDUP_WINDOW_MS
            ) {
                Log.d(TAG, "Snapshot fetch skipped (dedup): $queueId, reason=$reason")
                return@withLock
            }
            try {
                val items = musicRepository.get()
                    .getQueueItems(queueId, limit = FETCH_LIMIT, offset = 0)
                _queueItems.value = QueueItemsSnapshot(
                    queueId = queueId,
                    items = items,
                    fetchedAt = System.currentTimeMillis(),
                )
                Log.d(TAG, "Snapshot updated: $queueId (${items.size} items, reason=$reason)")
            } catch (e: Exception) {
                // Keep the previous snapshot visible. The next server
                // event will re-trigger the fetch, so a single failed
                // attempt should not leave consumers with empty data.
                Log.w(TAG, "Snapshot fetch failed for $queueId (reason=$reason): ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "QueueItemsCoord"
        private const val DEBOUNCE_MS = 200L
        private const val FETCH_LIMIT = 500
        /**
         * Two trigger paths can land on the same `queueId` within
         * milliseconds of each other (forced refresh from
         * blocked-cleanup + the debounced merge trigger). Skip the
         * second fetch when the snapshot we just stored is still this
         * fresh: server emits a new event for any real change anyway.
         */
        private const val DEDUP_WINDOW_MS = 500L
    }
}
