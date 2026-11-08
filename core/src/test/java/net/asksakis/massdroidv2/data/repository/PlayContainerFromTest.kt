package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import org.junit.Test

/**
 * A track tapped in an album or a regular playlist starts the container there (issue #84).
 * Played as a bare track it left a queue of one.
 */
class PlayContainerFromTest {

    private val album = "library://album/12"
    private val track = "library://track/7"

    /** Every play_media sent, as the arguments the server receives. */
    private val sent = mutableListOf<JsonObject>()

    private fun repository(
        blockedArtists: Boolean = false,
        firstAnswer: Exception? = null
    ): Pair<MusicRepositoryImpl, MaWebSocketClient> {
        val players = mockk<PlayerRepository>(relaxed = true).also {
            every { it.selectedPlayer } returns MutableStateFlow(null)
            every { it.hasBlockedArtists() } returns blockedArtists
        }
        val ws = mockk<MaWebSocketClient>()
        every { ws.serverSchemaVersion() } returns 65
        every { ws.events } returns MutableSharedFlow()
        coEvery { ws.sendCommand(MaCommands.PlayerQueues.PLAY_MEDIA, any(), any(), any(), any()) } answers {
            sent += secondArg<JsonObject>()
            if (sent.size == 1) firstAnswer?.let { throw it }
            null
        }
        val repository = MusicRepositoryImpl(
            wsClient = ws,
            imageResolver = mockk<ImageUrlResolver>(relaxed = true),
            json = Json,
            playerRepository = dagger.Lazy { players }
        )
        return repository to ws
    }

    private fun JsonObject.media(): List<String> = getValue("media").jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content

    @Test
    fun `the container replaces the queue and starts at the tapped track`() = runTest {
        val (repository, _) = repository()

        repository.playContainerFrom("q", album, track)

        val args = sent.single()
        assertThat(args.media()).containsExactly(album)
        assertThat(args.string("start_item")).isEqualTo(track)
        assertThat(args.string("option")).isEqualTo("replace")
    }

    /** The blocked-artist plan picks a start item of its own and must not replace the tap. */
    @Test
    fun `blocked artists do not move the start away from the tapped track`() = runTest {
        val (repository, ws) = repository(blockedArtists = true)

        repository.playContainerFrom("q", album, track)

        assertThat(sent.single().string("start_item")).isEqualTo(track)
        coVerify(exactly = 0) { ws.sendCommand(MaCommands.Music.ALBUM_TRACKS, any(), any(), any(), any()) }
    }

    @Test
    fun `a start the server cannot find plays the track on its own`() = runTest {
        val notFound = MaApiException("No playable items found", MaApiException.MEDIA_NOT_FOUND_CODE)
        val (repository, _) = repository(firstAnswer = notFound)

        repository.playContainerFrom("q", album, track)

        assertThat(sent).hasSize(2)
        assertThat(sent[1].media()).containsExactly(track)
        assertThat(sent[1].string("start_item")).isNull()
        assertThat(sent[1].string("option")).isEqualTo("replace")
    }

    /** A timeout may have started the container already, so nothing is sent after it. */
    @Test
    fun `a timeout is not answered with a second play`() = runTest {
        val timeout = MaApiException("no answer", MaApiException.TIMEOUT_CODE)
        val (repository, _) = repository(firstAnswer = timeout)

        val failure = runCatching { repository.playContainerFrom("q", album, track) }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(timeout)
        assertThat(sent).hasSize(1)
    }
}
