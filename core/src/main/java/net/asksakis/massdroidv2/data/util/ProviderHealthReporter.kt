package net.asksakis.massdroidv2.data.util

import android.os.SystemClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * View-agnostic signal that bulk MA resolution (Last.fm similar-artist resolution, Discover
 * recommendations, ...) couldn't complete because `music/search` / `music/artists/get` timed out.
 *
 * The MA server gathers every provider for a search with no per-provider timeout, so one slow or
 * rate-limited provider hangs the whole call. The resolvers cap each call with a short timeout and
 * report here when they give up. A single app-level consumer surfaces a transient notice, so no
 * screen needs its own "couldn't load" plumbing. Emissions are debounced so a burst of timeouts
 * from one resolution pass produces at most one notice.
 */
@Singleton
class ProviderHealthReporter @Inject constructor() {

    private val _searchDegraded = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val searchDegraded: SharedFlow<Unit> = _searchDegraded.asSharedFlow()

    @Volatile
    private var lastEmitElapsed = -COOLDOWN_MS

    /** Report that a bulk-resolution MA RPC timed out. Debounced to one notice per cooldown. */
    fun reportSearchTimeout() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitElapsed < COOLDOWN_MS) return
        lastEmitElapsed = now
        _searchDegraded.tryEmit(Unit)
    }

    private companion object {
        const val COOLDOWN_MS = 120_000L
    }
}
