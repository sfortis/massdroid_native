package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.websocket.MaAuthProbe
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.OAuthCallback
import net.asksakis.massdroidv2.data.websocket.OAuthCallbackBus
import net.asksakis.massdroidv2.data.websocket.ServerAnswer
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.model.AuthProviderInfo
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * A Home Assistant sign-in start checks that the phone reaches the addresses the link opens,
 * which can take seconds. A start cancelled during the checks, or replaced by a newer start,
 * must not open the browser or overwrite the newer start's nonce (code check, 2026-11-03).
 */
class HomeAssistantOAuthStartTest {

    private val serverUrl = "http://ma.lan:8095"
    private val nonces = CopyOnWriteArrayList<String>()
    private val firstCheckHeld = CompletableDeferred<Unit>()
    private val releaseFirstCheck = CompletableDeferred<Unit>()
    private val first = AtomicBoolean(true)
    private val probe = mockk<MaAuthProbe> {
        coEvery { identify(serverUrl) } returns
            ServerAnswer.MusicAssistant("2.10.9", listOf(AuthProviderInfo("ha", "homeassistant", true)))
        coEvery { fetchAuthorizationUrl(serverUrl, "ha", any()) } answers {
            nonces += thirdArg<String>().substringAfterLast('/')
            "http://ha.lan:8123/auth/authorize?n=${nonces.size}"
        }
        // The Home Assistant page is another address, so each start checks it once; the first
        // start's check waits for the test.
        coEvery { answersHttp(any()) } coAnswers {
            if (first.compareAndSet(true, false)) {
                firstCheckHeld.complete(Unit)
                releaseFirstCheck.await()
            }
            true
        }
    }
    private val bus = OAuthCallbackBus()
    private val repo = MaAuthRepositoryImpl(
        probe,
        mockk<MaWebSocketClient>(relaxed = true),
        mockk<SettingsRepository>(relaxed = true),
        bus,
        mockk<DiscoverCache>(relaxed = true),
        SessionEventBus()
    )

    @Test
    fun `a start cancelled during its checks opens nothing`() = runBlocking {
        repo.checkServer(serverUrl)
        val start = async(Dispatchers.Default) { repo.startHomeAssistantOAuth(serverUrl) }
        withTimeout(WAIT_MS) { firstCheckHeld.await() }

        repo.cancelOAuth()
        releaseFirstCheck.complete(Unit)

        assertThat(withTimeout(WAIT_MS) { start.await() }).isNull()
        assertThat(repo.oauthInProgress.value).isFalse()
    }

    @Test
    fun `a start replaced during its checks leaves the newer start in place`() = runBlocking {
        repo.checkServer(serverUrl)
        val older = async(Dispatchers.Default) { repo.startHomeAssistantOAuth(serverUrl) }
        withTimeout(WAIT_MS) { firstCheckHeld.await() }

        repo.cancelOAuth()
        val newer = repo.startHomeAssistantOAuth(serverUrl)
        releaseFirstCheck.complete(Unit)

        assertThat(newer).isNotNull()
        assertThat(withTimeout(WAIT_MS) { older.await() }).isNull()
        assertThat(repo.oauthInProgress.value).isTrue()

        // The newer start's callback still completes the sign-in. The repository's collector
        // of the bus runs on its own scope, so the callback is sent until it is taken; the
        // completion is watched before the first send, because that flow keeps nothing.
        val completed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(WAIT_MS) { repo.completedOAuthSignIns.first() }
        }
        withTimeoutOrNull(WAIT_MS) {
            while (!completed.isCompleted) {
                bus.publish(OAuthCallback(state = nonces[1], code = "newer token"))
                delay(POLL_MS)
            }
        }
        assertThat(completed.await()?.token).isEqualTo("newer token")
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
