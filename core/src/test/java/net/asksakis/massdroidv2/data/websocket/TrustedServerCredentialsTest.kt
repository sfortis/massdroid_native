package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Automatic reconnects go to the saved address on whatever network the phone is on. The token
 * and the password may only go to the server the user signed in to.
 */
class TrustedServerCredentialsTest {

    @Test
    fun `an automatic reconnect to the trusted server sends credentials`() {
        assertThat(credentialsAllowedFor(explicitSignIn = false, trusted = "home", offered = "home")).isTrue()
    }

    @Test
    fun `an automatic reconnect to a different server at the same address sends nothing`() {
        assertThat(credentialsAllowedFor(explicitSignIn = false, trusted = "home", offered = "other")).isFalse()
    }

    @Test
    fun `an explicit sign-in trusts the server that answers`() {
        assertThat(credentialsAllowedFor(explicitSignIn = true, trusted = "home", offered = "new")).isTrue()
    }

    @Test
    fun `the first connection after an update has no trusted server yet and is allowed`() {
        assertThat(credentialsAllowedFor(explicitSignIn = false, trusted = null, offered = "home")).isTrue()
    }
}
