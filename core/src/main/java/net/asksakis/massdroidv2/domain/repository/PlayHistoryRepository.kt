package net.asksakis.massdroidv2.domain.repository

import net.asksakis.massdroidv2.data.database.PlayOrigin
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
    /** The track's effective score: the stored one faded by its age. */
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

/**
 * One entry of a Recommendation insights list. [plays] is how many times it was played
 * in the window, and every list is ordered by it. Copies of the same item under different providers or letter cases are
 * merged into one entry, and [uri] is the library copy when there is one.
 */
data class InsightsArtist(val uri: String, val name: String, val plays: Int)

/** A genre in the Insights list, see [InsightsArtist]. */
data class InsightsGenre(val genre: String, val plays: Int)

/**
 * A track in the Insights list, see [InsightsArtist]. [artistName] joins every credited
 * artist. [albumUri] is the album the entry opens, the library copy when there is one.
 */
data class InsightsTrack(
    val uri: String,
    val name: String,
    val artistName: String,
    val albumUri: String?,
    val albumName: String?,
    val plays: Int
)

/** An album in the Insights list, see [InsightsArtist]. [artistName] is its most played artist. */
data class InsightsAlbum(
    val uri: String,
    val name: String,
    val artistName: String,
    val imageUrl: String?,
    val year: Int?,
    val plays: Int
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

/** One (track, genre) pair with how many times the track played in the window. */
data class GenrePlayRow(
    val trackUri: String,
    val genre: String,
    val plays: Int
)

interface PlayHistoryRepository {
    /**
     * [origin] records WHY this play happened, which is the difference between real
     * taste and the engine hearing its own output back. It defaults to
     * [PlayOrigin.UNKNOWN] so a caller that genuinely cannot tell says so, rather
     * than claiming the play was organic.
     */
    suspend fun recordPlay(
        track: Track,
        queueId: String,
        listenedMs: Long? = null,
        artists: List<Pair<String, String>> = emptyList(),
        origin: PlayOrigin = PlayOrigin.UNKNOWN
    ): Long
    suspend fun getRecentAlbums(limit: Int = 10): List<RecentAlbum>
    /**
     * The Recommendation insights lists. Blocked artists, and the tracks and albums
     * credited to them, are left out.
     */
    suspend fun getTopGenres(days: Int = 30, limit: Int = 10): List<InsightsGenre>
    suspend fun getTopArtists(days: Int = 30, limit: Int = 10): List<InsightsArtist>
    suspend fun getTopTracks(days: Int = 30, limit: Int = 10): List<InsightsTrack>
    suspend fun getTopAlbums(days: Int = 30, limit: Int = 10): List<InsightsAlbum>
    suspend fun getScoredGenres(days: Int = 90, limit: Int = 20): List<GenreScore>
    suspend fun getScoredArtists(days: Int = 90, limit: Int = 50): List<ArtistScore>
    suspend fun getArtistDaypartAffinity(targetHour: Int, days: Int = 180): Map<String, Double>
    suspend fun getArtistDominantDecades(days: Int = 365): Map<String, Int>
    /** [genre] may be any spelling: it is matched to the stored name by `genreKey`. */
    suspend fun getTopDecadesForGenre(genre: String, days: Int = 365, limit: Int = 3): List<DecadeScore>
    /**
     * Raw material for the anchor-family floor: per-track genre rows with play
     * counts since [sinceMs], and the same restricted to organic plays. See
     * `anchorFamilies` in the recommendation package for how they are judged.
     */
    suspend fun getGenrePlayRows(sinceMs: Long): List<GenrePlayRow>
    suspend fun getOrganicGenrePlayRows(sinceMs: Long): List<GenrePlayRow>
    /** Keyed and valued by stored genre names, the same names [getScoredGenres] returns. */
    suspend fun getGenreAdjacencyMap(): Map<String, Set<String>>
    /**
     * Artist keys per genre, keyed by the STORED spelling (one per genre since
     * schema 21), because callers also show the key as the genre's name. A caller
     * that meets these keys with names from another source (provider genres, a
     * persisted name) must compare them by `genreKey`, as `buildGenreData` does.
     */
    suspend fun getGenreArtistMap(): Map<String, List<String>>
    suspend fun getRediscoverAlbums(limit: Int = 10): List<RecentAlbum>
    suspend fun getPlaysForTimeAnalysis(days: Int = 30): List<Long>
    /** Cached MA similar artists for [artistUri] (uri, name, genres), null when absent/stale. */
    suspend fun getCachedMaSimilarArtists(artistUri: String, maxAgeMs: Long): List<CachedSimilarArtist>?
    suspend fun cacheMaSimilarArtists(artistUri: String, similar: List<CachedSimilarArtist>)

    /**
     * MA `similar_tracks` for one seed track. The mix engine's track route asks once
     * per seed on every build, which cost 8 to 14 seconds of a 15-second budget
     * before this existed; the ordering carries the similarity ranking and is
     * preserved.
     *
     * [emptyMaxAgeMs] governs a cached EMPTY answer, which means "this provider
     * returned nothing for this seed" rather than "we never asked". It is kept for
     * less time than a real answer: a provider that does not implement
     * `similar_tracks` will keep not implementing it, but a provider that simply had
     * nothing for one track may later.
     */
    suspend fun getCachedSimilarTracks(
        seedUri: String,
        maxAgeMs: Long,
        emptyMaxAgeMs: Long
    ): List<Track>?

    /**
     * Stores what `similar_tracks` answered, INCLUDING an empty answer. Not caching
     * the empty case meant a provider that does not support the feature was probed
     * once per seed on every single build, which is eight wasted round-trips per mix
     * for that user.
     */
    suspend fun cacheSimilarTracks(seedUri: String, tracks: List<Track>)
    /** The genre mix engine's artist tracks (`artist_tracks`, sampled), keyed by normalized artist uri. */
    suspend fun getCachedArtistTracks(artistUri: String, maxAgeMs: Long): List<Track>?
    suspend fun cacheArtistTracks(artistUri: String, tracks: List<Track>)

    /**
     * The seed engine's MA `top_tracks` answer for [artistUri]. Kept apart from
     * [getCachedArtistTracks]: the two engines ask for different lists under the
     * same artist uri.
     */
    suspend fun getCachedArtistTopTracks(artistUri: String, maxAgeMs: Long): List<Track>?
    suspend fun cacheArtistTopTracks(artistUri: String, tracks: List<Track>)
    /** Cached provider URI for an artist name resolved by the genre engine (null if absent or stale). */
    /**
     * Seed-track seeds: listened tracks whose effective (time-faded) score is at
     * or above [minScore], ordered by preference (effective score desc, then
     * recency). minScore is the Strictness knob: 0 = any non-negative recent
     * track, higher = only your more-loved tracks. [SeedTrack.score] is the
     * effective score in every seed query.
     */
    suspend fun getSeedTracks(sinceMs: Long, minListenedMs: Long, minScore: Double, limit: Int): List<SeedTrack>
    /**
     * Recently-played well-listened tracks ordered by recency, keeping those whose
     * effective (time-faded) score is at or above [minScore].
     */
    suspend fun getRecentSeedTracks(
        sinceMs: Long,
        minListenedMs: Long,
        minScore: Double,
        limit: Int
    ): List<SeedTrack>

    /**
     * Seed candidates the listener has demonstrably come back to: at least
     * [minPlays] plays all time, ordered by replay count then stored score (the
     * returned score is the effective one). Recency
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
    /** [genre] may be any spelling: it is matched to the stored name by `genreKey`. */
    suspend fun getArtistsByGenre(genre: String): List<Pair<String, String>>
    suspend fun searchArtistUrisByGenre(query: String): List<String>
    suspend fun resolveLibraryArtistUri(name: String): String?
    suspend fun getLibraryArtistUriMap(): Map<String, String>
    suspend fun enrichArtistGenres(artistName: String, genres: List<String>)
    suspend fun cleanup(retentionMonths: Int = 6)
    suspend fun clearRecommendationData()
}
