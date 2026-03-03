package net.asksakis.massdroidv2.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlbumEntity::class,
        ArtistEntity::class,
        GenreEntity::class,
        TrackEntity::class,
        TrackArtistEntity::class,
        TrackGenreEntity::class,
        PlayHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao
}
