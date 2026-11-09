package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * Signing in when no sign-in screen is open, which is how the car, a widget and every
 * reconnect sign in.
 *
 * With the token gone, a reconnect stopped at "No token or credentials available" although
 * the password was saved, and the car showed its tabs as signed in but never connected, until
 * the user typed the password again (issue #80).
 */
class UnattendedSignInTest {

    @Test
    fun `a saved token is tried first`() {
        assertThat(automaticSignIn(token = "t", hasSavedPassword = true, passwordAllowed = true))
            .isEqualTo(AutomaticSignIn.TOKEN)
    }

    @Test
    fun `no token signs in with the saved password`() {
        assertThat(automaticSignIn(token = null, hasSavedPassword = true, passwordAllowed = true))
            .isEqualTo(AutomaticSignIn.PASSWORD)
    }

    @Test
    fun `a blank token from storage counts as no token`() {
        assertThat(automaticSignIn(token = "", hasSavedPassword = true, passwordAllowed = true))
            .isEqualTo(AutomaticSignIn.PASSWORD)
    }

    @Test
    fun `the password does not go to a server it was not saved for`() {
        assertThat(automaticSignIn(token = null, hasSavedPassword = true, passwordAllowed = false))
            .isEqualTo(AutomaticSignIn.NONE)
    }

    @Test
    fun `nothing saved leaves the sign-in to the user`() {
        assertThat(automaticSignIn(token = null, hasSavedPassword = false, passwordAllowed = true))
            .isEqualTo(AutomaticSignIn.NONE)
    }

    @Test
    fun `a saved password without a token is enough to connect unattended`() {
        assertThat(canSignInUnattended("https://ma.example", token = "", username = "u", password = "p")).isTrue()
    }

    @Test
    fun `a saved token alone is enough to connect unattended`() {
        assertThat(canSignInUnattended("https://ma.example", token = "t", username = "", password = "")).isTrue()
    }

    @Test
    fun `a username without its password is not enough`() {
        assertThat(canSignInUnattended("https://ma.example", token = "", username = "u", password = "")).isFalse()
    }

    @Test
    fun `no server address is not enough`() {
        assertThat(canSignInUnattended("", token = "t", username = "u", password = "p")).isFalse()
        assertThat(canSignInUnattended("ma.example", token = "t", username = "", password = "")).isFalse()
    }

    // The car shows its Sign in prompt from this flow, so it has to turn false on a sign out
    // and true again on the next sign-in, once each.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the saved credentials follow a sign out and the next sign-in`() = runTest {
        val token = MutableStateFlow("t")
        val password = MutableStateFlow("p")
        val settings = mockk<SettingsRepository> {
            every { serverUrl } returns flowOf("https://ma.example")
            every { authToken } returns token
            every { username } returns flowOf("u")
            every { this@mockk.password } returns password
        }
        val connector = SavedCredentialsConnector(mockk(), settings)
        val seen = mutableListOf<Boolean>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { connector.savedCredentials.toList(seen) }

        // A sign out forgets the token first and then the password.
        token.value = ""
        password.value = ""
        token.value = "t2"
        collecting.cancel()

        assertThat(seen).containsExactly(true, false, true).inOrder()
    }
}
