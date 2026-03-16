package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
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
    suspend fun recordListen(track: Track, artists: List<Pair<String, String>>, listenedMs: Long? = null)
    suspend fun recordLike(track: Track, artists: List<Pair<String, String>>)
    suspend fun recordUnlike(track: Track, artists: List<Pair<String, String>>)

    suspend fun setArtistBlocked(artistUri: String, artistName: String?, blocked: Boolean)
    suspend fun getBlockedArtistUris(): Set<String>
    suspend fun getBlockedArtists(): List<BlockedArtistInfo>
    suspend fun getArtistMetrics(days: Int = 120): Map<String, ArtistLearningMetrics>
    suspend fun getSuppressedArtistUris(days: Int = 120): Set<String>
    suspend fun getSuppressedTrackUris(): Set<String>
}
