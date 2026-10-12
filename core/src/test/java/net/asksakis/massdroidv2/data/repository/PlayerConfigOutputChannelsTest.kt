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
import net.asksakis.massdroidv2.data.websocket.ServerPlayer
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.IntSetting
import net.asksakis.massdroidv2.domain.model.OutputProtocol
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a protocol copy that only inherits leaves the native key in charge`() {
        // The server fills a protocol entry the player has nothing stored for with the
        // native value, so equal values mean no override and the native key is the one
        // to write: writing it is what reaches every protocol.
        val values = values(
            """{
              "output_channels": {"value": "mono"},
              "sp_a||protocol||output_channels": {"value": "mono"}
            }"""
        )

        assertEquals("output_channels", repository().resolveProtocolConfigKey(values, "output_channels"))
    }

    @Test
    fun `a protocol copy that differs is an override and wins over the native key`() {
        // MA applies the rendering player's own stored value over the parent's, so this
        // speaker plays its left channel whatever the native entry says. Writing the
        // native key here would leave the app showing a value nobody hears.
        val values = values(
            """{
              "output_channels": {"value": "mono"},
              "sp_a||protocol||output_channels": {"value": "left"}
            }"""
        )

        assertEquals(
            "sp_a||protocol||output_channels",
            repository().resolveProtocolConfigKey(values, "output_channels")
        )
    }

    @Test
    fun `among several overrides the preferred protocol still wins`() {
        // The non-preferred override comes first, so a first-match lookup would pick it.
        val values = values(
            """{
              "output_channels": {"value": "stereo"},
              "preferred_output_protocol": {"value": "sp_b"},
              "sp_a||protocol||output_channels": {"value": "mono"},
              "sp_b||protocol||output_channels": {"value": "right"}
            }"""
        )

        assertEquals(
            "sp_b||protocol||output_channels",
            repository().resolveProtocolConfigKey(values, "output_channels")
        )
    }

    @Test
    fun `with nothing preferred or enabled the only wrapped copy is still found`() {
        val values = values("""{"sp_a||protocol||output_channels": {"value": "left"}}""")

        assertEquals(
            "sp_a||protocol||output_channels",
            repository().resolveProtocolConfigKey(values, "output_channels")
        )
    }

    /** The entries MA lists for a Sendspin output bridged over Cast while the Cast device is idle. */
    private val idleCastSendspin = """
        "spb_a||protocol||sendspin_cast_experimental": {"type": "alert", "category": "protocol_sendspin", "value": null},
        "spb_a||protocol||enabled": {"type": "boolean", "category": "protocol_sendspin", "value": true}
    """.trimIndent()

    @Test
    fun `an idle Sendspin over Cast output waits for playback to show its delay`() = runBlocking {
        serverConfig("""{"output_channels": ${channelEntry.format("left")}, $idleCastSendspin}""")

        val config = repository().getPlayerConfig("p1")!!

        assertNull(config.sendspinStaticDelayKey)
        assertTrue(config.sendspinDelayWaitsForPlayback)
    }

    @Test
    fun `a playing Sendspin over Cast output has its delay`() = runBlocking {
        serverConfig(
            """{$idleCastSendspin,
              "spb_a||protocol||sendspin_static_delay": {"type": "integer", "category": "protocol_sendspin", "value": 415}
            }"""
        )

        val config = repository().getPlayerConfig("p1")!!

        assertEquals("spb_a||protocol||sendspin_static_delay", config.sendspinStaticDelayKey)
        assertEquals(415, config.sendspinStaticDelayMs)
        assertFalse(config.sendspinDelayWaitsForPlayback)
    }

    @Test
    fun `a registered Sendspin output without a delay entry does not wait for playback`() = runBlocking {
        serverConfig(
            """{
              "sp_a||protocol||enabled": {"type": "boolean", "category": "protocol_sendspin", "value": true},
              "sp_a||protocol||preferred_sendspin_format": {"type": "string", "category": "protocol_sendspin", "value": "automatic"}
            }"""
        )

        assertFalse(repository().getPlayerConfig("p1")!!.sendspinDelayWaitsForPlayback)
    }

    @Test
    fun `a disabled Sendspin output does not wait for playback`() = runBlocking {
        serverConfig(
            """{"spb_a||protocol||enabled": {"type": "boolean", "category": "protocol_sendspin", "value": false}}"""
        )

        assertFalse(repository().getPlayerConfig("p1")!!.sendspinDelayWaitsForPlayback)
    }

    @Test
    fun `sync adjust is read for every output that has one`() = runBlocking {
        serverConfig(
            """{
              "sync_adjust": {"type": "integer", "value": -10},
              "apf0||protocol||sync_adjust": {"type": "integer", "value": 40},
              "apf0||protocol||enabled": {"type": "boolean", "category": "protocol_airplay", "value": true}
            }"""
        )

        val config = repository().getPlayerConfig("p1")!!

        assertEquals(
            mapOf(
                "native" to IntSetting("sync_adjust", -10),
                "apf0" to IntSetting("apf0||protocol||sync_adjust", 40),
            ),
            config.syncAdjust
        )
    }

    @Test
    fun `a player's outputs are read from output_protocols`() {
        val server = json.decodeFromString<ServerPlayer>(
            """{"player_id": "p1", "output_protocols": [
              {"output_protocol_id": "native", "name": "SONOS S1", "protocol_domain": "sonos_s1", "is_native": true, "derived_from": null},
              {"output_protocol_id": "spb_f0", "name": "Sendspin", "protocol_domain": "sendspin", "is_native": false, "derived_from": "apf0"}
            ]}"""
        )

        val player = server.toDomain(imageResolver = mockk(relaxed = true))

        assertEquals(
            listOf(
                OutputProtocol("native", "sonos_s1"),
                OutputProtocol("spb_f0", "sendspin", derivedFrom = "apf0"),
            ),
            player.outputProtocols
        )
    }

    @Test
    fun `an output without an id is dropped and the player still decodes`() {
        val server = json.decodeFromString<ServerPlayer>(
            """{"player_id": "p1", "active_output_protocol": "spb_ac", "output_protocols": [
              {"name": "Broken", "protocol_domain": "airplay"},
              {"output_protocol_id": "spb_ac", "protocol_domain": "sendspin", "derived_from": "native"}
            ]}"""
        )

        val player = server.toDomain(imageResolver = mockk(relaxed = true))

        assertEquals(listOf(OutputProtocol("spb_ac", "sendspin", derivedFrom = "native")), player.outputProtocols)
        assertEquals("spb_ac", player.activeOutputProtocol)
    }
}
