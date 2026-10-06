package net.asksakis.massdroidv2.data.repository

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import net.asksakis.massdroidv2.data.database.AlbumEntity
import net.asksakis.massdroidv2.data.database.AppDatabase
import net.asksakis.massdroidv2.data.database.ArtistTrackCacheEntity
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.GenreEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.PlayHistoryEntity
import net.asksakis.massdroidv2.data.database.PlayOrigin
import net.asksakis.massdroidv2.data.database.MaSimilarArtistEntity
import net.asksakis.massdroidv2.data.database.MaSimilarTrackCacheEntity
import net.asksakis.massdroidv2.data.database.SeedTrackRow
import net.asksakis.massdroidv2.data.database.TrackArtistEntity
import net.asksakis.massdroidv2.data.database.ArtistGenreEntity
import net.asksakis.massdroidv2.data.database.TrackEntity
import net.asksakis.massdroidv2.data.database.TrackGenreEntity
import net.asksakis.massdroidv2.data.genre.GenreSpellingResolver
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.effectiveTrackScore
import net.asksakis.massdroidv2.domain.recommendation.genreNamesMatching
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import net.asksakis.massdroidv2.domain.recommendation.storedTrackScoreFloor
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.CachedSimilarArtist
import net.asksakis.massdroidv2.domain.repository.DecadeScore
import net.asksakis.massdroidv2.domain.repository.GenrePlayRow
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.InsightsAlbum
import net.asksakis.massdroidv2.domain.repository.InsightsArtist
import net.asksakis.massdroidv2.domain.repository.InsightsGenre
import net.asksakis.massdroidv2.domain.repository.InsightsTrack
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.RecentAlbum
import net.asksakis.massdroidv2.domain.repository.SeedTrack
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
    private val appDatabase: AppDatabase,
    private val genreSpellings: GenreSpellingResolver
) : PlayHistoryRepository {

    companion object {
        private const val TAG = "PlayHistoryRepo"
        private const val MILLIS_PER_DAY = 86_400_000L
        /** Bound variables per `IN` query, under the 999 SQLite allows on older Android. */
        private const val SQL_IN_CHUNK = 500

        /**
         * How long a seed's cached `similar_tracks` is kept before the sweep on write
         * removes it. Matches the generator's own TTL for the same rows.
         */
        private const val SIMILAR_TRACK_CACHE_DAYS = 14L
        private const val MILLIS_PER_HOUR = 3_600_000.0
        private const val BLL_DECAY = -1.5
        private const val BLL_MIN_HOURS = 0.5
        private const val BLL_FLOOR = -100.0
        private const val SESSION_GAP_MS = 30 * 60 * 1000L
        private const val SESSION_DECAY = 0.7
        private const val ADJACENCY_CACHE_HOURS = 48L
        private const val MIN_ADJACENCY_RATIO = 0.25
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
        artists: List<Pair<String, String>>,
        origin: PlayOrigin
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

        // Resolved before the transaction: the first call reads the database.
        val genreNames = genreSpellings.spellingsToStore(track.genres)

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

            // A track's genres describe the TRACK, and are attributed to the
            // PRIMARY artist only. Writing them to every credited artist made
            // genres bleed between unrelated acts: this provider files some
            // Last Shadow Puppets songs under Lindstrøm as well, so the disco
            // producer collected "indie rock" and the indie band collected
            // "space disco" - and a Smart Mix cluster then merged 70s disco,
            // UK indie and mainstream pop into one "pop" family.
            //
            // Featured and credited artists keep their own genres, which come
            // from MusicBrainz per ENTITY rather than from whatever they were
            // credited on.
            val primaryArtistKey = artistsToPersist.firstOrNull()?.first
            for (genre in genreNames) {
                dao.insertGenre(GenreEntity(name = genre))
                dao.insertTrackGenre(TrackGenreEntity(trackUri = trackKey, genreName = genre))
                primaryArtistKey?.let {
                    dao.insertArtistGenre(ArtistGenreEntity(artistUri = it, genreName = genre))
                }
            }

            dao.insertPlay(
                PlayHistoryEntity(
                    trackUri = trackKey,
                    queueId = queueId,
                    playedAt = System.currentTimeMillis(),
                    listenedMs = listenedMs,
                    origin = origin.stored
                )
            )
        }
        adjacencyCache = null
        val listenSec = listenedMs?.let { "${it / 1000}s" } ?: "?"
        Log.d(TAG, "Recorded play: ${track.name} ($listenSec, ${origin.stored}, ${artists.size} artists, ${track.genres.size} genres)")
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

    override suspend fun getTopGenres(days: Int, limit: Int): List<InsightsGenre> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        return InsightsRanking.rankGenres(dao.getGenrePlayTimestamps(since), limit)
    }

    override suspend fun getTopArtists(days: Int, limit: Int): List<InsightsArtist> =
        InsightsRanking.rankArtists(insightsPlays(days), dao.getBlockedArtistUris().toSet(), limit)

    override suspend fun getTopTracks(days: Int, limit: Int): List<InsightsTrack> =
        InsightsRanking.rankTracks(insightsPlays(days), dao.getBlockedArtistUris().toSet(), limit)

    override suspend fun getTopAlbums(days: Int, limit: Int): List<InsightsAlbum> =
        InsightsRanking.rankAlbums(insightsPlays(days), dao.getBlockedArtistUris().toSet(), limit)

    private suspend fun insightsPlays(days: Int): List<InsightsRanking.Play> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        return InsightsRanking.plays(dao.getInsightsPlayRows(since))
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
        // The query matches the stored name exactly, and the caller's name may be
        // another spelling (a shortcut, a cached Discover tile, a provider genre).
        val stored = genreSpellings.spellingToQuery(genre)
        return dao.getTopDecadesForGenre(genre = stored, since = since, limit = limit)
            .map { DecadeScore(decade = it.decade, score = it.playCount.toDouble()) }
    }

    override suspend fun getGenreAdjacencyMap(): Map<String, Set<String>> {
        val now = System.currentTimeMillis()
        val cached = adjacencyCache
        if (cached != null && now - adjacencyCacheTime < ADJACENCY_CACHE_HOURS * MILLIS_PER_DAY / 24) {
            return cached
        }
        val coOccurrences = dao.getGenreCoOccurrences()
        // Build genre artist counts for relative threshold
        val genreCounts = mutableMapOf<String, Int>()
        for (row in coOccurrences) {
            genreCounts[row.genre1] = (genreCounts[row.genre1] ?: 0) + row.coCount
            genreCounts[row.genre2] = (genreCounts[row.genre2] ?: 0) + row.coCount
        }
        val result = mutableMapOf<String, MutableSet<String>>()
        for (row in coOccurrences) {
            val minCount = minOf(genreCounts[row.genre1] ?: 0, genreCounts[row.genre2] ?: 0)
            if (minCount <= 0) continue
            val ratio = row.coCount.toDouble() / minCount
            if (ratio < MIN_ADJACENCY_RATIO) continue
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

    override suspend fun getGenrePlayRows(sinceMs: Long): List<GenrePlayRow> =
        dao.getGenrePlayRows(sinceMs).map { GenrePlayRow(it.trackUri, it.genre, it.plays) }

    override suspend fun getOrganicGenrePlayRows(sinceMs: Long): List<GenrePlayRow> =
        dao.getOrganicGenrePlayRows(sinceMs).map { GenrePlayRow(it.trackUri, it.genre, it.plays) }

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

    override suspend fun getSeedTracks(
        sinceMs: Long,
        minListenedMs: Long,
        minScore: Double,
        limit: Int
    ): List<SeedTrack> {
        val now = System.currentTimeMillis()
        // Ranked here rather than in SQL: the order is by the faded score, which
        // a query cannot compute (see PlayHistoryDao.getSeedTrackCandidates).
        return dao.getSeedTrackCandidates(sinceMs, minListenedMs, storedTrackScoreFloor(minScore))
            .map { it.toSeedTrack(now) }
            .filter { it.score >= minScore }
            .sortedWith(compareByDescending<SeedTrack> { it.score }.thenByDescending { it.lastPlayedAt })
            .take(limit.coerceAtLeast(0))
    }

    override suspend fun getCachedMaSimilarArtists(
        artistUri: String,
        maxAgeMs: Long
    ): List<CachedSimilarArtist>? {
        val fetchedAt = dao.getMaSimilarArtistsFetchedAt(artistUri) ?: return null
        if (System.currentTimeMillis() - fetchedAt >= maxAgeMs) return null
        return dao.getMaSimilarArtists(artistUri).map { row ->
            CachedSimilarArtist(
                uri = row.similarUri,
                name = row.similarName,
                genres = row.similarGenres.split(",").filter { it.isNotBlank() }
            )
        }
    }

    override suspend fun cacheMaSimilarArtists(artistUri: String, similar: List<CachedSimilarArtist>) {
        if (artistUri.isBlank()) return
        val now = System.currentTimeMillis()
        dao.upsertMaSimilarArtists(
            similar.mapIndexed { index, s ->
                MaSimilarArtistEntity(
                    sourceUri = artistUri,
                    similarUri = s.uri,
                    similarName = s.name,
                    similarGenres = s.genres.joinToString(","),
                    position = index,
                    fetchedAt = now
                )
            }
        )
    }

    override suspend fun getCachedSimilarTracks(
        seedUri: String,
        maxAgeMs: Long,
        emptyMaxAgeMs: Long
    ): List<Track>? {
        val cache = dao.getMaSimilarTrackCache(seedUri) ?: return null
        val tracks = try {
            json.decodeFromString(ListSerializer(Track.serializer()), cache.tracksJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode similar-track cache for $seedUri: ${e.message}")
            return null
        }
        // An empty answer expires sooner than a real one. Both are answers though:
        // returning the empty list rather than null is what stops the caller asking
        // the server again.
        val ttl = if (tracks.isEmpty()) emptyMaxAgeMs else maxAgeMs
        if (System.currentTimeMillis() - cache.fetchedAt > ttl) return null
        return tracks
    }

    override suspend fun cacheSimilarTracks(seedUri: String, tracks: List<Track>) {
        if (seedUri.isBlank()) return
        val now = System.currentTimeMillis()
        try {
            dao.upsertMaSimilarTrackCache(
                MaSimilarTrackCacheEntity(
                    seedUri = seedUri,
                    tracksJson = json.encodeToString(ListSerializer(Track.serializer()), tracks),
                    fetchedAt = now
                )
            )
            // Swept on write, like the artist-track cache. Seeds rotate, so without
            // this the table would keep a row for every track that ever seeded a mix.
            dao.deleteExpiredMaSimilarTrackCache(now - (SIMILAR_TRACK_CACHE_DAYS * MILLIS_PER_DAY))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache similar tracks for $seedUri: ${e.message}")
        }
    }

    override suspend fun getRecentSeedTracks(
        sinceMs: Long,
        minListenedMs: Long,
        minScore: Double,
        limit: Int
    ): List<SeedTrack> {
        val now = System.currentTimeMillis()
        // The rows arrive in recency order; the floor is on the faded score, so it
        // is applied here before the limit, not in SQL after it.
        return dao.getRecentSeedTrackCandidates(sinceMs, minListenedMs, storedTrackScoreFloor(minScore))
            .asSequence()
            .map { it.toSeedTrack(now) }
            .filter { it.score >= minScore }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    override suspend fun getConfirmedSeedTracks(
        sinceMs: Long,
        minListenedMs: Long,
        minPlays: Int,
        limit: Int
    ): List<SeedTrack> {
        val now = System.currentTimeMillis()
        return dao.getConfirmedSeedTracks(sinceMs, minListenedMs, minPlays, limit).map { it.toSeedTrack(now) }
    }

    /** Carries the effective score: every seed consumer ranks by current taste. */
    private fun SeedTrackRow.toSeedTrack(now: Long) = SeedTrack(
        trackUri = trackUri,
        trackName = trackName,
        artistName = artistName,
        artistUri = artistUri,
        lastPlayedAt = lastPlayedAt,
        score = effectiveTrackScore(score, scoreUpdatedAt, now),
        genres = genres?.split(",")?.filter { g -> g.isNotBlank() } ?: emptyList(),
        artistGenres = artistGenres?.split(",")?.filter { g -> g.isNotBlank() } ?: emptyList()
    )

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

    override suspend fun getAllGenreNames(): List<String> = dao.getAllGenreNames()

    override suspend fun getArtistGenreMap(artistUris: List<String>): Map<String, List<String>> {
        if (artistUris.isEmpty()) return emptyMap()
        return dao.getGenresForArtists(artistUris.distinct())
            .groupBy({ it.artistUri }, { it.genre })
    }

    override suspend fun getArtistsByGenre(genre: String): List<Pair<String, String>> =
        dao.getArtistsByGenre(genreSpellings.spellingToQuery(genre)).map { it.name to it.uri }

    /**
     * Library artists with a genre that matches [query] by `genreKey`. SQL `LIKE` compared the
     * raw names, so "synth pop" missed the stored "synthpop"; the key cannot be computed in
     * SQL, so the few hundred genre names are matched here. The names go back in chunks
     * because SQLite on older Android allows 999 bound variables per query.
     */
    override suspend fun searchArtistUrisByGenre(query: String): List<String> {
        val names = genreNamesMatching(query, dao.getLibraryArtistGenreNames())
        return names.chunked(SQL_IN_CHUNK).flatMap { dao.getLibraryArtistUrisForGenres(it) }.distinct()
    }

    override suspend fun resolveLibraryArtistUri(name: String): String? =
        dao.getArtistUrisByName(name).firstOrNull { it.startsWith("library://") }

    override suspend fun getLibraryArtistUriMap(): Map<String, String> =
        dao.getLibraryArtistUris().associate { it.name to it.uri }

    override suspend fun enrichArtistGenres(artistName: String, genres: List<String>) {
        val uris = dao.getArtistUrisByName(artistName)
        if (uris.isEmpty()) return
        val normalizedGenres = genreSpellings.spellingsToStore(genres)
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
        val dupBefore = try {
            dao.countDuplicateArtistMappings()
        } catch (_: Exception) {
            -1
        }
        appDatabase.withTransaction {
            dao.deleteOlderThan(cutoff)
            dao.deleteOldSmartFeedback(feedbackCutoff)
            // Self-healing (deterministic, structural only): collapse provider://
            // artist mappings onto the canonical library:// row of the same
            // artist, then propagate genres across an artist's remaining URIs.
            // Does NOT touch the genres themselves (a wrong MA or MusicBrainz
            // genre needs a separate validation path). Runs before orphan cleanup so the
            // provider artists it strips get swept in the same pass.
            dao.consolidateProviderArtistMappings()
            dao.backfillArtistGenres()
            dao.deleteOrphanTracks()
            dao.deleteOrphanAlbums()
            dao.deleteOrphanArtists()
            dao.deleteOrphanArtistGenres()
            dao.deleteOrphanGenres()
        }
        val dupAfter = try {
            dao.countDuplicateArtistMappings()
        } catch (_: Exception) {
            -1
        }
        Log.d(TAG, "Cleanup done: retention $retentionMonths months, feedback $FEEDBACK_RETENTION_DAYS days, orphans purged, duplicate artist mappings $dupBefore -> $dupAfter")
    }

    override suspend fun clearRecommendationData() {
        dao.clearRecommendationData()
        adjacencyCache = null
        Log.w(TAG, "Recommendation DB data cleared by user action")
    }

    @VisibleForTesting
    internal data class WeightedPlay(val playedAt: Long, val weight: Double)

    @VisibleForTesting
    internal fun completionWeight(listenedMs: Long?, durationSec: Double?): Double {
        if (listenedMs == null || durationSec == null || durationSec <= 0.0) return 1.0
        val ratio = min((listenedMs / 1000.0) / durationSec, 1.0)
        return COMPLETION_FLOOR + COMPLETION_RANGE * ratio
    }

    @VisibleForTesting
    internal fun computeWeightedBllScore(nowMs: Long, plays: List<WeightedPlay>): Double {
        val sorted = plays.sortedBy { it.playedAt }
        var sessionCount = 0
        var prevTime = 0L
        val sum = sorted.sumOf { (playedAt, weight) ->
            // The FIRST play always resets sessionCount (prevTime == 0L gate): it has
            // no predecessor, so it is never an in-session repeat and keeps full
            // weight (sessionFactor 0.7^0 = 1). Do NOT "simplify" away the prevTime>0
            // guard - dropping it would dampen every mix's opening track.
            if (playedAt - prevTime < SESSION_GAP_MS && prevTime > 0) {
                sessionCount++
            } else {
                sessionCount = 0
            }
            prevTime = playedAt
            val sessionFactor = SESSION_DECAY.pow(sessionCount)
            val hoursAgo = ((nowMs - playedAt).toDouble() / MILLIS_PER_HOUR).coerceAtLeast(BLL_MIN_HOURS)
            hoursAgo.pow(BLL_DECAY) * weight * sessionFactor
        }
        return if (sum > 0.0) ln(sum) else BLL_FLOOR
    }

    private fun sanitizeYear(year: Int?): Int? = year?.takeIf { it > 0 }
}
