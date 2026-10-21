package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import org.junit.Test

/**
 * Commands whose form depends on the server version.
 *
 * Users run many Music Assistant versions, so each newer form is sent only to a server whose
 * schema has it, and an older server keeps the form it understands. Radio became a
 * `radio_playlist://` item per seed in schema 63 (MA 2.10.0).
 */
class ServerFeatureGateTest {

    private val sent = mutableListOf<Pair<String, JsonObject?>>()

    private fun repository(schema: Int?): MusicRepositoryImpl {
        val ws = mockk<MaWebSocketClient>()
        every { ws.serverSchemaVersion() } returns schema
        every { ws.events } returns MutableSharedFlow()
        val command = slot<String>()
        val args = slot<JsonObject>()
        coEvery { ws.sendCommand(capture(command), capture(args), any(), any(), any()) } answers {
            sent += command.captured to args.captured
            null
        }
        // play_media reads the selected player afterwards; a relaxed mock's flow would hold an Object.
        val players = mockk<PlayerRepository>(relaxed = true)
        every { players.selectedPlayer } returns MutableStateFlow(null)
        return MusicRepositoryImpl(
            wsClient = ws,
            imageResolver = mockk<ImageUrlResolver>(relaxed = true),
            json = Json,
            playerRepository = dagger.Lazy { players }
        )
    }

    @Test
    fun `radio is sent as Endless Mix playlists from schema 63`() = runTest {
        repository(63).playMedia("q", listOf("library://artist/1", "deezer://artist/2"), "replace", radioMode = true)

        val (command, args) = sent.single()
        assertThat(command).isEqualTo("player_queues/play_media")
        assertThat(args!!["media"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly(
            "radio_playlist://playlist/library://artist/1",
            "radio_playlist://playlist/deezer://artist/2",
        ).inOrder()
        assertThat(args.containsKey("radio_mode")).isFalse()
    }

    @Test
    fun `an older server keeps radio_mode and the plain seeds`() = runTest {
        repository(62).playMedia("q", listOf("library://artist/1"), "replace", radioMode = true)

        val args = sent.single().second!!
        assertThat(args["media"]!!.jsonArray.single().jsonPrimitive.content).isEqualTo("library://artist/1")
        assertThat(args["radio_mode"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun `a track list that is not radio is sent untouched on a new server`() = runTest {
        repository(91).playMedia("q", listOf("deezer://track/5"), "replace")

        val args = sent.single().second!!
        assertThat(args["media"]!!.jsonArray.single().jsonPrimitive.content).isEqualTo("deezer://track/5")
        assertThat(args.containsKey("radio_mode")).isFalse()
    }
}
