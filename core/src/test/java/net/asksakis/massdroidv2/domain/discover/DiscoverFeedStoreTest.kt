package net.asksakis.massdroidv2.domain.discover

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.genre.GenreRepository
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ServerEvent
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.GenreItem
import net.asksakis.massdroidv2.domain.recommendation.DiscoverFeedOrchestrator
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import org.junit.Test

/**
 * The shared Discover feed: shown from the disk cache at once, built while a screen watches or
 * Android Auto asks, built only once for every consumer, and kept free of a previous account's
 * results.
 */
class DiscoverFeedStoreTest {

    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val wsClient = mockk<MaWebSocketClient> {
        every { connectionState } returns connection
        every { events } returns MutableSharedFlow<ServerEvent>()
    }
    private val cache = mockk<DiscoverCache>(relaxed = true)
    private val orchestrator = mockk<DiscoverFeedOrchestrator>()
    private val genreRepository = mockk<GenreRepository> {
        coEvery { genreArtistMap() } returns emptyMap()
    }
    private val sessionEventBus = SessionEventBus()

    private val cachedSection = albumSection("Cached")
    private val liveSection = albumSection("Live")

    private fun store() = DiscoverFeedStore(
        feedOrchestrator = orchestrator,
        discoverCache = cache,
        wsClient = wsClient,
        musicRepository = mockk<MusicRepository>(relaxed = true),
        genreRepository = genreRepository,
        sessionEventBus = sessionEventBus,
    )

    @Test
    fun `nothing is built or read before a consumer activates the store`() = runBlocking {
        connection.value = connected()
        store()
        delay(SETTLE_MS)
        coVerify(exactly = 0) { orchestrator.buildFeed() }
        coVerify(exactly = 0) { cache.load() }
    }

    @Test
    fun `the cached feed shows first and the first connect replaces it with a live one`() = runBlocking {
        coEvery { cache.load() } returns DiscoverCache.CacheData(sections = listOf(cachedSection))
        every { cache.isStale(any()) } returns false
        coEvery { orchestrator.buildFeed() } returns feed(liveSection)
        val store = store()
        val viewer = launch(Dispatchers.Default) { store.state.collect {} }

        store.activate()
        val shown = withTimeout(WAIT_MS) { store.state.first { it.sections.isNotEmpty() } }
        assertThat(shown.sections).containsExactly(cachedSection)
        assertThat(shown.isLoading).isFalse()

        connection.value = connected()
        val live = withTimeout(WAIT_MS) { store.state.first { it.sections == listOf(liveSection) } }
        assertThat(live.sections).containsExactly(liveSection)
        coVerify(timeout = WAIT_MS) { cache.save(match { it.sections == listOf(liveSection) }) }
        assertThat(store.libraryContext()).isNotNull()
        viewer.cancel()
    }

    @Test
    fun `a consumer asking while the connect build runs waits for it instead of building again`() = runBlocking {
        coEvery { cache.load() } returns null
        val release = CompletableDeferred<Unit>()
        coEvery { orchestrator.buildFeed() } coAnswers {
            release.await()
            feed(liveSection)
        }
        val store = store()
        val viewer = launch(Dispatchers.Default) { store.state.collect {} }
        store.activate()
        connection.value = connected()
        delay(SETTLE_MS)

        val waiting = async { store.awaitFeed(maxAgeMs = 0) }
        delay(SETTLE_MS)
        release.complete(Unit)

        assertThat(withTimeout(WAIT_MS) { waiting.await() }).containsExactly(liveSection)
        coVerify(exactly = 1) { orchestrator.buildFeed() }
        viewer.cancel()
    }

    @Test
    fun `without a connection the last rendered feed is returned and nothing is built`() = runBlocking {
        coEvery { cache.load() } returns DiscoverCache.CacheData(sections = listOf(cachedSection))
        every { cache.isStale(any()) } returns true
        val store = store()

        val sections = withTimeout(WAIT_MS) { store.awaitFeed(maxAgeMs = 0) }

        assertThat(sections).containsExactly(cachedSection)
        coVerify(exactly = 0) { orchestrator.buildFeed() }
    }

    @Test
    fun `a failed build keeps the feed on screen`() = runBlocking {
        coEvery { cache.load() } returns DiscoverCache.CacheData(sections = listOf(cachedSection))
        every { cache.isStale(any()) } returns true
        coEvery { orchestrator.buildFeed() } throws IllegalStateException("server gone")
        val store = store()
        val viewer = launch(Dispatchers.Default) { store.state.collect {} }
        store.activate()
        withTimeout(WAIT_MS) { store.state.first { it.sections.isNotEmpty() } }

        connection.value = connected()
        coVerify(timeout = WAIT_MS) { orchestrator.buildFeed() }
        delay(SETTLE_MS)

        assertThat(store.state.value.sections).containsExactly(cachedSection)
        assertThat(store.state.value.isLoading).isFalse()
        viewer.cancel()
    }

