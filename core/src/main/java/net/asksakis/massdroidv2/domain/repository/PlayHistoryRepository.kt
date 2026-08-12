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
    /** MA uri of the artist, e.g. `library://artist/59`. Lets the generator ask MA
     *  for similar artists directly instead of resolving the name. */
    val artistUri: String = "",
    /** MusicBrainz id of the artist when known, for unambiguous genre lookups. */
    val artistMbid: String? = null,
    val lastPlayedAt: Long,
    val score: Double = 0.0,
    val genres: List<String> = emptyList(),
    /**
     * Artist-level genres as stored in `artist_genres`. Cleaner than the
     * crowd-noisy track tags, so cluster coherence checks prefer these - though
     * MusicBrainz, when it has an answer, is preferred over both.
     */
    val artistGenres: List<String> = emptyList()
)

/** One MA similar-artist edge, as cached. */
data class CachedSimilarArtist(
    val uri: String,
    val name: String,
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
    /** Cached MA similar artists for [artistUri] (uri, name, genres), null when absent/stale. */
    suspend fun getCachedMaSimilarArtists(artistUri: String, maxAgeMs: Long): List<CachedSimilarArtist>?
    suspend fun cacheMaSimilarArtists(artistUri: String, similar: List<CachedSimilarArtist>)
    suspend fun getCachedArtistTracks(artistUri: String, maxAgeMs: Long): List<Track>?
    suspend fun cacheArtistTracks(artistUri: String, tracks: List<Track>)
    /** Cached provider URI for an artist name resolved by the genre engine (null if absent or stale). */
    /**
     * Seed-track seeds: listened tracks scored at or above [minScore], ordered
     * by preference (score desc, then recency). minScore is the Strictness knob:
     * 0 = any non-disliked recent track, higher = only your more-loved tracks.
     */
    suspend fun getSeedTracks(sinceMs: Long, minListenedMs: Long, minScore: Double, limit: Int): List<SeedTrack>
    /** Recently-played well-listened tracks ordered by recency (no score floor). */
    suspend fun getRecentSeedTracks(
        sinceMs: Long,
        minListenedMs: Long,
        minScore: Double,
        limit: Int
    ): List<SeedTrack>

    /**
     * Seed candidates the listener has demonstrably come back to: at least
     * [minPlays] plays all time, ordered by replay count then score. Recency
     * alone is owned by whatever generated the most plays (the mixes), so this
     * is the pool that carries actual taste.
     */
    suspend fun getConfirmedSeedTracks(
        sinceMs: Long,
        minListenedMs: Long,
        minPlays: Int,
        limit: Int
    ): List<SeedTrack>
    suspend fun getAllGenreNames(): List<String>
    /**
     * Genres the DB holds for each of [artistUris] (missing artists are absent
     * from the map). Batched because the Smart Mix genre gate judges the whole
     * candidate pool at once.
     */
    suspend fun getArtistGenreMap(artistUris: List<String>): Map<String, List<String>>
    suspend fun getArtistsByGenre(genre: String): List<Pair<String, String>>
    suspend fun searchArtistUrisByGenre(query: String): List<String>
    suspend fun resolveLibraryArtistUri(name: String): String?
    suspend fun getLibraryArtistUriMap(): Map<String, String>
    suspend fun enrichArtistGenres(artistName: String, genres: List<String>)
    suspend fun cleanup(retentionMonths: Int = 6)
    suspend fun clearRecommendationData()
}
