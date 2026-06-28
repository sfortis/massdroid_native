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

/** A recently listened track used as a seed for the seed-track generator. */
data class SeedTrack(
    val trackUri: String,
    val trackName: String,
    val artistName: String,
    val lastPlayedAt: Long,
    val score: Double = 0.0,
    val genres: List<String> = emptyList()
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

data class TrackScore(
    val trackUri: String,
    val trackName: String,
    val score: Double
)

data class DecadeScore(
    val decade: Int,
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
    suspend fun getTopTracks(days: Int = 30, limit: Int = 10): List<TrackScore>
    suspend fun getTopAlbums(days: Int = 30, limit: Int = 10): List<AlbumScore>
    suspend fun getScoredGenres(days: Int = 90, limit: Int = 20): List<GenreScore>
    suspend fun getScoredArtists(days: Int = 90, limit: Int = 50): List<ArtistScore>
    suspend fun getArtistDaypartAffinity(targetHour: Int, days: Int = 180): Map<String, Double>
    suspend fun getArtistDominantDecades(days: Int = 365): Map<String, Int>
    suspend fun getTopDecadesForGenre(genre: String, days: Int = 365, limit: Int = 3): List<DecadeScore>
    suspend fun getGenreAdjacencyMap(): Map<String, Set<String>>
    suspend fun getGenreArtistMap(): Map<String, List<String>>
    suspend fun getRediscoverAlbums(limit: Int = 10): List<RecentAlbum>
    suspend fun getPlaysForTimeAnalysis(days: Int = 30): List<Long>
    suspend fun getCachedArtistTracks(artistUri: String, maxAgeMs: Long): List<Track>?
    suspend fun cacheArtistTracks(artistUri: String, tracks: List<Track>)
    /** Cached provider URI for a Last.fm similar-artist name (null if absent or older than maxAgeMs). */
    suspend fun getCachedResolvedArtistUri(name: String, maxAgeMs: Long): String?
    suspend fun cacheResolvedArtistUri(name: String, uri: String)
    /** Cached playable URI for a normalized "artist|track" name key (seed-track resolution). */
    suspend fun getCachedResolvedTrackUri(nameKey: String, maxAgeMs: Long): String?
    suspend fun cacheResolvedTrackUri(nameKey: String, uri: String)
    /**
     * Seed-track seeds: listened tracks scored at or above [minScore], ordered
     * by preference (score desc, then recency). minScore is the Strictness knob:
     * 0 = any non-disliked recent track, higher = only your more-loved tracks.
     */
    suspend fun getSeedTracks(sinceMs: Long, minListenedMs: Long, minScore: Double, limit: Int): List<SeedTrack>
    /** Recently-played well-listened tracks ordered by recency (no score floor). */
    suspend fun getRecentSeedTracks(sinceMs: Long, minListenedMs: Long, limit: Int): List<SeedTrack>
    suspend fun getAllGenreNames(): List<String>
    suspend fun getArtistsByGenre(genre: String): List<Pair<String, String>>
    suspend fun searchArtistUrisByGenre(query: String): List<String>
    suspend fun resolveLibraryArtistUri(name: String): String?
    suspend fun getLibraryArtistUriMap(): Map<String, String>
    suspend fun enrichArtistGenres(artistName: String, genres: List<String>)
    suspend fun cleanup(retentionMonths: Int = 6)
    suspend fun clearRecommendationData()
}
