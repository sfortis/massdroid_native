package net.asksakis.massdroidv2.data.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Offset-based forward pager over a library endpoint, shared by the phone Library tabs and the
 * TV browse/home rows.
 *
 * One instance owns the paged list for one category/tab: [reload] fetches a fresh page 0
 * (cancelling any in-flight load so a stale slow response can never overwrite a newer one) and
 * [loadMore] appends the next page, de-duplicated by [key] so a LazyList never sees a duplicate
 * stable key. Paging stops once a short/empty page signals the end (no fixed cap, the whole
 * library is reachable by paging).
 *
 * The server offset always advances by the RAW page size; [augmentFirstPage] (merge extra matches
 * into page 0, e.g. genre-matched artists) and [transformPage] (client-side safety filtering)
 * only shape what is published, so client-side additions/removals can never shift later pages.
 * [onPageLoaded] receives every raw page for side effects (e.g. background enrichment).
 *
 * [fetch] is expected to read its remaining query params (search, sort, filters) live from the
 * owner's state: every param change MUST be followed by [reload], otherwise a later [loadMore]
 * appends pages from a different query onto the current list.
 *
 * Not thread-safe by design: [scope] is expected to be Main-confined (viewModelScope).
 */
class LibraryPager<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val augmentFirstPage: (suspend (List<T>) -> List<T>)? = null,
    private val transformPage: ((List<T>) -> List<T>)? = null,
    private val onPageLoaded: ((List<T>) -> Unit)? = null,
    private val fetch: suspend (limit: Int, offset: Int) -> List<T>,
) {
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private var job: Job? = null
    private var offset = 0
    private var endReached = false

    /** Replace the list with a fresh page 0, cancelling any in-flight load. */
    fun reload() = startReload(publishOnlyIfEmpty = false)

    /**
     * First page for a tab that has never loaded; no-op when data or an active load exists.
     * The result is published only if the list is STILL empty on completion, so a local
     * mutation racing the preload (optimistic favorite, a just-created playlist) is never
     * clobbered by a stale page 0.
     */
    fun reloadIfEmpty() {
        if (_items.value.isEmpty() && job?.isActive != true) startReload(publishOnlyIfEmpty = true)
    }

    private fun startReload(publishOnlyIfEmpty: Boolean) {
        job?.cancel()
        // LAZY start so the `job` field is assigned before the body can run (an immediate
        // dispatcher would otherwise execute the body undispatched, and a body that completes
        // before the assignment would fail the ownership check in finally, leaking the flag).
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            _loading.value = true
            try {
                val raw = fetch(pageSize, 0)
                val augmented = augmentFirstPage?.invoke(raw) ?: raw
                val page = transformPage?.invoke(augmented) ?: augmented
                if (!publishOnlyIfEmpty || _items.value.isEmpty()) {
                    offset = raw.size
                    endReached = raw.size < pageSize
                    _items.value = page
                    onPageLoaded?.invoke(raw)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reload failed: ${e.message}")
            } finally {
                // Only the job currently owning the slot clears the flag; a cancelled load must
                // not wipe the spinner of the reload that replaced it.
                if (job === coroutineContext[Job]) _loading.value = false
            }
        }
        job = newJob
        newJob.start()
    }

    fun loadMore() {
        if (endReached || job?.isActive == true) return
        job = scope.launch(start = CoroutineStart.LAZY) {
            _loadingMore.value = true
            try {
                val raw = fetch(pageSize, offset)
                offset += raw.size
                endReached = raw.size < pageSize
                if (raw.isNotEmpty()) {
                    val page = transformPage?.invoke(raw) ?: raw
                    _items.update { current ->
                        val seen = current.mapTo(HashSet(), key)
                        current + page.filter { seen.add(key(it)) }
                    }
                    onPageLoaded?.invoke(raw)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadMore failed: ${e.message}")
            } finally {
                // Same ownership rule as startReload: a cancelled load must not wipe the
                // spinner of the load that replaced it.
                if (job === coroutineContext[Job]) _loadingMore.value = false
            }
        }
        job?.start()
    }

    /**
     * Keep loading pages until the server has no more, for a caller that filters the list
     * itself and therefore cannot rely on scrolling to pull the rest in.
     *
     * A filter applied on top of a paged list is only as good as what has been paged: a
     * filter that matches three of the first fifty leaves a list too short to scroll, so
     * nothing ever asks for page two and the other matches stay invisible. [maxPages]
     * bounds a pathological library rather than the ordinary case.
     */
    fun loadAll(maxPages: Int = DEFAULT_MAX_PAGES) {
        if (endReached) return
        job?.cancel()
        job = scope.launch(start = CoroutineStart.LAZY) {
            // Both flags: this may have just cancelled a reload, whose own finally will
            // decline to clear _loading now that it no longer owns the slot.
            _loading.value = false
            _loadingMore.value = true
            try {
                var pages = 0
                while (!endReached && pages < maxPages) {
                    val raw = fetch(pageSize, offset)
                    offset += raw.size
                    endReached = raw.size < pageSize
                    pages++
                    if (raw.isEmpty()) break
                    val page = transformPage?.invoke(raw) ?: raw
                    _items.update { current ->
                        val seen = current.mapTo(HashSet(), key)
                        current + page.filter { seen.add(key(it)) }
                    }
                    onPageLoaded?.invoke(raw)
                }
                if (pages >= maxPages && !endReached) {
                    Log.w(TAG, "loadAll stopped at $maxPages pages with more available")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadAll failed: ${e.message}")
            } finally {
                if (job === coroutineContext[Job]) _loadingMore.value = false
            }
        }
        job?.start()
    }

    /** Mutate the published items in place (optimistic favorite/remove updates). */
    fun update(transform: (List<T>) -> List<T>) {
        _items.update(transform)
    }

    /** Drop all state (account switch / session reset). */
    fun clear() {
        job?.cancel()
        offset = 0
        endReached = false
        _items.value = emptyList()
        _loading.value = false
        _loadingMore.value = false
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 50

        /** Page ceiling for [loadAll]: 50 pages is 2500 items, far past any real library tab. */
        const val DEFAULT_MAX_PAGES = 50
        private const val TAG = "LibraryPager"
    }
}
