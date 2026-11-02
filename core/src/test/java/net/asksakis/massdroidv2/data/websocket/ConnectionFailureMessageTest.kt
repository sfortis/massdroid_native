package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.UnknownHostException
import javax.net.ssl.SSLProtocolException

/**
 * The connection error is the only clue a user gets after typing a wrong address, so the
 * common mistakes must read as their cause, not as OkHttp's wording.
 */
class ConnectionFailureMessageTest {

    @Test
    fun `the Home Assistant address says it is not Music Assistant`() {
        val error = ProtocolException("Expected HTTP 101 response but was '404 Not Found'")
        assertThat(describeConnectionFailure(error, "http://homeassistant.local:8123"))
            .isEqualTo(NOT_MUSIC_ASSISTANT_MESSAGE)
    }

    @Test
    fun `a proxy that cannot reach Music Assistant names the gateway`() {
        val error = ProtocolException("Expected HTTP 101 response but was '502 Bad Gateway'")
        assertThat(describeConnectionFailure(error, "https://ma.example.com")).contains("HTTP 502")
    }

    @Test
    fun `https on a plain http port says so`() {
        val error = SSLProtocolException(
            "Read error: ssl=0x7a: Failure in SSL library, usually a protocol error\n" +
                "error:100000f7:SSL routines:OPENSSL_internal:WRONG_VERSION_NUMBER"
        )
        assertThat(describeConnectionFailure(error, "https://homeassistant.local:8095"))
            .isEqualTo(NOT_HTTPS_MESSAGE)
    }

    @Test
    fun `a refused connection names the host and port`() {
        val error = ConnectException("Failed to connect to homeassistant.local/192.168.1.41:80")
        assertThat(describeConnectionFailure(error, "http://homeassistant.local"))
            .contains("homeassistant.local:80")
    }

    @Test
    fun `a wrapped cause is found`() {
        val error = IOException("outer", UnknownHostException("Unable to resolve host"))
        assertThat(describeConnectionFailure(error, "http://ma.local:8095")).startsWith("Can't find ma.local")
    }

    @Test
    fun `a proxy that drops the upgrade is not called Home Assistant`() {
        val error = ProtocolException("Expected HTTP 101 response but was '400 Bad Request'")
        val message = describeConnectionFailure(error, "https://ma.example.com")
        assertThat(message).isNotEqualTo(NOT_MUSIC_ASSISTANT_MESSAGE)
        assertThat(message).contains("HTTP 400")
    }

    @Test
    fun `a reconnect after a working session does not blame the address`() {
        val error = ConnectException("Failed to connect to ma.local/192.168.1.41:8095")
        assertThat(describeConnectionFailure(error, "http://ma.local:8095", signingIn = false))
            .isEqualTo("Can't reach ma.local:8095. Retrying.")
    }

    @Test
    fun `an unknown failure keeps its own message`() {
        assertThat(describeConnectionFailure(IOException("Socket closed"), "http://ma.local:8095"))
            .isEqualTo("Socket closed")
    }

    @Test
    fun `the sign-in check says nothing answers for a refused or silent address`() {
        assertThat(describeServerCheckFailure(ConnectException("refused"), "http://192.168.1.41:8095"))
            .isEqualTo(NO_MUSIC_ASSISTANT_MESSAGE)
        assertThat(describeServerCheckFailure(null, "http://192.168.1.41:8095"))
            .isEqualTo(NO_MUSIC_ASSISTANT_MESSAGE)
    }

    @Test
    fun `the sign-in check names the secure switch for a plain server`() {
        val error = SSLProtocolException("Read error: ssl=0x7: WRONG_VERSION_NUMBER")
        assertThat(describeServerCheckFailure(error, "https://192.168.1.41:8095"))
            .isEqualTo(NOT_SECURE_MESSAGE)
    }

    @Test
    fun `the sign-in check keeps the advice for Home Assistant and unknown names`() {
        val notFound = ProtocolException("Expected HTTP 101 response but was '404 Not Found'")
        assertThat(describeServerCheckFailure(notFound, "http://homeassistant.local:8123"))
            .isEqualTo(NOT_MUSIC_ASSISTANT_MESSAGE)
        assertThat(describeServerCheckFailure(UnknownHostException("mass.local"), "http://mass.local:8095"))
            .contains(".local")
    }
}
