package net.asksakis.massdroidv2.data.repository

import android.util.Log
import androidx.room.withTransaction
import net.asksakis.massdroidv2.data.database.AlbumEntity
import net.asksakis.massdroidv2.data.database.AppDatabase
import net.asksakis.massdroidv2.data.database.ArtistTrackCacheEntity
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.GenreEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.PlayHistoryEntity
import net.asksakis.massdroidv2.data.database.TrackArtistEntity
import net.asksakis.massdroidv2.data.database.ArtistGenreEntity
import net.asksakis.massdroidv2.data.database.TrackEntity
import net.asksakis.massdroidv2.data.database.TrackGenreEntity
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import net.asksakis.massdroidv2.domain.repository.AlbumScore
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.DecadeScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.RecentAlbum
import net.asksakis.massdroidv2.domain.repository.TrackScore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.min

@Singleton
class PlayHistoryRepositoryImpl @Inject constructor(
    private val dao: PlayHistoryDao,
    private val json: Json,
    private val appDatabase: AppDatabase
) : PlayHistoryRepository {

    companion object {
        private const val TAG = "PlayHistoryRepo"
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val MILLIS_PER_HOUR = 3_600_000.0
        private const val BLL_DECAY = -1.5
        private const val BLL_MIN_HOURS = 0.5
        private const val BLL_FLOOR = -100.0
        private const val ADJACENCY_CACHE_HOURS = 6L
        private const val FEEDBACK_RETENTION_DAYS = 120L
        private const val COMPLETION_FLOOR = 0.3
        private const val COMPLETION_RANGE = 0.7
    }

    private var adjacencyCache: Map<String, Set<String>>? = null
    private var adjacencyCacheTime: Long = 0L

    override suspend fun recordPlay(
        track: Track,
        queueId: String,
        listenedMs: Long?,
        artists: List<Pair<String, String>>
    ): Long {
        val trackKey = MediaIdentity.canonicalTrackKey(track.itemId, track.uri) ?: return -1L
        val albumKey = MediaIdentity.canonicalAlbumKey(track.albumItemId, track.albumUri)
        val normalizedArtists = artists.mapNotNull { (uri, name) ->
            MediaIdentity.canonicalArtistKey(uri = uri)?.let { it to name }
        }.distinctBy { it.first }
        val fallbackArtistKey = MediaIdentity.canonicalArtistKey(track.artistItemId, track.artistUri)
        val fallbackArtistName = track.artistNames
            .split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { "Artist" }
        val artistsToPersist = if (normalizedArtists.isNotEmpty()) {
            normalizedArtists
        } else {
            fallbackArtistKey?.let { listOf(it to fallbackArtistName) } ?: emptyList()
        }

        val id = appDatabase.withTransaction {
            if (!albumKey.isNullOrBlank()) {
                dao.insertAlbum(
                    AlbumEntity(
                        uri = albumKey,
                        name = track.albumName,
                        imageUrl = track.imageUrl,
                        year = sanitizeYear(track.year)
                    )
                )
            }

            dao.insertTrack(
                TrackEntity(
                    uri = trackKey,
                    name = track.name,
                    albumUri = albumKey,
                    duration = track.duration,
                    imageUrl = track.imageUrl
                )
            )

            for ((artistKey, name) in artistsToPersist) {
                dao.insertArtist(ArtistEntity(uri = artistKey, name = name))
                dao.insertTrackArtist(TrackArtistEntity(trackUri = trackKey, artistUri = artistKey))
            }

            for (genre in track.genres) {
                val normalized = normalizeGenre(genre)
                if (normalized.isNotBlank()) {
                    dao.insertGenre(GenreEntity(name = normalized))
                    dao.insertTrackGenre(TrackGenreEntity(trackUri = trackKey, genreName = normalized))
                    for ((artistKey, _) in artistsToPersist) {
                        dao.insertArtistGenre(ArtistGenreEntity(artistUri = artistKey, genreName = normalized))
                    }
                }
            }

            dao.insertPlay(
                PlayHistoryEntity(
                    trackUri = trackKey,
                    queueId = queueId,
                    playedAt = System.currentTimeMillis(),
                    listenedMs = listenedMs
                )
            )
        }
        adjacencyCache = null
        val listenSec = listenedMs?.let { "${it / 1000}s" } ?: "?"
        Log.d(TAG, "Recorded play: ${track.name} ($listenSec, ${artists.size} artists, ${track.genres.size} genres)")
        return id
    }

    override suspend fun getRecentAlbums(limit: Int): List<RecentAlbum> {
        val since = System.currentTimeMillis() - (30 * MILLIS_PER_DAY)
        return dao.getRecentAlbums(since, limit).map {
            RecentAlbum(
                albumName = it.albumName,
                albumUri = it.albumUri,
                imageUrl = it.imageUrl,
                year = it.year,
                lastPlayedAt = it.lastPlayedAt
            )
        }
    }

    override suspend fun getTopGenres(days: Int, limit: Int): List<GenreScore> =
        getScoredGenres(days, limit)

    override suspend fun getTopArtists(days: Int, limit: Int): List<ArtistScore> =
        getScoredArtists(days, limit)

    override suspend fun getTopTracks(days: Int, limit: Int): List<TrackScore> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        return dao.getTopTracks(since, limit).map {
            TrackScore(
                trackUri = it.trackUri,
                trackName = it.trackName,
                score = it.playCount.toDouble()
            )
        }
    }

    override suspend fun getTopAlbums(days: Int, limit: Int): List<AlbumScore> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        val rows = dao.getTopAlbums(since, limit * 2)
        return rows.map {
            AlbumScore(
                albumUri = it.albumUri,
                albumName = it.albumName,
                imageUrl = it.imageUrl,
                year = it.year,
                score = it.playCount.toDouble()
            )
        }.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun getScoredGenres(days: Int, limit: Int): List<GenreScore> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        val nowMs = System.currentTimeMillis()
        val timestamps = dao.getGenrePlayTimestamps(since)
        val grouped = timestamps.groupBy { it.genre }
        return grouped.map { (genre, plays) ->
            val weighted = plays.map { p ->
                WeightedPlay(p.playedAt, completionWeight(p.listenedMs, p.duration))
            }
            GenreScore(
                genre = genre,
                score = computeWeightedBllScore(nowMs, weighted)
            )
        }.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun getScoredArtists(days: Int, limit: Int): List<ArtistScore> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        val nowMs = System.currentTimeMillis()
        val timestamps = dao.getArtistPlayTimestamps(since)
        val grouped = timestamps.groupBy { it.artistName }
        return grouped.map { (name, plays) ->
            val weighted = plays.map { p ->
                WeightedPlay(p.playedAt, completionWeight(p.listenedMs, p.duration))
            }
            ArtistScore(
                artistUri = plays.minOf { it.artistUri },
                artistName = name,
                score = computeWeightedBllScore(nowMs, weighted)
            )
        }.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun getArtistDaypartAffinity(targetHour: Int, days: Int): Map<String, Double> {
        val hour = targetHour.coerceIn(0, 23)
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        val timestamps = dao.getArtistPlayTimestamps(since)
        if (timestamps.isEmpty()) return emptyMap()
        return timestamps
            .groupBy { it.artistName }
            .entries.associate { (name, plays) ->
                val uri = plays.minOf { it.artistUri }
                val avg = plays
                    .map { row ->
                        val playedHour = Instant.ofEpochMilli(row.playedAt)
                            .atZone(ZoneId.systemDefault())
                            .hour
                        val diff = kotlin.math.abs(playedHour - hour)
                        val circular = minOf(diff, 24 - diff).toDouble()
                        kotlin.math.exp(-circular / 4.0)
                    }
                    .average()
                uri to avg.coerceIn(0.0, 1.0)
            }
    }

    override suspend fun getArtistDominantDecades(days: Int): Map<String, Int> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        val rows = dao.getArtistDecadePlayCounts(since)
        return rows
            .groupBy { it.artistName }
            .entries.associate { (_, buckets) ->
                val canonicalUri = buckets.minOf { it.artistUri }
                val totalByDecade = buckets
                    .groupBy { it.decade }
                    .mapValues { (_, b) -> b.sumOf { it.playCount } }
                canonicalUri to (totalByDecade.maxByOrNull { it.value }?.key ?: 0)
            }
            .filterValues { it > 0 }
    }

    override suspend fun getTopDecadesForGenre(genre: String, days: Int, limit: Int): List<DecadeScore> {
        if (genre.isBlank()) return emptyList()
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        return dao.getTopDecadesForGenre(genre = genre, since = since, limit = limit)
            .map { DecadeScore(decade = it.decade, score = it.playCount.toDouble()) }
    }

    override suspend fun getGenreAdjacencyMap(): Map<String, Set<String>> {
        val now = System.currentTimeMillis()
        val cached = adjacencyCache
        if (cached != null && now - adjacencyCacheTime < ADJACENCY_CACHE_HOURS * MILLIS_PER_DAY / 24) {
            return cached
        }
        val coOccurrences = dao.getGenreCoOccurrences()
        val result = mutableMapOf<String, MutableSet<String>>()
        for (row in coOccurrences) {
            result.getOrPut(row.genre1) { mutableSetOf() }.add(row.genre2)
            result.getOrPut(row.genre2) { mutableSetOf() }.add(row.genre1)
        }
        adjacencyCache = result
        adjacencyCacheTime = now
        return result
    }

    override suspend fun getGenreArtistMap(): Map<String, List<String>> {
        val rows = dao.getGenreArtistUris()
        val canonicalUri = rows
            .groupBy { it.artistName }
            .mapValues { (_, entries) -> entries.minOf { it.artistUri } }
        return rows
            .groupBy { normalizeGenre(it.genre) }
            .mapValues { (_, entries) ->
                entries.map { canonicalUri[it.artistName] ?: it.artistUri }.distinct()
            }
    }

    override suspend fun getRediscoverAlbums(limit: Int): List<RecentAlbum> {
        val now = System.currentTimeMillis()
        val before = now - (30 * MILLIS_PER_DAY)
        val after = now - (180 * MILLIS_PER_DAY)
        return dao.getRediscoverAlbums(before, after, limit).map {
            RecentAlbum(
                albumName = it.albumName,
                albumUri = it.albumUri,
                imageUrl = it.imageUrl,
                year = it.year,
                lastPlayedAt = it.lastPlayedAt
            )
        }
    }

    override suspend fun getPlaysForTimeAnalysis(days: Int): List<Long> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        return dao.getPlaysForTimeAnalysis(since).map { it.playedAt }
    }

    override suspend fun getCachedArtistTracks(artistUri: String, maxAgeMs: Long): List<Track>? {
        val cache = dao.getArtistTrackCache(artistUri) ?: return null
        if (System.currentTimeMillis() - cache.fetchedAt > maxAgeMs) return null
        return try {
            json.decodeFromString(ListSerializer(Track.serializer()), cache.tracksJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode artist track cache for $artistUri: ${e.message}")
            null
        }
    }

    override suspend fun cacheArtistTracks(artistUri: String, tracks: List<Track>) {
        val now = System.currentTimeMillis()
        val payload = json.encodeToString(ListSerializer(Track.serializer()), tracks)
        dao.upsertArtistTrackCache(
            ArtistTrackCacheEntity(
                artistUri = artistUri,
                tracksJson = payload,
                fetchedAt = now
            )
        )
        dao.deleteExpiredArtistTrackCache(now - (14 * MILLIS_PER_DAY))
    }

    override suspend fun searchArtistUrisByGenre(query: String, limit: Int): List<String> =
        dao.searchArtistUrisByGenre(query, limit)

    override suspend fun resolveLibraryArtistUri(name: String): String? =
        dao.getArtistUrisByName(name).firstOrNull { it.startsWith("library://") }

    override suspend fun getLibraryArtistUriMap(): Map<String, String> =
        dao.getLibraryArtistUris().associate { it.name to it.uri }

    override suspend fun enrichArtistGenres(artistName: String, genres: List<String>) {
        val uris = dao.getArtistUrisByName(artistName)
        if (uris.isEmpty()) return
        val normalizedGenres = genres.mapNotNull { normalizeGenre(it).ifBlank { null } }
        if (normalizedGenres.isEmpty()) return
        for (genre in normalizedGenres) {
            dao.insertGenre(GenreEntity(name = genre))
            for (uri in uris) {
                dao.insertArtistGenre(ArtistGenreEntity(artistUri = uri, genreName = genre))
            }
        }
        Log.d(TAG, "Enriched artist genres: $artistName -> $normalizedGenres (${uris.size} URIs)")
    }

    override suspend fun cleanup(retentionMonths: Int) {
        val cutoff = System.currentTimeMillis() - (retentionMonths * 30L * MILLIS_PER_DAY)
        val feedbackCutoff = System.currentTimeMillis() - (FEEDBACK_RETENTION_DAYS * MILLIS_PER_DAY)
        appDatabase.withTransaction {
            dao.deleteOlderThan(cutoff)
            dao.deleteOldSmartFeedback(feedbackCutoff)
            dao.deleteOrphanTracks()
            dao.deleteOrphanAlbums()
            dao.deleteOrphanArtists()
            dao.deleteOrphanArtistGenres()
            dao.deleteOrphanGenres()
        }
        Log.d(TAG, "Cleaned up play history older than $retentionMonths months + smart feedback older than $FEEDBACK_RETENTION_DAYS days + orphans")
    }

    override suspend fun clearRecommendationData() {
        dao.clearRecommendationData()
        adjacencyCache = null
        Log.w(TAG, "Recommendation DB data cleared by user action")
    }

    private data class WeightedPlay(val playedAt: Long, val weight: Double)

    private fun completionWeight(listenedMs: Long?, durationSec: Double?): Double {
        if (listenedMs == null || durationSec == null || durationSec <= 0.0) return 1.0
        val ratio = min((listenedMs / 1000.0) / durationSec, 1.0)
        return COMPLETION_FLOOR + COMPLETION_RANGE * ratio
    }

    private fun computeWeightedBllScore(nowMs: Long, plays: List<WeightedPlay>): Double {
        val sum = plays.sumOf { (playedAt, weight) ->
            val hoursAgo = ((nowMs - playedAt).toDouble() / MILLIS_PER_HOUR).coerceAtLeast(BLL_MIN_HOURS)
            hoursAgo.pow(BLL_DECAY) * weight
        }
        return if (sum > 0.0) ln(sum) else BLL_FLOOR
    }

    private fun sanitizeYear(year: Int?): Int? = year?.takeIf { it > 0 }
}
