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
        val error = okHttpConnectFailure("homeassistant.local/192.168.1.41:80", REFUSED)
        assertThat(describeConnectionFailure(error, "http://homeassistant.local"))
            .startsWith("Nothing answers at homeassistant.local:80.")
    }

    @Test
    fun `an unreachable host is not called a wrong port`() {
        val error = okHttpConnectFailure("ma.local/192.168.1.41:8095", NO_ROUTE)
        assertThat(describeConnectionFailure(error, "http://ma.local:8095"))
            .startsWith("This device can't reach ma.local:8095.")
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
    fun `the sign-in check names the port when another port refuses the connection`() {
        val error = okHttpConnectFailure("ma.tail1234.ts.net/100.64.0.7:8085", REFUSED)
        assertThat(describeServerCheckFailure(error, "http://ma.tail1234.ts.net:8085")).isEqualTo(
            "Nothing answers at ma.tail1234.ts.net:8085. Check the address and the port. " +
                "Music Assistant normally uses port 8095."
        )
        assertThat(describeServerCheckFailure(IOException("outer", error), "http://100.64.0.7:8085"))
            .contains("100.64.0.7:8085")
    }

    @Test
    fun `the sign-in check keeps the network advice when another port cannot be reached`() {
        // A VPN that is off: Android reports the unreachable host as a ConnectException too.
        val error = okHttpConnectFailure("ma.example.com/203.0.113.7:443", NO_ROUTE)
        assertThat(describeServerCheckFailure(error, "https://ma.example.com"))
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

    // OkHttp's ConnectException around the one Android's libcore raises, as a device reports them.
    private fun okHttpConnectFailure(address: String, errno: String): ConnectException {
        val socket = ConnectException("failed to connect to /${address.substringAfter('/')} after 10000ms: $errno")
        return ConnectException("Failed to connect to $address").apply { initCause(socket) }
    }

    private companion object {
        const val REFUSED = "isConnected failed: ECONNREFUSED (Connection refused)"
        const val NO_ROUTE = "isConnected failed: EHOSTUNREACH (No route to host)"
    }
}
