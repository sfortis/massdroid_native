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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PlaylistSortKey
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import org.junit.Test

/**
 * A track tapped in its album or playlist on the phone follows the server's default for a
 * track: a default that starts the track now plays the container from it on, and one that
 * only queues it leaves the tap a single track, as it always was.
 */
class PlayTappedTrackTest {

    private val playlist = "library://playlist/50"
    private val track = "library://track/7"

    /** Every play_media sent, as the arguments the server receives. */
    private val sent = mutableListOf<JsonObject>()

    private fun repository(
        trackDefault: String?,
        configFailure: Exception? = null,
        firstPlayAnswer: Exception? = null
    ): Pair<MusicRepositoryImpl, MaWebSocketClient> {
        val players = mockk<PlayerRepository>(relaxed = true).also {
            every { it.selectedPlayer } returns MutableStateFlow(null)
            every { it.hasBlockedArtists() } returns false
        }
        val ws = mockk<MaWebSocketClient>()
        every { ws.serverSchemaVersion() } returns 65
        every { ws.serverVersion() } returns "2.10.9"
        every { ws.events } returns MutableSharedFlow()
        coEvery { ws.sendCommand(MaCommands.ConfigCore.GET, any(), any(), any(), any()) } answers {
            configFailure?.let { throw it }
            buildJsonObject {
                putJsonObject("values") {
                    putJsonObject("default_enqueue_option_track") {
                        trackDefault?.let { put("value", it) }
                    }
                }
            }
        }
        coEvery { ws.sendCommand(MaCommands.PlayerQueues.PLAY_MEDIA, any(), any(), any(), any()) } answers {
            sent += secondArg<JsonObject>()
            if (sent.size == 1) firstPlayAnswer?.let { throw it }
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

    private fun assertContainerFromTrack(option: String) {
        val args = sent.single()
        assertThat(args.media()).containsExactly(playlist)
        assertThat(args.string("start_item")).isEqualTo(track)
        assertThat(args.string("option")).isEqualTo(option)
    }

    /** Exactly the old tap: the track alone, and the server applies its own default to it. */
    private fun assertTrackAlone() {
        val args = sent.single()
        assertThat(args.media()).containsExactly(track)
        assertThat(args.string("start_item")).isNull()
        assertThat(args.string("option")).isNull()
    }

    @Test
    fun `a play default plays the container from the tapped track with play`() = runTest {
        repository(trackDefault = "play").first.playTappedTrack("q", track, playlist)

        assertContainerFromTrack("play")
    }

    @Test
    fun `a replace default plays the container from the tapped track with replace`() = runTest {
        repository(trackDefault = "replace").first.playTappedTrack("q", track, playlist)

        assertContainerFromTrack("replace")
    }

    @Test
    fun `an add default leaves the tap a single track`() = runTest {
        repository(trackDefault = "add").first.playTappedTrack("q", track, playlist)

        assertTrackAlone()
    }

    @Test
    fun `a next default leaves the tap a single track`() = runTest {
        repository(trackDefault = "next").first.playTappedTrack("q", track, playlist)

        assertTrackAlone()
    }

    @Test
    fun `an unreadable default leaves the tap a single track`() = runTest {
        val unreadable = MaApiException("no answer", MaApiException.TIMEOUT_CODE)

        repository(trackDefault = null, configFailure = unreadable).first.playTappedTrack("q", track, playlist)

        assertTrackAlone()
    }

    /** No container is passed when it would not play in the order on screen. */
    @Test
    fun `without a container the server default is not even read`() = runTest {
        val (repository, ws) = repository(trackDefault = "play")

        repository.playTappedTrack("q", track, containerUri = null)

        assertTrackAlone()
        coVerify(exactly = 0) { ws.sendCommand(MaCommands.ConfigCore.GET, any(), any(), any(), any()) }
    }

    /** MA sorts the container first and then starts it at the start item. */
    @Test
    fun `the order on screen goes with the container`() = runTest {
        repository(trackDefault = "play").first.playTappedTrack("q", track, playlist, PlaylistSortKey.NAME)

        val args = sent.single()
        assertThat(args.string("start_item")).isEqualTo(track)
        assertThat(args.string("sort_by") ?: args.string("sort_field")).isEqualTo("name")
    }

    @Test
    fun `a start the server cannot find plays the track alone with the same option`() = runTest {
        val notFound = MaApiException("No playable items found", MaApiException.MEDIA_NOT_FOUND_CODE)

        repository(trackDefault = "play", firstPlayAnswer = notFound).first.playTappedTrack("q", track, playlist)

        assertThat(sent).hasSize(2)
        assertThat(sent[1].media()).containsExactly(track)
        assertThat(sent[1].string("option")).isEqualTo("play")
    }
}
