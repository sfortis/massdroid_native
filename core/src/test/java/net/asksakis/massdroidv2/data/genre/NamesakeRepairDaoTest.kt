package net.asksakis.massdroidv2.data.genre

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import net.asksakis.massdroidv2.data.database.AppDatabase
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.ArtistGenreEntity
import net.asksakis.massdroidv2.data.database.GenreEntity
import net.asksakis.massdroidv2.data.database.MusicBrainzArtistTagsEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.PlayHistoryEntity
import net.asksakis.massdroidv2.data.database.TrackArtistEntity
import net.asksakis.massdroidv2.data.database.TrackEntity
import net.asksakis.massdroidv2.data.database.TrackGenreEntity
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.data.musicbrainz.GenreOutcome
import net.asksakis.massdroidv2.data.musicbrainz.GenreResolution
import net.asksakis.massdroidv2.data.musicbrainz.IdentityEvidence
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The namesake repair's SQL on a real in-memory Room database. The repair's decisions
 * are covered in [NamesakeGenreRepairTest] with a mocked DAO; this checks that the
 * queries select and delete the rows those decisions name. The rows replay the S25
 * of 2026-11-12: Aes Dana stored as a black metal band, Lauge credited with it on a
 * track, and Wild under its own id with a power metal namesake's genres.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NamesakeRepairDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PlayHistoryDao

    private val now = System.currentTimeMillis()
    private val wildMbid = "a4884e68-a658-4270-a609-8e7ea5c7bced"

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.playHistoryDao()
        artist("deezer://artist/5586", "Aes Dana")
        artist("library://artist/345", "Lauge", mbid = "9f72adf4-c318-439e-b6a5-64586c04bda2")
        artist("library://artist/9", "Wild", mbid = wildMbid)
        artist("deezer://artist/9", "Wild")
        artist("deezer://artist/1", "Unplayed")
        artist("deezer://artist/lauge", "Lauge")
        artist("deezer://artist/odd", MBID_SHAPED_NAME)
        track("deezer://track/snoflinga", "Snöflinga", "deezer://artist/5586", "library://artist/345")
        track("deezer://track/love", "Love", "library://artist/9")
        track("deezer://track/odd", "Odd", "deezer://artist/odd")
        play("deezer://track/odd", now - DAY)
        play("deezer://track/snoflinga", now - DAY)
        play("deezer://track/love", now - 200 * DAY)
        listOf("deezer://artist/5586", "library://artist/345", "deezer://artist/lauge").forEach { genre(it, "black metal") }
        genre("library://artist/9", "power metal")
        genre("deezer://artist/9", "power metal")
        trackGenre("deezer://track/snoflinga", "black metal")
        trackGenre("deezer://track/snoflinga", "ambient")
        cacheRow("aes dana", "4b1591fd-90b7-4694-957a-400de3e629da", "black metal,metal")
        cacheRow("unplayed", "11111111-1111-1111-1111-111111111111", "jazz")
        cacheRow("lauge", "9f72adf4-c318-439e-b6a5-64586c04bda2", "ambient,dark ambient,drone")
        cacheRow(wildMbid, "ad73761f-c66d-4149-899d-21f56d1969a1", "power metal,metal")
        // Played 200 days ago, outside the window.
        cacheRow("wild", "dddddddd-dddd-dddd-dddd-dddddddddddd", "rock")
        // Played yesterday, but its key has the shape of an id, so it is not name-keyed.
        cacheRow(MBID_SHAPED_NAME, MBID_SHAPED_NAME, "synth-pop")
        cacheRow("9f72adf4-c318-439e-b6a5-64586c04bda2", "9f72adf4-c318-439e-b6a5-64586c04bda2", "ambient")
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `only name-keyed rows of recently played artists are re-checked`() = runBlocking<Unit> {
        val rows = dao.getPlayedNameResolvedTags(now - 90 * DAY).map { it.artistName }
        assertThat(rows).containsExactly("aes dana", "lauge")
    }

    @Test
    fun `the played window decides which name-keyed rows are re-checked`() = runBlocking<Unit> {
        assertThat(dao.getPlayedNameResolvedTags(now - 90 * DAY).map { it.artistName }).doesNotContain("wild")
        assertThat(dao.getPlayedNameResolvedTags(now - 365 * DAY).map { it.artistName }).contains("wild")
    }

    @Test
    fun `a key shaped like an id is never taken for a name`() = runBlocking<Unit> {
        assertThat(dao.getPlayedNameResolvedTags(now - 90 * DAY).map { it.artistName }).doesNotContain(MBID_SHAPED_NAME)
    }

    @Test
    fun `id-keyed rows are re-checked when they name another artist than their id`() = runBlocking<Unit> {
        assertThat(dao.getMismatchedIdTags().map { it.artistName }).containsExactly(wildMbid)
    }

    @Test
    fun `artists are found by lowercase name and by id`() = runBlocking<Unit> {
        assertThat(dao.getArtistsByLowercaseName("wild").map { it.uri })
            .containsExactly("library://artist/9", "deezer://artist/9")
        assertThat(dao.getArtistsByMbid(wildMbid).map { it.uri }).containsExactly("library://artist/9")
    }

    @Test
    fun `the sample track, the co-credits and the track uris come from the credits`() = runBlocking<Unit> {
        val aesDana = listOf("deezer://artist/5586")
        assertThat(dao.getSampleTrackName(aesDana)).isEqualTo("Snöflinga")
        assertThat(dao.getCoCreditedArtists(aesDana).map { it.uri })
            .containsExactly("deezer://artist/5586", "library://artist/345")
        assertThat(dao.getTrackUrisOfArtists(aesDana)).containsExactly("deezer://track/snoflinga")
    }

    @Test
    fun `the deletes remove only the named genres of the named rows`() = runBlocking<Unit> {
        dao.deleteArtistGenresNamed("deezer://artist/5586", listOf("black metal"))
        dao.deleteTrackGenresOfArtists(listOf("deezer://artist/5586"), listOf("black metal"))

        assertThat(artistGenres("deezer://artist/5586")).isEmpty()
        assertThat(artistGenres("library://artist/345")).containsExactly("black metal")
        assertThat(trackGenres("deezer://track/snoflinga")).containsExactly("ambient")
    }

    /** M3: a genre left on another uri of the name was copied straight back by the backfill. */
    @Test
    fun `a repaired id-keyed artist does not get the namesake's genre back from its other uris`() = runBlocking<Unit> {
        val resolver = mockk<MusicBrainzGenreResolver>(relaxed = true)
        coEvery { resolver.recheck(any(), any(), any(), any()) } returns
            GenreResolution(emptyList(), GenreOutcome.NOT_FOUND)
        coEvery { resolver.recheck(wildMbid, any(), any(), any()) } returns
            GenreResolution(listOf("indie pop"), GenreOutcome.GENRES, wildMbid, IdentityEvidence.MBID)
        coEvery { resolver.cachedGenres(any(), any()) } returns null
        repairWith(resolver).runIfDue()
        dao.backfillArtistGenres()

        assertThat(artistGenres("library://artist/9")).containsExactly("indie pop")
        assertThat(artistGenres("deezer://artist/9")).containsExactly("indie pop")
    }

    /** Lauge is on the shared track as 345; its deezer uri is not, and the backfill links them. */
    @Test
    fun `a co-credited artist does not get the namesake's genre back from its other uris`() = runBlocking<Unit> {
        val resolver = mockk<MusicBrainzGenreResolver>(relaxed = true)
        coEvery { resolver.recheck(any(), any(), any(), any()) } returns GenreResolution(emptyList(), GenreOutcome.NOT_FOUND)
        coEvery { resolver.recheck("aes dana", any(), any(), any()) } returns GenreResolution(
            listOf("ambient", "psybient"), GenreOutcome.GENRES, "ed5cd4a2-0000-0000-0000-000000000000", IdentityEvidence.RECORDING
        )
        coEvery { resolver.cachedGenres("Lauge", any()) } returns listOf("ambient", "dark ambient", "drone")

        repairWith(resolver).runIfDue()
        dao.backfillArtistGenres()

        assertThat(artistGenres("library://artist/345")).doesNotContain("black metal")
        assertThat(artistGenres("deezer://artist/lauge")).doesNotContain("black metal")
        assertThat(trackGenres("deezer://track/snoflinga")).containsExactly("ambient", "psybient")
    }

    private fun repairWith(resolver: MusicBrainzGenreResolver): NamesakeGenreRepair {
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.musicBrainzIdentityRevision } returns flowOf(1)
        coEvery { settings.musicBrainzRepairCursor } returns flowOf("")
        coEvery { settings.musicBrainzRepairFailures } returns flowOf(0)
        val transactions = object : TransactionRunner {
            override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
        }
        return NamesakeGenreRepair(dao, resolver, settings, transactions, GenreSpellingResolver(dao))
    }

    private suspend fun artist(uri: String, name: String, mbid: String? = null) =
        dao.insertArtist(ArtistEntity(uri = uri, name = name, mbid = mbid))

    private suspend fun track(uri: String, name: String, vararg artistUris: String) {
        dao.insertTrack(TrackEntity(uri = uri, name = name))
        artistUris.forEach { dao.insertTrackArtist(TrackArtistEntity(trackUri = uri, artistUri = it)) }
    }

    private suspend fun play(trackUri: String, at: Long) {
        dao.insertPlay(PlayHistoryEntity(trackUri = trackUri, queueId = "q", playedAt = at))
    }

    private suspend fun genre(artistUri: String, name: String) {
        dao.insertGenre(GenreEntity(name = name))
        dao.insertArtistGenre(ArtistGenreEntity(artistUri = artistUri, genreName = name))
    }

    private suspend fun trackGenre(trackUri: String, name: String) {
        dao.insertGenre(GenreEntity(name = name))
        dao.insertTrackGenre(TrackGenreEntity(trackUri = trackUri, genreName = name))
    }

    private suspend fun cacheRow(key: String, mbid: String, tags: String) =
        dao.upsertMusicBrainzTags(MusicBrainzArtistTagsEntity(artistName = key, mbid = mbid, tags = tags, fetchedAt = now))

    private fun artistGenres(uri: String): List<String> =
        column("SELECT genre_name FROM artist_genres WHERE artist_uri = ?", uri)

    private fun trackGenres(uri: String): List<String> =
        column("SELECT genre_name FROM track_genres WHERE track_uri = ?", uri)

    private fun column(sql: String, arg: String): List<String> =
        db.openHelper.readableDatabase.query(sql, arrayOf(arg)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val MBID_SHAPED_NAME = "0e40a8b6-278e-4734-ab15-3912770c8bbb"
    }
}
