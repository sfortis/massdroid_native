package net.asksakis.massdroidv2.data.signin

import com.google.common.truth.Truth.assertThat
import android.content.Context
import android.security.KeyChain
import android.security.KeyChainException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.connection.ChosenServer
import net.asksakis.massdroidv2.domain.connection.ServerCheck
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.After
import org.junit.Test

/**
 * The sign-in form's username and password check. The car closes its screen only after a
 * sign-in that was sent, so [SignInController.login] says whether it sent one. Only the username
 * is trimmed, because a space at either end can be part of a password. A certificate is applied
 * only while it is still the selected one (code check, 2026-11-02).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInControllerTest {

    private val homeUrl = "http://home:8095"
    private val server = ChosenServer(homeUrl, "Home", ServerCheck.Found(homeUrl, "2.10.9", "home"))
    private val session = mockk<SignInSession>(relaxed = true) {
        every { error } returns MutableStateFlow(null)
    }
    private val client = mockk<MaWebSocketClient>(relaxed = true)
    private val alias = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository>(relaxed = true) {
        every { clientCertAlias } returns alias
        coEvery { setClientCertAlias(any()) } answers { alias.value = firstArg() }
    }
    private val context = mockk<Context>(relaxed = true).also { every { it.applicationContext } returns it }
    private val keyA = mockk<PrivateKey>()
    private val chainA = arrayOf(mockk<X509Certificate>())

    @After
    fun tearDown() = unmockkStatic(KeyChain::class)

    private fun TestScope.controller(): SignInController {
        val controller = SignInController.Factory(client, settings, mockk<MaAuthRepository>(relaxed = true), session)
            .create(backgroundScope, StandardTestDispatcher(testScheduler))
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

    @Test
    fun `a password with spaces at either end is sent exactly as typed`() = runTest {
        val controller = controller()

        assertThat(controller.login(server, "alice", "  secret ")).isTrue()

        verify { session.login(homeUrl, "home", "alice", "  secret ", any()) }
    }

    @Test
    fun `a certificate still selected when it loads is applied`() = runTest {
        mockkStatic(KeyChain::class)
        every { KeyChain.getPrivateKey(any(), "A") } returns keyA
        every { KeyChain.getCertificateChain(any(), "A") } returns chainA
        val controller = controller()

        controller.selectCertificate("A", context)
        runCurrent()

        verify { client.configureMtls(keyA, chainA) }
        assertThat(alias.value).isEqualTo("A")
    }

    @Test
    fun `a certificate replaced while it loads is not applied`() = runTest {
        mockkStatic(KeyChain::class)
        every { KeyChain.getPrivateKey(any(), "A") } answers {
            alias.value = "B" // the user picks B while KeyChain still answers for A
            keyA
        }
        every { KeyChain.getCertificateChain(any(), "A") } returns chainA
        val controller = controller()

        controller.selectCertificate("A", context)
        runCurrent()

        verify(exactly = 0) { client.configureMtls(any(), any()) }
        verify(exactly = 0) { client.clearMtls() }
        assertThat(alias.value).isEqualTo("B")
    }

    @Test
    fun `a certificate that fails to load after a newer pick does not clear the newer one`() = runTest {
        mockkStatic(KeyChain::class)
        every { KeyChain.getPrivateKey(any(), "A") } answers {
            alias.value = "B"
            throw KeyChainException("gone")
        }
        val controller = controller()

        controller.selectCertificate("A", context)
        runCurrent()

        verify(exactly = 0) { client.clearMtls() }
        assertThat(alias.value).isEqualTo("B")
    }

    @Test
    fun `a certificate that fails to load while still selected is forgotten`() = runTest {
        mockkStatic(KeyChain::class)
        every { KeyChain.getPrivateKey(any(), "A") } throws KeyChainException("gone")
        val controller = controller()

        controller.selectCertificate("A", context)
        runCurrent()

        verify { client.clearMtls() }
        assertThat(alias.value).isNull()
    }
}
