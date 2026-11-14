package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private val instancesRead = MutableStateFlow(0)
    private var readsStarted = 0
    private val sent = mutableListOf<Pair<String, JsonObject?>>()

    private fun repository(replies: Map<String, String> = emptyMap()): PartyRepositoryImpl {
        val ws = mockk<MaWebSocketClient>()
        coEvery { ws.sendCommand(any(), any(), any(), any(), any()) } answers {
            sent += firstArg<String>() to secondArg<JsonObject?>()
            replies[firstArg<String>()]?.let { Json.parseToJsonElement(it) } as JsonElement?
        }
        val cache = mockk<ProviderManifestCache>()
        every { cache.loadedDomains } returns domains
        every { cache.instancesRead } returns instancesRead
        every { cache.instanceReadsStarted } answers { readsStarted }
        every { cache.instanceIdOf("party") } answers { "party--abc".takeIf { "party" in domains.value } }
        return PartyRepositoryImpl(ws, cache)
    }

    private val playerConfig = """
        {"values": {"player": {"value": "kitchen", "default_value": "__auto__",
          "options": [{"value": "__auto__", "title": "Auto"}, {"value": "kitchen", "title": "Kitchen"}]}}}
    """

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
    fun `an admin reads the party player setting of the loaded plugin instance`() = runTest {
        domains.value = setOf("party")
        val repository = repository(
            mapOf("auth/me" to """{"role": "admin"}""", "config/providers/get" to playerConfig)
        )

        val setting = repository.playerSetting()!!

        assertThat(setting.value).isEqualTo("kitchen")
        assertThat(sent.last()).isEqualTo(
            "config/providers/get" to buildJsonObject { put("instance_id", "party--abc") }
        )
    }

    @Test
    fun `an account that is not an admin gets no setting to change`() = runTest {
        domains.value = setOf("party")
        val repository = repository(
            mapOf("auth/me" to """{"role": "user"}""", "config/providers/get" to playerConfig)
        )

        assertThat(repository.playerSetting()).isNull()
        assertThat(sent.map { it.first }).doesNotContain("config/providers/get")
    }

    @Test
    fun `saving the party player sends only that key to the plugin instance`() = runTest {
        domains.value = setOf("party")
        val repository = repository(mapOf("config/providers/save" to "{}"))
        val done = async { repository.setPlayer("car-id") }
        runCurrent()

        instancesRead.value = 1
        done.await()

        assertThat(sent.last()).isEqualTo(
            "config/providers/save" to buildJsonObject {
                put("provider_domain", "party")
                put("instance_id", "party--abc")
                put("values", buildJsonObject { put("player", "car-id") })
            }
        )
    }

    @Test
    fun `party stays available while the save waits for the plugin's reload`() = runTest {
        domains.value = setOf("party")
        val repository = repository(mapOf("config/providers/save" to "{}"))
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.available.collect { seen += it } }

        val done = async { repository.setPlayer("car-id") }
        runCurrent()
        // A read of the plugin list in the middle of the reload misses the plugin.
        domains.value = emptySet()
        instancesRead.value = 1
        runCurrent()
        assertThat(done.isCompleted).isFalse()
        domains.value = setOf("party")
        instancesRead.value = 2
        done.await()

        assertThat(seen).doesNotContain(false)
    }

    @Test
    fun `a reload that is never seen releases the hold after the bound`() = runTest {
        domains.value = setOf("party")
        val repository = repository(mapOf("config/providers/save" to "{}"))
        val done = async { repository.setPlayer("car-id") }
        runCurrent()
        domains.value = emptySet()

        advanceUntilIdle()

        assertThat(done.isCompleted).isTrue()
        assertThat(repository.available.first()).isFalse()
    }

    @Test
    fun `saving the value the server already stores sends no save and holds nothing`() = runTest {
        domains.value = setOf("party")
        val repository = repository(mapOf("config/providers/get" to playerConfig, "config/providers/save" to "{}"))

        // Completes without a read of the plugin list: the server would not reload the plugin.
        repository.setPlayer("kitchen")

        assertThat(sent.map { it.first }).doesNotContain("config/providers/save")
        domains.value = emptySet()
        assertThat(repository.available.first()).isFalse()
    }

    @Test
    fun `a read of the plugin list that started before the save was answered does not end the wait`() = runTest {
        domains.value = setOf("party")
        val repository = repository(mapOf("config/providers/save" to "{}"))
        readsStarted = 1
        val done = async { repository.setPlayer("car-id") }
        runCurrent()

        // The read in flight during the save lists the plugin as it was before the reload.
        instancesRead.value = 1
        runCurrent()
        assertThat(done.isCompleted).isFalse()

        readsStarted = 2
        instancesRead.value = 2
        done.await()
    }

    @Test
    fun `party stays available until the last of two overlapping saves has seen the reload`() = runTest {
        domains.value = setOf("party")
        val repository = repository(mapOf("config/providers/save" to "{}"))
        val first = async { repository.setPlayer("car-id") }
        runCurrent()
        readsStarted = 1
        val second = async { repository.setPlayer("kitchen") }
        runCurrent()

        // A read started between the two saves ends the first wait only.
        instancesRead.value = 1
        runCurrent()
        assertThat(first.isCompleted).isTrue()
        assertThat(second.isCompleted).isFalse()
        domains.value = emptySet()
        assertThat(repository.available.first()).isTrue()

        readsStarted = 2
        domains.value = setOf("party")
        instancesRead.value = 2
        second.await()
    }

    @Test
    fun `a refused save is an error and does not hold party`() = runTest {
        domains.value = setOf("party")
        val repository = repository()

        val result = runCatching { repository.setPlayer("car-id") }

        assertThat(result.isFailure).isTrue()
        domains.value = emptySet()
        assertThat(repository.available.first()).isFalse()
    }

    @Test
    fun `the silent disco mode is recognised`() = runTest {
        assertThat(repository(mapOf("party/config" to """{"mode": "remote"}""")).info().mode).isEqualTo(PartyMode.REMOTE)
    }
}
