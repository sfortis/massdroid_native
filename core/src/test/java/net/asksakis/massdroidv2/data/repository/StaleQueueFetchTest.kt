package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import org.junit.Test

/**
 * Each selection fetches its player's queue. When the user switches players before the first
 * answer arrives, the late answer must not land on the player selected now.
 */
class StaleQueueFetchTest {

    private val wsClient = mockk<MaWebSocketClient>(relaxed = true)

    private fun repository(): PlayerRepositoryImpl {
        every { wsClient.events } returns MutableSharedFlow()
        every { wsClient.connectionState } returns MutableStateFlow(ConnectionState.Disconnected)
        coEvery {
            wsClient.sendCommand("player_queues/get_active_queue", any(), any(), any(), any())
        } coAnswers {
            val playerId = secondArg<JsonObject?>()?.get("player_id")?.jsonPrimitive?.content
            // The first player's server is slow; the second answers at once.
            if (playerId == "slow") delay(400)
            buildJsonObject { put("queue_id", playerId) }
        }
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
    fun `a late queue answer for the previous player does not land on the current one`() = runBlocking {
        val repo = repository()

        repo.selectPlayer("slow")
        delay(50)
        repo.selectPlayer("fast")
        delay(800)

        assertThat(repo.queueState.value?.queueId).isEqualTo("fast")
    }
}
