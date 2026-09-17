package net.asksakis.massdroidv2.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the output channel setting (`output_channels`: stereo/left/right/mono) is read out
 * of a player's MA config.
 *
 * A plain player carries it under the bare key. A universal player carries one copy per
 * output protocol, wrapped as `<protocol>||protocol||output_channels`, and only the copy
 * of the protocol the player actually outputs through is the one to show and to write:
 * a stereo-pair speaker whose left/right setting landed on a disabled protocol would keep
 * playing both channels while the app claimed otherwise.
 */
class PlayerConfigOutputChannelsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val wsClient = mockk<MaWebSocketClient>(relaxed = true)

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
            json = json,
            playHistoryRepository = mockk<PlayHistoryRepository>(relaxed = true),
            settingsRepository = settings,
            smartListeningRepository = smartListening,
            musicBrainzGenreResolver = mockk(relaxed = true),
            sessionEventBus = eventBus,
            queueItemsCoordinator = mockk(relaxed = true),
        )
    }

    private fun values(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    /** A `config/players/get` answer whose `values` are the given entries. */
    private fun serverConfig(valuesRaw: String) {
        val answer = json.parseToJsonElement(
            """{"player_id":"p1","name":"Kitchen","values":$valuesRaw}"""
        )
        coEvery { wsClient.sendCommand("config/players/get", any<JsonObject>(), any(), any()) } returns answer
    }

    private val channelEntry = """
        {"type":"string","value":"%s","default_value":"stereo","options":[
          {"value":"stereo","title":"Stereo (both channels)"},
          {"value":"left","title":"Left channel only"},
          {"value":"right","title":"Right channel only"},
          {"value":"mono","title":"Mono (both channels)"}
        ]}
    """.trimIndent()

    @Test
    fun `a plain player reads the bare key with its options`() = runBlocking {
        serverConfig("""{"output_channels": ${channelEntry.format("left")}}""")

        val config = repository().getPlayerConfig("p1")!!

        assertEquals("output_channels", config.outputChannelsKey)
        assertEquals("left", config.outputChannels)
        assertEquals(listOf("stereo", "left", "right", "mono"), config.outputChannelsOptions.map { it.value })
        assertEquals("Left channel only", config.outputChannelsOptions[1].title)
    }

    @Test
    fun `a universal player's Sendspin format is read from its protocol entry`() = runBlocking {
        serverConfig(
            """{
              "preferred_output_protocol": {"value": "auto"},
              "sp_a||protocol||enabled": {"value": true},
              "sp_a||protocol||preferred_sendspin_format": {"value": "flac:48000:16:2", "options": [
                {"value": "automatic", "title": "Automatic"},
                {"value": "flac:48000:16:2", "title": "FLAC 48 kHz"}
              ]}
            }"""
        )

        val config = repository().getPlayerConfig("p1")!!

        assertEquals("sp_a||protocol||preferred_sendspin_format", config.sendspinFormatKey)
        assertEquals("flac:48000:16:2", config.sendspinFormat)
        assertEquals(listOf("automatic", "flac:48000:16:2"), config.sendspinFormatOptions.map { it.value })
    }

    @Test
    fun `a player without the entry reports none`() = runBlocking {
        serverConfig("""{"volume_control":{"value":"native"}}""")

        val config = repository().getPlayerConfig("p1")!!

        assertNull(config.outputChannelsKey)
        assertNull(config.outputChannels)
        assertNull(config.sendspinFormatKey)
        assertEquals(emptyList<String>(), config.outputChannelsOptions.map { it.value })
    }

    @Test
    fun `a universal player uses the protocol named as preferred output`() {
        // The non-preferred protocol comes first, so a first-match lookup would pick it.
        val values = values(
            """{
              "preferred_output_protocol": {"value": "sp_b"},
              "sp_a||protocol||enabled": {"value": true},
              "sp_a||protocol||output_channels": {"value": "stereo"},
              "sp_b||protocol||enabled": {"value": true},
              "sp_b||protocol||output_channels": {"value": "right"}
            }"""
        )

        val key = repository().resolveProtocolConfigKey(values, "output_channels")

        assertEquals("sp_b||protocol||output_channels", key)
    }

    @Test
    fun `on automatic protocol choice the first enabled protocol wins`() {
        val values = values(
            """{
              "preferred_output_protocol": {"value": "auto"},
              "sp_a||protocol||enabled": {"value": false},
              "sp_a||protocol||output_channels": {"value": "stereo"},
              "sp_b||protocol||enabled": {"value": true},
              "sp_b||protocol||output_channels": {"value": "left"}
            }"""
        )

        val key = repository().resolveProtocolConfigKey(values, "output_channels")

        assertEquals("sp_b||protocol||output_channels", key)
    }

    @Test
    fun `the bare key wins over any wrapped copy`() {
        val values = values(
            """{
              "output_channels": {"value": "mono"},
              "sp_a||protocol||output_channels": {"value": "left"}
            }"""
        )

        assertEquals("output_channels", repository().resolveProtocolConfigKey(values, "output_channels"))
    }

    @Test
    fun `with nothing preferred or enabled the only wrapped copy is still found`() {
        val values = values("""{"sp_a||protocol||output_channels": {"value": "left"}}""")

        assertEquals(
            "sp_a||protocol||output_channels",
            repository().resolveProtocolConfigKey(values, "output_channels")
        )
    }
}
