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
