package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.asksakis.massdroidv2.data.provider.ProviderManifestCache
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PartyInfo
import net.asksakis.massdroidv2.domain.model.PartyMode
import org.junit.Test

/**
 * The Party plugin's read commands as the app reads them. The plugin's commands exist only
 * while it is loaded, and its answers may lack fields an older or newer server does not send.
 */
class PartyRepositoryImplTest {

    private val domains = MutableStateFlow(setOf("spotify"))

    private fun repository(replies: Map<String, String> = emptyMap()): PartyRepositoryImpl {
        val ws = mockk<MaWebSocketClient>()
        coEvery { ws.sendCommand(any(), any(), any(), any(), any()) } answers {
            replies[firstArg<String>()]?.let { Json.parseToJsonElement(it) } as JsonElement?
        }
        val cache = mockk<ProviderManifestCache>()
        every { cache.loadedDomains } returns domains
        return PartyRepositoryImpl(ws, cache)
    }

    @Test
    fun `party is available only while the plugin is loaded`() = runTest {
        val repository = repository()
        assertThat(repository.available.first()).isFalse()

        domains.value = setOf("spotify", "party")

        assertThat(repository.available.first()).isTrue()
    }

    @Test
    fun `no join link while guest access is off`() = runTest {
        assertThat(repository(mapOf("party/url" to "null")).joinLink()).isNull()
        assertThat(repository().joinLink()).isNull()
    }

    @Test
    fun `a link through an external URL or remote access opens from anywhere`() = runTest {
        val external = repository(mapOf("party/url" to "\"https://mass.example.com/?join=ABC\"")).joinLink()!!
        val remote = repository(
            mapOf("party/url" to "\"https://app.music-assistant.io/?remote_id=R1&join=ABC\"")
        ).joinLink()!!

        assertThat(external.url).isEqualTo("https://mass.example.com/?join=ABC")
        assertThat(external.homeNetworkOnly).isFalse()
        assertThat(remote.homeNetworkOnly).isFalse()
    }

    @Test
    fun `a link to the server's local address opens only at home`() = runTest {
        val link = repository(mapOf("party/url" to "\"http://192.168.1.20:8095/?join=ABC\"")).joinLink()!!

        assertThat(link.homeNetworkOnly).isTrue()
    }

    @Test
    fun `the target queue is read as given, and null when there is none`() = runTest {
        assertThat(repository(mapOf("party/player" to "\"media_player.kitchen\"")).targetQueueId())
            .isEqualTo("media_player.kitchen")
        assertThat(repository(mapOf("party/player" to "null")).targetQueueId()).isNull()
    }

    @Test
    fun `the config is read for its name, text and mode only`() = runTest {
        val config = """
            {"party_name": "Road trip", "qr_text": "Scan to add songs", "mode": "venue",
             "request_badge_color": "#2D6A4F", "a_field_from_a_newer_server": 1}
        """

        val info = repository(mapOf("party/config" to config)).info()

        assertThat(info).isEqualTo(PartyInfo(name = "Road trip", qrText = "Scan to add songs", mode = PartyMode.VENUE))
    }

    @Test
    fun `a config without a name and with an unknown mode still reads`() = runTest {
        val info = repository(mapOf("party/config" to """{"party_name": null, "qr_text": "", "mode": "arena"}""")).info()

        assertThat(info).isEqualTo(PartyInfo(name = null, qrText = null, mode = PartyMode.UNKNOWN))
    }

    @Test
    fun `the silent disco mode is recognised`() = runTest {
        assertThat(repository(mapOf("party/config" to """{"mode": "remote"}""")).info().mode).isEqualTo(PartyMode.REMOTE)
    }
}
