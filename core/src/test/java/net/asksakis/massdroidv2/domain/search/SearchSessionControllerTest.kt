package net.asksakis.massdroidv2.domain.search

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * The rules behind the search screen, all of which reached a reporter before they could be
 * tested: the search lived in the `:app` module, which has no unit tests.
 *
 * Reported on 2026-09-28 against v2.36.0 (#76): a progress line that kept running with no
 * search behind it, and results that answered an earlier query sitting under new text with
 * only an error message to explain the mismatch.
 */
class SearchSessionControllerTest {

    private companion object {
        /** Long enough that the search is still in flight while the test acts on it. */
        const val SLOW_SERVER_MS = 10_000L
    }

    private fun album(name: String) = Album(
        itemId = name,
        provider = "library",
        name = name,
        uri = "library://album/$name"
    )

    private fun hit(name: String) = SearchResult(albums = listOf(album(name)))

    private class Fixture(val music: MusicRepository, val settings: SettingsRepository)

    private fun fixture(): Fixture {
        val music = mockk<MusicRepository>()
        val settings = mockk<SettingsRepository>(relaxed = true)
        return Fixture(music, settings)
    }

    private fun TestScope.controllerOver(f: Fixture) =
        SearchSessionController(f.music, f.settings, this)

    // --- when a query is worth sending ---

    @Test
    fun `typing a single character searches nothing`() = runTest {
        val f = fixture()
        val controller = controllerOver(f)

        controller.updateQuery("a")
        advanceUntilIdle()

        assertThat(controller.session.value.query).isEqualTo("a")
        coVerify(exactly = 0) { f.music.search(any(), any(), any()) }
    }

    @Test
    fun `submitting a single character searches it`() = runTest {
        val f = fixture()
        coEvery { f.music.search("a", any(), any()) } returns hit("A")
        val controller = controllerOver(f)

        controller.updateQuery("a")
        controller.submitQuery()
        advanceUntilIdle()

        assertThat(controller.session.value.results.albums).hasSize(1)
        coVerify(exactly = 1) { f.music.search("a", any(), any()) }
    }

    @Test
    fun `typing waits out the debounce before it asks the server`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } returns hit("A")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(500)
        coVerify(exactly = 0) { f.music.search(any(), any(), any()) }

        advanceUntilIdle()
        coVerify(exactly = 1) { f.music.search("eels", any(), any()) }
    }

    @Test
    fun `a keystroke during the debounce replaces the query that would have been sent`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } returns hit("A")
        val controller = controllerOver(f)

        controller.updateQuery("eel")
        advanceTimeBy(100)
        controller.updateQuery("eels")
        advanceUntilIdle()

        coVerify(exactly = 0) { f.music.search("eel", any(), any()) }
        coVerify(exactly = 1) { f.music.search("eels", any(), any()) }
    }

    // --- the progress line ---

    @Test
    fun `clearing the field stops the progress line`() = runTest {
        val f = fixture()
        // The server has to be still thinking when the field is emptied, otherwise there
        // is no running search for the clear to cancel and the test proves nothing.
        coEvery { f.music.search(any(), any(), any()) } coAnswers {
            delay(SLOW_SERVER_MS)
            hit("A")
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(700)
        assertThat(controller.session.value.isSearching).isTrue()

        // Emptying the field cancels the job that would have cleared the line, so the
        // line used to keep running until some later search finished.
        controller.updateQuery("")
        advanceUntilIdle()

        assertThat(controller.session.value.isSearching).isFalse()
    }

    @Test
    fun `clearing the field also drops the results`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } returns hit("A")
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.updateQuery("")
        advanceUntilIdle()

        assertThat(controller.session.value.results.isEmpty).isTrue()
        assertThat(controller.session.value.resultsQuery).isEmpty()
    }

    @Test
    fun `the progress line stops once the search answers`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } returns hit("A")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()

        assertThat(controller.session.value.isSearching).isFalse()
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels")
    }

    // --- a failed search ---

    @Test
    fun `a failure drops results that answer an earlier query`() = runTest {
        val f = fixture()
        coEvery { f.music.search("folge 82", any(), any()) } returns hit("Folge 82")
        coEvery { f.music.search("folge 83", any(), any()) } throws RuntimeException("timed out")
        val controller = controllerOver(f)
        controller.updateQuery("folge 82")
        advanceUntilIdle()

        controller.updateQuery("folge 83")
        advanceUntilIdle()

        // The field says 83, so 82's albums are not an answer to anything on screen.
        assertThat(controller.session.value.query).isEqualTo("folge 83")
        assertThat(controller.session.value.results.isEmpty).isTrue()
        assertThat(controller.session.value.resultsQuery).isEmpty()
    }

    @Test
    fun `a failed retry of the same query keeps what is already shown`() = runTest {
        val f = fixture()
        var answered = false
        coEvery { f.music.search("eels", any(), any()) } answers {
            if (answered) throw RuntimeException("timed out") else hit("Eels").also { answered = true }
        }
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        // The search key on the keyboard asks the same query again; losing the results
        // already on screen would be a worse answer than keeping them.
        controller.submitQuery()
        advanceUntilIdle()

        assertThat(controller.session.value.results.albums).hasSize(1)
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels")
    }

    @Test
    fun `a failure says so and stops the progress line`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } throws RuntimeException("timed out")
        val controller = controllerOver(f)
        val seen = mutableListOf<String>()
        val collector = launch { controller.errors.collect { seen += it } }

        controller.updateQuery("eels")
        advanceUntilIdle()

        assertThat(seen).containsExactly("Search failed, try again")
        assertThat(controller.session.value.isSearching).isFalse()
        collector.cancel()
    }

    // --- the recent-search history ---

    @Test
    fun `a query the server matched is remembered`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any()) } returns hit("Eels")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()

        coVerify(exactly = 1) { f.settings.addRecentSearch("eels", null) }
    }

    @Test
    fun `a query that matched nothing is not remembered`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } returns SearchResult()
        val controller = controllerOver(f)

        controller.updateQuery("zzzz")
        advanceUntilIdle()

        coVerify(exactly = 0) { f.settings.addRecentSearch(any(), any()) }
    }

    @Test
    fun `the longer query replaces the stub this run stored`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } returns hit("Eels")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()
        controller.updateQuery("eels beautiful")
        advanceUntilIdle()

        coVerify(exactly = 1) { f.settings.addRecentSearch("eels beautiful", "eels") }
    }

    @Test
    fun `an unrelated query does not replace the earlier entry`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } returns hit("X")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()
        controller.updateQuery("pink floyd")
        advanceUntilIdle()

        coVerify(exactly = 1) { f.settings.addRecentSearch("pink floyd", null) }
    }

    // --- reset ---

    @Test
    fun `a reset clears the session`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any()) } returns hit("Eels")
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.reset()
        advanceUntilIdle()

        assertThat(controller.session.value).isEqualTo(SearchSession())
    }
}
