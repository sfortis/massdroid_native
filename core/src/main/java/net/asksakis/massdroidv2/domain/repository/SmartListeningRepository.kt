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
     * nobody has blocked. A lookup the server could not answer ends the run
     * without recording the fingerprint, so it runs again on the next connect.
     * Returns true when a uri that was not blocked yet was added.
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

    /**
     * Tracks kept out of generated mixes, by both engines and by the Genre Radio
     * server-radio sanitizer: disliked, an effective score below -0.15, or a skip
     * before half of the track within the last 60 days. A like or a full listen after
     * the last such skip brings the track back, the score rule included; a dislike
     * stays (see `isTrackExcludedFromMixes`). Both sets come from one read of the
     * database.
     */
    suspend fun getMixExcludedTracks(): MixExcludedTracks
}

/**
 * The tracks kept out of generated mixes, by [uris] and by identity [keys]
 * ([trackIdentityKey]). The keys let a rejection follow the RECORDING rather than
 * one uri for it. Measured on a real library: 5 of 22 disliked tracks also existed
 * under a second uri and came back through it.
 */
data class MixExcludedTracks(val uris: Set<String>, val keys: Set<String>) {
    companion object {
        val NONE = MixExcludedTracks(emptySet(), emptySet())
    }
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
