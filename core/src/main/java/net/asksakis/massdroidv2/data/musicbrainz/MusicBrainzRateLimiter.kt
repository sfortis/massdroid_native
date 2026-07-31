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

    /**
     * Called when MusicBrainz answered 503: hold everything off for a while, so
     * a run that has annoyed the server does not keep hammering it. Honours
     * `Retry-After` (in seconds) when the server sends one.
     */
    suspend fun backOff(retryAfterSeconds: Long?) {
        val penalty = (retryAfterSeconds?.times(1000) ?: RATE_LIMIT_PENALTY_MS)
            .coerceIn(RATE_LIMIT_PENALTY_MS, MAX_PENALTY_MS)
        mutex.withLock {
            nextAllowedAt = maxOf(System.currentTimeMillis(), nextAllowedAt) + penalty
        }
    }

    private companion object {
        // MusicBrainz publishes one request per second but measures it more
        // strictly than that: a background run at 1.1s intervals still drew 503s,
        // so the floor sits above the published rate.
        const val MIN_INTERVAL_MS = 1500L
        const val RATE_LIMIT_PENALTY_MS = 10_000L
        const val MAX_PENALTY_MS = 60_000L
    }
}
