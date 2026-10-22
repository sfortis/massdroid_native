package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

/**
 * Pins what counts as a live socket when it is probed after a network switch.
 *
 * Only a missing answer may close the connection, because closing it restarts Sendspin and
 * cuts the audio. A refusal from the server proves the socket works as well as a result does.
 */
class HandoverProbeVerdictTest {

    @Test
    fun `an answer keeps the connection`() {
        assertThat(isAnswerFromServer(null)).isTrue()
    }

    @Test
    fun `a refusal from the server keeps the connection`() {
        val refusal = MaApiException("not allowed", MaApiException.INSUFFICIENT_PERMISSIONS_CODE)
        assertThat(isAnswerFromServer(refusal)).isTrue()
    }

    @Test
    fun `no answer before the deadline drops the connection`() {
        val timeout = MaApiException("Request timed out", MaApiException.TIMEOUT_CODE)
        assertThat(isAnswerFromServer(timeout)).isFalse()
    }

    @Test
    fun `a socket that went away drops the connection`() {
        val lost = MaApiException("Connection lost", MaApiException.CONNECTION_LOST_CODE)
        assertThat(isAnswerFromServer(lost)).isFalse()
    }

    @Test
    fun `any other failure drops the connection`() {
        assertThat(isAnswerFromServer(IOException("broken pipe"))).isFalse()
    }
}
