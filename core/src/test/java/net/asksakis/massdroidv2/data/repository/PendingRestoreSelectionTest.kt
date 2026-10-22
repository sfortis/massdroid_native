package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ServerEvent
import net.asksakis.massdroidv2.data.websocket.ServerInfo
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import org.junit.Test

/**
 * A saved player that has not appeared yet is restored when it does, unless the user picked
 * another player in the meantime: that choice must stand. The same holds for a selected player
 * that MA removes and adds again, as it does when a provider reloads.
 */
class PendingRestoreSelectionTest {

    private val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 16)
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    private fun repository(savedPlayerId: String): PlayerRepositoryImpl {
        val wsClient = mockk<MaWebSocketClient>(relaxed = true) {
            every { this@mockk.events } returns this@PendingRestoreSelectionTest.events
            every { connectionState } returns connection
        }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { smartListeningEnabled } returns MutableStateFlow(false)
            every { selectedPlayerId } returns flowOf(savedPlayerId)
        }
        lastSettings = settings
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

    private lateinit var lastSettings: SettingsRepository

    private fun playerRemoved(id: String) = ServerEvent(event = "player_removed", objectId = id, data = null)

    private fun playerAdded(id: String) = ServerEvent(
        event = "player_added",
        objectId = id,
        data = buildJsonObject {
            put("player_id", id)
            put("display_name", id)
            put("available", true)
        }
    )

    private suspend fun awaitPlayer(repo: PlayerRepositoryImpl, id: String) = withTimeout(5_000) {
        while (repo.players.value.none { it.playerId == id }) delay(10)
    }

    @Test
    fun `a player picked while the saved one is missing keeps the selection when the saved one appears`() =
        runBlocking {
            val repo = repository(savedPlayerId = "saved")
            connection.value = ConnectionState.Connected(ServerInfo("server", "2.10.7", 65, "http://ma"))
            delay(200)

            events.emit(playerAdded("chosen"))
            awaitPlayer(repo, "chosen")
            repo.selectPlayer("chosen")

            events.emit(playerAdded("saved"))
            awaitPlayer(repo, "saved")
            delay(200)

            assertThat(repo.selectedPlayer.value?.playerId).isEqualTo("chosen")
        }

    @Test
    fun `a selected player that is removed and added again is selected again`() = runBlocking {
        val repo = repository(savedPlayerId = "saved")
        connection.value = ConnectionState.Connected(ServerInfo("server", "2.10.7", 65, "http://ma"))
        delay(200)
        events.emit(playerAdded("saved"))
        withTimeout(5_000) { while (repo.selectedPlayer.value?.playerId != "saved") delay(10) }

        events.emit(playerRemoved("saved"))
        withTimeout(5_000) { while (repo.selectedPlayer.value != null) delay(10) }
        events.emit(playerAdded("saved"))
        withTimeout(5_000) { while (repo.selectedPlayer.value?.playerId != "saved") delay(10) }

        assertThat(repo.selectedPlayer.value?.playerId).isEqualTo("saved")
        // The saved choice was never cleared, so a restart restores it too.
        coVerify(exactly = 0) { lastSettings.setSelectedPlayerId(null) }
    }

    @Test
    fun `the saved player is still restored when nobody picked another`() = runBlocking {
        val repo = repository(savedPlayerId = "saved")
        connection.value = ConnectionState.Connected(ServerInfo("server", "2.10.7", 65, "http://ma"))
        delay(200)

        events.emit(playerAdded("saved"))
        awaitPlayer(repo, "saved")
        withTimeout(5_000) {
            while (repo.selectedPlayer.value?.playerId != "saved") delay(10)
        }

        assertThat(repo.selectedPlayer.value?.playerId).isEqualTo("saved")
    }
}
