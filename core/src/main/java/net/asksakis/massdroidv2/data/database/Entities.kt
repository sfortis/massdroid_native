package net.asksakis.massdroidv2.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val uri: String,
    val name: String,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    val year: Int? = null
)

/** Indexed by [name]: the genre-gap and identity queries look artists up by name. */
@Entity(tableName = "artists", indices = [Index("name")])
data class ArtistEntity(
    @PrimaryKey val uri: String,
    val name: String,
    /**
     * MusicBrainz id, when Music Assistant reported one (library artists only).
     * Stored because it is the only unambiguous handle on an artist: a genre
     * lookup by NAME picks whoever happens to share it, which is how the
     * Scottish Annie's folk tags could end up describing the Norwegian pop one.
     */
    val mbid: String? = null
)

@Entity(tableName = "genres")
data class GenreEntity(
    @PrimaryKey val name: String
)

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = AlbumEntity::class,
        parentColumns = ["uri"],
        childColumns = ["album_uri"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("album_uri")]
)
data class TrackEntity(
    @PrimaryKey val uri: String,
    val name: String,
    @ColumnInfo(name = "album_uri") val albumUri: String? = null,
    val duration: Double? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    /**
     * Preference as last written, at [scoreUpdatedAt]. It fades with time, so read
     * it through `effectiveTrackScore` wherever it stands for current taste.
     */
    val score: Double = 0.0,
    /** When [score] was last written; the age the fading is computed from. */
    @ColumnInfo(name = "score_updated_at", defaultValue = "0") val scoreUpdatedAt: Long = 0L,
    /**
     * When the listener said "Not for me", or null. Kept apart from [score]
     * because it is an explicit instruction: it keeps the track out of mixes
     * permanently, while the score is allowed to fade.
     */
    @ColumnInfo(name = "disliked_at") val dislikedAt: Long? = null
)

