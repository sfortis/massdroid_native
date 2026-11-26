package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.database.ArtistNameGenreRow
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import org.junit.Test

/**
 * Pins the genre lookup by artist name. A Smart Mix candidate carries a provider
 * uri with no row of its own while the library uri of the same artist holds the
 * genres, so MGMT and The Shins were treated as genre-less.
 */
class ArtistGenresByNameTest {

    private val dao = mockk<PlayHistoryDao>(relaxed = true)

    private val repo = PlayHistoryRepositoryImpl(
        dao = dao,
        json = Json { ignoreUnknownKeys = true },
        appDatabase = mockk(relaxed = true),
        genreSpellings = mockk(relaxed = true),
    )

    @Test
    fun `genres stored under any uri of the name are found, keyed by the name`() = runTest {
        // One row per uri the artist is stored under.
        coEvery { dao.getGenresForArtistNames(any()) } returns listOf(
            ArtistNameGenreRow("MGMT", "indie rock"),
            ArtistNameGenreRow("MGMT", "psychedelic pop"),
            ArtistNameGenreRow("MGMT", "indie rock"),
            ArtistNameGenreRow("The Shins", "indie pop"),
        )

        val map = repo.getArtistGenreMapByName(listOf("MGMT", "The Shins", "Nobody Known"))

        assertThat(map["MGMT"]).containsExactly("indie rock", "psychedelic pop")
        assertThat(map["The Shins"]).containsExactly("indie pop")
        assertThat(map).doesNotContainKey("Nobody Known")
    }

    @Test
    fun `Greek names that differ only in a number stay apart`() = runTest {
        // A genreKey of either name keeps only the digit, which joined them.
        coEvery { dao.getGenresForArtistNames(any()) } returns listOf(
            ArtistNameGenreRow("Τρύπες", "greek rock"),
            ArtistNameGenreRow("Ξ 2", "laiko"),
        )

        val map = repo.getArtistGenreMapByName(listOf("Τρύπες", "Τρύπες 2"))

        assertThat(map["Τρύπες"]).containsExactly("greek rock")
        assertThat(map).doesNotContainKey("Τρύπες 2")
    }

    @Test
    fun `a row for a name that was not asked for is ignored`() = runTest {
        coEvery { dao.getGenresForArtistNames(any()) } returns listOf(ArtistNameGenreRow("Other", "jazz"))

        assertThat(repo.getArtistGenreMapByName(listOf("MGMT"))).isEmpty()
    }

    @Test
    fun `the lookup is one query per batch, not one per name`() = runTest {
        coEvery { dao.getGenresForArtistNames(any()) } returns emptyList()

        repo.getArtistGenreMapByName((1..20).map { "Artist $it" })

        coVerify(exactly = 1) { dao.getGenresForArtistNames(any()) }
    }

    @Test
    fun `no names, no query`() = runTest {
        assertThat(repo.getArtistGenreMapByName(listOf("", " "))).isEmpty()
        coVerify(exactly = 0) { dao.getGenresForArtistNames(any()) }
    }
}
