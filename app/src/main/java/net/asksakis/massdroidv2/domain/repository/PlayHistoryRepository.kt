package net.asksakis.massdroidv2.domain.repository

import net.asksakis.massdroidv2.domain.model.Track

data class RecentAlbum(
    val albumName: String,
    val albumUri: String,
    val imageUrl: String?,
    val year: Int?,
    val lastPlayedAt: Long
)

data class GenreScore(
    val genre: String,
    val score: Double
)

data class ArtistScore(
    val artistUri: String,
    val artistName: String,
    val score: Double
)

data class AlbumScore(
    val albumUri: String,
    val albumName: String,
    val imageUrl: String?,
    val year: Int?,
    val score: Double
)

data class PlayHistoryEntry(
    val trackUri: String,
    val trackName: String,
    val artistNames: List<String>,
    val albumName: String?,
    val albumUri: String?,
    val imageUrl: String?,
    val genres: List<String>,
    val year: Int?,
    val playedAt: Long
)

interface PlayHistoryRepository {
    suspend fun recordPlay(
        track: Track,
        queueId: String,
        listenedMs: Long? = null,
        artists: List<Pair<String, String>> = emptyList()
    ): Long
    suspend fun getRecentAlbums(limit: Int = 10): List<RecentAlbum>
    suspend fun getTopGenres(days: Int = 30, limit: Int = 10): List<GenreScore>
    suspend fun getTopArtists(days: Int = 30, limit: Int = 10): List<ArtistScore>
    suspend fun getTopAlbums(days: Int = 30, limit: Int = 10): List<AlbumScore>
    suspend fun getScoredGenres(days: Int = 90, limit: Int = 20): List<GenreScore>
    suspend fun getScoredArtists(days: Int = 90, limit: Int = 50): List<ArtistScore>
    suspend fun getGenreAdjacencyMap(): Map<String, Set<String>>
    suspend fun getRediscoverAlbums(limit: Int = 10): List<RecentAlbum>
    suspend fun getPlaysForTimeAnalysis(days: Int = 30): List<Long>
    suspend fun cleanup(retentionMonths: Int = 6)
}
