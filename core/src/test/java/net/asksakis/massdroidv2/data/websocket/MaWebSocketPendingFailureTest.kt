package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * What a command in flight is told when the socket goes away.
 *
 * Measured in the field on 2026-09-20: with WiFi and mobile data both switched
 * off, a search sat spinning for 27 seconds and then displayed "no results". The
 * server had never been asked. `failAllPending` completed the waiting request with
 * `null`, and `null` is how the server says "done, nothing to return", so
 * `MusicRepositoryImpl.search` turned a dropped command into an empty
 * `SearchResult` and the screen reported it as an answer.
 *
 * The distinction these tests lock down is therefore not cosmetic. Every caller
 * that maps `null` onto an empty list or an absent item depends on `null` meaning
 * the server spoke.
 */
class MaWebSocketPendingFailureTest {

    private fun client() = MaWebSocketClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

    @Test
    fun `a command waiting when the connection drops is failed, not answered with null`() {
        val client = client()
        val pending = CompletableDeferred<JsonElement?>()
        client.pendingRequests["msg-1"] = pending

        client.failAllPending("Connection lost")

        assertThat(pending.isCompleted).isTrue()
        val error = runCatching { runBlocking { pending.await() } }.exceptionOrNull()
        assertThat(error).isInstanceOf(MaApiException::class.java)
        assertThat((error as MaApiException).isConnectionLost).isTrue()
    }

    @Test
    fun `the reason is carried into the message so a transport command can retry on it`() {
        val client = client()
        val pending = CompletableDeferred<JsonElement?>()
        client.pendingRequests["msg-2"] = pending

        client.failAllPending("Connection closed")

        val error = runCatching { runBlocking { pending.await() } }.exceptionOrNull()
        assertThat(error).hasMessageThat().isEqualTo("Connection closed")
    }

    @Test
    fun `every waiting command is failed, not only the first`() {
        val client = client()
        val first = CompletableDeferred<JsonElement?>()
        val second = CompletableDeferred<JsonElement?>()
        client.pendingRequests["msg-3"] = first
        client.pendingRequests["msg-4"] = second

        client.failAllPending("Disconnected")

        // isCompleted alone would pass against the old `complete(null)` too, so
        // assert on what each one actually carries.
        listOf(first, second).forEach { deferred ->
            val error = runCatching { runBlocking { deferred.await() } }.exceptionOrNull()
            assertThat(error).isInstanceOf(MaApiException::class.java)
            assertThat((error as MaApiException).isConnectionLost).isTrue()
        }
        assertThat(client.pendingRequests).isEmpty()
    }

    @Test
    fun `a lost connection is distinguishable from a timeout`() {
        val lost = MaApiException("Connection lost", MaApiException.CONNECTION_LOST_CODE)
        val timedOut = MaApiException("Request timed out", MaApiException.TIMEOUT_CODE)

        assertThat(lost.isConnectionLost).isTrue()
        assertThat(lost.isTimeout).isFalse()
        assertThat(timedOut.isTimeout).isTrue()
        assertThat(timedOut.isConnectionLost).isFalse()
    }

    /**
     * A server error must keep reading as a server error. The retry helper in
     * `PlayerRepositoryImpl` resends only on a lost connection, so widening the
     * check to any failure would make it repeat commands the server has refused.
     */
    @Test
    fun `a refusal from the server is not reported as a lost connection`() {
        val refused = MaApiException("The requested media item could not be found.", 2)

        assertThat(refused.isConnectionLost).isFalse()
        assertThat(refused.isTimeout).isFalse()
    }
}
