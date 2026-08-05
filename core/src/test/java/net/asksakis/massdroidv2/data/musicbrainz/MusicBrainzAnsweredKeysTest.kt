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
class MusicBrainzAnsweredKeysTest {

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

    @Test
    fun `an artist with genres counts as answered`() = runTest {
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(row("air", "electronic", 1))

        val answered = resolver.answeredKeys(listOf(MusicBrainzGenreResolver.ArtistRef("Air")))

        assertThat(answered).containsExactly("air")
    }

    @Test
    fun `an artist MusicBrainz does not know also counts as answered`() = runTest {
        // The whole point: this is a settled question, not outstanding work.
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(row("bam spacey", "", 1))

        val answered = resolver.answeredKeys(listOf(MusicBrainzGenreResolver.ArtistRef("Bam Spacey")))

        assertThat(answered).containsExactly("bam spacey")
    }

    @Test
    fun `a stale empty answer is asked again`() = runTest {
        // Empty answers expire sooner than real ones, so a newly catalogued
        // artist is picked up rather than written off forever.
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(row("bam spacey", "", 20))

        val answered = resolver.answeredKeys(listOf(MusicBrainzGenreResolver.ArtistRef("Bam Spacey")))

        assertThat(answered).isEmpty()
    }

    @Test
    fun `a genuinely unasked artist is not answered`() = runTest {
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns emptyList()

        val answered = resolver.answeredKeys(listOf(MusicBrainzGenreResolver.ArtistRef("Nobody")))

        assertThat(answered).isEmpty()
    }

    @Test
    fun `an id is the key when Music Assistant knew one`() = runTest {
        // Names are not identities; an MBID is, so it wins as the cache key.
        coEvery { dao.getMusicBrainzTagsFor(any()) } returns listOf(row("mbid-1", "rock", 1))

        val answered = resolver.answeredKeys(
            listOf(MusicBrainzGenreResolver.ArtistRef("Nirvana", mbid = "mbid-1"))
        )

        assertThat(answered).containsExactly("mbid-1")
    }

    @Test
    fun `nothing to ask about answers nothing`() = runTest {
        assertThat(resolver.answeredKeys(emptyList())).isEmpty()
    }
}
