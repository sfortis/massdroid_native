package net.asksakis.massdroidv2.data.musicbrainz

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global rate limiter for MusicBrainz.
 *
 * Their terms allow one request per second per application, and exceeding it
 * gets the client blocked rather than throttled, so every call goes through
 * here. Being a singleton acquired immediately before the HTTP call makes it
 * impossible for a caller to bypass. Mirrors `LastFmRateLimiter`, at a fifth of
 * the rate.
 */
@Singleton
class MusicBrainzRateLimiter @Inject constructor() {
    private val mutex = Mutex()
    private var nextAllowedAt = 0L

    /** Suspends until the caller is allowed to issue its MusicBrainz request. */
    suspend fun acquire() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = nextAllowedAt - now
            if (waitMs > 0) delay(waitMs)
            nextAllowedAt = maxOf(now, nextAllowedAt) + MIN_INTERVAL_MS
        }
    }

    private companion object {
        // 1 request/second, with a margin so clock jitter cannot push us over.
        const val MIN_INTERVAL_MS = 1100L
    }
}
