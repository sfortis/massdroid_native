package net.asksakis.massdroidv2.domain.search

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.repository.SearchScope
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

        val LIBRARY = SearchScope.LIBRARY
        val ALL = SearchScope.ALL
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
        // The library pass finds nothing unless a test says otherwise, so the other tests
        // describe the full search alone.
        coEvery { music.search(any(), any(), any(), LIBRARY) } returns SearchResult()
        val settings = mockk<SettingsRepository>(relaxed = true)
        return Fixture(music, settings)
    }

    private fun TestScope.controllerOver(f: Fixture) =
        SearchSessionController(f.music, f.settings, this, testScheduler.timeSource)

    private fun albums(count: Int, prefix: String = "A") = (1..count).map { album("$prefix$it") }

    private fun track(name: String) = Track(
        itemId = name,
        provider = "library",
        name = name,
        uri = "library://track/$name"
    )

    // --- when a query is worth sending ---

    @Test
    fun `typing a single character searches nothing`() = runTest {
        val f = fixture()
        val controller = controllerOver(f)

        controller.updateQuery("a")
        advanceUntilIdle()

        assertThat(controller.session.value.query).isEqualTo("a")
        coVerify(exactly = 0) { f.music.search(any(), any(), any(), any()) }
    }

    @Test
    fun `submitting a single character searches it`() = runTest {
        val f = fixture()
        coEvery { f.music.search("a", any(), any(), ALL) } returns hit("A")
        val controller = controllerOver(f)

        controller.updateQuery("a")
        controller.submitQuery()
        advanceUntilIdle()

        assertThat(controller.session.value.results.albums).hasSize(1)
        coVerify(exactly = 1) { f.music.search("a", any(), any(), ALL) }
    }

    @Test
    fun `typing waits out the debounce before it asks the server`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } returns hit("A")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(500)
        coVerify(exactly = 0) { f.music.search(any(), any(), any(), ALL) }

        advanceUntilIdle()
        coVerify(exactly = 1) { f.music.search("eels", any(), any(), ALL) }
    }

    @Test
    fun `a keystroke during the debounce replaces the query that would have been sent`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } returns hit("A")
        val controller = controllerOver(f)

        controller.updateQuery("eel")
        advanceTimeBy(100)
        controller.updateQuery("eels")
        advanceUntilIdle()

        coVerify(exactly = 0) { f.music.search("eel", any(), any(), ALL) }
        coVerify(exactly = 1) { f.music.search("eels", any(), any(), ALL) }
    }

    // --- the progress line ---

    @Test
    fun `clearing the field stops the progress line`() = runTest {
        val f = fixture()
        // The server has to be still thinking when the field is emptied, otherwise there
        // is no running search for the clear to cancel and the test proves nothing.
        coEvery { f.music.search(any(), any(), any(), ALL) } coAnswers {
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
        coEvery { f.music.search(any(), any(), any(), ALL) } returns hit("A")
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
        coEvery { f.music.search(any(), any(), any(), ALL) } returns hit("A")
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
        coEvery { f.music.search("folge 82", any(), any(), ALL) } returns hit("Folge 82")
        coEvery { f.music.search("folge 83", any(), any(), ALL) } throws RuntimeException("timed out")
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
        coEvery { f.music.search("eels", any(), any(), ALL) } answers {
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
        coEvery { f.music.search(any(), any(), any(), ALL) } throws RuntimeException("timed out")
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
        coEvery { f.music.search("eels", any(), any(), ALL) } returns hit("Eels")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()

        coVerify(exactly = 1) { f.settings.addRecentSearch("eels", null) }
    }

    @Test
    fun `a query that matched nothing is not remembered`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } returns SearchResult()
        val controller = controllerOver(f)

        controller.updateQuery("zzzz")
        advanceUntilIdle()

        coVerify(exactly = 0) { f.settings.addRecentSearch(any(), any()) }
    }

    @Test
    fun `the longer query replaces the stub this run stored`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } returns hit("Eels")
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
        coEvery { f.music.search(any(), any(), any(), ALL) } returns hit("X")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()
        controller.updateQuery("pink floyd")
        advanceUntilIdle()

        coVerify(exactly = 1) { f.settings.addRecentSearch("pink floyd", null) }
    }

    // --- a slow provider (#76) ---
    //
    // Music Assistant answers without a provider that has not answered within 8 s, and the
    // answer does not say so. With Spotify on its shared rate limit that happened often
    // enough to show "No results" for things that exist.

    @Test
    fun `the first page asks for twenty per category`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } returns hit("A")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()

        coVerify(exactly = 1) { f.music.search("eels", any(), FIRST_PAGE_LIMIT, ALL) }
        assertThat(FIRST_PAGE_LIMIT).isEqualTo(20)
    }

    @Test
    fun `a fast empty answer is final`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } returns SearchResult()
        val controller = controllerOver(f)

        controller.updateQuery("zzzz")
        advanceUntilIdle()

        assertThat(controller.session.value.resultsQuery).isEqualTo("zzzz")
        coVerify(exactly = 1) { f.music.search(any(), any(), any(), ALL) }
    }

    @Test
    fun `a slow empty answer is asked once more before it counts as nothing found`() = runTest {
        val f = fixture()
        var calls = 0
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            if (calls++ == 0) {
                delay(SLOW_ANSWER_MS + 500)
                SearchResult()
            } else {
                hit("Eels")
            }
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(600 + SLOW_ANSWER_MS + 600)
        // The first answer is in, but it is not shown as "No results".
        assertThat(controller.session.value.resultsQuery).isEmpty()
        assertThat(controller.session.value.isSearching).isTrue()

        advanceUntilIdle()
        assertThat(controller.session.value.results.albums).hasSize(1)
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels")
        coVerify(exactly = 2) { f.music.search("eels", any(), any(), ALL) }
    }

    @Test
    fun `a slow empty answer that stays empty shows nothing found after one retry`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } coAnswers {
            delay(SLOW_ANSWER_MS + 500)
            SearchResult()
        }
        val controller = controllerOver(f)

        controller.updateQuery("zzzz")
        advanceUntilIdle()

        assertThat(controller.session.value.resultsQuery).isEqualTo("zzzz")
        assertThat(controller.session.value.isSearching).isFalse()
        coVerify(exactly = 2) { f.music.search(any(), any(), any(), ALL) }
    }

    @Test
    fun `a query that fills on the retry is remembered`() = runTest {
        val f = fixture()
        var calls = 0
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            if (calls++ == 0) {
                delay(SLOW_ANSWER_MS + 500)
                SearchResult()
            } else {
                hit("Eels")
            }
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()

        coVerify(exactly = 1) { f.settings.addRecentSearch("eels", null) }
    }

    @Test
    fun `a slow answer is shown at once and only grows from the retry`() = runTest {
        val f = fixture()
        var calls = 0
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            if (calls++ == 0) {
                delay(SLOW_ANSWER_MS + 500)
                SearchResult(albums = albums(2))
            } else {
                // The provider that was missing brings tracks, and fewer albums than shown.
                SearchResult(albums = albums(1), tracks = listOf(track("T1")))
            }
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(600 + SLOW_ANSWER_MS + 600)
        assertThat(controller.session.value.results.albums).hasSize(2)
        assertThat(controller.session.value.isSearching).isFalse()

        advanceUntilIdle()
        assertThat(controller.session.value.results.albums).hasSize(2)
        assertThat(controller.session.value.results.tracks).hasSize(1)
        coVerify(exactly = 2) { f.music.search("eels", any(), any(), ALL) }
    }

    // --- the library first ---

    @Test
    fun `the library matches show while the full search is still out`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any(), LIBRARY) } returns hit("Mine")
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            delay(3_000)
            SearchResult(albums = listOf(album("Mine"), album("Spotify")))
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(700)

        assertThat(controller.session.value.results.albums.map { it.name }).containsExactly("Mine")
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels")
        assertThat(controller.session.value.isSearching).isTrue()
        coVerify(exactly = 1) { f.music.search("eels", any(), FIRST_PAGE_LIMIT, ALL) }
    }

    @Test
    fun `the full answer replaces the library matches`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any(), LIBRARY) } returns hit("Mine")
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            delay(3_000)
            SearchResult(albums = listOf(album("Mine"), album("Spotify")))
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()

        assertThat(controller.session.value.results.albums.map { it.name })
            .containsExactly("Mine", "Spotify").inOrder()
        assertThat(controller.session.value.isSearching).isFalse()
    }

    @Test
    fun `an empty library does not read as nothing found while the full search is out`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            delay(5_000)
            hit("Eels")
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(2_000)

        // "No results" needs an answer to this exact query, and the library's empty one is not it.
        assertThat(controller.session.value.resultsQuery).isEmpty()
        assertThat(controller.session.value.isSearching).isTrue()

        advanceUntilIdle()
        assertThat(controller.session.value.results.albums).hasSize(1)
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels")
    }

    @Test
    fun `a library answer that arrives after the text changed is dropped`() = runTest {
        val f = fixture()
        // An answer already on its way when the search is cancelled.
        coEvery { f.music.search("eels", any(), any(), LIBRARY) } coAnswers {
            withContext(NonCancellable) { delay(300) }
            hit("Old")
        }
        coEvery { f.music.search(any(), any(), any(), ALL) } coAnswers {
            delay(1_000)
            hit("New")
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(700)
        controller.updateQuery("eels b")
        advanceTimeBy(300)

        assertThat(controller.session.value.results.isEmpty).isTrue()
        assertThat(controller.session.value.resultsQuery).isEmpty()

        advanceUntilIdle()
        assertThat(controller.session.value.results.albums.map { it.name }).containsExactly("New")
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels b")
        // Nor does the cancelled search go on to ask the providers for the old text.
        coVerify(exactly = 0) { f.music.search("eels", any(), any(), ALL) }
    }

    @Test
    fun `a failed library search still runs the full search`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any(), LIBRARY) } throws RuntimeException("unknown argument")
        coEvery { f.music.search("eels", any(), any(), ALL) } returns hit("Eels")
        val controller = controllerOver(f)
        val seen = mutableListOf<String>()
        val collector = launch { controller.errors.collect { seen += it } }

        controller.updateQuery("eels")
        advanceUntilIdle()

        assertThat(controller.session.value.results.albums).hasSize(1)
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels")
        assertThat(seen).isEmpty()
        collector.cancel()
    }

    @Test
    fun `a stalled library search holds up the full search for three seconds at most`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any(), LIBRARY) } coAnswers {
            delay(SLOW_SERVER_MS)
            hit("Mine")
        }
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            delay(1_000)
            hit("Eels")
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(600 + LIBRARY_PASS_TIMEOUT_MS - 100)
        coVerify(exactly = 0) { f.music.search("eels", any(), any(), ALL) }

        advanceTimeBy(100 + 1_000 + 100)
        assertThat(controller.session.value.results.albums.map { it.name }).containsExactly("Eels")
        assertThat(controller.session.value.isSearching).isFalse()
        assertThat(LIBRARY_PASS_TIMEOUT_MS).isEqualTo(3_000L)
    }

    @Test
    fun `a failed full search keeps the library matches`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any(), LIBRARY) } returns hit("Mine")
        coEvery { f.music.search("eels", any(), any(), ALL) } throws RuntimeException("timed out")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceUntilIdle()

        assertThat(controller.session.value.results.albums.map { it.name }).containsExactly("Mine")
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels")
        assertThat(controller.session.value.isSearching).isFalse()
    }

    // --- one search at a time while typing ---

    @Test
    fun `typing during a search waits for its answer and then asks only for the latest text`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            delay(5_000)
            hit("Eels")
        }
        coEvery { f.music.search(neq("eels"), any(), any(), ALL) } coAnswers {
            delay(1_000)
            hit("X")
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(1_600)
        controller.updateQuery("eels b")
        advanceTimeBy(700)
        controller.updateQuery("eels be")
        advanceTimeBy(2_000)
        // The server cannot drop "eels", so nothing else is sent while it runs.
        coVerify(exactly = 0) { f.music.search(neq("eels"), any(), any(), ALL) }

        advanceTimeBy(1_800)
        // "eels" has answered, but its results are not an answer to the text in the field.
        assertThat(controller.session.value.results.isEmpty).isTrue()
        assertThat(controller.session.value.isSearching).isTrue()

        advanceUntilIdle()
        assertThat(controller.session.value.resultsQuery).isEqualTo("eels be")
        coVerify(exactly = 0) { f.music.search("eels b", any(), any(), ALL) }
        coVerify(exactly = 1) { f.music.search("eels be", any(), any(), ALL) }
    }

    @Test
    fun `text typed just before an answer still waits out its debounce`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), any(), ALL) } coAnswers {
            delay(5_000)
            hit("Eels")
        }
        coEvery { f.music.search("eels b", any(), any(), ALL) } returns hit("X")
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(5_400)
        // 200 ms before "eels" answers: the listener may still be typing.
        controller.updateQuery("eels b")
        advanceTimeBy(500)
        coVerify(exactly = 0) { f.music.search("eels b", any(), any(), ALL) }

        advanceTimeBy(200)
        coVerify(exactly = 1) { f.music.search("eels b", any(), any(), ALL) }
    }

    @Test
    fun `the search key during a search asks at once`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } coAnswers {
            delay(5_000)
            hit("A")
        }
        val controller = controllerOver(f)

        controller.updateQuery("eels")
        advanceTimeBy(1_600)
        controller.updateQuery("eels b")
        controller.submitQuery()
        advanceTimeBy(100)

        coVerify(exactly = 1) { f.music.search("eels b", any(), any(), ALL) }
    }

    @Test
    fun `the search key for the text already on its way sends nothing new`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } coAnswers {
            delay(5_000)
            hit("Radiohead")
        }
        val controller = controllerOver(f)

        controller.updateQuery("radiohead")
        advanceTimeBy(3_000)
        controller.submitQuery()
        advanceUntilIdle()

        coVerify(exactly = 1) { f.music.search("radiohead", any(), any(), ALL) }
        assertThat(controller.session.value.resultsQuery).isEqualTo("radiohead")

        // Answered now, so the key asks again.
        controller.submitQuery()
        advanceUntilIdle()
        coVerify(exactly = 2) { f.music.search("radiohead", any(), any(), ALL) }
    }

    // --- a chip asks for more ---

    @Test
    fun `a chip asks for thirty of its category`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), FIRST_PAGE_LIMIT, ALL) } returns
            SearchResult(albums = albums(FIRST_PAGE_LIMIT))
        coEvery { f.music.search("eels", listOf(MediaType.ALBUM), any(), ALL) } returns
            SearchResult(albums = albums(DEEP_SEARCH_LIMIT))
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.deepen(MediaType.ALBUM)
        advanceUntilIdle()

        coVerify(exactly = 1) { f.music.search("eels", listOf(MediaType.ALBUM), DEEP_SEARCH_LIMIT, ALL) }
        assertThat(DEEP_SEARCH_LIMIT).isEqualTo(30)
        assertThat(controller.session.value.results.albums).hasSize(DEEP_SEARCH_LIMIT)
    }

    @Test
    fun `a chip for a category the first page did not fill asks for nothing`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), FIRST_PAGE_LIMIT, ALL) } returns
            SearchResult(albums = albums(FIRST_PAGE_LIMIT - 1))
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.deepen(MediaType.ALBUM)
        advanceUntilIdle()

        coVerify(exactly = 0) { f.music.search(any(), listOf(MediaType.ALBUM), any(), ALL) }
    }

    @Test
    fun `a shorter deep answer does not shrink the list`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), FIRST_PAGE_LIMIT, ALL) } returns
            SearchResult(albums = albums(FIRST_PAGE_LIMIT))
        coEvery { f.music.search("eels", listOf(MediaType.ALBUM), any(), ALL) } returns
            SearchResult(albums = albums(3, prefix = "Lib"))
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.deepen(MediaType.ALBUM)
        advanceUntilIdle()

        assertThat(controller.session.value.results.albums).hasSize(FIRST_PAGE_LIMIT)
    }

    @Test
    fun `an empty deep answer keeps the list`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), FIRST_PAGE_LIMIT, ALL) } returns
            SearchResult(albums = albums(FIRST_PAGE_LIMIT))
        coEvery { f.music.search("eels", listOf(MediaType.ALBUM), any(), ALL) } returns SearchResult()
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.deepen(MediaType.ALBUM)
        advanceUntilIdle()

        assertThat(controller.session.value.results.albums).hasSize(FIRST_PAGE_LIMIT)
    }

    @Test
    fun `a chip tapped before the new text is answered asks for nothing`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), FIRST_PAGE_LIMIT, ALL) } returns
            SearchResult(albums = albums(FIRST_PAGE_LIMIT))
        val controller = controllerOver(f)
        controller.updateQuery("pink")
        advanceUntilIdle()

        // Still the debounce: the full albums on screen answer "pink", not "pink floyd".
        controller.updateQuery("pink floyd")
        controller.deepen(MediaType.ALBUM)
        advanceUntilIdle()

        coVerify(exactly = 0) { f.music.search(any(), listOf(MediaType.ALBUM), any(), ALL) }
    }

    @Test
    fun `a new search stops every chip still loading, not only the last one`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), FIRST_PAGE_LIMIT, ALL) } returns
            SearchResult(albums = albums(FIRST_PAGE_LIMIT), tracks = (1..FIRST_PAGE_LIMIT).map { track("T$it") })
        var albumAsks = 0
        coEvery { f.music.search("eels", listOf(MediaType.ALBUM), any(), ALL) } coAnswers {
            delay(if (albumAsks++ == 0) 2_000 else 4_000)
            SearchResult(albums = albums(DEEP_SEARCH_LIMIT))
        }
        coEvery { f.music.search("eels", listOf(MediaType.TRACK), any(), ALL) } coAnswers {
            delay(4_000)
            SearchResult()
        }
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.deepen(MediaType.ALBUM)
        controller.deepen(MediaType.TRACK)
        advanceTimeBy(100)
        controller.submitQuery()
        advanceTimeBy(100)
        controller.deepen(MediaType.ALBUM)
        advanceTimeBy(2_500)

        // The first album deepen was stopped by the search, so it cannot end the spinner
        // of the one that replaced it.
        assertThat(controller.session.value.deepening).contains(MediaType.ALBUM)
    }

    @Test
    fun `the chip shows that it is loading while more of its category comes`() = runTest {
        val f = fixture()
        coEvery { f.music.search("eels", any(), FIRST_PAGE_LIMIT, ALL) } returns
            SearchResult(albums = albums(FIRST_PAGE_LIMIT))
        coEvery { f.music.search("eels", listOf(MediaType.ALBUM), any(), ALL) } coAnswers {
            delay(4_000)
            SearchResult(albums = albums(DEEP_SEARCH_LIMIT))
        }
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.deepen(MediaType.ALBUM)
        advanceTimeBy(1_000)
        assertThat(controller.session.value.deepening).containsExactly(MediaType.ALBUM)

        advanceUntilIdle()
        assertThat(controller.session.value.deepening).isEmpty()
    }

    // --- reset ---

    @Test
    fun `a reset clears the session`() = runTest {
        val f = fixture()
        coEvery { f.music.search(any(), any(), any(), ALL) } returns hit("Eels")
        val controller = controllerOver(f)
        controller.updateQuery("eels")
        advanceUntilIdle()

        controller.reset()
        advanceUntilIdle()

        assertThat(controller.session.value).isEqualTo(SearchSession())
    }
}
