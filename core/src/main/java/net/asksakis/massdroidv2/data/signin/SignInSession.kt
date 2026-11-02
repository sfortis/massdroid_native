package net.asksakis.massdroidv2.data.signin

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.websocket.AuthenticatedSession
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaAuthProbe
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ServerAnswer
import net.asksakis.massdroidv2.domain.connection.displayAddressOf
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

/**
 * The phone's sign-in to a server and everything it saves, owned by the app instead of a
 * screen. The sign-in used to run in the Settings or onboarding ViewModel, so leaving the
 * screen while it ran cancelled the saving, or the return to the server that worked.
 *
 * Nothing is saved until the attempt's own connection, told apart by its generation, is
 * [ConnectionState.Connected]. A failed, timed-out or abandoned attempt connects again to the
 * server used before it. This class is also the only writer of the saved token on the phone:
 * it saves the token of every other connection, and forgets a token only when the server
 * refused it ([MaWebSocketClient.rejectedTokens]). The Home Assistant sign-in comes back here
 * too ([MaAuthRepository.completedOAuthSignIns]).
 *
 * The phone (Settings and onboarding) and the car sign in here. The TV keeps its own path.
 */
@Singleton
class SignInSession internal constructor(
    private val wsClient: MaWebSocketClient,
    private val settings: SettingsRepository,
    private val auth: MaAuthRepository,
    private val probe: MaAuthProbe,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        wsClient: MaWebSocketClient,
        settings: SettingsRepository,
        auth: MaAuthRepository,
        probe: MaAuthProbe
    ) : this(wsClient, settings, auth, probe, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val started = AtomicBoolean(false)
    // Serialises every write of the account: an attempt's save, the save of other connections,
    // forgetting a refused token or a server, and Sign out.
    private val saveLock = Mutex()
    // Guards [pending] and [attemptJob], which the UI thread and the attempts change.
    private val lock = Any()
    private var pending: Attempt? = null
    private var attemptJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)

    /** Why the last attempt failed. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _attemptedUrl = MutableStateFlow<String?>(null)

    /**
     * The server the last sign-in went to. The client's connection error belongs to that
     * attempt, so a screen shows it only while the same server is chosen.
     */
    val attemptedUrl: StateFlow<String?> = _attemptedUrl.asStateFlow()

    private val _succeeded = MutableStateFlow<String?>(null)

    /**
     * The server the last attempt signed in to and saved, until a screen [consumeSuccess]es
     * it. Change server closes on this, not on the saved address, which already matches when
     * the user signs in to the current server again.
     */
    val succeeded: StateFlow<String?> = _succeeded.asStateFlow()

    /** Starts saving the token of every connection. Called once by the application. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            saveLock.withLock { settings.migrateToServerAccounts() }
            wsClient.connectionState.collect { state ->
                if (state is ConnectionState.Connected && !hasPending()) saveConnection(state.generation)
            }
        }
        scope.launch {
            wsClient.rejectedTokens.collect { rejected ->
                saveLock.withLock { settings.forgetRejectedToken(rejected.serverId, rejected.token) }
                Log.d(TAG, "Refused token forgotten")
            }
        }
        scope.launch {
            auth.completedOAuthSignIns.collect { signIn -> continueWithToken(signIn.url, signIn.token) }
        }
    }

    /**
     * Signs in to [serverUrl] with a username and password. [serverId] is the `server_id` the
     * server gave when it was checked, if it answered, and the password goes only to a server
     * with that id. Without it, [savedPasswordOwner] names the server a saved password belongs
     * to, when the password is that one, and the password goes only to that server. It is read
     * while the attempt prepares, so the attempt itself starts with this call.
     */
    fun login(
        serverUrl: String,
        serverId: String?,
        username: String,
        password: String,
        savedPasswordOwner: suspend () -> String? = { null }
    ) {
        begin(serverUrl, serverId) {
            val expected = serverId ?: savedPasswordOwner()
            Credentials(username, password) {
                wsClient.connectWithLogin(serverUrl, username, password, expected) { }
            }
        }
    }

    /**
     * Forgets the address [url] from the server list, and the account of the server behind it
     * when it has no other address; see [SettingsRepository.forgetServerAt]. The server is the
     * one that answers at the address now, which is the one the form showed for it.
     */
    fun forget(url: String) {
        scope.launch {
            val serverId = withTimeoutOrNull(FORGET_PROBE_TIMEOUT_MS) {
                (probe.identify(url) as? ServerAnswer.MusicAssistant)?.serverId
            }
            saveLock.withLock {
                settings.forgetServerAt(url, serverId, settings.serverUrl.first())
            }
        }
    }

    /** Signs in to [serverUrl] with the token saved for [serverId], the "Continue as" choice. */
    fun continueWithSavedAccount(serverUrl: String, serverId: String) {
        begin(serverUrl, serverId) { previous ->
            val account = settings.serverAccount(serverId).first()
            if (!account.hasToken) {
                _error.value = "Enter your username and password."
                return@begin null
            }
            // The password is kept only for the current server, so it stays when the account
            // continued is that server's. Another server's token never falls back to it.
            val password = previous?.takeIf { it.serverId == serverId }?.password.orEmpty()
            Credentials(account.username, password) {
                if (password.isNotBlank()) {
                    wsClient.setSavedCredentials(account.username, password)
                } else {
                    wsClient.clearSavedCredentials()
                }
                wsClient.connect(serverUrl, account.token, MaWebSocketClient.ConnectOrigin.SIGN_IN, serverId)
            }
        }
    }

    /** Signs in to [serverUrl] with a token a Home Assistant sign-in returned. */
    private fun continueWithToken(serverUrl: String, token: String) {
        begin(serverUrl, serverId = null) {
            Credentials(username = "", password = "") {
                wsClient.clearSavedCredentials()
                wsClient.connect(serverUrl, token, MaWebSocketClient.ConnectOrigin.SIGN_IN)
            }
        }
    }

    /**
     * Abandons the sign-in in progress when the user leaves its server, and connects to the
     * previous server again if the attempt had already connected elsewhere. A connection that
     * is not an attempt, such as the return to the previous server, is left alone. A pending
     * Home Assistant sign-in is dropped as well.
     */
    fun cancel() {
        auth.cancelOAuth()
        _attemptedUrl.value = null
        _error.value = null
        _succeeded.value = null
        val (attempt, job) = synchronized(lock) {
            val attempt = pending ?: return
            pending = null
            // Cancelled here, not in the coroutine below: a job that has not started yet then
            // never runs, so it cannot connect after the cancel.
            attemptJob?.cancel()
            attempt to attemptJob
        }
        scope.launch {
            job?.join()
            // Saved before the cancel reached it: the sign-in is done.
            if (attempt.saved || !attempt.connectIssued) return@launch
            Log.d(TAG, "Sign-in to ${displayAddressOf(attempt.url)} abandoned")
            saveLock.withLock {
                // A newer attempt that began meanwhile owns the connection and its return.
                if (!hasPending()) returnTo(attempt.previous)
            }
        }
    }

    /** Sign out: stops an attempt without returning anywhere, then forgets this server's token. */
    suspend fun signOut() {
        val job = synchronized(lock) {
            pending = null
            attemptJob
        }
        job?.cancelAndJoin()
        saveLock.withLock { auth.signOut() }
    }

    fun clearError() {
        _error.value = null
    }

    fun consumeSuccess() {
        _succeeded.value = null
    }

    private fun hasPending(): Boolean = synchronized(lock) { pending != null }

    /**
     * Starts an attempt. [prepare] reads what it needs and returns how to connect, or null to
     * give up before anything was reset or sent. The attempt is pending from this call on, so
     * a [cancel] that comes before it connects is never missed.
     */
    private fun begin(serverUrl: String, serverId: String?, prepare: suspend (Previous?) -> Credentials?) {
        _error.value = null
        _attemptedUrl.value = serverUrl
        _succeeded.value = null
        val attempt = Attempt(serverUrl, serverId)
        synchronized(lock) {
            // An attempt that replaces one in flight returns, if it fails, to the server used
            // before the first one, unless the first one finished and saved its server. Taken
            // over under the lock the first one marks its connect under, so nothing is missed.
            pending?.takeIf { !it.saved }?.let(attempt::takeOver)
            val running = attemptJob
            pending = attempt
            running?.cancel()
            attemptJob = scope.launch {
                running?.join()
                run(attempt, prepare)
            }
        }
    }

    private suspend fun run(attempt: Attempt, prepare: suspend (Previous?) -> Credentials?) {
        if (!attempt.previousKnown) {
            val current = currentAccount()
            synchronized(lock) {
                if (!attempt.previousKnown) {
                    attempt.previous = current
                    attempt.previousKnown = true
                }
            }
        }
        val previous = attempt.previous

        val credentials = prepare(previous)
        if (credentials == null) {
            fail(attempt, outcome = null)
            return
        }
        attempt.username = credentials.username
        attempt.password = credentials.password
        // Decided once, before connecting. Without a server_id from the check, the address
        // decides; the save corrects a server that turns out to be another one.
        val switching = previous != null && isOtherServer(previous.url, previous.serverId, attempt.url, attempt.serverId)
        // Marked under the lock: a cancel or a newer attempt that came while this one was
        // reading takes over, and one that comes after sees the connect this one is making.
        val leaving = synchronized(lock) {
            if (pending !== attempt) return
            val leaving = switching && !attempt.leftPrevious
            val comingBack = !switching && attempt.leftPrevious
            attempt.leftPrevious = switching
            attempt.connectIssued = true
            if (leaving) Leaving.LEAVE else if (comingBack) Leaving.COME_BACK else Leaving.STAY
        }
        val generation = withContext(NonCancellable) {
            // The reset and the connect go together, so a cancel never finds the previous
            // server's state wiped while its connection is still up.
            when (leaving) {
                Leaving.LEAVE -> leavePrevious()
                // An earlier attempt left this server; coming back keeps its player.
                Leaving.COME_BACK -> settings.setSelectedPlayerId(previous?.selectedPlayerId)
                Leaving.STAY -> Unit
            }
            credentials.connect()
        }
        val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
            wsClient.connectionState.first { it.endsAttempt(generation) }
        }
        val session = (outcome as? ConnectionState.Connected)?.let { wsClient.authenticatedSession(generation) }
        val wrongServer = session != null && attempt.serverId != null && session.serverId != attempt.serverId
        if (session != null && !wrongServer) {
            withContext(NonCancellable) { saveLock.withLock { save(attempt, session) } }
            return
        }
        if (outcome == null || outcome is ConnectionState.Connected) wsClient.disconnect()
        fail(attempt, outcome)
    }

    /** Saves a successful attempt. A server the check could not name is reset for here. */
    private suspend fun save(attempt: Attempt, session: AuthenticatedSession) {
        val previous = attempt.previous
        val switched = previous != null && isOtherServer(previous.url, previous.serverId, attempt.url, session.serverId)
        val lateSwitch = switched && !attempt.leftPrevious
        if (lateSwitch) leavePrevious()
        settings.saveSignIn(session.serverId, session.url, session.token, attempt.username, attempt.password)
        if (attempt.password.isNotBlank()) {
            wsClient.setSavedCredentials(attempt.username, attempt.password)
        } else if (switched) {
            wsClient.clearSavedCredentials()
        }
        attempt.saved = true
        val (current, replacedByNewer) = synchronized(lock) {
            val inCharge = pending
            if (inCharge === attempt) pending = null
            (inCharge === attempt) to (inCharge != null && inCharge !== attempt)
        }
        // A newer attempt owns the connection; it connects to its own server.
        if (lateSwitch && !replacedByNewer) {
            // Everything that loads per account loads on a new connection, so the server that
            // turned out to be another one is connected again after the reset.
            wsClient.disconnect()
            wsClient.connect(session.url, session.token, MaWebSocketClient.ConnectOrigin.SAVED_CREDENTIALS, session.serverId)
        }
        // Only the attempt still in charge reports success: one that a newer attempt or a cancel
        // replaced must not close a screen that is waiting for the newer one.
        if (current) _succeeded.value = attempt.url
        Log.d(TAG, "Signed in to ${displayAddressOf(attempt.url)}, saved")
    }

    private suspend fun fail(attempt: Attempt, outcome: ConnectionState?) {
        val stillCurrent = synchronized(lock) { (pending === attempt).also { if (it) pending = null } }
        // A newer attempt or a cancel owns the connection now.
        if (!stillCurrent) return
        val previous = attempt.previous
        val returned = if (attempt.connectIssued) saveLock.withLock { returnTo(previous) } else false
        if (outcome == null && !attempt.connectIssued) return
        val reason = (outcome as? ConnectionState.Error)?.message ?: "The server did not answer."
        val target = displayAddressOf(attempt.url)
        _error.value = when {
            returned && previous != null && !sameServer(previous.url, attempt.url) ->
                "Couldn't sign in to $target. $reason Still using ${displayAddressOf(previous.url)}."
            previous != null -> "Couldn't sign in to $target. $reason"
            // A first sign-in: the screen shows the client's own error.
            else -> null
        }
    }

    /**
     * Connects to the server used before a failed or abandoned attempt, with its token, or with
     * its password when it has no token, and returns true. With neither, or with no previous
     * server, the attempt's connection is closed instead. The connection names the previous
     * server, so the trust the attempt's server may have gained does not refuse it.
     */
    private suspend fun returnTo(previous: Previous?): Boolean {
        if (previous == null || (previous.token.isBlank() && previous.password.isBlank())) {
            wsClient.disconnect()
            return false
        }
        settings.setSelectedPlayerId(previous.selectedPlayerId)
        if (previous.password.isNotBlank()) wsClient.setSavedCredentials(previous.username, previous.password)
        // A blank token is fine: the client then signs in with the saved password.
        wsClient.connect(
            previous.url,
            previous.token,
            MaWebSocketClient.ConnectOrigin.SAVED_CREDENTIALS,
            previous.serverId
        )
        // After the connect, which replaced the attempt's socket, so that socket's late trust
        // write cannot overwrite this one.
        settings.setTrustedServerId(previous.serverId)
        return true
    }

    /** Drops the previous server's per-account state, its selected player included. */
    private suspend fun leavePrevious() {
        settings.setSelectedPlayerId(null)
        auth.prepareServerSwitch()
    }

    /** Saves the token of a connection that is not an attempt, such as a reconnect. */
    private suspend fun saveConnection(generation: Int) = saveLock.withLock {
        val session = wsClient.authenticatedSession(generation) ?: return@withLock
        settings.saveConnectionToken(session.serverId, session.url, session.token)
    }

    private suspend fun currentAccount(): Previous? {
        val url = settings.serverUrl.first()
        if (url.isBlank()) return null
        return Previous(
            url = url,
            serverId = settings.trustedServerId.first(),
            token = settings.authToken.first(),
            username = settings.username.first(),
            password = settings.password.first(),
            selectedPlayerId = settings.selectedPlayerId.first()
        )
    }

    /** How an attempt signs in, once [begin]'s preparation has read what it needs. */
    private class Credentials(val username: String, val password: String, val connect: () -> Int)

    /** The server and account the app used before an attempt, to return to when it fails. */
    private class Previous(
        val url: String,
        val serverId: String?,
        val token: String,
        val username: String,
        val password: String,
        val selectedPlayerId: String?
    ) {
        override fun toString(): String = "Previous(${displayAddressOf(url)})"
    }

    /** One sign-in. Written by its own job and read by [cancel] and the attempt replacing it. */
    private class Attempt(val url: String, val serverId: String?) {
        @Volatile var previous: Previous? = null
        @Volatile var previousKnown = false
        @Volatile var username = ""
        @Volatile var password = ""
        // This attempt, or one it replaced, connected away from the previous server.
        @Volatile var connectIssued = false
        // This attempt, or one it replaced, dropped the previous server's state.
        @Volatile var leftPrevious = false
        @Volatile var saved = false

        /** Takes over where [replaced] would have returned to, and what it already did. */
        fun takeOver(replaced: Attempt) {
            if (replaced.previousKnown) {
                previous = replaced.previous
                previousKnown = true
            }
            connectIssued = replaced.connectIssued
            leftPrevious = replaced.leftPrevious
        }

        override fun toString(): String = "Attempt(${displayAddressOf(url)})"
    }

    private enum class Leaving { LEAVE, COME_BACK, STAY }

    private companion object {
        const val TAG = "SignIn"
        // A little longer than the client's own handshake watchdog, so this only fires when the
        // attempt never resolves.
        const val ATTEMPT_TIMEOUT_MS = 35_000L
        // An address that does not answer this soon is forgotten by what was saved for it.
        const val FORGET_PROBE_TIMEOUT_MS = 5_000L
    }
}

/** Whether this state ends the attempt whose connection is [generation]. */
private fun ConnectionState.endsAttempt(generation: Int): Boolean = when (this) {
    is ConnectionState.Connected -> this.generation == generation
    is ConnectionState.Error -> this.generation == generation
    // Someone closed the connection, such as Sign out or the user's disconnect.
    ConnectionState.Disconnected -> true
    ConnectionState.Connecting -> false
}

/**
 * Whether the server at [url] with [serverId] is another server than the one at [previousUrl]
 * with [previousId]. The `server_id`s decide when both are known, so the remote and the home
 * address of one server are the same server. Otherwise the addresses decide.
 */
internal fun isOtherServer(previousUrl: String, previousId: String?, url: String, serverId: String?): Boolean =
    if (previousId != null && serverId != null) previousId != serverId else !sameServer(previousUrl, url)
