package net.asksakis.massdroidv2.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbum(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtist(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenre(genre: GenreEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackArtist(trackArtist: TrackArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackGenre(trackGenre: TrackGenreEntity)

    @Insert
    suspend fun insertPlay(play: PlayHistoryEntity): Long

    @Insert
    suspend fun insertSmartFeedback(feedback: SmartFeedbackEntity)

    @Insert
    suspend fun insertSmartFeedback(feedback: List<SmartFeedbackEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistGenre(artistGenre: ArtistGenreEntity)

    @Query("SELECT genre_name FROM artist_genres WHERE artist_uri = :artistUri")
    suspend fun getGenresForArtist(artistUri: String): List<String>

    @Query("SELECT uri FROM artists WHERE name = :name")
    suspend fun getArtistUrisByName(name: String): List<String>

    @Query("SELECT DISTINCT name FROM artists WHERE name != ''")
    suspend fun getAllArtistNames(): List<String>

    @Query("SELECT name FROM genres ORDER BY name")
    suspend fun getAllGenreNames(): List<String>

    @Query("""
        SELECT DISTINCT g.name
        FROM genres g
        JOIN artist_genres ag ON ag.genre_name = g.name
        JOIN artists a ON a.uri = ag.artist_uri
        WHERE a.uri LIKE 'library://%'
        ORDER BY g.name
    """)
    suspend fun getLibraryGenres(): List<String>

    @Query("""
        SELECT DISTINCT a.name, a.uri
        FROM artist_genres ag
        JOIN artists a ON a.uri = ag.artist_uri
        WHERE ag.genre_name = :genre AND a.uri LIKE 'library://%'
        ORDER BY a.name
    """)
    suspend fun getLibraryArtistsByGenre(genre: String): List<ArtistNameUri>

    @Query("""
        SELECT a.name, MIN(a.uri) AS uri
        FROM artist_genres ag
        JOIN artists a ON a.uri = ag.artist_uri
        WHERE ag.genre_name = :genre
        GROUP BY a.name
        ORDER BY a.name
    """)
    suspend fun getArtistsByGenre(genre: String): List<ArtistNameUri>

    @Query("SELECT name, uri FROM artists WHERE uri LIKE 'library://%'")
    suspend fun getLibraryArtistUris(): List<ArtistNameUri>

    @Query("""
        INSERT OR IGNORE INTO artist_genres (artist_uri, genre_name)
        SELECT a2.uri, ag.genre_name
        FROM artist_genres ag
        JOIN artists a1 ON a1.uri = ag.artist_uri
        JOIN artists a2 ON a2.name = a1.name
        WHERE a2.uri != ag.artist_uri
    """)
    suspend fun backfillArtistGenres()

    @Query("""
        SELECT DISTINCT ag.artist_uri FROM artist_genres ag
        WHERE ag.genre_name LIKE '%' || :query || '%'
          AND ag.artist_uri LIKE 'library://%'
    """)
    suspend fun searchArtistUrisByGenre(query: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLastFmTags(tags: LastFmArtistTagsEntity)

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSimilarArtists(entities: List<LastFmSimilarArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistTrackCache(cache: ArtistTrackCacheEntity)

    @Query("SELECT * FROM artist_track_cache WHERE artist_uri = :artistUri LIMIT 1")
    suspend fun getArtistTrackCache(artistUri: String): ArtistTrackCacheEntity?

    @Query("DELETE FROM artist_track_cache WHERE fetched_at < :olderThan")
    suspend fun deleteExpiredArtistTrackCache(olderThan: Long)

    @Query("SELECT * FROM lastfm_similar_artists WHERE source_artist = :sourceArtist ORDER BY match_score DESC")
    suspend fun getSimilarArtists(sourceArtist: String): List<LastFmSimilarArtistEntity>

    @Query("SELECT fetched_at FROM lastfm_similar_artists WHERE source_artist = :sourceArtist LIMIT 1")
    suspend fun getSimilarArtistsFetchedAt(sourceArtist: String): Long?

    @Query(
        """UPDATE lastfm_similar_artists SET
           resolved_item_id = :itemId, resolved_provider = :provider, resolved_name = :name,
           resolved_image_url = :imageUrl, resolved_uri = :uri, resolved_at = :resolvedAt
           WHERE source_artist = :sourceArtist AND similar_artist = :similarArtist"""
    )
    suspend fun updateSimilarArtistResolved(
        sourceArtist: String, similarArtist: String,
        itemId: String?, provider: String?, name: String?, imageUrl: String?, uri: String?,
        resolvedAt: Long
    )

    @Query(
        """UPDATE lastfm_similar_artists SET
           resolved_item_id = NULL, resolved_provider = NULL, resolved_name = NULL,
           resolved_image_url = NULL, resolved_uri = NULL, resolved_at = NULL
           WHERE source_artist = :sourceArtist"""
    )
    suspend fun clearSimilarArtistResolved(sourceArtist: String)

    @Query("SELECT * FROM lastfm_artist_tags WHERE artist_name = :artistName")
    suspend fun getLastFmTags(artistName: String): LastFmArtistTagsEntity?

    @Query("DELETE FROM artist_genres WHERE artist_uri NOT IN (SELECT DISTINCT uri FROM artists)")
    suspend fun deleteOrphanArtistGenres()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlockedArtist(artist: BlockedArtistEntity)

    @Query("DELETE FROM blocked_artists WHERE artist_uri = :artistUri")
    suspend fun deleteBlockedArtist(artistUri: String)

    @Query("SELECT artist_uri FROM blocked_artists")
    suspend fun getBlockedArtistUris(): List<String>

    @Query("SELECT artist_uri FROM blocked_artists")
    fun observeBlockedArtistUris(): Flow<List<String>>

    @Query(
        """
        SELECT artist_uri AS artistUri, artist_name AS artistName, blocked_at AS blockedAt
        FROM blocked_artists
        ORDER BY blocked_at DESC
        """
    )
    suspend fun getBlockedArtists(): List<BlockedArtistRow>

    @Query("UPDATE tracks SET score = score + :delta WHERE uri = :trackUri")
    suspend fun adjustTrackScore(trackUri: String, delta: Double)

    @Query("SELECT uri FROM tracks WHERE score < :threshold")
    suspend fun getSuppressedTrackUris(threshold: Double = -0.15): List<String>

    @Query(
        """
        SELECT
            sf.artist_uri AS artistUri,
            a.name AS artistName,
            sf.signal,
            sf.created_at AS createdAt
        FROM smart_feedback sf
        JOIN artists a ON a.uri = sf.artist_uri
        WHERE sf.artist_uri IS NOT NULL
          AND sf.created_at > :since
        """
    )
    suspend fun getArtistFeedbackSignals(since: Long): List<ArtistFeedbackSignalRow>

    // Top genres by play count (track_genres + artist_genres via Last.fm)
    @Query(
        """
        SELECT genre, SUM(cnt) AS playCount FROM (
            SELECT g.name AS genre, COUNT(*) AS cnt
            FROM play_history ph
            JOIN track_genres tg ON tg.track_uri = ph.track_uri
            JOIN genres g ON g.name = tg.genre_name
            WHERE ph.played_at > :since
            GROUP BY g.name
            UNION ALL
            SELECT g.name AS genre, COUNT(*) AS cnt
            FROM play_history ph
            JOIN track_artists ta ON ta.track_uri = ph.track_uri
            JOIN artist_genres ag ON ag.artist_uri = ta.artist_uri
            JOIN genres g ON g.name = ag.genre_name
            WHERE ph.played_at > :since
              AND ag.genre_name NOT IN (
                  SELECT tg2.genre_name FROM track_genres tg2 WHERE tg2.track_uri = ph.track_uri
              )
            GROUP BY g.name
        )
        GROUP BY genre
        ORDER BY playCount DESC
        LIMIT :limit
        """
    )
    suspend fun getTopGenres(since: Long, limit: Int): List<GenrePlayCount>

    // Top artists by play count (grouped by name to merge cross-provider URIs)
    @Query(
        """
        SELECT
            MIN(a.uri) AS artistUri,
            a.name AS artistName,
            COUNT(*) AS playCount
        FROM play_history ph
        JOIN track_artists ta ON ta.track_uri = ph.track_uri
        JOIN artists a ON a.uri = ta.artist_uri
        WHERE ph.played_at > :since
        GROUP BY a.name
        ORDER BY playCount DESC
        LIMIT :limit
        """
    )
    suspend fun getTopArtists(since: Long, limit: Int): List<ArtistPlayCount>

    // Top tracks by play count
    @Query(
        """
        SELECT t.uri AS trackUri, t.name AS trackName, COUNT(*) AS playCount
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        WHERE ph.played_at > :since
        GROUP BY t.uri
        ORDER BY playCount DESC
        LIMIT :limit
        """
    )
    suspend fun getTopTracks(since: Long, limit: Int): List<TrackPlayCount>

    // Top albums by play count
    @Query(
        """
        SELECT al.uri AS albumUri, al.name AS albumName, al.image_url AS imageUrl,
               al.year AS year, COUNT(*) AS playCount
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        JOIN albums al ON al.uri = t.album_uri
        WHERE ph.played_at > :since
        GROUP BY al.uri
        ORDER BY playCount DESC
        LIMIT :limit
        """
    )
    suspend fun getTopAlbums(since: Long, limit: Int): List<AlbumPlayCount>

    // Recent albums (most recently played)
    @Query(
        """
        SELECT al.uri AS albumUri, al.name AS albumName, al.image_url AS imageUrl,
               al.year AS year, MAX(ph.played_at) AS lastPlayedAt
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        JOIN albums al ON al.uri = t.album_uri
        WHERE ph.played_at > :since
        GROUP BY al.uri
        ORDER BY lastPlayedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentAlbums(since: Long, limit: Int): List<RecentAlbumRow>

    // Rediscover: albums played between 30-180 days ago
    @Query(
        """
        SELECT al.uri AS albumUri, al.name AS albumName, al.image_url AS imageUrl,
               al.year AS year, MAX(ph.played_at) AS lastPlayedAt
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        JOIN albums al ON al.uri = t.album_uri
        WHERE ph.played_at < :before AND ph.played_at > :after
        GROUP BY al.uri
        ORDER BY lastPlayedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRediscoverAlbums(before: Long, after: Long, limit: Int): List<RecentAlbumRow>

    // Recent plays with full details
    @Query(
        """
        SELECT ph.id, ph.track_uri AS trackUri, t.name AS trackName,
               t.album_uri AS albumUri, al.name AS albumName,
               t.image_url AS imageUrl, t.duration, al.year,
               ph.queue_id AS queueId, ph.played_at AS playedAt,
               ph.listened_ms AS listenedMs
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        LEFT JOIN albums al ON al.uri = t.album_uri
        WHERE ph.played_at > :since
        ORDER BY ph.played_at DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentPlays(since: Long, limit: Int): List<RecentPlayRow>

    // Timestamps for time-of-day analysis
    @Query(
        """
        SELECT ph.played_at AS playedAt
        FROM play_history ph
        WHERE ph.played_at > :since
        """
    )
    suspend fun getPlaysForTimeAnalysis(since: Long): List<TimeAnalysisRow>

    // Helpers: get artists for a track
    @Query(
        """
        SELECT a.uri AS artistUri, a.name AS artistName
        FROM track_artists ta
        JOIN artists a ON a.uri = ta.artist_uri
        WHERE ta.track_uri = :trackUri
        """
    )
    suspend fun getArtistsForTrack(trackUri: String): List<TrackArtistRow>

    // Helpers: get genres for a track
    @Query("SELECT genre_name FROM track_genres WHERE track_uri = :trackUri")
    suspend fun getGenresForTrack(trackUri: String): List<String>

    // Genre play timestamps for BLL scoring (track_genres + artist_genres)
    @Query(
        """
        SELECT genre, playedAt, listenedMs, duration FROM (
            SELECT g.name AS genre, ph.played_at AS playedAt,
                   ph.listened_ms AS listenedMs, t.duration
            FROM play_history ph
            JOIN tracks t ON t.uri = ph.track_uri
            JOIN track_genres tg ON tg.track_uri = ph.track_uri
            JOIN genres g ON g.name = tg.genre_name
            WHERE ph.played_at > :since
            UNION ALL
            SELECT g.name AS genre, ph.played_at AS playedAt,
                   ph.listened_ms AS listenedMs, t.duration
            FROM play_history ph
            JOIN tracks t ON t.uri = ph.track_uri
            JOIN track_artists ta ON ta.track_uri = ph.track_uri
            JOIN artist_genres ag ON ag.artist_uri = ta.artist_uri
            JOIN genres g ON g.name = ag.genre_name
            WHERE ph.played_at > :since
              AND ag.genre_name NOT IN (
                  SELECT tg2.genre_name FROM track_genres tg2 WHERE tg2.track_uri = ph.track_uri
              )
        )
        """
    )
    suspend fun getGenrePlayTimestamps(since: Long): List<GenrePlayTimestamp>

    // Artist play timestamps for BLL scoring (grouped by name to merge cross-provider URIs)
    @Query(
        """
        SELECT
            MIN(a.uri) AS artistUri,
            a.name AS artistName,
            ph.played_at AS playedAt,
            ph.listened_ms AS listenedMs,
            t.duration
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        JOIN track_artists ta ON ta.track_uri = ph.track_uri
        JOIN artists a ON a.uri = ta.artist_uri
        WHERE ph.played_at > :since
        GROUP BY ph.id, a.name
        """
    )
    suspend fun getArtistPlayTimestamps(since: Long): List<ArtistPlayTimestamp>

    // Genre -> artist URI mappings (for genre radio), merged by artist name
    @Query(
        """
        SELECT genre, MIN(artistUri) AS artistUri, artistName FROM (
            SELECT tg.genre_name AS genre, a.uri AS artistUri, a.name AS artistName
            FROM track_genres tg
            JOIN track_artists ta ON ta.track_uri = tg.track_uri
            JOIN artists a ON a.uri = ta.artist_uri
            UNION
            SELECT ag.genre_name AS genre, ag.artist_uri AS artistUri, a2.name AS artistName
            FROM artist_genres ag
            JOIN artists a2 ON a2.uri = ag.artist_uri
        )
        GROUP BY genre, artistName
        """
    )
    suspend fun getGenreArtistUris(): List<GenreArtistUri>

    // Genre co-occurrence for adjacency map (artist_genres pairs count too)
    @Query(
        """
        SELECT genre1, genre2, SUM(cnt) AS coCount FROM (
            SELECT tg1.genre_name AS genre1, tg2.genre_name AS genre2, COUNT(*) AS cnt
            FROM track_genres tg1
            JOIN track_genres tg2 ON tg1.track_uri = tg2.track_uri
            WHERE tg1.genre_name < tg2.genre_name
            GROUP BY tg1.genre_name, tg2.genre_name
            UNION ALL
            SELECT ag1.genre_name AS genre1, ag2.genre_name AS genre2, COUNT(*) AS cnt
            FROM artist_genres ag1
            JOIN artist_genres ag2 ON ag1.artist_uri = ag2.artist_uri
            WHERE ag1.genre_name < ag2.genre_name
            GROUP BY ag1.genre_name, ag2.genre_name
        )
        GROUP BY genre1, genre2
        HAVING coCount >= 2
        """
    )
    suspend fun getGenreCoOccurrences(): List<GenreCoOccurrence>

    // Artist -> decade play counts (merged by artist name)
    @Query(
        """
        SELECT
               MIN(ta.artist_uri) AS artistUri,
               a.name AS artistName,
               ((al.year / 10) * 10) AS decade,
               COUNT(*) AS playCount
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        JOIN albums al ON al.uri = t.album_uri
        JOIN track_artists ta ON ta.track_uri = t.uri
        JOIN artists a ON a.uri = ta.artist_uri
        WHERE ph.played_at > :since
          AND al.year IS NOT NULL
          AND al.year > 0
        GROUP BY a.name, decade
        """
    )
    suspend fun getArtistDecadePlayCounts(since: Long): List<ArtistDecadePlayCount>

    // Top listened decades for a specific genre (track_genres + artist_genres)
    @Query(
        """
        SELECT decade, SUM(cnt) AS playCount FROM (
            SELECT ((al.year / 10) * 10) AS decade, COUNT(*) AS cnt
            FROM play_history ph
            JOIN tracks t ON t.uri = ph.track_uri
            JOIN albums al ON al.uri = t.album_uri
            JOIN track_genres tg ON tg.track_uri = t.uri
            WHERE ph.played_at > :since
              AND al.year IS NOT NULL AND al.year > 0
              AND tg.genre_name = :genre
            GROUP BY decade
            UNION ALL
            SELECT ((al.year / 10) * 10) AS decade, COUNT(*) AS cnt
            FROM play_history ph
            JOIN tracks t ON t.uri = ph.track_uri
            JOIN albums al ON al.uri = t.album_uri
            JOIN track_artists ta ON ta.track_uri = t.uri
            JOIN artist_genres ag ON ag.artist_uri = ta.artist_uri
            WHERE ph.played_at > :since
              AND al.year IS NOT NULL AND al.year > 0
              AND ag.genre_name = :genre
              AND :genre NOT IN (
                  SELECT tg2.genre_name FROM track_genres tg2 WHERE tg2.track_uri = t.uri
              )
            GROUP BY decade
        )
        GROUP BY decade
        ORDER BY playCount DESC
        LIMIT :limit
        """
    )
    suspend fun getTopDecadesForGenre(
        genre: String,
        since: Long,
        limit: Int
    ): List<DecadePlayCount>

    // Cleanup
    @Query("DELETE FROM play_history WHERE played_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM tracks WHERE uri NOT IN (SELECT DISTINCT track_uri FROM play_history) AND score = 0.0")
    suspend fun deleteOrphanTracks()

    @Query("DELETE FROM albums WHERE uri NOT IN (SELECT DISTINCT album_uri FROM tracks WHERE album_uri IS NOT NULL)")
    suspend fun deleteOrphanAlbums()

    @Query("DELETE FROM artists WHERE uri NOT IN (SELECT DISTINCT artist_uri FROM track_artists)")
    suspend fun deleteOrphanArtists()

    @Query(
        """
        DELETE FROM genres WHERE name NOT IN (
            SELECT DISTINCT genre_name FROM track_genres
            UNION
            SELECT DISTINCT genre_name FROM artist_genres
        )
        """
    )
    suspend fun deleteOrphanGenres()

    @Query("DELETE FROM smart_feedback WHERE created_at < :before")
    suspend fun deleteOldSmartFeedback(before: Long)

    @Query("DELETE FROM smart_feedback")
    suspend fun clearSmartFeedback()

    @Query("DELETE FROM blocked_artists")
    suspend fun clearBlockedArtists()

    @Query("DELETE FROM play_history")
    suspend fun clearPlayHistory()

    @Query("DELETE FROM track_genres")
    suspend fun clearTrackGenres()

    @Query("DELETE FROM track_artists")
    suspend fun clearTrackArtists()

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM artists")
    suspend fun clearArtists()

    @Query("DELETE FROM genres")
    suspend fun clearGenres()

    @Query("DELETE FROM artist_genres")
    suspend fun clearArtistGenres()

    @Query("DELETE FROM lastfm_artist_tags")
    suspend fun clearLastFmTags()

    @Query("DELETE FROM lastfm_similar_artists")
    suspend fun clearLastFmSimilarArtists()

    @Transaction
    suspend fun clearRecommendationData() {
        clearSmartFeedback()
        clearBlockedArtists()
        clearPlayHistory()
        clearArtistGenres()
        clearLastFmTags()
        clearLastFmSimilarArtists()
        clearTrackGenres()
        clearTrackArtists()
        clearTracks()
        clearAlbums()
        clearArtists()
        clearGenres()
    }
}

// Projection data classes

data class GenrePlayCount(
    val genre: String,
    val playCount: Int
)

data class ArtistPlayCount(
    @ColumnInfo(name = "artistUri") val artistUri: String,
    @ColumnInfo(name = "artistName") val artistName: String,
    val playCount: Int
)

data class TrackPlayCount(
    @ColumnInfo(name = "trackUri") val trackUri: String,
    @ColumnInfo(name = "trackName") val trackName: String,
    val playCount: Int
)

data class AlbumPlayCount(
    @ColumnInfo(name = "albumUri") val albumUri: String,
    @ColumnInfo(name = "albumName") val albumName: String,
    @ColumnInfo(name = "imageUrl") val imageUrl: String?,
    val year: Int?,
    val playCount: Int
)

data class RecentAlbumRow(
    @ColumnInfo(name = "albumUri") val albumUri: String,
    @ColumnInfo(name = "albumName") val albumName: String,
    @ColumnInfo(name = "imageUrl") val imageUrl: String?,
    val year: Int?,
    @ColumnInfo(name = "lastPlayedAt") val lastPlayedAt: Long
)

data class RecentPlayRow(
    val id: Long,
    @ColumnInfo(name = "trackUri") val trackUri: String,
    @ColumnInfo(name = "trackName") val trackName: String,
    @ColumnInfo(name = "albumUri") val albumUri: String?,
    @ColumnInfo(name = "albumName") val albumName: String?,
    @ColumnInfo(name = "imageUrl") val imageUrl: String?,
    val duration: Double?,
    val year: Int?,
    @ColumnInfo(name = "queueId") val queueId: String,
    @ColumnInfo(name = "playedAt") val playedAt: Long,
    @ColumnInfo(name = "listenedMs") val listenedMs: Long?
)

data class TimeAnalysisRow(
    @ColumnInfo(name = "playedAt") val playedAt: Long
)

data class TrackArtistRow(
    @ColumnInfo(name = "artistUri") val artistUri: String,
    @ColumnInfo(name = "artistName") val artistName: String
)

data class GenrePlayTimestamp(
    val genre: String,
    @ColumnInfo(name = "playedAt") val playedAt: Long,
    @ColumnInfo(name = "listenedMs") val listenedMs: Long?,
    val duration: Double?
)

data class ArtistPlayTimestamp(
    @ColumnInfo(name = "artistUri") val artistUri: String,
    @ColumnInfo(name = "artistName") val artistName: String,
    @ColumnInfo(name = "playedAt") val playedAt: Long,
    @ColumnInfo(name = "listenedMs") val listenedMs: Long?,
    val duration: Double?
)

data class ArtistNameUri(
    val name: String,
    val uri: String
)

data class GenreArtistUri(
    val genre: String,
    @ColumnInfo(name = "artistUri") val artistUri: String,
    @ColumnInfo(name = "artistName") val artistName: String
)

data class GenreCoOccurrence(
    val genre1: String,
    val genre2: String,
    val coCount: Int
)

data class ArtistDecadePlayCount(
    @ColumnInfo(name = "artistUri") val artistUri: String,
    @ColumnInfo(name = "artistName") val artistName: String,
    val decade: Int,
    val playCount: Int
)

data class DecadePlayCount(
    val decade: Int,
    val playCount: Int
)

data class ArtistFeedbackSignalRow(
    @ColumnInfo(name = "artistUri") val artistUri: String,
    @ColumnInfo(name = "artistName") val artistName: String,
    val signal: Double,
    @ColumnInfo(name = "createdAt") val createdAt: Long
)

data class BlockedArtistRow(
    @ColumnInfo(name = "artistUri") val artistUri: String,
    @ColumnInfo(name = "artistName") val artistName: String?,
    @ColumnInfo(name = "blockedAt") val blockedAt: Long
)
