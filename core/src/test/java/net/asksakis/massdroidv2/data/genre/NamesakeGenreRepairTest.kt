package net.asksakis.massdroidv2.data.genre

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.MusicBrainzArtistTagsEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.SampleTrack
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.data.musicbrainz.GenreOutcome
import net.asksakis.massdroidv2.data.musicbrainz.GenreResolution
import net.asksakis.massdroidv2.data.musicbrainz.IdentityEvidence
import net.asksakis.massdroidv2.data.musicbrainz.Lookup
import io.mockk.coVerifyOrder
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * The one-off repair of genres a namesake brought. The data replays the S25 of
 * 2026-11-12: the ambient project Aes Dana was stored as a French black metal band,
 * and Lauge, credited with it on a track, inherited the black metal. The first run
 * there also emptied Aqua and Birdy, whose re-check identified nobody; those cases
 * must change nothing.
 */
class NamesakeGenreRepairTest {

    private val dao = mockk<PlayHistoryDao>(relaxed = true)
    private val resolver = mockk<MusicBrainzGenreResolver>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private var inTransaction = false
    private val transactions = object : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R {
            inTransaction = true
            return block().also { inTransaction = false }
        }
    }
    private val repair = NamesakeGenreRepair(dao, resolver, settings, transactions, GenreSpellingResolver(dao))

    private val aesDanaRow = MusicBrainzArtistTagsEntity(
        artistName = "aes dana", mbid = "4b1591fd-90b7-4694-957a-400de3e629da",
        tags = "black metal,metal", fetchedAt = 0L
    )
    private val aesDana = ArtistEntity(uri = "deezer://artist/5586", name = "Aes Dana")
    private val lauge = ArtistEntity(uri = "library://artist/345", name = "Lauge", mbid = "9f72adf4-c318-439e-b6a5-64586c04bda2")
    private val laugeOnDeezer = ArtistEntity(uri = "deezer://artist/345", name = "Lauge")
    private val maOnlyGuest = ArtistEntity(uri = "deezer://artist/77", name = "Guest")
    private val ambientProject = "ed5cd4a2-0000-0000-0000-000000000000"

    private fun due(cursor: String = "", revision: Int = 1, failures: Int = 0, failedRow: String = "aes dana") {
        coEvery { settings.musicBrainzIdentityRevision } returns flowOf(revision)
        coEvery { settings.musicBrainzRepairCursor } returns flowOf(cursor)
        coEvery { settings.musicBrainzRepairFailures } returns flowOf(failures)
        coEvery { settings.musicBrainzRepairFailedRow } returns flowOf(failedRow)
        coEvery { dao.getPlayedNameResolvedTags(any()) } returns listOf(aesDanaRow)
        coEvery { dao.getMismatchedIdTags() } returns emptyList()
        coEvery { dao.getArtistsByLowercaseName("aes dana") } returns listOf(aesDana)
        coEvery { dao.getSampleTracks(any(), any()) } returns samples("Snöflinga")
        coEvery { dao.getCoCreditedArtists(any()) } returns listOf(aesDana, lauge, maOnlyGuest)
        coEvery { dao.getTrackUrisOfArtists(any()) } returns listOf("deezer://track/1")
        coEvery { resolver.cachedGenres("Lauge", lauge.mbid) } returns listOf("ambient", "dark ambient", "drone")
        coEvery { resolver.cachedGenres("Guest", null) } returns null
        coEvery { dao.getArtistsByLowercaseName("lauge") } returns listOf(lauge, laugeOnDeezer)
    }

    /** Sample tracks all credited to the Aes Dana uri. */
    private fun samples(vararg names: String) = names.map { SampleTrack(aesDana.uri, it) }

    /** The recheck answers [resolution]; every recording names its MBID when it was found by one. */
    private fun answering(resolution: GenreResolution) {
        coEvery { resolver.recheck(any(), any(), any(), any()) } returns resolution
        val byRecording: Lookup<String> = when {
            resolution.outcome == GenreOutcome.UNAVAILABLE -> Lookup.Unavailable
            resolution.evidence == IdentityEvidence.RECORDING && resolution.mbid != null -> Lookup.Found(resolution.mbid!!)
            else -> Lookup.Missing
        }
        coEvery { resolver.identifyByRecording(any(), any()) } returns byRecording
    }

    @Test
    fun `a namesake's genres are taken back from the artist, its tracks and its co-credits`() = runTest {
        due()
        answering(GenreResolution(listOf("psybient", "ambient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.RECORDING))

        repair.runIfDue()

        coVerify { resolver.identifyByRecording("Aes Dana", "Snöflinga") }
        coVerify { resolver.recheck("aes dana", "Aes Dana", ambientProject, null) }
        coVerify { dao.deleteTrackGenresOfArtists(listOf(aesDana.uri), listOf("black metal", "metal")) }
        coVerify { dao.deleteArtistGenresNamed(aesDana.uri, listOf("black metal", "metal")) }
        coVerify { dao.deleteArtistGenresNamed(lauge.uri, listOf("black metal", "metal")) }
        coVerify { dao.insertArtistGenre(match { it.artistUri == aesDana.uri && it.genreName == "psybient" }) }
        coVerify { dao.insertTrackGenre(match { it.trackUri == "deezer://track/1" && it.genreName == "psybient" }) }
        coVerify { settings.setMusicBrainzIdentityRevision(2) }
        coVerify { settings.setMusicBrainzRepairCursor("") }
    }

    /** The cache row and the genres are one write: a crash between them lost the repair. */
    @Test
    fun `the corrected identity is stored inside the same transaction as the genres`() = runTest {
        due()
        answering(GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.RECORDING))
        var storedInTransaction = false
        coEvery { resolver.storeIdentity(any(), any(), any()) } answers { storedInTransaction = inTransaction }

        repair.runIfDue()

        coVerify { resolver.storeIdentity("aes dana", ambientProject, listOf("psybient")) }
        assertThat(storedInTransaction).isTrue()
    }

    /** Aqua and Birdy on the S25: identified by nobody now, which says nothing against the old answer. */
    @Test
    fun `a re-check that identifies nobody changes neither the genres nor the cache`() = runTest {
        due()
        answering(GenreResolution(emptyList(), GenreOutcome.NOT_FOUND))

        repair.runIfDue()

        coVerify(exactly = 0) { dao.deleteTrackGenresOfArtists(any(), any()) }
        coVerify(exactly = 0) { dao.deleteArtistGenresNamed(any(), any()) }
        coVerify(exactly = 0) { resolver.storeIdentity(any(), any(), any()) }
        coVerify { settings.setMusicBrainzIdentityRevision(2) }
    }

    @Test
    fun `the same artist answering again changes nothing`() = runTest {
        due()
        answering(GenreResolution(listOf("black metal", "metal"), GenreOutcome.GENRES, aesDanaRow.mbid, IdentityEvidence.RECORDING))

        repair.runIfDue()

        coVerify(exactly = 0) { dao.deleteArtistGenresNamed(any(), any()) }
        coVerify(exactly = 0) { resolver.storeIdentity(any(), any(), any()) }
    }

    /** Its genres came from Music Assistant then, which the namesake did not bring. */
    @Test
    fun `a co-credited artist with no cached answer of its own is not touched`() = runTest {
        due()
        answering(GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.RECORDING))

        repair.runIfDue()

        coVerify(exactly = 0) { dao.deleteArtistGenresNamed(maOnlyGuest.uri, any()) }
    }

    @Test
    fun `tracks keep a wrong genre that a co-credited artist carries itself`() = runTest {
        due()
        coEvery { resolver.cachedGenres("Lauge", lauge.mbid) } returns listOf("ambient", "metal")
        answering(GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.RECORDING))

        repair.runIfDue()

        coVerify { dao.deleteTrackGenresOfArtists(listOf(aesDana.uri), listOf("black metal")) }
        coVerify { dao.deleteArtistGenresNamed(lauge.uri, listOf("black metal")) }
    }

    /** backfillArtistGenres would copy a genre back from another uri of the same name. */
    @Test
    fun `an id-keyed row also repairs the other uris of the artist's name`() = runTest {
        due()
        val wild = ArtistEntity(uri = "library://artist/9", name = "Wild", mbid = "a4884e68-a658-4270-a609-8e7ea5c7bced")
        val wildOnDeezer = ArtistEntity(uri = "deezer://artist/9", name = "Wild")
        coEvery { dao.getPlayedNameResolvedTags(any()) } returns emptyList()
        coEvery { dao.getMismatchedIdTags() } returns listOf(
            MusicBrainzArtistTagsEntity(wild.mbid!!, "ad73761f-c66d-4149-899d-21f56d1969a1", "power metal,metal", 0L)
        )
        coEvery { dao.getArtistsByMbid(wild.mbid!!) } returns listOf(wild)
        coEvery { dao.getArtistsByLowercaseName("wild") } returns listOf(wild, wildOnDeezer)
        coEvery { dao.getCoCreditedArtists(any()) } returns emptyList()
        answering(GenreResolution(listOf("indie pop"), GenreOutcome.GENRES, wild.mbid, IdentityEvidence.MBID))

        repair.runIfDue()

        coVerify { dao.deleteArtistGenresNamed(wildOnDeezer.uri, listOf("power metal", "metal")) }
        coVerify { resolver.recheck(wild.mbid!!, "Wild", wild.mbid, null) }
    }

    /** A request that never completed is no answer: nothing is removed and the run resumes later. */
    @Test
    fun `MusicBrainz not answering pauses the repair without touching any genre`() = runTest {
        due()
        answering(GenreResolution(emptyList(), GenreOutcome.UNAVAILABLE))

        repair.runIfDue()

        coVerify(exactly = 0) { dao.deleteTrackGenresOfArtists(any(), any()) }
        coVerify(exactly = 0) { settings.setMusicBrainzIdentityRevision(any()) }
        coVerify(exactly = 0) { settings.setMusicBrainzRepairCursor(any()) }
    }

    @Test
    fun `a resumed run skips the artists it already finished`() = runTest {
        due(cursor = "aes dana")

        repair.runIfDue()

        coVerify(exactly = 0) { resolver.recheck(any(), any(), any(), any()) }
        coVerify { settings.setMusicBrainzIdentityRevision(2) }
    }

    @Test
    fun `a finished repair does not run again`() = runTest {
        due(revision = 2)

        repair.runIfDue()

        coVerify(exactly = 0) { dao.getPlayedNameResolvedTags(any()) }
    }

    /** Storing revision 2 over a failed revision 1 would skip the recording re-check for good. */
    @Test
    fun `the repair waits for the recording re-check of revision 1`() = runTest {
        due(revision = 0)

        repair.runIfDue()

        coVerify(exactly = 0) { dao.getPlayedNameResolvedTags(any()) }
        coVerify(exactly = 0) { settings.setMusicBrainzIdentityRevision(any()) }
    }

    @Test
    fun `only a different artist answering takes genres back`() {
        val notFound = GenreResolution(emptyList(), GenreOutcome.NOT_FOUND)
        assertThat(misattributedGenres("4b1591fd", listOf("black metal", "metal"), notFound)).isEmpty()
        val sameArtist = GenreResolution(listOf("metal"), GenreOutcome.GENRES, "4b1591fd", IdentityEvidence.RECORDING)
        assertThat(misattributedGenres("4b1591fd", listOf("black metal", "metal"), sameArtist)).isEmpty()
    }

    @Test
    fun `genres both artists share are kept, compared by genre key`() {
        val answer = GenreResolution(listOf("Post-Rock"), GenreOutcome.GENRES, "new", IdentityEvidence.RECORDING)
        assertThat(misattributedGenres("old", listOf("post rock", "metal"), answer)).containsExactly("metal")
    }

    @Test
    fun `no answer concludes nothing`() {
        val answer = GenreResolution(emptyList(), GenreOutcome.UNAVAILABLE)
        assertThat(misattributedGenres("old", listOf("metal"), answer)).isNull()
    }

    /** Labelle: the recording search missed, and the name picks the US group LaBelle. */
    @Test
    fun `a name-only re-identification corrects nothing`() = runTest {
        due()
        answering(GenreResolution(listOf("disco", "soul"), GenreOutcome.GENRES, "us-labelle", IdentityEvidence.NAME))

        repair.runIfDue()

        coVerify(exactly = 0) { resolver.storeIdentity(any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteArtistGenresNamed(any(), any()) }
        coVerify(exactly = 0) { dao.deleteTrackGenresOfArtists(any(), any()) }
    }

    /** backfillArtistGenres would copy the genre back to 345 from the uri not on the track. */
    @Test
    fun `a co-credited artist loses the genre on every uri of its name`() = runTest {
        due()
        answering(GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.RECORDING))

        repair.runIfDue()

        coVerify { dao.deleteArtistGenresNamed(laugeOnDeezer.uri, listOf("black metal", "metal")) }
    }

    /** Announced inside the transaction, a fetch could refill the session cache from the old row. */
    @Test
    fun `the identity change is announced after the transaction`() = runTest {
        due()
        answering(GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.RECORDING))
        var announcedInTransaction: Boolean? = null
        io.mockk.every { resolver.announceIdentityChange(any()) } answers { announcedInTransaction = inTransaction }

        repair.runIfDue()

        assertThat(announcedInTransaction).isFalse()
        coVerifyOrder {
            resolver.storeIdentity("aes dana", ambientProject, any())
            resolver.announceIdentityChange("aes dana")
        }
    }

    @Test
    fun `a row without an answer pauses the run and counts the failure`() = runTest {
        due(failures = 1)
        answering(GenreResolution(emptyList(), GenreOutcome.UNAVAILABLE))

        repair.runIfDue()

        coVerify { settings.setMusicBrainzRepairFailures(2) }
        coVerify(exactly = 0) { settings.setMusicBrainzRepairCursor(any()) }
        coVerify(exactly = 0) { settings.setMusicBrainzIdentityRevision(any()) }
    }

    /** A row that always fails would otherwise keep the repair from ever finishing. */
    @Test
    fun `a row that failed three runs in a row is left unchanged and passed`() = runTest {
        due(failures = 2)
        coEvery { resolver.identifyByRecording(any(), any()) } throws IllegalStateException("boom")

        repair.runIfDue()

        coVerify(exactly = 0) { dao.deleteArtistGenresNamed(any(), any()) }
        coVerify { settings.setMusicBrainzRepairCursor("aes dana") }
        coVerify { settings.setMusicBrainzIdentityRevision(2) }
    }

    @Test
    fun `only an id or a recording overrules the cached answer`() {
        fun by(evidence: IdentityEvidence?) = GenreResolution(listOf("x"), GenreOutcome.GENRES, "new", evidence)
        assertThat(correctedIdentity("old", by(IdentityEvidence.MBID))).isEqualTo("new")
        assertThat(correctedIdentity("old", by(IdentityEvidence.RECORDING))).isEqualTo("new")
        assertThat(correctedIdentity("old", by(IdentityEvidence.NAME))).isNull()
        assertThat(correctedIdentity("old", by(null))).isNull()
    }

    /** A count taken on another row, or during an outage that row did not cause, starts over. */
    @Test
    fun `a failure count taken on another row starts over at one`() = runTest {
        due(failures = 2, failedRow = "a row that dropped out")
        answering(GenreResolution(emptyList(), GenreOutcome.UNAVAILABLE))

        repair.runIfDue()

        coVerify { settings.setMusicBrainzRepairFailures(1) }
        coVerify { settings.setMusicBrainzRepairFailedRow("aes dana") }
        coVerify(exactly = 0) { settings.setMusicBrainzRepairCursor(any()) }
    }

    /** SQLite's lower() folds only ASCII, so the name lookup can miss the artist's own uri. */
    @Test
    fun `a co-credited artist with a non-ASCII name still loses the genre on its own uri`() = runTest {
        due()
        val olafur = ArtistEntity(uri = "deezer://artist/olafur", name = "Ólafur Arnalds")
        coEvery { dao.getCoCreditedArtists(any()) } returns listOf(aesDana, olafur)
        coEvery { resolver.cachedGenres("Ólafur Arnalds", null) } returns listOf("neoclassical")
        coEvery { dao.getArtistsByLowercaseName("ólafur arnalds") } returns emptyList()
        answering(GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.RECORDING))

        repair.runIfDue()

        coVerify { dao.deleteArtistGenresNamed(olafur.uri, listOf("black metal", "metal")) }
    }

    /** Three tracks of the name, all credited to the same new artist: corrected. */
    @Test
    fun `recordings that agree correct the row`() = runTest {
        due()
        coEvery { dao.getSampleTracks(any(), any()) } returns samples("Anamnesis", "Low Tide", "Snöflinga")
        coEvery { resolver.identifyByRecording(any(), any()) } returns Lookup.Found(ambientProject)
        coEvery { resolver.recheck(any(), any(), any(), any()) } returns
            GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.MBID)

        repair.runIfDue()

        coVerify(exactly = 3) { resolver.identifyByRecording("Aes Dana", any()) }
        coVerify { resolver.storeIdentity("aes dana", ambientProject, listOf("psybient")) }
    }

    /**
     * Cannons on the S25: the history held a US and an Israeli band of that name, and one
     * track named the Israeli one. Recordings naming different artists correct nothing.
     */
    @Test
    fun `recordings that name different artists leave the row unchanged`() = runTest {
        due()
        coEvery { dao.getArtistsByLowercaseName("aes dana") } returns listOf(aesDana)
        coEvery { dao.getSampleTracks(any(), any()) } returns samples("Invisible Cities", "Fire for You")
        coEvery { resolver.identifyByRecording(any(), "Invisible Cities") } returns Lookup.Found("israeli-band")
        coEvery { resolver.identifyByRecording(any(), "Fire for You") } returns Lookup.Found("us-band")
        coEvery { resolver.recheck(any(), any(), any(), any()) } returns
            GenreResolution(emptyList(), GenreOutcome.NO_GENRES, "israeli-band", IdentityEvidence.RECORDING)

        repair.runIfDue()

        coVerify(exactly = 0) { resolver.storeIdentity(any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteArtistGenresNamed(any(), any()) }
        coVerify(exactly = 0) { dao.deleteTrackGenresOfArtists(any(), any()) }
    }

    @Test
    fun `a recording without an answer pauses the row instead of deciding on the others`() = runTest {
        due()
        coEvery { dao.getSampleTracks(any(), any()) } returns samples("Anamnesis", "Snöflinga")
        coEvery { resolver.identifyByRecording(any(), "Anamnesis") } returns Lookup.Found(ambientProject)
        coEvery { resolver.identifyByRecording(any(), "Snöflinga") } returns Lookup.Unavailable

        repair.runIfDue()

        coVerify(exactly = 0) { resolver.storeIdentity(any(), any(), any()) }
        coVerify(exactly = 0) { settings.setMusicBrainzRepairCursor(any()) }
    }

    /** M1: two uris of one name; the second uri's recording was not found, so it may be another artist. */
    @Test
    fun `a uri whose recordings were not found leaves the row unchanged`() = runTest {
        due()
        val usBand = ArtistEntity(uri = "library://artist/cannons", name = "Aes Dana")
        coEvery { dao.getArtistsByLowercaseName("aes dana") } returns listOf(aesDana, usBand)
        coEvery { dao.getSampleTracks(any(), any()) } returns
            listOf(SampleTrack(aesDana.uri, "Anamnesis"), SampleTrack(usBand.uri, "Fire for You"))
        coEvery { resolver.identifyByRecording(any(), "Anamnesis") } returns Lookup.Found(ambientProject)
        coEvery { resolver.identifyByRecording(any(), "Fire for You") } returns Lookup.Missing
        coEvery { resolver.recheck(any(), any(), any(), any()) } returns
            GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.MBID)

        repair.runIfDue()

        coVerify(exactly = 0) { resolver.storeIdentity(any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteArtistGenresNamed(any(), any()) }
        coVerify { settings.setMusicBrainzRepairCursor("aes dana") }
    }

    @Test
    fun `every uri naming the same artist by a recording corrects the row`() = runTest {
        due()
        val onLibrary = ArtistEntity(uri = "library://artist/5586", name = "Aes Dana")
        coEvery { dao.getArtistsByLowercaseName("aes dana") } returns listOf(aesDana, onLibrary)
        coEvery { dao.getSampleTracks(any(), any()) } returns
            listOf(SampleTrack(aesDana.uri, "Anamnesis"), SampleTrack(onLibrary.uri, "Snöflinga"))
        coEvery { resolver.identifyByRecording(any(), any()) } returns Lookup.Found(ambientProject)
        coEvery { resolver.recheck(any(), any(), any(), any()) } returns
            GenreResolution(listOf("psybient"), GenreOutcome.GENRES, ambientProject, IdentityEvidence.MBID)

        repair.runIfDue()

        coVerify { resolver.storeIdentity("aes dana", ambientProject, listOf("psybient")) }
    }

    /** L2: two artists already named decide the row; a later track is not asked and cannot pause it. */
    @Test
    fun `recordings stop at the second artist and a later failure does not pause the row`() = runTest {
        due()
        coEvery { dao.getSampleTracks(any(), any()) } returns samples("Fire for You", "Invisible Cities", "Zebra")
        coEvery { resolver.identifyByRecording(any(), "Fire for You") } returns Lookup.Found("us-band")
        coEvery { resolver.identifyByRecording(any(), "Invisible Cities") } returns Lookup.Found("israeli-band")
        coEvery { resolver.identifyByRecording(any(), "Zebra") } returns Lookup.Unavailable

        repair.runIfDue()

        coVerify(exactly = 0) { resolver.identifyByRecording(any(), "Zebra") }
        coVerify(exactly = 0) { resolver.storeIdentity(any(), any(), any()) }
        coVerify { settings.setMusicBrainzRepairCursor("aes dana") }
    }
}
