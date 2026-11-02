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
    fun `a refused token on another server does not fall back to the saved password`() {
        assertThat(passwordFallbackAllowedFor(trusted = "home", offered = "new")).isFalse()
    }

    @Test
    fun `a refused token on the trusted server falls back to the saved password`() {
        assertThat(passwordFallbackAllowedFor(trusted = "home", offered = "home")).isTrue()
    }

    @Test
    fun `a connect that names its server sends credentials only to that server`() {
        val named = credentialGates(expected = "office", explicitSignIn = false, trusted = "home", offered = "office")
        assertThat(named.credentials).isTrue()
        assertThat(named.passwordFallback).isTrue()
        val other = credentialGates(expected = "office", explicitSignIn = true, trusted = "office", offered = "home")
        assertThat(other.credentials).isFalse()
        assertThat(other.passwordFallback).isFalse()
        assertThat(credentialGates(expected = "office", explicitSignIn = false, trusted = null, offered = null).credentials).isFalse()
    }

    @Test
    fun `a connect that names no server leaves the decision to the trusted server`() {
        val gates = credentialGates(expected = null, explicitSignIn = true, trusted = "home", offered = "new")
        assertThat(gates.credentials).isTrue()
        assertThat(gates.passwordFallback).isFalse()
    }

    @Test
    fun `the first connection after an update has no trusted server yet and is allowed`() {
        assertThat(credentialsAllowedFor(explicitSignIn = false, trusted = null, offered = "home")).isTrue()
    }
}
