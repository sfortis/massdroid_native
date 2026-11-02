package net.asksakis.massdroidv2.data.signin

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.connection.ChosenServer
import net.asksakis.massdroidv2.domain.connection.ServerCheck
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * The sign-in form's username and password check. The car closes its screen only after a
 * sign-in that was sent, so [SignInController.login] says whether it sent one (code check,
 * 2026-11-02).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInControllerTest {

    private val homeUrl = "http://home:8095"
    private val server = ChosenServer(homeUrl, "Home", ServerCheck.Found(homeUrl, "2.10.9", "home"))
    private val session = mockk<SignInSession>(relaxed = true) {
        every { error } returns MutableStateFlow(null)
    }

    private fun TestScope.controller(): SignInController {
        val controller = SignInController.Factory(
            mockk<MaWebSocketClient>(relaxed = true),
            mockk<SettingsRepository>(relaxed = true),
            mockk<MaAuthRepository>(relaxed = true),
            session
        ).create(backgroundScope)
        // The error is shared while a screen collects it.
        backgroundScope.launch { controller.error.collect {} }
        return controller
    }

    @Test
    fun `input that is blank after trimming is rejected and nothing is sent`() = runTest {
        val controller = controller()

        assertThat(controller.login(server, "  ", "secret")).isFalse()
        assertThat(controller.login(server, "alice", "   ")).isFalse()
        runCurrent()

        assertThat(controller.error.value).isEqualTo("Enter your username and password.")
        verify(exactly = 0) { session.login(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `valid input is sent to the server the check named`() = runTest {
        val controller = controller()

        assertThat(controller.login(server, " alice ", "secret")).isTrue()
        runCurrent()

        assertThat(controller.error.value).isNull()
        verify { session.login(homeUrl, "home", "alice", "secret", any()) }
    }
}
