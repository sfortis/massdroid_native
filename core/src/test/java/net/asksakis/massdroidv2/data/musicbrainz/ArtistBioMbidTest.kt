package net.asksakis.massdroidv2.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import org.junit.Test

/**
 * The bio lookup puts the id Music Assistant reports into a musicbrainz.org URL path,
 * so only a value in the UUID form may get there; anything else falls back to the
 * name search, as when no id is known.
 */
class ArtistBioMbidTest {

    private val dao = mockk<PlayHistoryDao>(relaxed = true)
    private val genreResolver = mockk<MusicBrainzGenreResolver>(relaxed = true)
    private val wikipedia = mockk<WikipediaSummaries>(relaxed = true)
    private val resolver = ArtistBioResolver(
        dao, Json { ignoreUnknownKeys = true }, mockk(relaxed = true), genreResolver, wikipedia
    )

    @Test
    fun `an id that is not a MusicBrainz id is not put into a URL`() = runBlocking<Unit> {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        coEvery { genreResolver.findMbidForBio("Wild") } returns null
        val urls = mutableListOf<String>()
        val url = slot<String>()
        every { wikipedia.get(capture(url)) } answers { urls += url.captured; null }

        resolver.resolve("Wild", mbid = "../recording/x")

        assertThat(urls).isEmpty()
        coVerify { genreResolver.findMbidForBio("Wild") }
    }

    @Test
    fun `a MusicBrainz id is looked up directly`() = runBlocking<Unit> {
        coEvery { dao.getMusicBrainzTags(any()) } returns null
        val urls = mutableListOf<String>()
        val url = slot<String>()
        every { wikipedia.get(capture(url)) } answers { urls += url.captured; null }

        resolver.resolve("Wild", mbid = "a4884e68-a658-4270-a609-8e7ea5c7bced")

        assertThat(urls.single()).contains("/artist/a4884e68-a658-4270-a609-8e7ea5c7bced?")
        coVerify(exactly = 0) { genreResolver.findMbidForBio(any()) }
    }
}
