package net.asksakis.massdroidv2.data.genre

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.database.ArtistEntity
import net.asksakis.massdroidv2.data.database.ArtistGenreEntity
import net.asksakis.massdroidv2.data.database.ArtistNeedingGenres
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the enricher's discovery phase: filling genres for the Smart Mix
 * candidates the genre gate cannot judge.
 *
 * Why this exists. The gate keeps a candidate it cannot describe, because
 * dropping the unknown makes whole scenes invisible. On a real library that
 * turned into the dominant source of off-genre tracks: two thirds of the
 * candidates passing the gate passed it unjudged, and an avant-jazz artist with
 * no genres anywhere opened a deep-house mix.
 *
 * The Smart Mix build used to warm these itself. It did not work: the seed half
 * resolved 0 of 20 on thirteen consecutive builds, each in 10 ms, because every
 * slot went to artists MusicBrainz had already answered "nothing" for - the
 * cache hides empty answers, so they resurface as gaps forever. The work now
 * lives here, outside any build, filtered by [MusicBrainzGenreResolver.stillWorthAsking].
 */
class DiscoveryGenreEnrichmentTest {

    private val dao = mockk<PlayHistoryDao>(relaxed = true)
    private val resolver = mockk<MusicBrainzGenreResolver>(relaxed = true)

