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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaylistSortKey
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import org.junit.Test

/**
 * Commands whose form depends on the server version.
 *
 * Users run many Music Assistant versions, so each newer form is sent only to a server whose
 * schema has it, and an older server keeps the form it understands. Radio became a
 * `radio_playlist://` item per seed in schema 63 (MA 2.10.0); a per-user dislike is certain
 * from schema 85 (MA 2.11.0b4); a playlist is sorted by its listing's `sort_field` from schema 95
 * (MA 2.11, PR #7004).
 */
class ServerFeatureGateTest {

    private val sent = mutableListOf<Pair<String, JsonObject?>>()

    private fun repository(schema: Int?, version: String? = null): MusicRepositoryImpl {
        val ws = mockk<MaWebSocketClient>()
        every { ws.serverSchemaVersion() } returns schema
        every { ws.serverVersion() } returns version
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

    @Test
    fun `a server below schema 85 is not asked to record a dislike`() = runTest {
        val recorded = repository(80).dislikeOnServer("deezer://track/5")

        assertThat(recorded).isFalse()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a dislike is sent as favorite false on the item`() = runTest {
        val recorded = repository(85).dislikeOnServer("deezer://track/5")

        assertThat(recorded).isTrue()
        val (command, args) = sent.single()
        assertThat(command).isEqualTo("music/favorites/set_item")
        assertThat(args!!["item"]!!.jsonPrimitive.content).isEqualTo("deezer://track/5")
        assertThat(args["favorite"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `undo restores a like on a track that was a favorite, and clears otherwise`() = runTest {
        val repo = repository(91)

        repo.undoServerDislike("deezer://track/5", wasFavorite = true)
        repo.undoServerDislike("deezer://track/6", wasFavorite = false)

        assertThat(sent.map { it.second!!["favorite"] }).containsExactly(
            kotlinx.serialization.json.JsonPrimitive(true),
            JsonNull,
        ).inOrder()
    }

    @Test
    fun `undo sends nothing to a server that could not have recorded the dislike`() = runTest {
        val repo = repository(65)

        repo.undoServerDislike("deezer://track/5", wasFavorite = true)

        assertThat(sent).isEmpty()
    }

    @Test
    fun `a 2_10 server is asked for a sorted playlist with sort_by`() = runTest {
        repository(65, "2.10.7").playMedia("q", "library://playlist/50", "replace", sortKey = PlaylistSortKey.ARTIST)

        val args = sent.single().second!!
        assertThat(args["sort_by"]!!.jsonPrimitive.content).isEqualTo("artist")
        assertThat(args.containsKey("sort_field")).isFalse()
    }

    @Test
    fun `a 2_11 server is asked for the listing sort instead of the deprecated sort_by`() = runTest {
        repository(99, "2.11.0b7").playMedia(
            "q", "library://playlist/50", "replace", sortKey = PlaylistSortKey.DURATION_DESC
        )

        val args = sent.single().second!!
        assertThat(args["sort_field"]!!.jsonPrimitive.content).isEqualTo("duration")
        assertThat(args["sort_direction"]!!.jsonPrimitive.content).isEqualTo("desc")
        assertThat(args.containsKey("sort_by")).isFalse()
    }

    @Test
    fun `the playlist's own order sends no sort on any server`() = runTest {
        repository(99, "2.11.0b7").playMedia("q", "library://playlist/50", "replace", sortKey = PlaylistSortKey.POSITION)
        repository(65, "2.10.7").playMedia("q", "library://playlist/50", "replace", sortKey = PlaylistSortKey.POSITION)

        for ((_, args) in sent) {
            assertThat(args!!.keys).containsNoneOf("sort_by", "sort_field", "sort_direction")
        }
    }

    @Test
    fun `a 2_11 server sorts the playlist listing and returns all of it`() = runTest {
        // Without a limit the 2.11 listing stops at 500 tracks, while the queue plays them all.
        repository(99, "2.11.0b7").getPlaylistTracks("50", "library", sortKey = PlaylistSortKey.NAME)

        val (command, args) = sent.single()
        assertThat(command).isEqualTo("music/playlists/playlist_tracks")
        assertThat(args!!["sort_field"]!!.jsonPrimitive.content).isEqualTo("name")
        assertThat(args["sort_direction"]!!.jsonPrimitive.content).isEqualTo("asc")
        assertThat(args["limit"]!!.jsonPrimitive.content).isEqualTo("0")
    }

    @Test
    fun `an older server gets the playlist listing request it has always had`() = runTest {
        // It sorts nothing itself; the app orders the answer under the server's rules.
        repository(65, "2.10.7").getPlaylistTracks("50", "library", sortKey = PlaylistSortKey.NAME)

        assertThat(sent.single().second!!.keys).containsNoneOf("sort_field", "sort_direction", "limit")
    }

    @Test
    fun `a 2_11 listing asked for without an order keeps the server's default`() = runTest {
        // Android Auto and the playlist membership check read playlists this way; they keep the
        // capped default rather than loading every track of a large playlist.
        repository(99, "2.11.0b7").getPlaylistTracks("50", "library")

        assertThat(sent.single().second!!.keys).containsNoneOf("sort_field", "sort_direction", "limit")
    }
}
