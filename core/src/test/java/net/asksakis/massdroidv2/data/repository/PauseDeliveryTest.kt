package net.asksakis.massdroidv2.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
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

    /**
     * The real repository, with every flow its constructor collects backed by a
     * live empty flow.
     *
     * A relaxed mock answers a Flow property with a stub that throws as soon as it
     * is collected, and the constructor starts four collectors on Dispatchers.IO.
     * Those exceptions escape the test that built the object and land on whichever
     * test runs next, which is why they are stubbed here rather than left relaxed.
     */
    private fun repository(): PlayerRepositoryImpl {
        every { wsClient.events } returns MutableSharedFlow()
        every { wsClient.connectionState } returns MutableStateFlow(ConnectionState.Disconnected)
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { smartListeningEnabled } returns MutableStateFlow(false)
        }
        val smartListening = mockk<SmartListeningRepository>(relaxed = true) {
            every { blockedArtistUris } returns MutableStateFlow(emptySet())
        }
        val eventBus = mockk<SessionEventBus>(relaxed = true) {
            every { resets } returns MutableSharedFlow()
        }
        return PlayerRepositoryImpl(
            wsClient = wsClient,
            imageResolver = mockk<ImageUrlResolver>(relaxed = true),
            json = Json { ignoreUnknownKeys = true },
            playHistoryRepository = mockk<PlayHistoryRepository>(relaxed = true),
            settingsRepository = settings,
            smartListeningRepository = smartListening,
            musicBrainzGenreResolver = mockk(relaxed = true),
            sessionEventBus = eventBus,
            queueItemsCoordinator = mockk(relaxed = true),
        )
    }

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

    @Test
    fun `a pause after the burst window is a new interruption`() = runBlocking {
        coEvery { wsClient.sendCommand(any(), any<JsonObject>(), any(), any()) } returns null
        val repository = repository()
        var clock = 0L
        repository.elapsedMs = { clock }

        repository.pause("player-1")
        clock = 600
        repository.pause("player-1")

        coVerify(exactly = 2) {
            wsClient.sendCommand("players/cmd/pause", any<JsonObject>(), false, any())
        }
    }

    @Test
    fun `the window is measured from the command that went out, not from the last attempt`() = runBlocking {
        // Otherwise a handler firing every 200 ms would keep pushing the window
        // forward and the pause would never be sent at all.
        coEvery { wsClient.sendCommand(any(), any<JsonObject>(), any(), any()) } returns null
        val repository = repository()
        var clock = 0L
        repository.elapsedMs = { clock }

        repository.pause("player-1")
        clock = 200
        repository.pause("player-1")
        clock = 400
        repository.pause("player-1")
        clock = 600
        repository.pause("player-1")

        coVerify(exactly = 2) {
            wsClient.sendCommand("players/cmd/pause", any<JsonObject>(), false, any())
        }
    }

    @Test
    fun `one interruption sends one pause, however many handlers act on it`() = runBlocking {
        // A phone call takes audio focus, the route disappears and the media
        // session gets its own callback. Each of those pauses, and on 2026-09-17
        // that put four identical commands on the wire in 42 ms.
        coEvery { wsClient.sendCommand(any(), any<JsonObject>(), any(), any()) } returns null
        val repository = repository()

        repeat(4) { repository.pause("player-1") }

        coVerify(exactly = 1) {
            wsClient.sendCommand("players/cmd/pause", any<JsonObject>(), false, any())
        }
    }

    @Test
    fun `pausing again after a play is a new decision, not a repeat`() = runBlocking {
        coEvery { wsClient.sendCommand(any(), any<JsonObject>(), any(), any()) } returns null
        val repository = repository()

        repository.pause("player-1")
        repository.play("player-1")
        repository.pause("player-1")

        coVerify(exactly = 2) {
            wsClient.sendCommand("players/cmd/pause", any<JsonObject>(), false, any())
        }
    }

    @Test
    fun `a re-assert is sent even right behind another pause`() = runBlocking {
        // Re-asserting exists precisely because the first pause may have been
        // written into a socket whose peer was already gone.
        coEvery { wsClient.sendCommand(any(), any<JsonObject>(), any(), any()) } returns null
        val repository = repository()

        repository.pause("player-1")
        repository.pauseConfirmed("player-1")

        coVerify(exactly = 1) {
            wsClient.sendCommand("players/cmd/pause", any<JsonObject>(), true, any())
        }
    }

    @Test
    fun `each player keeps its own burst window`() = runBlocking {
        coEvery { wsClient.sendCommand(any(), any<JsonObject>(), any(), any()) } returns null
        val repository = repository()

        repository.pause("player-1")
        repository.pause("player-2")

        coVerify(exactly = 2) {
            wsClient.sendCommand("players/cmd/pause", any<JsonObject>(), false, any())
        }
    }
}
