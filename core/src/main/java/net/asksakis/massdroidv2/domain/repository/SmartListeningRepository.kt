package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import net.asksakis.massdroidv2.data.database.PlayOrigin
import net.asksakis.massdroidv2.domain.model.Track

data class ArtistLearningMetrics(
    val score: Double,
    val negativeSignals: Int,
    val totalSignals: Int
)

data class BlockedArtistInfo(
    val artistUri: String,
    val artistName: String?,
    val blockedAt: Long
)

interface SmartListeningRepository {
    val blockedArtistUris: Flow<Set<String>>

    suspend fun recordSkip(track: Track, artists: List<Pair<String, String>>, listenedMs: Long? = null)
    suspend fun recordListen(
        track: Track,
        artists: List<Pair<String, String>>,
        listenedMs: Long? = null,
        origin: PlayOrigin = PlayOrigin.UNKNOWN
    )
    suspend fun recordLike(track: Track, artists: List<Pair<String, String>>)
    suspend fun recordUnlike(track: Track, artists: List<Pair<String, String>>)

    /**
     * An explicit "not this one". The only negative signal that needs no
     * interpretation: every other one is inferred from behaviour, and until this
     * existed the only way to reject a track was to skip it (ambiguous) or block
     * its artist (far too broad). Returns a receipt for [undoDislike], or null
     * if nothing was recorded.
     */
    suspend fun recordDislike(track: Track, artists: List<Pair<String, String>>): DislikeReceipt?

    /** Puts back exactly what [recordDislike] changed. */
    suspend fun undoDislike(receipt: DislikeReceipt)

    suspend fun setArtistBlocked(artistUri: String, artistName: String?, blocked: Boolean)

    /**
     * Expands stored blocks to every uri the server currently knows for each
     * artist.
     *
     * [providersFingerprint] identifies the server's music providers. The work
     * is skipped while it matches what the last expansion saw, and repeats when
     * it changes, because a newly added provider gives blocked artists uris that
     * nobody has blocked. Returns true when something was actually expanded.
     */
    suspend fun backfillBlockedArtistAliases(providersFingerprint: String): Boolean
    suspend fun getBlockedArtistUris(): Set<String>
    suspend fun getBlockedArtists(): List<BlockedArtistInfo>

    /**
     * Forgets every blocked artist.
     *
     * Its own action rather than part of the recommendation reset: a block is an
     * instruction the listener typed in, not something the engine inferred, and it
     * is honoured even when Smart Listening is off. Wiping it as a side effect of
     * clearing the learned stats destroyed the only hand-curated list in the app.
     */
    suspend fun clearBlockedArtists()
    suspend fun getArtistMetrics(days: Int = 120): Map<String, ArtistLearningMetrics>
    suspend fun getSuppressedArtistUris(days: Int = 120): Set<String>
    suspend fun getSuppressedTrackUris(): Set<String>

    /**
     * Identity keys ([trackIdentityKey]) of suppressed tracks, so a rejection follows
     * the RECORDING rather than one uri for it. Measured on a real library: 5 of 22
     * disliked tracks also existed under a second uri and came back through it.
     */
    suspend fun getSuppressedTrackKeys(): Set<String>
}

/**
 * What a dislike changed, so it can be put back exactly.
 *
 * The track score is restored rather than nudged back, because a dislike SETS
 * the score instead of adding to it: a delta could not promise to bury a track
 * the listener had previously loved.
 */
data class DislikeReceipt(
    val trackKey: String,
    val previousScore: Double,
    val artistSignal: Double,
    val artistUris: List<String>,
    val createdAt: Long,
)
