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

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val uri: String,
    val name: String
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
    @ColumnInfo(name = "image_url") val imageUrl: String? = null
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
    @ColumnInfo(name = "listened_ms") val listenedMs: Long? = null
)

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

@Entity(tableName = "lastfm_artist_tags")
data class LastFmArtistTagsEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_name") val artistName: String,
    val tags: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)

@Entity(
    tableName = "blocked_artists",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["uri"],
            childColumns = ["artist_uri"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("artist_uri")]
)
data class BlockedArtistEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_uri")
    val artistUri: String,
    @ColumnInfo(name = "artist_name") val artistName: String? = null,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long
)

@Entity(
    tableName = "lastfm_similar_artists",
    primaryKeys = ["source_artist", "similar_artist"]
)
data class LastFmSimilarArtistEntity(
    @ColumnInfo(name = "source_artist") val sourceArtist: String,
    @ColumnInfo(name = "similar_artist") val similarArtist: String,
    @ColumnInfo(name = "match_score") val matchScore: Double,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)

@Entity(tableName = "artist_track_cache")
data class ArtistTrackCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "artist_uri") val artistUri: String,
    @ColumnInfo(name = "tracks_json") val tracksJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long
)