    @Test
    fun `an account switch drops the feed and the mix context`() = runBlocking {
        coEvery { cache.load() } returns null
        coEvery { orchestrator.buildFeed() } returns feed(liveSection)
        val store = store()
        val viewer = launch(Dispatchers.Default) { store.state.collect {} }
        store.activate()
        connection.value = connected()
        withTimeout(WAIT_MS) { store.state.first { it.sections.isNotEmpty() } }

        sessionEventBus.emitReset()

        withTimeout(WAIT_MS) { store.state.first { it.sections.isEmpty() } }
        assertThat(store.libraryContext()).isNull()
        viewer.cancel()
    }

    @Test
    fun `a build that finishes after an account switch is dropped`() = runBlocking {
        coEvery { cache.load() } returns null
        val release = CompletableDeferred<Unit>()
        coEvery { orchestrator.buildFeed() } coAnswers {
            // The switch cancels the build, but a build that already has its answer finishes
            // without suspending again; NonCancellable stands for that.
            withContext(NonCancellable) { release.await() }
            feed(liveSection)
        }
        val store = store()
        val viewer = launch(Dispatchers.Default) { store.state.collect {} }
        store.activate()
        connection.value = connected()
        coVerify(timeout = WAIT_MS) { orchestrator.buildFeed() }

        sessionEventBus.emitReset()
        delay(SETTLE_MS)
        release.complete(Unit)
        delay(SETTLE_MS)

        assertThat(store.state.value.sections).isEmpty()
        assertThat(store.libraryContext()).isNull()
        coVerify(exactly = 0) { cache.save(any()) }
        viewer.cancel()
    }

    @Test
    fun `an empty build keeps the feed on screen and out of the cache`() = runBlocking {
        coEvery { cache.load() } returns DiscoverCache.CacheData(sections = listOf(cachedSection))
        every { cache.isStale(any()) } returns true
        coEvery { orchestrator.buildFeed() } returns feed()
        val store = store()
        val viewer = launch(Dispatchers.Default) { store.state.collect {} }
        store.activate()
        withTimeout(WAIT_MS) { store.state.first { it.sections.isNotEmpty() } }

        connection.value = connected()
        coVerify(timeout = WAIT_MS) { orchestrator.buildFeed() }
        delay(SETTLE_MS)

        assertThat(store.state.value.sections).containsExactly(cachedSection)
        coVerify(exactly = 0) { cache.save(any()) }
        viewer.cancel()
    }

    @Test
    fun `with nobody watching a connect builds nothing until a screen starts watching`() = runBlocking {
        coEvery { cache.load() } returns null
        coEvery { orchestrator.buildFeed() } returns feed(liveSection)
        val store = store()
        store.activate()
        connection.value = connected()
        delay(SETTLE_MS)
        coVerify(exactly = 0) { orchestrator.buildFeed() }

        val viewer = launch(Dispatchers.Default) { store.state.collect {} }

        withTimeout(WAIT_MS) { store.state.first { it.sections == listOf(liveSection) } }
        coVerify(exactly = 1) { orchestrator.buildFeed() }
        viewer.cancel()
    }

    @Test
    fun `the radio genres are the feed's genres that have artists, in any spelling`() = runBlocking {
        coEvery { cache.load() } returns null
        coEvery { orchestrator.buildFeed() } returns feed(liveSection).copy(
            genreArtists = mapOf("rock" to listOf("library://artist/1"), "jazz" to emptyList()),
            strictGenreArtists = mapOf("synthpop" to listOf("library://artist/2")),
            genreItems = listOf(genre("Rock"), genre("Synth-pop"), genre("Jazz"), genre("Abstract hip hop")),
        )
        val store = store()
        assertThat(store.radioGenres()).isEmpty()
        connection.value = connected()

        store.awaitFeed(maxAgeMs = 0)

        assertThat(store.radioGenres().map { it.name }).containsExactly("Rock", "Synth-pop")
        sessionEventBus.emitReset()
        withTimeout(WAIT_MS) { store.state.first { it.sections.isEmpty() } }
        assertThat(store.radioGenres()).isEmpty()
    }

    private fun connected() = ConnectionState.Connected(mockk(relaxed = true))

    private fun genre(name: String) = GenreItem(name = name, count = 1, imageUrl = null)

    private fun feed(vararg sections: DiscoverSection) = DiscoverFeedOrchestrator.DiscoverFeed(
        sections = sections.toList(),
        topArtists = emptyList(),
        artistByUri = emptyMap(),
        genreArtists = mapOf("rock" to listOf("library://artist/1")),
        strictGenreArtists = emptyMap(),
    )

    private fun albumSection(title: String) = DiscoverSection.AlbumSection(
        title,
        listOf(Album(itemId = "1", provider = "library", name = title, uri = "library://album/$title")),
    )

    private companion object {
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 200L
    }
}
