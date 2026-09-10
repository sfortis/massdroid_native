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
    private var consecutiveRateLimits = 0

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
     *
     * The hold doubles on every consecutive 503 and resets on the next success.
     * The 503s are not a reaction to our own pace: a field day showed 86 give-ups
     * while we were sending one to three requests a minute against a limit of one
     * a second. The limit is per IP address, and something else on it (the Music
     * Assistant server asks MusicBrainz too) or the server's own load draws them.
     * A fixed ten seconds then retried once was usually still inside the busy
     * spell; the same request tends to go through a minute later.
     */
    suspend fun backOff(retryAfterSeconds: Long?) {
        mutex.withLock {
            consecutiveRateLimits++
            val penalty = penaltyMs(consecutiveRateLimits, retryAfterSeconds)
            nextAllowedAt = maxOf(System.currentTimeMillis(), nextAllowedAt) + penalty
        }
    }

    /** A request went through: the busy spell is over, the next 503 starts small again. */
    suspend fun noteSuccess() {
        mutex.withLock { consecutiveRateLimits = 0 }
    }

    internal companion object {
        // MusicBrainz publishes one request per second but measures it more
        // strictly than that: a background run at 1.1s intervals still drew 503s,
        // so the floor sits above the published rate.
        const val MIN_INTERVAL_MS = 1500L
        const val RATE_LIMIT_PENALTY_MS = 10_000L
        const val MAX_PENALTY_MS = 60_000L

        /**
         * Hold after the [consecutive]th 503 in a row: 10 s, 20 s, 40 s, then the
         * cap. A `Retry-After` from the server wins when it asks for longer.
         */
        fun penaltyMs(consecutive: Int, retryAfterSeconds: Long?): Long {
            val doublings = (consecutive - 1).coerceIn(0, 6)
            val escalated = (RATE_LIMIT_PENALTY_MS shl doublings).coerceAtMost(MAX_PENALTY_MS)
            val requested = retryAfterSeconds?.times(1000)?.coerceAtMost(MAX_PENALTY_MS) ?: 0L
            return maxOf(escalated, requested)
        }
    }
}
