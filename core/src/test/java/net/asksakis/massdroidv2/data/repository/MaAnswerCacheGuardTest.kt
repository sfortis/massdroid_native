package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ServerInfo
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * The cached MA answers are keyed by one server's library ids, so they are cleared when a
 * connection reaches another server, and only then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaAnswerCacheGuardTest {

    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val client = mockk<MaWebSocketClient> { every { connectionState } returns connection }
    private val cacheServer = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { maCacheServer } returns cacheServer
        coEvery { setMaCacheServer(any()) } answers { cacheServer.value = firstArg() }
    }
    private val dao = mockk<PlayHistoryDao>(relaxed = true)

    private fun TestScope.start() =
        MaAnswerCacheGuard(client, settings, dao, TestScope(StandardTestDispatcher(testScheduler))).start()

    private fun connected(serverId: String) = ConnectionState.Connected(ServerInfo(serverId, "2.10.9", 28, "x"))

    @Test
    fun `a connection to another server clears the caches`() = runTest {
        cacheServer.value = "home"
        start()

        connection.value = connected("test")
        runCurrent()

        coVerify(exactly = 1) { dao.clearMaAnswerCaches() }
    }

    @Test
    fun `the first recorded server clears them once, and a reconnect to it keeps them`() = runTest {
        start()

        connection.value = connected("home")
        runCurrent()
        connection.value = ConnectionState.Connecting
        runCurrent()
        connection.value = connected("home")
        runCurrent()

        // Once, for the first start after the update, which may find another server's answers.
        coVerify(exactly = 1) { dao.clearMaAnswerCaches() }
    }

    @Test
    fun `a clear that fails is tried again on the next connection and the guard keeps running`() = runTest {
        cacheServer.value = "home"
        var calls = 0
        coEvery { dao.clearMaAnswerCaches() } answers { if (++calls == 1) throw IllegalStateException("database closed") }
        start()

        connection.value = connected("test")
        runCurrent()
        connection.value = ConnectionState.Connecting
        runCurrent()
        connection.value = connected("test")
        runCurrent()

        coVerify(exactly = 2) { dao.clearMaAnswerCaches() }
        assertThat(cacheServer.value).isEqualTo("test")
    }
}
