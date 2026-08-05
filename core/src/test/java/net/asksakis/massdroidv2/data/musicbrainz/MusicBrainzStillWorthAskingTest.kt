package net.asksakis.massdroidv2.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.database.MusicBrainzArtistTagsEntity
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import org.junit.Test

/**
 * Pins which artists still count as outstanding genre work.
 *
 * "MusicBrainz does not know this artist" is an answer, not a gap. Treating it
 * as a gap made the enricher re-list the same artists on every start and then
 * report none enriched, which reads as a broken engine: measured on a real
 * library, all 586 of the reported gaps had already been answered, and the run
 * took 425 ms because every one of them was a cache read.
 */
class MusicBrainzStillWorthAskingTest {

    private val dao = mockk<PlayHistoryDao>(relaxed = true)
    private val resolver = MusicBrainzGenreResolver(
        dao = dao,
        okHttpClient = mockk(relaxed = true),
        json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        rateLimiter = MusicBrainzRateLimiter(),
    )

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun row(key: String, tags: String, ageDays: Long) =
        MusicBrainzArtistTagsEntity(
            artistName = key,
            mbid = "",
            tags = tags,
            fetchedAt = now - ageDays * day,
        )

    private val air = MusicBrainzGenreResolver.ArtistRef("Air")
    private val unknown = MusicBrainzGenreResolver.ArtistRef("Bam Spacey")

    @Test
    fun `an artist with genres is not worth asking again`() = runTest {
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(row("air", "electronic", 1))

        assertThat(resolver.stillWorthAsking(listOf(air))).isEmpty()
    }

    @Test
    fun `an artist MusicBrainz does not know is also not worth asking again`() = runTest {
        // The whole point: this is a settled question, not outstanding work.
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(row("bam spacey", "", 1))

        assertThat(resolver.stillWorthAsking(listOf(unknown))).isEmpty()
    }

    @Test
    fun `a stale empty answer is asked again`() = runTest {
        // Empty answers expire sooner than real ones, so a newly catalogued
        // artist is picked up rather than written off forever.
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(row("bam spacey", "", 20))

        assertThat(resolver.stillWorthAsking(listOf(unknown))).containsExactly(unknown)
    }

    @Test
    fun `a never-asked artist is worth asking`() = runTest {
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns emptyList()

        assertThat(resolver.stillWorthAsking(listOf(air))).containsExactly(air)
    }

    @Test
    fun `an id is the key when Music Assistant knew one`() = runTest {
        // Names are not identities; an MBID is, so it wins as the cache key.
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(row("mbid-1", "rock", 1))
        val nirvana = MusicBrainzGenreResolver.ArtistRef("Nirvana", mbid = "mbid-1")

        assertThat(resolver.stillWorthAsking(listOf(nirvana))).isEmpty()
    }

    @Test
    fun `an unreadable cache asks again rather than reporting nothing to do`() = runTest {
        coEvery { dao.getMusicBrainzTagsFor(any()) } throws RuntimeException("db gone")

        assertThat(resolver.stillWorthAsking(listOf(air))).containsExactly(air)
    }

    @Test
    fun `nothing to ask about is nothing to ask about`() = runTest {
        assertThat(resolver.stillWorthAsking(emptyList())).isEmpty()
    }
}
