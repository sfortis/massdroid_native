package net.asksakis.massdroidv2.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import org.junit.Test

/**
 * Whether a pause command is merely written to the socket or waited on.
 *
 * A pause sent when an external sink disappears is often sent at the moment the
 * phone also loses the network, and a WebSocket write into a socket whose peer
 * is already unreachable is buffered locally and then discarded with the
 * connection, reporting no error. The queue then keeps playing on the server and
 * the next reconnect streams it to the phone speaker. The reconnect path
 * therefore re-asserts the pause and orders the Sendspin refresh behind the
 * server's answer, which only works while [PlayerRepositoryImpl.pauseConfirmed]
 * actually waits for that answer.
 */
class PauseDeliveryTest {

    private val wsClient = mockk<MaWebSocketClient>(relaxed = true)

    private fun repository() = PlayerRepositoryImpl(
        wsClient = wsClient,
        imageResolver = mockk<ImageUrlResolver>(relaxed = true),
        json = Json { ignoreUnknownKeys = true },
        playHistoryRepository = mockk<PlayHistoryRepository>(relaxed = true),
        settingsRepository = mockk<SettingsRepository>(relaxed = true),
        smartListeningRepository = mockk<SmartListeningRepository>(relaxed = true),
        musicBrainzGenreResolver = mockk(relaxed = true),
        sessionEventBus = mockk(relaxed = true),
        queueItemsCoordinator = mockk(relaxed = true),
    )

    @Test
    fun `pauseConfirmed waits for the server to answer the command`() = runBlocking {
        coEvery { wsClient.sendCommand(any(), any<JsonObject>(), any(), any()) } returns null

        repository().pauseConfirmed("player-1")

        coVerify(exactly = 1) {
            wsClient.sendCommand("players/cmd/pause", any<JsonObject>(), true, any())
        }
    }

    @Test
    fun `plain pause stays fire-and-forget`() = runBlocking {
        coEvery { wsClient.sendCommand(any(), any<JsonObject>(), any(), any()) } returns null

        repository().pause("player-1")

        // A UI tap must not block on the round trip: the server's own state
        // event is what updates the screen.
        coVerify(exactly = 1) {
            wsClient.sendCommand("players/cmd/pause", any<JsonObject>(), false, any())
        }
    }
}
