package net.asksakis.massdroidv2.data.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Global concurrency cap for bulk Last.fm -> MA resolution RPCs (similar-artist
 * resolution, Discover recommendation resolution, ...).
 *
 * The Music Assistant server runs on a single event loop behind one shared WS
 * pipeline. Firing dozens of `music/search` + `music/artists/get` at once (the
 * unthrottled `async {}.awaitAll()` loops) bursts ~40 RPCs/sec, starves
 * latency-sensitive traffic (seek/play/pause/Sendspin) and, worse, the bulk
 * responses themselves don't return in time so the feature it feeds (e.g. the
 * artist Similar Artists row) silently comes back empty.
 *
 * A fixed inter-call delay was tried before and reverted for being too slow
 * (it serialised the work). Instead we keep the calls parallel but bound the
 * number in flight. The semaphore is a single shared instance so the cap is on
 * the COMBINED in-flight count across every caller, not per call site.
 */
const val MA_BULK_MAX_CONCURRENCY = 4

private val maBulkRpcSemaphore = Semaphore(MA_BULK_MAX_CONCURRENCY)

/**
 * Map [block] over [items] concurrently, with at most [MA_BULK_MAX_CONCURRENCY]
 * invocations in flight at any moment (shared globally). Drop-in replacement for
 * `items.map { async { block(it) } }.awaitAll()` when [block] issues MA RPCs.
 */
suspend fun <T, R> Iterable<T>.mapMaBounded(block: suspend (T) -> R): List<R> =
    coroutineScope {
        map { item ->
            async { maBulkRpcSemaphore.withPermit { block(item) } }
        }.awaitAll()
    }
