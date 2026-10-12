package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SendspinUnpairedSessionKeyTest {

    @Test
    fun `the same server and session give the same key`() {
        assertThat(SendspinNoiseConnector.unpairedSessionKey("server-a", "token-1"))
            .isEqualTo(SendspinNoiseConnector.unpairedSessionKey("server-a", "token-1"))
    }

    @Test
    fun `a new MA login ends the hold, because its token gives another key`() {
        assertThat(SendspinNoiseConnector.unpairedSessionKey("server-a", "token-2"))
            .isNotEqualTo(SendspinNoiseConnector.unpairedSessionKey("server-a", "token-1"))
    }

    @Test
    fun `another server in the same session is not held`() {
        assertThat(SendspinNoiseConnector.unpairedSessionKey("server-b", "token-1"))
            .isNotEqualTo(SendspinNoiseConnector.unpairedSessionKey("server-a", "token-1"))
    }

    @Test
    fun `the key names the server and does not carry the token`() {
        val key = SendspinNoiseConnector.unpairedSessionKey("server-a", "secret-token")

        assertThat(key).startsWith("server-a@")
        assertThat(key).doesNotContain("secret-token")
    }
}
