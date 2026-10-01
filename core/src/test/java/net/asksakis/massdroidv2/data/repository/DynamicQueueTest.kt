package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.image.ImageUrlResolver
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ServerQueue
import org.junit.Test

/**
 * A queue the server keeps filled itself, and the two controls it locks.
 *
 * Music Assistant 2.10 refuses `player_queues/shuffle` and `player_queues/repeat` while a
 * queue is dynamic, which it becomes when one of its sources feeds it on demand: a smart
 * playlist or a radio. The app did not read `is_dynamic` at all, so it offered both
 * toggles, the server refused every press, and the refusal reached the listener as "Not
 * connected to server" while the music kept playing.
 *
 * Don't Stop the Music is a separate flag and does not make a queue dynamic. Both were on
 * in the captured payload below, which is how the first fix came to blame the wrong one.
 *
 * The payload below is verbatim from `player_queues/get` on the production server on
 * 2026-09-30, trimmed to the fields this test is about.
 */
class DynamicQueueTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun resolver(): ImageUrlResolver {
        val ws = mockk<MaWebSocketClient>(relaxed = true)
        every { ws.externalServerUrl() } returns "https://example.invalid"
        every { ws.serverSchemaVersion() } returns 65
        every { ws.isOffLanImageHost(any()) } returns false
        return ImageUrlResolver(ws)
    }

    private fun queue(payload: String) =
        json.decodeFromString<ServerQueue>(payload).toDomain(resolver())

    @Test
    fun `a queue filled by the server is read as dynamic`() {
        val state = queue(
            """
            {
              "queue_id": "upsendspinclisendspinpi",
              "shuffle_enabled": true,
              "is_dynamic": true,
              "dont_stop_the_music_enabled": true,
              "repeat_mode": "off",
              "items": 69,
              "current_index": 46
            }
            """.trimIndent()
        )

        assertThat(state.isDynamic).isTrue()
        assertThat(state.autoplayEnabled).isTrue()
    }

    @Test
    fun `an ordinary queue is not dynamic`() {
        val state = queue(
            """
            {
              "queue_id": "player1",
              "shuffle_enabled": false,
              "is_dynamic": false,
              "repeat_mode": "all",
              "items": 12
            }
            """.trimIndent()
        )

        assertThat(state.isDynamic).isFalse()
    }

    @Test
    fun `a server too old to send the key leaves both controls working`() {
        val state = queue("""{"queue_id": "player1", "items": 3}""")

        assertThat(state.isDynamic).isFalse()
    }
}
