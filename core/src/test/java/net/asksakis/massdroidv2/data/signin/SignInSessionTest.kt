package net.asksakis.massdroidv2.data.signin

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.websocket.AuthenticatedSession
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaAuthProbe
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.RejectedToken
import net.asksakis.massdroidv2.data.websocket.ServerAnswer
import net.asksakis.massdroidv2.data.websocket.ServerInfo
import net.asksakis.massdroidv2.domain.model.ServerAccount
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.OAuthSignIn
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * The phone's sign-in session against a fake client and fake settings: what an attempt saves
 * and in which order, where a failed or abandoned attempt returns to, and which connections
 * save a token (code check of step 2, 2026-11-02).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInSessionTest {

    private val homeUrl = "http://home:8095"
    private val officeUrl = "http://office:8095"

    // The client: every connect is a new generation, answered by the test.
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val rejected = MutableSharedFlow<RejectedToken>(extraBufferCapacity = 4)
    private var generation = 0
    private val connects = mutableListOf<Pair<String, String>>()
    private val sessions = mutableMapOf<Int, AuthenticatedSession>()
    private val client = mockk<MaWebSocketClient>(relaxed = true) {
        every { connectionState } returns state
        every { rejectedTokens } returns rejected
        every { connect(any(), any(), any(), any()) } answers { connectTo(firstArg(), secondArg()) }
        every { connectWithLogin(any(), any(), any(), any(), any()) } answers {
            connectTo(firstArg(), "token of ${secondArg<String>()}")
        }
        every { authenticatedSession(any()) } answers {
            val wanted = firstArg<Int>()
            sessions[wanted]?.takeIf {
                wanted == generation && (state.value as? ConnectionState.Connected)?.generation == wanted
            }
        }
        every { disconnect() } answers { state.value = ConnectionState.Disconnected }
    }

    // The settings: signed in to the home server as alice, with a player selected.
    private val savedUrl = MutableStateFlow(homeUrl)
    private val trusted = MutableStateFlow<String?>("home")
    private val token = MutableStateFlow("home token")
    private val user = MutableStateFlow("alice")
    private val pass = MutableStateFlow("alice password")
    private val selected = MutableStateFlow<String?>("kitchen")
    private val accounts = mutableMapOf("home" to ServerAccount("home token", "alice"))
    private val writes = mutableListOf<String>()
    private val settings = mockk<SettingsRepository>(relaxed = true) {
        every { serverUrl } returns savedUrl
        every { trustedServerId } returns trusted
        every { authToken } returns token
        every { username } returns user
        every { password } returns pass
        every { selectedPlayerId } returns selected
        every { serverAccount(any()) } answers { flowOf(accounts[firstArg()] ?: ServerAccount("", "")) }
        coEvery { setSelectedPlayerId(any()) } answers {
            writes += "player=${firstArg<String?>()}"
            selected.value = firstArg()
        }
        coEvery { setTrustedServerId(any()) } answers { trusted.value = firstArg() }
        coEvery { saveSignIn(any(), any(), any(), any(), any()) } answers {
            writes += "save ${arg<String>(0)}"
            trusted.value = arg(0)
            savedUrl.value = arg(1)
            token.value = arg(2)
            user.value = arg(3)
            pass.value = arg(4)
            accounts[arg(0)] = ServerAccount(arg(2), arg(3))
        }
        coEvery { saveConnectionToken(any(), any(), any()) } answers { writes += "connection ${arg<String>(0)}" }
    }

    private val probe = mockk<MaAuthProbe>()

    private val oauth = MutableSharedFlow<OAuthSignIn>(extraBufferCapacity = 1)
    private val auth = mockk<MaAuthRepository>(relaxed = true) {
        every { completedOAuthSignIns } returns oauth
        coEvery { prepareServerSwitch() } answers { writes += "reset" }
    }

    private fun connectTo(url: String, token: String): Int {
        generation++
        connects += url to token
        state.value = ConnectionState.Connecting
        sessions[generation] = AuthenticatedSession(url, token, serverIdOf(url))
        return generation
    }

    private fun serverIdOf(url: String) = if (url == officeUrl) "office" else "home"

    private fun serverAccepts() {
        state.value = ConnectionState.Connected(info(sessions.getValue(generation).serverId), generation)
    }

    private fun serverRefuses() {
        state.value = ConnectionState.Error("Authentication failed: wrong password", generation)
    }

    private fun info(serverId: String) = ServerInfo(serverId, "2.10.9", 28, "http://x")

    private fun TestScope.session(): SignInSession =
        SignInSession(client, settings, auth, probe, backgroundScope).also {
            it.start()
            runCurrent()
            writes.clear()
        }

    @Test
    fun `a switch that connects saves the new server after dropping the old one's state`() = runTest {
        val session = session()
        session.login(officeUrl, "office", "bob", "bob password")
        runCurrent()
        serverAccepts()
        runCurrent()

        assertThat(writes).containsExactly("player=null", "reset", "save office").inOrder()
        assertThat(savedUrl.value).isEqualTo(officeUrl)
        assertThat(session.succeeded.value).isEqualTo(officeUrl)
    }

    @Test
    fun `a refused switch returns to the previous server with its token, player and trust`() = runTest {
        val session = session()
        session.login(officeUrl, "office", "bob", "wrong")
        runCurrent()
        trusted.value = "office" // the client may already trust the server that answered
        serverRefuses()
        runCurrent()

        assertThat(connects.last()).isEqualTo(homeUrl to "home token")
        assertThat(selected.value).isEqualTo("kitchen")
        assertThat(trusted.value).isEqualTo("home")
        assertThat(savedUrl.value).isEqualTo(homeUrl)
        assertThat(session.error.value).contains("Still using home:8095")
        verify { client.connect(homeUrl, "home token", MaWebSocketClient.ConnectOrigin.SAVED_CREDENTIALS, "home") }
        // A session the attempt may have left behind is not kept, unless it is the home server's.
        verify { client.forgetLastSession("home") }
    }

    @Test
    fun `a wrong password for the current server reconnects with its token`() = runTest {
        val session = session()
        session.login(homeUrl, "home", "alice", "typo")
        runCurrent()
        serverRefuses()
        runCurrent()

        assertThat(writes).doesNotContain("reset")
        assertThat(connects.last()).isEqualTo(homeUrl to "home token")
        assertThat(session.error.value).startsWith("Couldn't sign in to home:8095.")
        assertThat(session.error.value).doesNotContain("Still using")
    }

    @Test
    fun `a cancel before the attempt connected connects nowhere`() = runTest {
        val session = session()
        session.login(officeUrl, "office", "bob", "bob password")
        session.cancel()
        runCurrent()

        assertThat(connects).isEmpty()
        assertThat(writes).isEmpty()
    }

    @Test
    fun `a cancel after the attempt connected returns to the previous server`() = runTest {
        val session = session()
        session.login(officeUrl, "office", "bob", "bob password")
        runCurrent()
        session.cancel()
        runCurrent()

        assertThat(connects.last()).isEqualTo(homeUrl to "home token")
        assertThat(savedUrl.value).isEqualTo(homeUrl)
        verify { client.forgetLastSession("home") }
    }

    @Test
    fun `Continue as without a saved token gives up before resetting or connecting`() = runTest {
        val session = session()
        session.continueWithSavedAccount(officeUrl, "office")
        runCurrent()

        assertThat(connects).isEmpty()
        assertThat(writes).isEmpty()
        assertThat(session.error.value).isEqualTo("Enter your username and password.")

        // Nothing is left pending, so the next connection's token is saved.
        val reconnect = connectTo(homeUrl, "home token")
        state.value = ConnectionState.Connected(info("home"), reconnect)
        runCurrent()
        assertThat(writes).containsExactly("connection home")
    }

    @Test
    fun `Continue as connects with that server's token and names the server`() = runTest {
        accounts["office"] = ServerAccount("office token", "bob")
        val session = session()
        session.continueWithSavedAccount(officeUrl, "office")
        runCurrent()

        verify { client.connect(officeUrl, "office token", MaWebSocketClient.ConnectOrigin.SIGN_IN, "office") }
        verify { client.clearSavedCredentials() }
        serverAccepts()
        runCurrent()
        assertThat(user.value).isEqualTo("bob")
        assertThat(pass.value).isEmpty()
    }

    @Test
    fun `another address of the same server keeps its state and player`() = runTest {
        val session = session()
        session.login("https://music.example.org", "home", "alice", "alice password")
        runCurrent()
        state.value = ConnectionState.Connected(info("home"), generation)
        runCurrent()

        assertThat(writes).containsExactly("save home")
        assertThat(selected.value).isEqualTo("kitchen")
        verify { client.connectWithLogin("https://music.example.org", "alice", "alice password", "home", any()) }
    }

    @Test
    fun `an attempt that replaces another returns to the server used before both, and resets once`() = runTest {
        val session = session()
        session.login(officeUrl, "office", "bob", "bob password")
        runCurrent()
        session.login("http://third:8095", "third", "carol", "wrong")
        runCurrent()
        serverRefuses()
        runCurrent()

        assertThat(writes.count { it == "reset" }).isEqualTo(1)
        assertThat(connects.last()).isEqualTo(homeUrl to "home token")
    }

    @Test
    fun `a connection that is no longer the current one saves nothing`() = runTest {
        session()
        val old = connectTo(homeUrl, "home token")
        connectTo(homeUrl, "home token")
        state.value = ConnectionState.Connected(info("home"), old)
        runCurrent()

        assertThat(writes).isEmpty()
    }

    @Test
    fun `the account migration runs at start, and a refused token is forgotten`() = runTest {
        session()
        coVerify(exactly = 1) { settings.migrateToServerAccounts() }

        rejected.emit(RejectedToken("office", "office token"))
        runCurrent()
        coVerify { settings.forgetRejectedToken("office", "office token") }
    }

    @Test
    fun `Sign out stops an attempt without returning to the previous server`() = runTest {
        val session = session()
        session.login(officeUrl, "office", "bob", "bob password")
        runCurrent()
        val before = connects.size
        session.signOut()
        runCurrent()

        coVerify { auth.signOut() }
        assertThat(connects).hasSize(before)
    }

    @Test
    fun `a sign-in begun right after Sign out still connects`() = runTest {
        // Sign out finishes in the session's scope; the attempt it stops is the one in flight
        // when it was tapped, not one the user starts a moment later.
        val session = session()
        session.signOut()
        session.login(officeUrl, "office", "bob", "bob password")
        runCurrent()

        coVerify { auth.signOut() }
        assertThat(connects.map { it.first }).contains(officeUrl)
    }

    @Test
    fun `a sign-in begun during Sign out waits for it and does not return to the signed-out server`() = runTest {
        val signedOut = CompletableDeferred<Unit>()
        coEvery { auth.signOut() } coAnswers {
            signedOut.await()
            token.value = ""
            pass.value = ""
            trusted.value = null
        }
        val session = session()
        session.signOut()
        session.login(officeUrl, "office", "bob", "bob password")
        runCurrent()
        assertThat(connects).isEmpty()

        signedOut.complete(Unit)
        runCurrent()
        assertThat(connects.map { it.first }).containsExactly(officeUrl)

        serverRefuses()
        runCurrent()
        // Nothing is left to return to: the home server's token was forgotten by the sign out.
        assertThat(connects.map { it.first }).containsExactly(officeUrl)
        verify { client.disconnect() }
    }

    @Test
    fun `Sign out completes when the screen that asked for it closes`() = runTest {
        val session = session()
        val screen = CoroutineScope(Job())
        val controller = SignInController.Factory(client, settings, auth, session).create(screen)
        controller.signOut()
        screen.cancel()
        runCurrent()

        coVerify { auth.signOut() }
    }

    @Test
    fun `a password goes only to the server the check named, even when typed`() = runTest {
        val session = session()
        session.login(officeUrl, "office", "bob", "bob password")
        runCurrent()
        verify { client.connectWithLogin(officeUrl, "bob", "bob password", "office", any()) }
    }

    @Test
    fun `without a check, the saved password goes only to the server it belongs to`() = runTest {
        val session = session()
        session.login(homeUrl, serverId = null, username = "alice", password = "alice password") { "home" }
        runCurrent()
        verify { client.connectWithLogin(homeUrl, "alice", "alice password", "home", any()) }
    }

    @Test
    fun `Forget names the server that answers at the address, and the saved address`() = runTest {
        coEvery { probe.identify(officeUrl) } returns ServerAnswer.MusicAssistant("2.10.9", emptyList(), "office")
        val session = session()
        session.forget(officeUrl)
        runCurrent()
        coVerify { settings.forgetServerAt(officeUrl, "office", homeUrl) }
    }

    @Test
    fun `Forget of an address that does not answer leaves the server to what was saved`() = runTest {
        coEvery { probe.identify(officeUrl) } returns ServerAnswer.Failed(null)
        val session = session()
        session.forget(officeUrl)
        runCurrent()
        coVerify { settings.forgetServerAt(officeUrl, null, homeUrl) }
    }

    @Test
    fun `a Home Assistant sign-in is saved only once the server accepts its token`() = runTest {
        val session = session()
        oauth.emit(OAuthSignIn(officeUrl, "office token"))
        runCurrent()
        assertThat(writes).doesNotContain("save office")

        serverAccepts()
        runCurrent()
        assertThat(writes.last()).isEqualTo("save office")
        assertThat(session.succeeded.value).isEqualTo(officeUrl)
    }
}
