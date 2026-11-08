package net.asksakis.massdroidv2.data.provider

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.repository.PartyRepositoryImpl
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ServerEvent
import org.junit.Test

/** A plugin turned on or off on the server shows or hides its feature without a reconnect. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderManifestCacheTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 8)
    private var loaded = listOf("spotify")
    private val ws = mockk<MaWebSocketClient> {
        every { events } returns this@ProviderManifestCacheTest.events
        coEvery { sendCommand(MaCommands.Providers.LIST, any(), any(), any(), any()) } answers {
            json.parseToJsonElement(
                loaded.joinToString(",", "[", "]") {
                    """{"instance_id":"$it","domain":"$it","name":"$it","type":"plugin"}"""
                }
            )
        }
    }
    private val cache = ProviderManifestCache()
    private val party = PartyRepositoryImpl(ws, cache)

    private fun TestScope.follow() {
        backgroundScope.launch { cache.followProviderChanges(ws, json) }
        runCurrent()
    }

    private suspend fun TestScope.providersUpdated() {
        events.emit(ServerEvent(event = EventType.PROVIDERS_UPDATED))
        advanceTimeBy(SETTLE_MS + 1)
        runCurrent()
    }

    @Test
    fun `turning the party plugin on and off follows without a reconnect`() = runTest {
        follow()
        assertThat(party.available.first()).isFalse()

        loaded = listOf("spotify", "party")
        providersUpdated()
        assertThat(party.available.first()).isTrue()

        loaded = listOf("spotify")
        providersUpdated()
        assertThat(party.available.first()).isFalse()
    }

    @Test
    fun `a burst of provider events reads the list once`() = runTest {
        follow()
        repeat(5) {
            events.emit(ServerEvent(event = EventType.PROVIDERS_UPDATED))
            advanceTimeBy(SETTLE_MS / 2)
            runCurrent()
        }
        advanceTimeBy(SETTLE_MS * 10)
        runCurrent()

        coVerify(exactly = 1) { ws.sendCommand(MaCommands.Providers.LIST, any(), any(), any(), any()) }
    }

    @Test
    fun `other events do not read the list`() = runTest {
        follow()
        events.emit(ServerEvent(event = EventType.QUEUE_UPDATED))
        advanceTimeBy(SETTLE_MS + 1)
        runCurrent()

        coVerify(exactly = 0) { ws.sendCommand(MaCommands.Providers.LIST, any(), any(), any(), any()) }
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
