package net.asksakis.massdroidv2.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.database.AppDatabase
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.sendspin.SendspinSyncEngine
import net.asksakis.massdroidv2.data.sendspin.SendspinClient
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `smart_feedback` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `track_uri` TEXT,
                    `artist_uri` TEXT,
                    `action` TEXT NOT NULL,
                    `signal` REAL NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`track_uri`) REFERENCES `tracks`(`uri`) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(`artist_uri`) REFERENCES `artists`(`uri`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_feedback_track_uri` ON `smart_feedback` (`track_uri`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_feedback_artist_uri` ON `smart_feedback` (`artist_uri`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_smart_feedback_created_at` ON `smart_feedback` (`created_at`)")

            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `blocked_artists` (
                    `artist_uri` TEXT NOT NULL,
                    `artist_name` TEXT,
                    `blocked_at` INTEGER NOT NULL,
                    PRIMARY KEY(`artist_uri`),
                    FOREIGN KEY(`artist_uri`) REFERENCES `artists`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_blocked_artists_artist_uri` ON `blocked_artists` (`artist_uri`)")
        }
    }

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideMaWebSocketClient(
        okHttpClient: OkHttpClient,
        json: Json
    ): MaWebSocketClient = MaWebSocketClient(okHttpClient, json)

    @Provides
    @Singleton
    fun provideSendspinClient(
        wsClient: MaWebSocketClient,
        json: Json
    ): SendspinClient = SendspinClient(
        httpClientProvider = { wsClient.getHttpClient() },
        json = json
    )

    @Provides
    @Singleton
    fun provideSendspinEngine(): SendspinSyncEngine = SendspinSyncEngine()

    @Provides
    @Singleton
    fun provideAcousticLatencyCalibrator(): net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator =
        net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator()

    @Provides
    @Singleton
    fun provideSendspinManager(
        client: SendspinClient,
        engine: SendspinSyncEngine,
    ): SendspinManager = SendspinManager(client, engine)

    @Provides
    @Singleton
    fun provideMaAuthProbe(
        okHttpClient: OkHttpClient,
        json: Json
    ): net.asksakis.massdroidv2.data.websocket.MaAuthProbe =
        net.asksakis.massdroidv2.data.websocket.MaAuthProbe(okHttpClient, json)

    @Provides
    @Singleton
    fun provideLocalSpeakerVolumeBridge(
        @ApplicationContext ctx: Context,
        sendspinManager: SendspinManager
    ): net.asksakis.massdroidv2.data.sendspin.LocalSpeakerVolumeBridge {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return net.asksakis.massdroidv2.data.sendspin.LocalSpeakerVolumeBridge(
            audioManager = am,
            volumeEvents = sendspinManager.serverVolumeEvents,
            muteEvents = sendspinManager.serverMuteEvents
        )
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lastfm_similar_artists` (
                    `source_artist` TEXT NOT NULL,
                    `similar_artist` TEXT NOT NULL,
                    `match_score` REAL NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`source_artist`, `similar_artist`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artist_track_cache` (
                    `artist_uri` TEXT NOT NULL,
                    `tracks_json` TEXT NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`artist_uri`)
                )
                """.trimIndent()
            )
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `lastfm_similar_artists` ADD COLUMN `resolved_item_id` TEXT")
            database.execSQL("ALTER TABLE `lastfm_similar_artists` ADD COLUMN `resolved_provider` TEXT")
            database.execSQL("ALTER TABLE `lastfm_similar_artists` ADD COLUMN `resolved_name` TEXT")
            database.execSQL("ALTER TABLE `lastfm_similar_artists` ADD COLUMN `resolved_image_url` TEXT")
            database.execSQL("ALTER TABLE `lastfm_similar_artists` ADD COLUMN `resolved_uri` TEXT")
            database.execSQL("ALTER TABLE `lastfm_similar_artists` ADD COLUMN `resolved_at` INTEGER")
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `tracks` ADD COLUMN `score` REAL NOT NULL DEFAULT 0.0")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Recreate blocked_artists without FK constraint so orphan cleanup doesn't cascade-delete blocks
            database.execSQL("CREATE TABLE IF NOT EXISTS `blocked_artists_new` (`artist_uri` TEXT NOT NULL, `artist_name` TEXT, `blocked_at` INTEGER NOT NULL, PRIMARY KEY(`artist_uri`))")
            database.execSQL("INSERT OR IGNORE INTO `blocked_artists_new` SELECT * FROM `blocked_artists`")
            database.execSQL("DROP TABLE `blocked_artists`")
            database.execSQL("ALTER TABLE `blocked_artists_new` RENAME TO `blocked_artists`")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artist_genres` (
                    `artist_uri` TEXT NOT NULL,
                    `genre_name` TEXT NOT NULL,
                    PRIMARY KEY(`artist_uri`, `genre_name`),
                    FOREIGN KEY(`artist_uri`) REFERENCES `artists`(`uri`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`genre_name`) REFERENCES `genres`(`name`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_genres_genre_name` ON `artist_genres` (`genre_name`)")

            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lastfm_artist_tags` (
                    `artist_name` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`artist_name`)
                )
                """.trimIndent()
            )
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "massdroid.db"
    ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
        .fallbackToDestructiveMigration()
        .build()

    @Provides
    fun providePlayHistoryDao(db: AppDatabase): PlayHistoryDao = db.playHistoryDao()
}