@Entity(
    tableName = "track_artists",
    primaryKeys = ["track_uri", "artist_uri"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["uri"],
            childColumns = ["track_uri"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["uri"],
            childColumns = ["artist_uri"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("artist_uri")]
)
data class TrackArtistEntity(
    @ColumnInfo(name = "track_uri") val trackUri: String,
    @ColumnInfo(name = "artist_uri") val artistUri: String
)

@Entity(
    tableName = "track_genres",
    primaryKeys = ["track_uri", "genre_name"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["uri"],
            childColumns = ["track_uri"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["name"],
            childColumns = ["genre_name"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("genre_name")]
)
data class TrackGenreEntity(
    @ColumnInfo(name = "track_uri") val trackUri: String,
    @ColumnInfo(name = "genre_name") val genreName: String
)

@Entity(
    tableName = "play_history",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["uri"],
        childColumns = ["track_uri"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("track_uri"), Index("played_at")]
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_uri") val trackUri: String,
    @ColumnInfo(name = "queue_id") val queueId: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long? = null,
    /**
     * Why this play happened: the listener chose it, or a generated mix served it.
     * See [PlayOrigin].
     *
     * Without this the engine could not tell its own output from real taste, and it
     * fed on itself. Measured on a real library, 12314 of 15062 played tracks have
     * exactly ONE play, so the "recently played" seed pool is largely made of mix
     * results; eight Guts tracks, each played once because a previous mix served
     * them, once made him a primary seed and produced a 33-track hip hop mix for a
     * listener whose history is over six thousand electronic plays.
     *
     * Rows written before this column existed read as [PlayOrigin.UNKNOWN], which
     * callers must treat as "not proven organic" rather than as organic.
     */
    @ColumnInfo(name = "origin", defaultValue = "unknown") val origin: String = PlayOrigin.UNKNOWN.stored
)

/**
 * Where a play came from. Stored as a string so an unrecognised future value read
 * by an older build degrades to [UNKNOWN] instead of failing to parse.
 */
enum class PlayOrigin(val stored: String) {
    /** The listener picked this: library, search, an album, a playlist. */
    ORGANIC("organic"),
    /** Served by Smart Mix. */
    SMART_MIX("smart_mix"),
    /** Served by Genre Radio. */
    GENRE_RADIO("genre_radio"),
    /** Predates the column, or the queue's provenance was not known at the time. */
    UNKNOWN("unknown");

    companion object {
        fun from(stored: String?): PlayOrigin =
            entries.firstOrNull { it.stored == stored } ?: UNKNOWN
    }
}

@Entity(
    tableName = "smart_feedback",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["uri"],
            childColumns = ["track_uri"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["uri"],
            childColumns = ["artist_uri"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("track_uri"), Index("artist_uri"), Index("created_at")]
)
data class SmartFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_uri") val trackUri: String? = null,
    @ColumnInfo(name = "artist_uri") val artistUri: String? = null,
    val action: String,
    val signal: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "artist_genres",
    primaryKeys = ["artist_uri", "genre_name"],
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["uri"],
            childColumns = ["artist_uri"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["name"],
            childColumns = ["genre_name"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("genre_name")]
)
data class ArtistGenreEntity(
    @ColumnInfo(name = "artist_uri") val artistUri: String,
    @ColumnInfo(name = "genre_name") val genreName: String
)


@Entity(
    tableName = "blocked_artists"
)
data class BlockedArtistEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_uri")
    val artistUri: String,
    @ColumnInfo(name = "artist_name") val artistName: String? = null,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long
)


/**
 * An artist's tracks as fetched for the genre mix engine (`artist_tracks`, sampled
 * and enriched with the artist's genres), keyed by the normalized artist uri.
 * The seed-track engine's `top_tracks` answers live in [MaArtistTopTracksEntity]:
 * until schema 22 both wrote here under the same key, so each could read the
 * other's list.
 */
@Entity(tableName = "artist_track_cache")
data class ArtistTrackCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_uri") val artistUri: String,
    @ColumnInfo(name = "tracks_json") val tracksJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)

/**
 * MusicBrainz genres per artist, the genre source for candidates nobody else can
 * describe.
 *
 * Measured on a real mix: Music Assistant returns similar artists as PROVIDER
 * items (Deezer), which carry neither genres nor an MBID, so 50-70% of a
 * candidate pool reached the genre gate unjudgeable and was admitted blind -
 * that is how Russian pop landed in a shoegaze mix. MusicBrainz answered for
 * 13 of 16 of those artists, precisely (Lost Frequencies -> house/dance/edm,
 * The Telescopes -> shoegaze/dream pop), with no API key.
 *
 * Keyed by the artist's MBID when one is known and by the lowercased name
 * otherwise (the resolvers' `cacheKey`), because many candidates have no MBID to
 * look up with. The column is still called `artist_name`, so it often holds an
 * MBID. [tags] is weight-ordered and may be EMPTY, which is a real
 * answer ("MusicBrainz knows nothing about them") and is cached as such so the
 * 1 req/s budget is never spent on the same dead end twice.
 */
@Entity(tableName = "musicbrainz_artist_tags")
data class MusicBrainzArtistTagsEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_name") val artistName: String,
    val mbid: String,
    val tags: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    /**
     * Artist biography, from Wikipedia by way of this row's MBID. Kept here
     * rather than in a table of its own because it is looked up by the same key,
     * from the same id, at the same moment - but with its OWN timestamp, so a
     * cached genre does not make the app believe it already tried for a bio.
     */
    val bio: String? = null,
    @ColumnInfo(name = "bio_fetched_at") val bioFetchedAt: Long? = null
)

/**
 * MA `similar_tracks` results, cached so a mix build does not re-ask the server for
 * every seed every time.
 *
 * The track route asks once per seed on every build, and measured on a real device
 * that cost 8 to 14 seconds of a 15-second budget while the artist route beside it
 * answered from Room. Shaped like [ArtistTrackCacheEntity] rather than
 * [MaSimilarArtistEntity] because the server returns whole playable tracks here, so
 * there is nothing to normalise into columns and the ordering (which carries the
 * similarity ranking) survives the round-trip as-is.
 *
 * Keyed by the SEED's uri. Standalone, like `artist_track_cache`: the tracks it
 * holds may never be played.
 */
@Entity(tableName = "ma_similar_track_cache")
data class MaSimilarTrackCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "seed_uri") val seedUri: String,
    @ColumnInfo(name = "tracks_json") val tracksJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)

/**
 * MA `similar_artists` results, cached so a mix build does not re-ask the server
 * every time. Without this the MA discovery route made ~42 live WS calls per mix
 * (18s) where the Last.fm route it replaces was answered from Room in 6.6s.
 *
 * Keyed by MA uris on both sides, so entries stay valid across name changes and
 * can be fed straight back into `top_tracks` with no resolution step.
 * Standalone, like `artist_track_cache`: these artists may never be played.
 */
@Entity(tableName = "ma_similar_artists", primaryKeys = ["source_uri", "similar_uri"])
data class MaSimilarArtistEntity(
    @ColumnInfo(name = "source_uri") val sourceUri: String,
    @ColumnInfo(name = "similar_uri") val similarUri: String,
    @ColumnInfo(name = "similar_name") val similarName: String,
    @ColumnInfo(name = "similar_genres") val similarGenres: String,
    /** Server-provided ordering; lower is more similar. */
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)

/**
 * MA `top_tracks` answers for the seed-track engine, keyed by the artist uri the
 * engine asked with. A few tracks per artist, kept 14 days. Standalone, like
 * `artist_track_cache`: these artists may never be played.
 */
@Entity(tableName = "ma_artist_top_tracks")
data class MaArtistTopTracksEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_uri") val artistUri: String,
    @ColumnInfo(name = "tracks_json") val tracksJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)
