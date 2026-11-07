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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.provider.ProviderManifestCache
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.SeedAddOutcome
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeed
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeedKind
import org.junit.Test

/**
 * The Smart Playlists plugin's commands as the app sends them: a seed is added by reading
 * the rules and writing them back whole, and nothing is written when the seed is not added.
 */
class SmartPlaylistRepositoryImplTest {

    private data class Sent(val command: String, val args: JsonObject?, val retryAfterTimeout: Boolean)

    private val sent = mutableListOf<Sent>()
    private val domains = MutableStateFlow(setOf("spotify", "smart_playlist"))
    private val seed = SmartPlaylistSeed(SmartPlaylistSeedKind.TRACK, "library://track/7", "Miracle")

    private fun repository(replies: Map<String, String>): SmartPlaylistRepositoryImpl {
        val ws = mockk<MaWebSocketClient>()
        coEvery { ws.sendCommand(any(), any(), any(), any(), any()) } answers {
            val command = firstArg<String>()
            sent += Sent(command, secondArg(), arg(4))
            replies[command]?.let { Json.parseToJsonElement(it) } as JsonElement?
        }
        val cache = mockk<ProviderManifestCache>()
        every { cache.loadedDomains } returns domains
        return SmartPlaylistRepositoryImpl(ws, cache)
    }

    private val rules = """
        {"seed_track_uris": ["library://track/1"], "seed_names": {"library://track/1": "First"},
         "limit": 50, "a_field_from_a_newer_server": true}
    """

    @Test
    fun `update_rules writes the server's rules back whole with the new seed`() = runTest {
        val outcome = repository(mapOf("smart_playlists/get_rules" to rules)).addSeed("uuid-1", seed)

        assertThat(outcome).isEqualTo(SeedAddOutcome.ADDED)
        val update = sent.single { it.command == "smart_playlists/update_rules" }.args!!
        assertThat(update["playlist_id"]!!.jsonPrimitive.content).isEqualTo("uuid-1")
        val written = update["rules"]!!.jsonObject
        assertThat(written["limit"]!!.jsonPrimitive.content).isEqualTo("50")
        assertThat(written["a_field_from_a_newer_server"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(written["seed_track_uris"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("library://track/1", "library://track/7").inOrder()
    }

    @Test
    fun `a seed already there is not written`() = runTest {
        val present = """{"seed_track_uris": ["library://track/7"]}"""

        val outcome = repository(mapOf("smart_playlists/get_rules" to present)).addSeed("uuid-1", seed)

        assertThat(outcome).isEqualTo(SeedAddOutcome.ALREADY_PRESENT)
        assertThat(sent.map { it.command }).containsExactly("smart_playlists/get_rules")
    }

    @Test
    fun `a full playlist is not written`() = runTest {
        val full = """{"seed_artist_uris": ["a1","a2","a3","a4","a5","a6","a7","a8","a9","a10"]}"""

        val outcome = repository(mapOf("smart_playlists/get_rules" to full)).addSeed("uuid-1", seed)

        assertThat(outcome).isEqualTo(SeedAddOutcome.FULL)
        assertThat(sent.map { it.command }).containsExactly("smart_playlists/get_rules")
    }

    @Test
    fun `only seed-based smart playlists are listed`() = runTest {
        val list = """
            [{"playlist_id": "b", "name": "Seeds", "rules": {"seed_artist_uris": ["x", "y"]}},
             {"playlist_id": "a", "name": "House filter", "rules": {"genres": ["House"], "seed_track_uris": []}}]
        """

        val listed = repository(mapOf("smart_playlists/list" to list)).seedPlaylists()

        assertThat(listed.map { it.playlistId to it.seedCount }).containsExactly("b" to 2)
    }

    /** A create that timed out may have run; repeating it would leave two playlists. */
    @Test
    fun `create sends one seed list, is dynamic, and is not repeated after a timeout`() = runTest {
        repository(emptyMap()).createWithSeed("Night drive", seed)

        val create = sent.single()
        assertThat(create.command).isEqualTo("smart_playlists/create")
        assertThat(create.retryAfterTimeout).isFalse()
        assertThat(create.args!!["name"]!!.jsonPrimitive.content).isEqualTo("Night drive")
        assertThat(create.args["is_dynamic"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(create.args["rules"]!!.jsonObject.keys).containsExactly("seed_track_uris", "seed_names")
    }

    @Test
    fun `the feature is available only while the plugin is loaded`() = runTest {
        val repo = repository(emptyMap())
        assertThat(repo.available.first()).isTrue()

        domains.value = setOf("spotify")
        assertThat(repo.available.first()).isFalse()
    }
}
