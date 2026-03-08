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
        PlayHistoryEntity::class,
        SmartFeedbackEntity::class,
        BlockedArtistEntity::class,
        ArtistGenreEntity::class,
        LastFmArtistTagsEntity::class,
        LastFmSimilarArtistEntity::class,
        ArtistTrackCacheEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao
}
