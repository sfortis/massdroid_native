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

    /**
     * Batched form for the Smart Mix genre gate, which judges ~100 candidate
     * artists per mix and cannot afford one round-trip each.
     */
    @Query("""
        SELECT artist_uri AS artistUri, genre_name AS genre
        FROM artist_genres
        WHERE artist_uri IN (:artistUris)
    """)
    suspend fun getGenresForArtists(artistUris: List<String>): List<ArtistGenreRow>

    /**
     * Write the id to every uri the artist is stored under, not just the library
     * one, because playback records tracks against whichever provider uri the
     * server sent and the seed queries read that row. Matching by name is the
     * same propagation `backfillArtistGenres` already does, and is safe in the
     * direction that matters: the risk it carries (two different acts sharing a
     * name) is exactly the risk the id exists to remove downstream, and having
     * no id at all guarantees the ambiguous name lookup instead.
     *
     * `mbid IS NULL` so an id already known for a specific uri is never
     * overwritten by a namesake's.
     */
    @Query("UPDATE artists SET mbid = :mbid WHERE name = :name AND mbid IS NULL")
    suspend fun setArtistMbidIfMissing(name: String, mbid: String)

    @Query("SELECT uri, name, mbid FROM artists WHERE uri IN (:uris)")
    suspend fun getArtistsByUris(uris: List<String>): List<ArtistIdentityRow>

    /** Repoint a library uri at whoever the server says owns it now. */
    @Query("UPDATE artists SET name = :name, mbid = :mbid WHERE uri = :uri")
    suspend fun replaceArtistIdentity(uri: String, name: String, mbid: String?)

    /** Drop the genres attached to a uri, for when that uri turned out to be someone else. */
    @Query("DELETE FROM artist_genres WHERE artist_uri = :uri")
    suspend fun deleteArtistGenres(uri: String)

    @Query("SELECT uri FROM artists WHERE name = :name")
    suspend fun getArtistUrisByName(name: String): List<String>

    /**
     * Artists carrying no genre at all, one row per name, with an MBID if any of
     * that name's uris has one. This is what the genre enricher works through:
     * MusicBrainz allows one request per second, so walking every artist would
     * take hours, while walking only the gaps takes minutes.
     *
     * [ArtistNeedingGenres.sampleTrack] is one of their recordings we actually
     * hold, and it is what lets MusicBrainz tell namesakes apart: a bare name is
     * not an identity, but two artists sharing a name do not share a recording.
     */
    @Query("""
        SELECT a.name AS name, MIN(a.uri) AS uri, MAX(a.mbid) AS mbid,
               (SELECT t.name FROM tracks t
                JOIN track_artists ta ON ta.track_uri = t.uri
                JOIN artists a2 ON a2.uri = ta.artist_uri
                WHERE a2.name = a.name AND t.name != ''
                LIMIT 1) AS sampleTrack
        FROM artists a
        LEFT JOIN artist_genres g ON g.artist_uri = a.uri
        WHERE a.name != '' AND g.artist_uri IS NULL
        GROUP BY a.name
    """)
    suspend fun getArtistsWithoutGenres(): List<ArtistNeedingGenres>

    /**
     * Discovery artists nothing can describe: names Music Assistant returned as
     * similar artists without genres, that carry no genres under any uri either.
     *
     * These never reach [getArtistsWithoutGenres] because most of them are not in
     * `artists` at all - they are candidates the user has never played, so only
     * the similar-artist cache knows them. That is exactly why the Smart Mix
     * genre gate cannot judge them: measured on a real library, two thirds of the
     * candidates that passed the gate passed it unjudged, and off-genre tracks
     * rode in on that.
     *
     * The name is matched against every uri, not just the one the similar-artist
     * row carries, because one artist legitimately has several (`library://` and
     * `deezer://`) and only one of them may hold the genres.
     */
    @Query("""
        SELECT s.similar_name AS name, MIN(s.similar_uri) AS uri,
               MAX((SELECT a.mbid FROM artists a
                    WHERE a.name = s.similar_name AND a.mbid IS NOT NULL LIMIT 1)) AS mbid,
               (SELECT t.name FROM tracks t
                JOIN track_artists ta ON ta.track_uri = t.uri
                JOIN artists a2 ON a2.uri = ta.artist_uri
                WHERE a2.name = s.similar_name AND t.name != ''
                LIMIT 1) AS sampleTrack
        FROM ma_similar_artists s
        WHERE s.similar_name != ''
          AND s.similar_genres = ''
          AND s.similar_name NOT IN (
              SELECT a2.name FROM artists a2
              JOIN artist_genres g2 ON g2.artist_uri = a2.uri
          )
        GROUP BY s.similar_name
    """)
    suspend fun getDiscoveryArtistsWithoutGenres(): List<ArtistNeedingGenres>

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
    suspend fun upsertArtistTrackCache(cache: ArtistTrackCacheEntity)

    // ---- MA similar-artists cache ----

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMaSimilarArtists(entities: List<MaSimilarArtistEntity>)

    @Query("SELECT * FROM ma_similar_artists WHERE source_uri = :sourceUri ORDER BY position")
    suspend fun getMaSimilarArtists(sourceUri: String): List<MaSimilarArtistEntity>

    @Query("SELECT MIN(fetched_at) FROM ma_similar_artists WHERE source_uri = :sourceUri")
    suspend fun getMaSimilarArtistsFetchedAt(sourceUri: String): Long?

    @Query("DELETE FROM ma_similar_artists WHERE fetched_at < :olderThan")
    suspend fun deleteExpiredMaSimilarArtists(olderThan: Long)

    @Query("SELECT * FROM artist_track_cache WHERE artist_uri = :artistUri LIMIT 1")
    suspend fun getArtistTrackCache(artistUri: String): ArtistTrackCacheEntity?

    @Query("DELETE FROM artist_track_cache WHERE fetched_at < :olderThan")
    suspend fun deleteExpiredArtistTrackCache(olderThan: Long)

    // ---- MA similar-tracks cache ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMaSimilarTrackCache(cache: MaSimilarTrackCacheEntity)

    @Query("SELECT * FROM ma_similar_track_cache WHERE seed_uri = :seedUri LIMIT 1")
    suspend fun getMaSimilarTrackCache(seedUri: String): MaSimilarTrackCacheEntity?

    @Query("DELETE FROM ma_similar_track_cache WHERE fetched_at < :olderThan")
    suspend fun deleteExpiredMaSimilarTrackCache(olderThan: Long)

    // ---- Seed-track generator caches ----

    // COLLATE NOCASE because the same artist reaches us with different casing
    // artist reaches us with different casing depending on the provider.
    @Query("SELECT * FROM musicbrainz_artist_tags WHERE artist_name = :artistName COLLATE NOCASE LIMIT 1")
    suspend fun getMusicBrainzTags(artistName: String): MusicBrainzArtistTagsEntity?

    @Query("SELECT * FROM musicbrainz_artist_tags WHERE artist_name IN (:artistNames)")
    suspend fun getMusicBrainzTagsFor(artistNames: List<String>): List<MusicBrainzArtistTagsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMusicBrainzTags(entity: MusicBrainzArtistTagsEntity)

    /**
     * Writes only the bio columns, creating the row if the genre lookup has not
     * happened yet. A full upsert would blank the tags this row may already hold.
     */
    @Transaction
    suspend fun upsertArtistBio(artistName: String, bio: String, fetchedAt: Long) {
        val updated = updateArtistBio(artistName, bio, fetchedAt)
        if (updated == 0) {
            insertArtistBioRow(
                MusicBrainzArtistTagsEntity(
                    artistName = artistName,
                    mbid = "",
                    tags = "",
                    // Not a genre answer: leave the tag timestamp at zero so the
                    // genre resolver still treats this artist as unasked.
                    fetchedAt = 0L,
                    bio = bio,
                    bioFetchedAt = fetchedAt
                )
            )
        }
    }

    @Query("UPDATE musicbrainz_artist_tags SET bio = :bio, bio_fetched_at = :fetchedAt WHERE artist_name = :artistName")
    suspend fun updateArtistBio(artistName: String, bio: String, fetchedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistBioRow(entity: MusicBrainzArtistTagsEntity)

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

    /** Every stored row, one per uri. For expansion and matching, not for display. */
    @Query(
        """
        SELECT artist_uri AS artistUri, artist_name AS artistName, blocked_at AS blockedAt
        FROM blocked_artists
        ORDER BY blocked_at DESC
        """
    )
    suspend fun getBlockedArtists(): List<BlockedArtistRow>

    /**
     * One row per artist, for the list a person reads.
     *
     * A block is stored under every uri the server knows for that artist, so the
     * raw table holds several rows for one act and showed them as duplicates.
     * Grouping by name is right HERE and nowhere else: this is a list of people,
     * and two same-named acts appearing once is a display detail, while matching
     * them as one artist would be a real mistake.
     */
    @Query(
        """
        SELECT MIN(artist_uri) AS artistUri, artist_name AS artistName, MAX(blocked_at) AS blockedAt
        FROM blocked_artists
        WHERE artist_name IS NOT NULL AND artist_name != ''
        GROUP BY artist_name
        ORDER BY blockedAt DESC
        """
    )
    suspend fun getBlockedArtistsForDisplay(): List<BlockedArtistRow>

    /** Blocks with no name attached cannot be grouped, so they are listed as they are. */
    @Query(
        """
        SELECT artist_uri AS artistUri, artist_name AS artistName, blocked_at AS blockedAt
        FROM blocked_artists
        WHERE artist_name IS NULL OR artist_name = ''
        ORDER BY blocked_at DESC
        """
    )
    suspend fun getUnnamedBlockedArtists(): List<BlockedArtistRow>

    @Query("UPDATE tracks SET score = score + :delta WHERE uri = :trackUri")
    suspend fun adjustTrackScore(trackUri: String, delta: Double)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlockedArtists(rows: List<BlockedArtistEntity>)

    /** Unblock fallback when the server could not list an artist's other uris. */
    @Query("DELETE FROM blocked_artists WHERE artist_name = :artistName")
    suspend fun deleteBlockedArtistsByName(artistName: String)

    @Query("SELECT score FROM tracks WHERE uri = :trackUri")
    suspend fun getTrackScore(trackUri: String): Double?

    /**
     * Sets a score outright rather than nudging it. An explicit dislike has to
     * bury the track whatever it scored before, which a delta cannot promise:
     * a track the listener once loved can sit well above the suppression line.
     */
    @Query("UPDATE tracks SET score = :score WHERE uri = :trackUri")
    suspend fun setTrackScore(trackUri: String, score: Double)

    /**
     * Restores a score only if nothing has touched it since.
     *
     * An undo puts back an absolute value, so it would otherwise silently
     * discard whatever happened in between: dislike a track at 0.0, listen to it
     * again for +0.5, then undo, and the score returns to 0.0 rather than 0.5.
     * Comparing against the value the dislike wrote makes the update a
     * compare-and-set, so a newer opinion always wins over an older undo.
     */
    @Query("UPDATE tracks SET score = :restore WHERE uri = :trackUri AND score = :expected")
    suspend fun restoreTrackScoreIfUnchanged(trackUri: String, expected: Double, restore: Double): Int

    /** Undo support: removes exactly the rows one action wrote. */
    @Query("DELETE FROM smart_feedback WHERE track_uri = :trackUri AND action = :action AND created_at = :createdAt")
    suspend fun deleteSmartFeedback(trackUri: String, action: String, createdAt: Long)

    /**
     * Artists whose MusicBrainz identity was decided by NAME ALONE, and for whom we
     * now hold a recording that can decide it properly.
     *
     * A cache row keyed by a name (rather than an MBID) is by definition one we
     * resolved with a bare name search - and a name is not an identity. Measured:
     * "Labelle" resolved to the American soul group LaBelle, so a Reunion Island
     * electronic producer was described as disco/funk/soul and mis-genred an entire
     * mix. These rows are re-asked once, this time disambiguated by the recording.
     *
     * The MBID pattern is 8-4-4-4-12 hex; anything else is a name key.
     */
    @Query("""
        SELECT m.artist_name FROM musicbrainz_artist_tags m
        WHERE m.artist_name NOT LIKE '________-____-____-____-____________'
          AND EXISTS (
              SELECT 1 FROM artists a
              JOIN track_artists ta ON ta.artist_uri = a.uri
              JOIN tracks t ON t.uri = ta.track_uri
              WHERE lower(a.name) = m.artist_name AND t.name != ''
          )
    """)
    suspend fun getNameResolvedArtistsWithRecording(): List<String>

    @Query("DELETE FROM musicbrainz_artist_tags WHERE artist_name IN (:keys)")
    suspend fun deleteMusicBrainzTags(keys: List<String>)

    /**
     * Drops the genres of every uri sharing this (lowercased) name, so a re-resolve
     * REPLACES them. Without this the insert-ignore write would merge the old wrong
     * tags with the new right ones and leave the artist described as both.
     */
    @Query("""
        DELETE FROM artist_genres WHERE artist_uri IN (
            SELECT a.uri FROM artists a WHERE lower(a.name) IN (:lowercaseNames)
        )
    """)
    suspend fun deleteArtistGenresByNames(lowercaseNames: List<String>)

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

    // Top genres by play count (track_genres + artist_genres)
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

    // Seed tracks for the seed-track recommendation generator: recently played
    // tracks the user actually listened to (not skipped), with their primary
    // artist name, most-recent first. One row per track.
    @Query(
        """
        SELECT t.uri AS trackUri, t.name AS trackName, a.name AS artistName,
               a.uri AS artistUri, a.mbid AS artistMbid,
               MAX(ph.played_at) AS lastPlayedAt, t.score AS score,
               (SELECT GROUP_CONCAT(tg.genre_name) FROM track_genres tg WHERE tg.track_uri = t.uri) AS genres,
               (SELECT GROUP_CONCAT(DISTINCT ag.genre_name) FROM artist_genres ag
                WHERE ag.artist_uri = ta.artist_uri) AS artistGenres
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        JOIN track_artists ta ON ta.track_uri = t.uri
        JOIN artists a ON a.uri = ta.artist_uri
        WHERE ph.played_at > :since AND COALESCE(ph.listened_ms, 0) >= :minListenedMs
          AND t.score >= :minScore
        GROUP BY t.uri
        ORDER BY t.score DESC, lastPlayedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getSeedTracks(since: Long, minListenedMs: Long, minScore: Double, limit: Int): List<SeedTrackRow>

    // Recency-ordered seed pool. Strictness re-ranks this toward score in code, so
    // low Strictness still favours genuinely recent tracks over top-scored ones.
    //
    // [minScore] exists because re-ranking is not filtering, and this slice was
    // 70% of the pool with no floor at all. A track played once and never again
    // scores 0.28 (2695 of them on a real library), so the engine's OWN output
    // came back as its input: the abstract-hip-hop artist Guts, eight tracks each
    // played exactly once, became a primary seed and gave a listener who has no
    // hip hop in their profile a 33-track hip hop mix. The floor sits just above
    // that 0.28 so a single passive play cannot seed, while anything replayed,
    // liked, or listened through still can.
    @Query(
        """
        SELECT t.uri AS trackUri, t.name AS trackName, a.name AS artistName,
               a.uri AS artistUri, a.mbid AS artistMbid,
               MAX(ph.played_at) AS lastPlayedAt, t.score AS score,
               (SELECT GROUP_CONCAT(tg.genre_name) FROM track_genres tg WHERE tg.track_uri = t.uri) AS genres,
               (SELECT GROUP_CONCAT(DISTINCT ag.genre_name) FROM artist_genres ag
                WHERE ag.artist_uri = ta.artist_uri) AS artistGenres
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        JOIN track_artists ta ON ta.track_uri = t.uri
        JOIN artists a ON a.uri = ta.artist_uri
        WHERE ph.played_at > :since AND COALESCE(ph.listened_ms, 0) >= :minListenedMs
          AND t.score >= :minScore
        GROUP BY t.uri
        ORDER BY lastPlayedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentSeedTracks(
        since: Long,
        minListenedMs: Long,
        minScore: Double,
        limit: Int
    ): List<SeedTrackRow>

    // Confirmed-taste seed pool: same shape as getRecentSeedTracks, but ordered by
    // how often the track was REPLAYED (all time, not just inside the window) and
    // then by score, keeping only tracks with at least :minPlays plays.
    //
    // Why a second pool exists: ordering by recency alone lets whatever produced
    // the most PLAY VOLUME own every row, and that is the generated mixes
    // themselves. Measured on a real 30-day history, the 600-row recency window
    // collapsed to 240 artists of which 88% had been played exactly once, while
    // 739 artists with 3+ plays existed in the same window and only ~10 got in.
    // Replay count is the one taste signal that survives that: it cannot be
    // manufactured by the engine queueing something the listener did not skip.
    //
    // The play count is deliberately ALL TIME: a track loved last year and heard
    // once this month is confirmed taste, not a passive play.
    @Query(
        """
        SELECT t.uri AS trackUri, t.name AS trackName, a.name AS artistName,
               a.uri AS artistUri, a.mbid AS artistMbid,
               MAX(ph.played_at) AS lastPlayedAt, t.score AS score,
               (SELECT GROUP_CONCAT(tg.genre_name) FROM track_genres tg WHERE tg.track_uri = t.uri) AS genres,
               (SELECT GROUP_CONCAT(DISTINCT ag.genre_name) FROM artist_genres ag
                WHERE ag.artist_uri = ta.artist_uri) AS artistGenres
        FROM play_history ph
        JOIN tracks t ON t.uri = ph.track_uri
        JOIN track_artists ta ON ta.track_uri = t.uri
        JOIN artists a ON a.uri = ta.artist_uri
        WHERE ph.played_at > :since AND COALESCE(ph.listened_ms, 0) >= :minListenedMs
        GROUP BY t.uri
        HAVING (SELECT COUNT(*) FROM play_history p2 WHERE p2.track_uri = t.uri) >= :minPlays
        ORDER BY (SELECT COUNT(*) FROM play_history p2 WHERE p2.track_uri = t.uri) DESC,
                 t.score DESC
        LIMIT :limit
        """
    )
    suspend fun getConfirmedSeedTracks(
        since: Long,
        minListenedMs: Long,
        minPlays: Int,
        limit: Int
    ): List<SeedTrackRow>

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

    // --- Self-healing: artist mapping consolidation ---
    // The same artist is often stored under several URIs (library:// plus one or
    // more provider:// URIs, or even two provider URIs). When a track is mapped
    // to more than one URI of the SAME artist (by name), the extras are
    // redundant and bloat per-artist grouping/scoring. Keep exactly one canonical
    // row per (track, artist name): prefer library://, otherwise the
    // alphabetically-first URI (deterministic). Delete the rest.
    @Query("""
        DELETE FROM track_artists
        WHERE artist_uri <> (
            SELECT t2.artist_uri
            FROM track_artists t2
            JOIN artists a2 ON a2.uri = t2.artist_uri
            JOIN artists a ON a.uri = track_artists.artist_uri
            WHERE t2.track_uri = track_artists.track_uri AND a2.name = a.name
            ORDER BY CASE WHEN t2.artist_uri LIKE 'library://%' THEN 0 ELSE 1 END, t2.artist_uri
            LIMIT 1
        )
    """)
    suspend fun consolidateProviderArtistMappings()

    // Count of tracks that still carry duplicate same-name artist rows, for
    // logging how much the consolidation healed.
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT ta.track_uri
            FROM track_artists ta
            JOIN artists a ON a.uri = ta.artist_uri
            GROUP BY ta.track_uri, a.name
            HAVING COUNT(*) > 1
        )
    """)
    suspend fun countDuplicateArtistMappings(): Int

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

    @Transaction
    suspend fun clearRecommendationData() {
        clearSmartFeedback()
        clearBlockedArtists()
        clearPlayHistory()
        clearArtistGenres()
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

data class SeedTrackRow(
    @ColumnInfo(name = "trackUri") val trackUri: String,
    @ColumnInfo(name = "trackName") val trackName: String,
    @ColumnInfo(name = "artistName") val artistName: String,
    @ColumnInfo(name = "artistUri") val artistUri: String,
    @ColumnInfo(name = "lastPlayedAt") val lastPlayedAt: Long,
    @ColumnInfo(name = "score") val score: Double,
    @ColumnInfo(name = "genres") val genres: String?,
    @ColumnInfo(name = "artistGenres") val artistGenres: String?
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

data class ArtistIdentityRow(
    val uri: String,
    val name: String,
    val mbid: String?
)

data class ArtistNeedingGenres(
    val name: String,
    val uri: String,
    val mbid: String?,
    /**
     * One recording we hold for this artist, used to disambiguate them from a
     * namesake. Null for discovery candidates, which have never been played and
     * so have no track of theirs in the database.
     */
    val sampleTrack: String? = null
)

data class ArtistGenreRow(
    val artistUri: String,
    val genre: String
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