    /** Pass-through: the production runner is Room's, which needs a device. */
    private val transactions = object : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
    }

    private val enricher = LibraryGenreEnricher(
        musicBrainzGenreResolver = resolver,
        dao = dao,
        settingsRepository = mockk(relaxed = true),
        musicRepository = mockk(relaxed = true),
        transactions = transactions,
    )

    private fun gap(name: String, uri: String, mbid: String? = null) =
        ArtistNeedingGenres(name = name, uri = uri, mbid = mbid)

    @Test
    fun `resolved genres are written to every uri the artist has`() = runTest {
        val deplume = gap("Alabaster Deplume", "deezer://artist/1092529")
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returns listOf(deplume)
        coEvery { resolver.stillWorthAsking(any()) } returns listOf(
            MusicBrainzGenreResolver.ArtistRef("Alabaster Deplume", null)
        )
        coEvery { resolver.resolve("Alabaster Deplume", null) } returns listOf("jazz", "spoken word")
        // The same artist is also known under a library uri.
        coEvery { dao.getArtistUrisByName("Alabaster Deplume") } returns listOf("library://artist/7")

        enricher.enrichDiscoveryArtists()

        val written = mutableListOf<ArtistGenreEntity>()
        coVerify { dao.insertArtistGenre(capture(written)) }
        assertThat(written.map { it.artistUri to it.genreName })
            .containsExactly(
                "deezer://artist/1092529" to "jazz",
                "library://artist/7" to "jazz",
                "deezer://artist/1092529" to "spoken word",
                "library://artist/7" to "spoken word",
            )
    }

    @Test
    fun `the artist row is created first, because most candidates were never played`() = runTest {
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returns
            listOf(gap("Nina Simone", "deezer://artist/42", mbid = "mbid-1"))
        coEvery { resolver.stillWorthAsking(any()) } returns listOf(
            MusicBrainzGenreResolver.ArtistRef("Nina Simone", "mbid-1")
        )
        coEvery { resolver.resolve("Nina Simone", "mbid-1") } returns listOf("soul")
        coEvery { dao.getArtistUrisByName(any()) } returns emptyList()

        enricher.enrichDiscoveryArtists()

        // artist_genres is a foreign key into artists, so without this the write
        // would be rejected for every candidate the user has never played.
        val artist = slot<ArtistEntity>()
        coVerify { dao.insertArtist(capture(artist)) }
        assertThat(artist.captured.uri).isEqualTo("deezer://artist/42")
        assertThat(artist.captured.name).isEqualTo("Nina Simone")
        assertThat(artist.captured.mbid).isEqualTo("mbid-1")
    }

    @Test
    fun `artists MusicBrainz already answered nothing for are not asked again`() = runTest {
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returns listOf(
            gap("Known Nothing", "deezer://artist/1"),
            gap("Never Asked", "deezer://artist/2"),
        )
        // Only the second is still outstanding.
        coEvery { resolver.stillWorthAsking(any()) } returns listOf(
            MusicBrainzGenreResolver.ArtistRef("Never Asked", null)
        )
        coEvery { resolver.resolve(any(), any()) } returns emptyList()

        enricher.enrichDiscoveryArtists()

        coVerify(exactly = 1) { resolver.resolve("Never Asked", null) }
        coVerify(exactly = 0) { resolver.resolve("Known Nothing", any()) }
    }

    @Test
    fun `a run drains the whole queue rather than stopping at a fixed cap`() = runTest {
        val many = (1..500).map { gap("Artist $it", "deezer://artist/$it") }
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returnsMany listOf(many, emptyList())
        coEvery { resolver.stillWorthAsking(any()) } returns
            many.map { MusicBrainzGenreResolver.ArtistRef(it.name, null) }
        coEvery { resolver.resolve(any(), any()) } returns emptyList()

        enricher.enrichDiscoveryArtists()

        // The old 300 cap meant an app left open all day stopped after twenty
        // minutes and the backlog only moved on websocket reconnects.
        coVerify(exactly = 500) { resolver.resolve(any(), any()) }
    }

    @Test
    fun `a resolver that never retires a name still terminates`() = runTest {
        // Worst case: the cache write fails, so stillWorthAsking keeps handing
        // back the same artists. Without the per-run attempted set this loops
        // forever and pins a thread on the MusicBrainz rate limiter.
        val stuck = listOf(gap("Never Cached", "deezer://artist/1"))
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returns stuck
        coEvery { resolver.stillWorthAsking(any()) } returns listOf(
            MusicBrainzGenreResolver.ArtistRef("Never Cached", null)
        )
        coEvery { resolver.resolve(any(), any()) } returns emptyList()

        enricher.enrichDiscoveryArtists()

        coVerify(exactly = 1) { resolver.resolve("Never Cached", null) }
    }

    @Test
    fun `artists that appear mid-run are picked up in the next round`() = runTest {
        val first = listOf(gap("First", "deezer://artist/1"))
        val second = listOf(gap("First", "deezer://artist/1"), gap("Arrived Later", "deezer://artist/2"))
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returnsMany listOf(first, second, emptyList())
        coEvery { resolver.stillWorthAsking(any()) } answers {
            firstArg<Collection<MusicBrainzGenreResolver.ArtistRef>>().toList()
        }
        coEvery { resolver.resolve(any(), any()) } returns emptyList()

        enricher.enrichDiscoveryArtists()

        coVerify(exactly = 1) { resolver.resolve("First", null) }
        coVerify(exactly = 1) { resolver.resolve("Arrived Later", null) }
    }

    @Test
    fun `nothing outstanding means no requests at all`() = runTest {
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returns listOf(gap("A", "deezer://artist/1"))
        coEvery { resolver.stillWorthAsking(any()) } returns emptyList()

        enricher.enrichDiscoveryArtists()

        coVerify(exactly = 0) { resolver.resolve(any(), any()) }
    }

    @Test
    fun `a failing artist does not abort the rest of the run`() = runTest {
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returns listOf(
            gap("Boom", "deezer://artist/1"),
            gap("Fine", "deezer://artist/2"),
        )
        coEvery { resolver.stillWorthAsking(any()) } returns listOf(
            MusicBrainzGenreResolver.ArtistRef("Boom", null),
            MusicBrainzGenreResolver.ArtistRef("Fine", null),
        )
        coEvery { resolver.resolve("Boom", null) } throws RuntimeException("network")
        coEvery { resolver.resolve("Fine", null) } returns listOf("house")
        coEvery { dao.getArtistUrisByName(any()) } returns emptyList()

        enricher.enrichDiscoveryArtists()

        coVerify(exactly = 1) { resolver.resolve("Fine", null) }
    }

    @Test
    fun `cancellation stops the sweep instead of burning the rest of the queue`() = runTest {
        // Swallowing CancellationException here was the real danger of an
        // unbounded loop: a cancelled sweep would walk all 1500 remaining
        // entries, fail instantly on each, mark every one `attempted`, and
        // retire the whole backlog without a single lookup.
        val many = (1..50).map { gap("Artist $it", "deezer://artist/$it") }
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returns many
        coEvery { resolver.stillWorthAsking(any()) } returns
            many.map { MusicBrainzGenreResolver.ArtistRef(it.name, null) }
        coEvery { resolver.resolve("Artist 1", null) } returns emptyList()
        coEvery { resolver.resolve("Artist 2", null) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking { enricher.enrichDiscoveryArtists() }
        }

        coVerify(exactly = 0) { resolver.resolve("Artist 3", null) }
    }

    @Test
    fun `the recording hint is passed through so namesakes can be told apart`() = runTest {
        // The whole point of sampleTrack: "Labelle" by name resolves to the American
        // soul group, but "Labelle" + "Playing at the End of the Universe" resolves
        // to the Reunion Island producer who actually recorded it.
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } returns listOf(
            ArtistNeedingGenres(
                name = "Labelle",
                uri = "deezer://artist/5285328",
                mbid = null,
                sampleTrack = "Playing at the End of the Universe",
            )
        )
        coEvery { resolver.stillWorthAsking(any()) } returns listOf(
            MusicBrainzGenreResolver.ArtistRef("Labelle", null)
        )
        coEvery { resolver.resolve(any(), any(), any()) } returns listOf("maloya")
        coEvery { dao.getArtistUrisByName(any()) } returns emptyList()

        enricher.enrichDiscoveryArtists()

        coVerify { resolver.resolve("Labelle", null, "Playing at the End of the Universe") }
    }

    @Test
    fun `a DB failure is survivable, not a crash`() = runTest {
        coEvery { dao.getDiscoveryArtistsWithoutGenres() } throws RuntimeException("db gone")

        enricher.enrichDiscoveryArtists()

        coVerify(exactly = 0) { resolver.resolve(any(), any()) }
    }
}
