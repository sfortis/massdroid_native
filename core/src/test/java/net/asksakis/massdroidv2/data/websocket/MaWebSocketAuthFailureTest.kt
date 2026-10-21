package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Only a sign-in the server refused may clear the saved token. A timeout or a dropped socket
 * during the exchange used to sign the user out after a network handover or a server restart.
 */
class MaWebSocketAuthFailureTest {

    @Test
    fun `a timed-out sign-in keeps the token`() {
        val e = MaApiException("Request timed out", MaApiException.TIMEOUT_CODE)
        assertThat(isTransientAuthFailure(e)).isTrue()
    }

    @Test
    fun `a sign-in cut off by a lost connection keeps the token`() {
        val e = MaApiException("Connection lost", MaApiException.CONNECTION_LOST_CODE)
        assertThat(isTransientAuthFailure(e)).isTrue()
    }

    @Test
    fun `a sign-in the server refused clears the token`() {
        assertThat(isTransientAuthFailure(MaApiException("Invalid token", 20))).isFalse()
        assertThat(isTransientAuthFailure(MaApiException("Login failed", -1))).isFalse()
    }

    @Test
    fun `an unexpected error is treated as a refusal, as before`() {
        assertThat(isTransientAuthFailure(IllegalStateException("parse error"))).isFalse()
    }
}
