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
        ArtistTrackCacheEntity::class,
        MaSimilarArtistEntity::class,
        MaSimilarTrackCacheEntity::class,
        MusicBrainzArtistTagsEntity::class
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    companion object {
        /**
         * Schema version. Referenced by the @Database annotation above and by the
         * destructive-migration reporter, which cannot read it from the database
         * itself: inside onDestructiveMigration the file still carries the OLD
         * version, so reporting `db.version` there said "v1 -> v1".
         */
        const val SCHEMA_VERSION = 18
    }
}
