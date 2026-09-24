package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.PlayMediaArgs
import net.asksakis.massdroidv2.domain.model.PlaylistSortKey
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import org.junit.Test

/**
 * Which listing orders may be handed to the server instead of an expanded track list.
 *
 * The gate exists because `sort_by` arrived in schema 63 and an older server does not refuse
 * it: `parse_arguments` defaults to `strict = False` and drops arguments it does not know. An
 * ungated call would leave that server playing its own order while the screen showed another,
 * with nothing to say so.
 */
class PlaylistServerSortTest {

    private fun repository(schema: Int?): MusicRepositoryImpl {
        val ws = mockk<MaWebSocketClient>()
        every { ws.serverSchemaVersion() } returns schema
        // Read once while the repository is constructed, to build its media-item update flow.
        every { ws.events } returns MutableSharedFlow()
        return MusicRepositoryImpl(
            wsClient = ws,
            imageResolver = mockk<ImageUrlResolver>(relaxed = true),
            json = Json,
            playerRepository = dagger.Lazy { mockk<PlayerRepository>(relaxed = true) }
        )
    }

    private fun supports(schema: Int?, key: PlaylistSortKey) =
        repository(schema).supportsServerSideSort(key)

    @Test
    fun `an order that needs the argument is refused below schema 63`() {
        for (key in PlaylistSortKey.entries.filter { it.serverKey != null }) {
            assertThat(supports(62, key)).isFalse()
        }
    }

    @Test
    fun `the playlist's own order is accepted on any server, because it asks for nothing`() {
        // Gating this one too sent the whole track list on Music Assistant 2.9 for the
        // default sort, which is the case that took the container path on every version.
        assertThat(supports(62, PlaylistSortKey.POSITION)).isTrue()
        assertThat(supports(null, PlaylistSortKey.POSITION)).isTrue()
    }

    @Test
    fun `an unknown schema is treated as too old for the argument`() {
        assertThat(supports(null, PlaylistSortKey.NAME)).isFalse()
    }

    @Test
    fun `every order the app offers is one the server can produce`() {
        // The orders on offer were aligned with `sort_tracks` so that no listing forces the
        // playlist to be sent track by track. A new key without a server counterpart, or a
        // reverse of name, artist or album, would quietly bring that path back.
        for (key in PlaylistSortKey.entries) {
            assertThat(supports(63, key)).isTrue()
        }
    }

    @Test
    fun `the playlist's own order needs no argument, and the rest name a server key`() {
        assertThat(PlaylistSortKey.POSITION.serverKey).isNull()
        assertThat(
            PlaylistSortKey.entries.filter { it != PlaylistSortKey.POSITION }.map { it.serverKey }
        ).containsExactly("position_desc", "name", "artist", "album", "duration", "duration_desc")
    }

    @Test
    fun `sort_by is left out of the command entirely when there is none to send`() {
        val args = PlayMediaArgs(queueId = "q", mediaUris = listOf("library://playlist/50"))

        assertThat(args.toJson().containsKey("sort_by")).isFalse()
    }

    @Test
    fun `sort_by reaches the server under the name the server uses`() {
        val args = PlayMediaArgs(
            queueId = "q",
            mediaUris = listOf("library://playlist/50"),
            option = "replace",
            sortBy = "position_desc"
        )

        assertThat(args.toJson()["sort_by"].toString()).isEqualTo("\"position_desc\"")
    }

    @Test
    fun `start_item is left out unless a blocked head has to be skipped`() {
        val args = PlayMediaArgs(queueId = "q", mediaUris = listOf("library://playlist/50"))

        assertThat(args.toJson().containsKey("start_item")).isFalse()
    }

    @Test
    fun `start_item names the track the container should begin at`() {
        // Sent instead of expanding the playlist, so that a blocked artist at the head is
        // never heard while the container still resolves in one pass.
        val args = PlayMediaArgs(
            queueId = "q",
            mediaUris = listOf("library://playlist/50"),
            sortBy = "name",
            startItem = "deezer--x://track/123"
        )
        val json = args.toJson()

        assertThat(json["start_item"].toString()).isEqualTo("\"deezer--x://track/123\"")
        assertThat(json["sort_by"].toString()).isEqualTo("\"name\"")
    }
}
