package net.asksakis.massdroidv2.data.genre

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.ServerGenre
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import org.junit.Test

/**
 * Pins the Library genre search: a query that names a genre keeps the whole tab in the
 * listener's sort, filtered to the items either source puts in the genre plus the name
 * matches. On 2026-10-06 the server mapped 8 library artists to Metal while the app's
 * MusicBrainz genres had 39, so neither source alone is enough.
 */
class LibraryGenreSearchTest {

    private val genreRepository = mockk<GenreRepository>()
    private val musicRepository = mockk<MusicRepository>()
    private val search = LibraryGenreSearch(genreRepository, musicRepository)

    private val metal = ServerGenre(33, "Metal")
    private val funk = ServerGenre(23, "Funk")

    /** One artist per letter, in the order a server sort returned them. */
    private val tab = listOf("Zeal", "Ministry", "Metallica", "Opeth", "Abba").map(::artist)

    @Test
    fun `the result keeps the server order and joins name, server and local matches`() = runTest {
        coEvery { genreRepository.searchArtistUris("metal") } returns listOf(uri("Opeth"))
        coEvery { musicRepository.getServerGenres() } returns listOf(metal, funk)
        val serverMetal = setOf(uri("Ministry"))

        val result = search.searchTab(
            query = "metal",
            fetchPage = { name, genreIds, limit, offset ->
                val all = when {
                    name != null -> tab.filter { it.name.contains(name, ignoreCase = true) }
                    genreIds != null -> {
                        assertThat(genreIds).containsExactly(metal.id)
                        tab.filter { it.uri in serverMetal }
                    }
                    else -> tab
                }
                all.drop(offset).take(limit)
            },
            uriOf = { it.uri },
            artistKeysOf = { listOfNotNull(MediaIdentity.artistKeyFromUri(it.uri)) },
            matchServerGenreArtists = false
        )

        assertThat(result?.map { it.name }).containsExactly("Ministry", "Metallica", "Opeth").inOrder()
    }

    @Test
    fun `a query that names no genre leaves the name search to the caller`() = runTest {
        coEvery { genreRepository.searchArtistUris("abba") } returns emptyList()
        coEvery { musicRepository.getServerGenres() } returns listOf(metal)

        val result = search.searchTab<Artist>(
            query = "abba",
            fetchPage = { _, _, _, _ -> error("the tab must not be read") },
            uriOf = { it.uri },
            artistKeysOf = { listOf(it.uri) },
            matchServerGenreArtists = false
        )

        assertThat(result).isNull()
    }

    @Test
    fun `a server without genres still filters by the local genres`() = runTest {
        coEvery { genreRepository.searchArtistUris("metal") } returns listOf(uri("Opeth"))
        coEvery { musicRepository.getServerGenres() } throws IllegalStateException("unknown command")

        val result = search.searchTab(
            query = "metal",
            fetchPage = { name, genreIds, limit, offset ->
                assertThat(genreIds).isNull()
                (if (name != null) emptyList() else tab).drop(offset).take(limit)
            },
            uriOf = { it.uri },
            artistKeysOf = { listOfNotNull(MediaIdentity.artistKeyFromUri(it.uri)) },
            matchServerGenreArtists = true
        )

        assertThat(result?.map { it.name }).containsExactly("Opeth")
    }

    @Test
    fun `a tab larger than the scan limit falls back to the name search`() = runTest {
        coEvery { genreRepository.searchArtistUris("metal") } returns listOf(uri("Opeth"))
        coEvery { musicRepository.getServerGenres() } returns emptyList()
        val huge = (1..6000).map { artist("Artist $it") }

        val result = search.searchTab(
            query = "metal",
            fetchPage = { _, _, limit, offset -> huge.drop(offset).take(limit) },
            uriOf = { it.uri },
            artistKeysOf = { listOf(it.uri) },
            matchServerGenreArtists = false
        )

        assertThat(result).isNull()
    }

    @Test
    fun `server genres match on the name, not on a word inside another genre`() = runTest {
        coEvery { genreRepository.searchArtistUris("funk metal") } returns emptyList()
        coEvery { musicRepository.getServerGenres() } returns listOf(metal, funk)

        assertThat(search.match("funk metal")).isNull()
        coEvery { genreRepository.searchArtistUris("metal") } returns emptyList()
        assertThat(search.match("metal")?.serverGenreIds).containsExactly(metal.id)
    }

    private fun uri(name: String) = "library://artist/${name.lowercase().replace(' ', '_')}"

    private fun artist(name: String) = Artist(
        itemId = name.lowercase().replace(' ', '_'),
        provider = "library",
        name = name,
        uri = uri(name)
    )
}
