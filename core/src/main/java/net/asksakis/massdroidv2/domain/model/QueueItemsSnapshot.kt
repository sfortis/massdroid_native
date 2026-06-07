package net.asksakis.massdroidv2.domain.model

/**
 * Canonical, single-source-of-truth view of the items in the currently
 * selected queue. Owned by `QueueItemsCoordinator` and exposed through
 * `PlayerRepository.queueItems`.
 *
 * All consumers that need queue items (Android Auto, NowPlaying adjacent
 * artwork, Queue screen, blocked-artist cleanup) should observe this
 * snapshot instead of calling `MusicRepository.getQueueItems()`
 * directly. That keeps the server from receiving the same RPC three or
 * four times per queue change, one per consumer.
 *
 * @property fetchedAt epoch-ms when the snapshot was retrieved; useful
 *           for callers that need to reason about staleness without
 *           triggering another fetch.
 */
data class QueueItemsSnapshot(
    val queueId: String,
    val items: List<QueueItem>,
    val fetchedAt: Long,
)
