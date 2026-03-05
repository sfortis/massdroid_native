package net.asksakis.massdroidv2.data.repository

import android.util.Log
import net.asksakis.massdroidv2.data.database.AlbumEntity
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.GenreEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.PlayHistoryEntity
import net.asksakis.massdroidv2.data.database.TrackArtistEntity
import net.asksakis.massdroidv2.data.database.TrackEntity
import net.asksakis.massdroidv2.data.database.TrackGenreEntity
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.AlbumScore
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.RecentAlbum
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow

@Singleton
class PlayHistoryRepositoryImpl @Inject constructor(
    private val dao: PlayHistoryDao
) : PlayHistoryRepository {

    companion object {
        private const val TAG = "PlayHistoryRepo"
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val MILLIS_PER_HOUR = 3_600_000.0
        private const val BLL_DECAY = -1.5
        private const val BLL_MIN_HOURS = 0.5
        private const val BLL_FLOOR = -100.0
        private const val ADJACENCY_CACHE_HOURS = 6L
    }

    private var adjacencyCache: Map<String, Set<String>>? = null
    private var adjacencyCacheTime: Long = 0L

    override suspend fun recordPlay(
        track: Track,
        queueId: String,
        listenedMs: Long?,
        artists: List<Pair<String, String>>
    ): Long {
        if (!track.albumUri.isNullOrBlank()) {
            dao.insertAlbum(
                AlbumEntity(
                    uri = track.albumUri,
                    name = track.albumName,
                    imageUrl = track.imageUrl,
                    year = track.year
                )
            )
        }

        dao.insertTrack(
            TrackEntity(
                uri = track.uri,
                name = track.name,
                albumUri = track.albumUri?.takeIf { it.isNotBlank() },
                duration = track.duration,
                imageUrl = track.imageUrl
            )
        )

        for ((uri, name) in artists) {
            dao.insertArtist(ArtistEntity(uri = uri, name = name))
            dao.insertTrackArtist(TrackArtistEntity(trackUri = track.uri, artistUri = uri))
        }

        for (genre in track.genres) {
            if (genre.isNotBlank()) {
                dao.insertGenre(GenreEntity(name = genre))
                dao.insertTrackGenre(TrackGenreEntity(trackUri = track.uri, genreName = genre))
            }
        }

        val play = PlayHistoryEntity(
            trackUri = track.uri,
            queueId = queueId,
            playedAt = System.currentTimeMillis(),
            listenedMs = listenedMs
        )
        val id = dao.insertPlay(play)
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
            GenreScore(
                genre = genre,
                score = computeBllScore(nowMs, plays.map { it.playedAt })
            )
        }.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun getScoredArtists(days: Int, limit: Int): List<ArtistScore> {
        val since = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        val nowMs = System.currentTimeMillis()
        val timestamps = dao.getArtistPlayTimestamps(since)
        val grouped = timestamps.groupBy { it.artistUri }
        return grouped.map { (uri, plays) ->
            ArtistScore(
                artistUri = uri,
                artistName = plays.first().artistName,
                score = computeBllScore(nowMs, plays.map { it.playedAt })
            )
        }.sortedByDescending { it.score }.take(limit)
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
        return dao.getGenreArtistUris()
            .groupBy({ it.genre }, { it.artistUri })
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

    override suspend fun cleanup(retentionMonths: Int) {
        val cutoff = System.currentTimeMillis() - (retentionMonths * 30L * MILLIS_PER_DAY)
        dao.deleteOlderThan(cutoff)
        dao.deleteOrphanTracks()
        dao.deleteOrphanAlbums()
        dao.deleteOrphanArtists()
        dao.deleteOrphanGenres()
        Log.d(TAG, "Cleaned up play history older than $retentionMonths months + orphans")
    }

    private fun computeBllScore(nowMs: Long, playTimestamps: List<Long>): Double {
        val sum = playTimestamps.sumOf { playedAt ->
            val hoursAgo = ((nowMs - playedAt).toDouble() / MILLIS_PER_HOUR).coerceAtLeast(BLL_MIN_HOURS)
            hoursAgo.pow(BLL_DECAY)
        }
        return if (sum > 0.0) ln(sum) else BLL_FLOOR
    }
}
