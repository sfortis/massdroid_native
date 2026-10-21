package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import org.junit.Test

/**
 * When a replace is marked, relative to the `play_media` that performs it.
 *
 * The server announces the new current track before it answers the call (2 ms apart on
 * 2026-10-21). The mark used to be set after the answer, so it landed on the mix's first track:
 * the track the mix interrupted was recorded as a full play and the mix's first track never was.
 */
class PlayMediaReplacementMarkTest {

    private val events = mutableListOf<String>()
    private val players = mockk<PlayerRepository>(relaxed = true).also {
        every { it.selectedPlayer } returns MutableStateFlow(null)
        every { it.notifyQueueReplacement(any()) } answers { events += "mark" }
        every { it.withdrawQueueReplacement(any()) } answers { events += "withdraw" }
    }

    private fun repository(failure: Exception? = null): MusicRepositoryImpl {
        val ws = mockk<MaWebSocketClient>()
        every { ws.serverSchemaVersion() } returns 65
        every { ws.events } returns MutableSharedFlow()
        coEvery { ws.sendCommand(any(), any(), any(), any(), any()) } answers {
            events += "send"
            failure?.let { throw it }
            null
        }
        return MusicRepositoryImpl(
            wsClient = ws,
            imageResolver = mockk<ImageUrlResolver>(relaxed = true),
            json = Json,
            playerRepository = dagger.Lazy { players }
        )
    }

    @Test
    fun `an awaited replace is marked before the command goes out`() = runTest {
        repository().playMedia("q", listOf("deezer://track/1"), "replace", awaitResponse = true)

        assertThat(events).containsExactly("mark", "send").inOrder()
    }

    @Test
    fun `a refused replace takes its mark back`() = runTest {
        val refusal = MaApiException("year must be in 1..9999, not 0", code = 999)

        runCatching { repository(refusal).playMedia("q", listOf("deezer://track/1"), "replace", awaitResponse = true) }

        assertThat(events).containsExactly("mark", "send", "withdraw").inOrder()
    }

    @Test
    fun `a replace that timed out keeps its mark, because it may have landed`() = runTest {
        val timeout = MaApiException("no answer", code = MaApiException.TIMEOUT_CODE)

        runCatching { repository(timeout).playMedia("q", listOf("deezer://track/1"), "replace", awaitResponse = true) }

        verify(exactly = 0) { players.withdrawQueueReplacement(any()) }
    }

    @Test
    fun `an add is not a replace and marks nothing`() = runTest {
        repository().playMedia("q", listOf("deezer://track/1"), "add", awaitResponse = true)

        assertThat(events).containsExactly("send")
    }
}
