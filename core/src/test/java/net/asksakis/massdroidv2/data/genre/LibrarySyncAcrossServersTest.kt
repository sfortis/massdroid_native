package net.asksakis.massdroidv2.data.genre

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.database.ArtistIdentityRow
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ServerInfo
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * The library sync against a database that holds another server's ids. Each MA server numbers
 * its own library, so `library://artist/5` is Rick Miller on one server and Broke For Free on the
 * other. Read as a reused id, that deleted Rick Miller's plays and feedback on every switch.
 */
class LibrarySyncAcrossServersTest {

    private val uri = "library://artist/5"
    private val dao = mockk<PlayHistoryDao>(relaxed = true) {
        coEvery { getArtistsByUris(listOf(uri)) } returns listOf(ArtistIdentityRow(uri, "Rick Miller", null))
    }
    private val music = mockk<MusicRepository> {
        coEvery { getArtists(limit = any(), offset = 0, orderBy = "name") } returns
            listOf(Artist(itemId = "5", provider = "library", name = "Broke For Free", uri = uri))
        coEvery { getArtists(limit = any(), offset = 1, orderBy = "name") } returns emptyList()
    }
    private val synced = mutableSetOf<String>()
    private val settings = mockk<SettingsRepository>(relaxed = true) {
        coEvery { addLibrarySyncServer(any()) } answers { synced.apply { add(firstArg()) }.toSet() }
    }
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val client = mockk<MaWebSocketClient> { every { connectionState } returns connection }
    private val transactions = object : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
    }
    private val enricher = LibraryGenreEnricher(
        musicBrainzGenreResolver = mockk(relaxed = true),
        dao = dao,
        settingsRepository = settings,
        musicRepository = music,
        transactions = transactions,
        genreSpellings = GenreSpellingResolver(dao),
        wsClient = client,
    )

    private fun connectTo(serverId: String) {
        connection.value = ConnectionState.Connected(ServerInfo(serverId, "2.10.9", 28, "x"))
    }

    @Test
    fun `a reused id on the only server is repointed`() = runTest {
        connectTo("home")

        enricher.syncLibraryArtists("home")

        coVerify(exactly = 1) { dao.reassignLibraryArtistUri(uri, "Rick Miller", "Broke For Free", null) }
    }

    @Test
    fun `another server's artist at the same id keeps the local artist's data`() = runTest {
        synced += "home"
        connectTo("test")

        enricher.syncLibraryArtists("test")

        coVerify(exactly = 0) { dao.reassignLibraryArtistUri(any(), any(), any(), any()) }
    }

    @Test
    fun `a sync whose server was switched away stops before it reads the page`() = runTest {
        // Started for home; the page is answered while the app is already on test.
        connectTo("test")

        enricher.syncLibraryArtists("home")

        coVerify(exactly = 0) { dao.reassignLibraryArtistUri(any(), any(), any(), any()) }
        coVerify(exactly = 0) { settings.addLibrarySyncServer(any()) }
    }
}
