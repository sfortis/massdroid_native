package net.asksakis.massdroidv2.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import androidx.room.RoomDatabase
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
import net.asksakis.massdroidv2.data.sendspin.isBluetoothSink
import net.asksakis.massdroidv2.data.sendspin.soleBluetoothSinkName
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val DATABASE_NAME = "massdroid.db"

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
        json: Json,
        accountNoticeReporter: net.asksakis.massdroidv2.data.util.AccountNoticeReporter,
        @ApplicationContext ctx: Context
    ): MaWebSocketClient = MaWebSocketClient(okHttpClient, json, accountNoticeReporter, ctx)

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
    fun provideSendspinSyncEngine(
        @ApplicationContext ctx: Context
    ): SendspinSyncEngine = SendspinSyncEngine(ctx)

    @Provides
    @Singleton
    fun provideSendspinDirectEngine(
        @ApplicationContext ctx: Context
    ): net.asksakis.massdroidv2.data.sendspin.SendspinDirectEngine =
        net.asksakis.massdroidv2.data.sendspin.SendspinDirectEngine(ctx)

    @Provides
    @Singleton
    fun provideNativeAcousticCalibrator(): net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator =
        net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator()

    @Provides
    @Singleton
    fun provideSendspinManager(
        client: SendspinClient,
        syncEngine: SendspinSyncEngine,
        directEngine: net.asksakis.massdroidv2.data.sendspin.SendspinDirectEngine,
        sessionEventBus: net.asksakis.massdroidv2.data.websocket.SessionEventBus,
    ): SendspinManager = SendspinManager(client, syncEngine, directEngine, sessionEventBus)

    @Provides
    @Singleton
    fun provideMaAuthProbe(
        okHttpClient: OkHttpClient,
        json: Json
    ): net.asksakis.massdroidv2.data.websocket.MaAuthProbe =
        net.asksakis.massdroidv2.data.websocket.MaAuthProbe(okHttpClient, json)

    @Provides
    @Singleton
    fun provideSendspinVolumeCoordinator(
        @ApplicationContext ctx: Context,
        sendspinManager: SendspinManager,
        settingsRepository: net.asksakis.massdroidv2.domain.repository.SettingsRepository,
        playerRepository: net.asksakis.massdroidv2.domain.repository.PlayerRepository,
    ): net.asksakis.massdroidv2.data.sendspin.SendspinVolumeCoordinator {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return net.asksakis.massdroidv2.data.sendspin.SendspinVolumeCoordinator(
            audioManager = am,
            serverVolumeEvents = sendspinManager.serverVolumeEvents,
            serverMuteEvents = sendspinManager.serverMuteEvents,
            syncEnabledFlow = settingsRepository.sendspinSyncSystemVolume,
            sendspinClientIdFlow = settingsRepository.sendspinClientId,
            lastVolumeFlow = settingsRepository.sendspinLastVolume,
            persistLastVolume = { settingsRepository.setSendspinLastVolume(it) },
            playerRepository = playerRepository,
            currentOutputDeviceType = { sendspinManager.getRoutedDeviceType() },
            // Resolve the BT route key. Prefer the Oboe-routed device name when it
            // resolves to a BT sink: it is the device actually playing, so with
            // multiple connected A2DP sinks it picks the right one (and matches the
            // acoustic-calibration route key, which uses the same source). Fall back
            // to the first connected BT sink while the Oboe stream is still settling
            // on connect (its name lags -> would be null -> "bt:unknown", a missed
            // car-audio match). If the routed device is known and NOT BT, no key.
            currentBtRouteKey = {
                val routedType = sendspinManager.getRoutedDeviceType()
                val name = when {
                    routedType == null -> am.soleBluetoothSinkName()
                    isBluetoothSink(routedType) ->
                        sendspinManager.getRoutedDeviceProductName() ?: am.soleBluetoothSinkName()
                    else -> null
                }
                name?.let { "bt:$it" }
            },
            carAudioDevicesFlow = settingsRepository.carAudioBtDevices,
            recordKnownBtDevice = { settingsRepository.recordKnownBtDevice(it) },
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

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Seed-track generator caches: Last.fm track.getSimilar results +
            // reusable name->playable-URI resolution. Both name-based, no FK.
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `lastfm_similar_tracks` (
                    `source_key` TEXT NOT NULL,
                    `similar_artist` TEXT NOT NULL,
                    `similar_track` TEXT NOT NULL,
                    `match_score` REAL NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`source_key`, `similar_artist`, `similar_track`)
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `track_uri_cache` (
                    `name_key` TEXT NOT NULL,
                    `uri` TEXT NOT NULL,
                    `resolved_at` INTEGER NOT NULL,
                    PRIMARY KEY(`name_key`)
                )
                """.trimIndent()
            )
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

    /**
     * Adds `play_history.origin`, so the engine can tell a track the listener chose
     * from one a generated mix served.
     *
     * Existing rows keep the literal `unknown`, which is honest: their provenance was
     * never recorded and must NOT be assumed organic. The default is declared on the
     * column itself so it matches the entity's `defaultValue` exactly, which is what
     * Room's schema validation compares.
     */
    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `play_history` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'unknown'"
            )
        }
    }

    /**
     * Adds the `similar_tracks` cache. Applies to everyone, including upgrades from
     * a release, which reach it as a second hop after [MIGRATION_10_17].
     *
     * Nothing is dropped and nothing is rewritten: an empty cache simply means the
     * next mix build asks the server as it did before and fills it.
     */
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ma_similar_track_cache` (
                    `seed_uri` TEXT NOT NULL,
                    `tracks_json` TEXT NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`seed_uri`)
                )
                """.trimIndent()
            )
        }
    }

    /** Adds the bio cache for a device already on 16. Unreleased path, same reason. */
    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `musicbrainz_artist_tags` ADD COLUMN `bio` TEXT")
            db.execSQL("ALTER TABLE `musicbrainz_artist_tags` ADD COLUMN `bio_fetched_at` INTEGER")
        }
    }

    /**
     * The four steps from v11 to v15, restored.
     *
     * They were written as the MusicBrainz Smart Mix engine landed and then deleted
     * once [MIGRATION_10_17] replaced that whole run with a single hop. Deleting them
     * was safe for releases, which go from v10 to v17 in one step, but not for
     * dev-latest: CI published a debug APK for each of those five pushes on
     * 2026-07-30, so a tester who updated inside that window sits on 11, 12, 13 or 14
     * with no path forward and loses their listening history to the destructive
     * fallback. Each statement below is the original one. From v15 the existing
     * [MIGRATION_15_16] chain carries them the rest of the way.
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `musicbrainz_artist_tags` (
                    `artist_name` TEXT NOT NULL,
                    `mbid` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`artist_name`)
                )
                """.trimIndent()
            )
        }
    }

    /** Empties the tag cache, which v12 had filled with free-text tags. */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM `musicbrainz_artist_tags`")
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `artists` ADD COLUMN `mbid` TEXT")
        }
    }

    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `lastfm_similar_tracks`")
            db.execSQL("DROP TABLE IF EXISTS `track_uri_cache`")
        }
    }

    /**
     * Drops the two Last.fm caches for a device that already ran a v15 build.
     *
     * Only reachable from an unreleased version, so it never runs for anyone
     * upgrading from a release - they take [MIGRATION_10_17] in one step. It exists
     * so a tester already on 15 is not thrown at the destructive fallback, which
     * would cost them their listening history.
     */
    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS lastfm_artist_tags")
            db.execSQL("DROP TABLE IF EXISTS lastfm_similar_artists")
        }
    }

    /**
     * Everything the Music-Assistant-native Smart Mix engine needs, as ONE step.
     *
     * v10 is what the last release before v2.32.0 shipped, so v10 -> v17 is the path
     * every user coming from a release takes, followed by [MIGRATION_17_18] and
     * [MIGRATION_18_19]. Stepping through v11 to v16 instead would only make that
     * upgrade do pointless work, since one of those steps empties a table the
     * previous one had just created.
     *
     * This hop does NOT make v11 to v14 unreachable states. They never reached a
     * release, but CI did publish a dev-latest build for each of them, so they have
     * their own small migrations further down.
     */
    private val MIGRATION_10_17 = object : Migration(10, 17) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // MA similar-artists cache. Uri-keyed on both sides so results can be
            // reused directly for top_tracks without any name resolution.
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ma_similar_artists` (
                    `source_uri` TEXT NOT NULL,
                    `similar_uri` TEXT NOT NULL,
                    `similar_name` TEXT NOT NULL,
                    `similar_genres` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`source_uri`, `similar_uri`)
                )
                """.trimIndent()
            )
            // MusicBrainz genres, keyed by MBID where one is known and by name
            // otherwise (see MusicBrainzGenreResolver).
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `musicbrainz_artist_tags` (
                    `artist_name` TEXT NOT NULL,
                    `mbid` TEXT NOT NULL,
                    `tags` TEXT NOT NULL,
                    `fetched_at` INTEGER NOT NULL,
                    PRIMARY KEY(`artist_name`)
                )
                """.trimIndent()
            )
            // MusicBrainz id per artist, so genre lookups are exact rather than
            // by name (names are not unique across unrelated acts).
            database.execSQL("ALTER TABLE `artists` ADD COLUMN `mbid` TEXT")
            // Caches of the Last.fm track-similarity route the mix engine no
            // longer uses: ~2.7 MB of a 45 MB database on a real install.
            database.execSQL("DROP TABLE IF EXISTS `lastfm_similar_tracks`")
            database.execSQL("DROP TABLE IF EXISTS `track_uri_cache`")
            // The Last.fm caches. These two ARE released (created back in v4 and
            // v5), so dropping them is real work, not churn - but it belongs in
            // this one step rather than a second hop, because no version between
            // 10 and 16 was ever released. Anyone coming from a release makes a
            // single jump.
            database.execSQL("DROP TABLE IF EXISTS `lastfm_artist_tags`")
            database.execSQL("DROP TABLE IF EXISTS `lastfm_similar_artists`")
            database.execSQL("ALTER TABLE `musicbrainz_artist_tags` ADD COLUMN `bio` TEXT")
            database.execSQL("ALTER TABLE `musicbrainz_artist_tags` ADD COLUMN `bio_fetched_at` INTEGER")
        }
    }

    /**
     * Every migration the database ships with.
     *
     * Named rather than inlined so a test can walk it: the gap this list once had at
     * v11 to v14 was invisible precisely because nothing could read the set back.
     * `MigrationCoverageTest` asserts that every version from [OLDEST_SHIPPED_SCHEMA]
     * reaches the current one.
     */
    @VisibleForTesting
    internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_17, MIGRATION_11_12,
        MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
        MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19
    )

    /**
     * The oldest schema that ever reached `origin/dev`, so the oldest an installation
     * can be on. Established by walking the history of the two AppDatabase paths and
     * matching both spellings the version constant has had (`version = N` inline in
     * the annotation until mid-2026, `SCHEMA_VERSION = N` after).
     */
    @VisibleForTesting
    internal const val OLDEST_SHIPPED_SCHEMA = 2

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        resetReporter: net.asksakis.massdroidv2.data.util.DatabaseResetReporter
    ): AppDatabase {
        // Read the on-disk version BEFORE Room opens the file: if a migration is
        // missing, the destructive fallback rebuilds the database and the old
        // version - the one piece of information needed to write the missing
        // migration - is gone by the time onDestructiveMigration runs.
        val existingVersion = readDatabaseVersion(context)
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME
        ).addMigrations(*ALL_MIGRATIONS)
            // Kept so a missing migration cannot brick the app, but no longer
            // silent: see DatabaseResetReporter.
            .fallbackToDestructiveMigration()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    // db.version is still the OLD one here, so the target comes
                    // from the schema constant rather than the file.
                    resetReporter.report(
                        net.asksakis.massdroidv2.data.util.DatabaseResetInfo(
                            fromVersion = existingVersion,
                            toVersion = AppDatabase.SCHEMA_VERSION,
                            appVersion = appVersionName(context)
                        )
                    )
                }
            })
            .build()
    }

    /** On-disk schema version, or 0 when there is no database yet (a fresh install). */
    private fun readDatabaseVersion(context: Context): Int {
        val file = context.getDatabasePath(DATABASE_NAME)
        if (!file.exists()) return 0
        return try {
            SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READONLY
            ).use { it.version }
        } catch (_: Exception) {
            0
        }
    }

    private fun appVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    @Provides
    fun providePlayHistoryDao(db: AppDatabase): PlayHistoryDao = db.playHistoryDao()
}
