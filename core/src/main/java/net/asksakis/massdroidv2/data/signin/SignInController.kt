package net.asksakis.massdroidv2.data.signin

import android.content.Context
import android.security.KeyChain
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.data.websocket.MaTokenInfo
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.connection.ChosenServer
import net.asksakis.massdroidv2.domain.connection.ServerCheck
import net.asksakis.massdroidv2.domain.connection.ServerRow
import net.asksakis.massdroidv2.domain.connection.normalizedAddress
import net.asksakis.massdroidv2.domain.model.AuthProviderInfo
import net.asksakis.massdroidv2.domain.model.ServerAccount
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * The sign-in form of the server chosen in step 1: with a username and password, with the
 * account saved for that server, or through Home Assistant, plus the client certificate for
 * servers behind mutual TLS. The phone's Settings screen, its onboarding and the car's sign-in
 * share it, so all three sign in the same way. Each ViewModel creates one with its own scope
 * through [Factory]; the attempt itself runs in the app-scoped [SignInSession], so leaving the
 * screen does not stop it.
 */
class SignInController private constructor(
    private val scope: CoroutineScope,
    private val wsClient: MaWebSocketClient,
    private val settings: SettingsRepository,
    private val auth: MaAuthRepository,
    private val session: SignInSession
) {
    class Factory @Inject constructor(
        private val wsClient: MaWebSocketClient,
        private val settings: SettingsRepository,
        private val auth: MaAuthRepository,
        private val session: SignInSession
    ) {
        fun create(scope: CoroutineScope): SignInController =
            SignInController(scope, wsClient, settings, auth, session)
    }

    private val formError = MutableStateFlow<String?>(null)

    /**
     * What went wrong: with the form itself, such as an empty field or a failed Home Assistant
     * sign-in, or with the last attempt, when the app went back to the previous server.
     */
    val error: StateFlow<String?> = combine(formError, session.error) { form, attempt -> form ?: attempt }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The server the last sign-in went to. The client's connection error belongs to that
     * attempt, so a screen shows it only while the same server is chosen.
     */
    val attemptedUrl: StateFlow<String?> = session.attemptedUrl

    /** The sign-in providers of the server checked last. */
    val providers: StateFlow<List<AuthProviderInfo>> = auth.availableProviders
    val oauthInProgress: StateFlow<Boolean> = auth.oauthInProgress
    val oauthErrors: SharedFlow<String> = auth.oauthErrors

    val clientCertAlias: StateFlow<String?> =
        settings.clientCertAlias.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * What the form may offer for [server]. The account saved for it gives the username and the
     * "Continue as" choice. The saved password belongs to the current server only, so another
     * server never sees it, not even prefilled.
     */
    fun savedSignIn(server: ChosenServer): Flow<SavedSignIn> {
        val serverId = (server.check as? ServerCheck.Found)?.serverId
        val account = serverId?.let(settings::serverAccount) ?: flowOf(null)
        return combine(
            settings.serverUrl,
            settings.trustedServerId,
            settings.username,
            settings.password,
            account
        ) { savedUrl, trustedId, username, password, saved ->
            savedSignInFor(
                server = server.url,
                serverId = serverId,
                current = CurrentAccount(savedUrl, trustedId, username, password),
                saved = saved
            )
        }
    }

    /**
     * Signs in to [server] with a username and password; see [SignInSession.login]. The
     * password goes only to the server the check named. When the check named none and the
     * password is the one saved for the current server, it goes only to that server.
     */
    fun login(server: ChosenServer, username: String, password: String) {
        val user = username.trim()
        val pass = password.trim()
        if (user.isBlank() || pass.isBlank()) {
            formError.value = "Enter your username and password."
            return
        }
        formError.value = null
        session.login(server.url, (server.check as? ServerCheck.Found)?.serverId, user, pass) {
            val saved = savedSignIn(server).first()
            saved.passwordServerId.takeIf { saved.password.isNotEmpty() && saved.password == pass }
        }
    }

    /** Forgets a server used before from the list; see [SignInSession.forget]. */
    fun forget(row: ServerRow) {
        if (row.canForget) session.forget(row.url)
    }

    /** The server the last sign-in saved, until [consumeSuccess]; see [SignInSession.succeeded]. */
    val succeeded: StateFlow<String?> = session.succeeded

    fun consumeSuccess() = session.consumeSuccess()

    /** Signs out of the current server; see [SignInSession.signOut]. */
    suspend fun signOut() = session.signOut()

    /** Signs in to [server] with the account saved for it, the "Continue as" choice. */
    fun continueAs(server: ChosenServer, saved: SavedSignIn) {
        val serverId = saved.serverId ?: return
        formError.value = null
        session.continueWithSavedAccount(server.url, serverId)
    }

    /**
     * Returns the URL to open in a browser tab to begin the Home Assistant sign-in, or null on
     * failure, which [oauthErrors] reports. The server is saved when the sign-in completes, not
     * here, so a sign-in the user abandons in the browser changes nothing.
     */
    suspend fun startHomeAssistantOAuth(serverUrl: String): String? {
        clearError()
        return auth.startHomeAssistantOAuth(serverUrl)
    }

    /** Abandons the sign-in in progress when the user leaves its server; see [SignInSession.cancel]. */
    fun cancelAttempt() {
        formError.value = null
        session.cancel()
    }

    fun clearError() {
        formError.value = null
        session.clearError()
    }

    fun setError(message: String) {
        formError.value = message
    }

    /** Uses the certificate the user picked in the system KeyChain picker. A null alias is a cancel. */
    fun selectCertificate(alias: String?, context: Context) {
        if (alias == null) return
        val appContext = context.applicationContext
        scope.launch {
            settings.setClientCertAlias(alias)
            loadCertificate(alias, appContext)
        }
    }

    /** Loads the saved certificate again, for a screen that may have been opened after a change. */
    fun loadSavedCertificate(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            settings.clientCertAlias.first()?.let { loadCertificate(it, appContext) }
        }
    }

    fun clearCertificate() {
        scope.launch {
            settings.setClientCertAlias(null)
            wsClient.clearMtls()
        }
    }

    private suspend fun loadCertificate(alias: String, context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val privateKey = KeyChain.getPrivateKey(context, alias)
                val certChain = KeyChain.getCertificateChain(context, alias)
                if (privateKey != null && certChain != null) {
                    wsClient.configureMtls(privateKey, certChain)
                    Log.d(TAG, "mTLS loaded: $alias")
                } else {
                    Log.e(TAG, "Failed to load cert for alias: $alias")
                    forgetCertificate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cert: ${e.message}")
                forgetCertificate()
            }
        }
    }

    private suspend fun forgetCertificate() {
        settings.setClientCertAlias(null)
        wsClient.clearMtls()
    }

    private companion object {
        const val TAG = "SignIn"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * What the sign-in form may offer for one server.
 *
 * @property serverId the server's `server_id`, when it answered the check.
 * @property continueAs the name of the account saved for the server, or null when it has no
 *   saved token.
 * @property passwordServerId the server the offered [password] belongs to, so it is sent only
 *   there even when the check has not answered; null when no password is offered, or when the
 *   saved account names no server (saved before the server was recorded).
 */
data class SavedSignIn(
    val serverId: String?,
    val username: String,
    val password: String,
    val continueAs: String?,
    val passwordServerId: String? = null
) {
    // The password is a secret, so it never reaches a log.
    override fun toString(): String =
        "SavedSignIn(serverId=$serverId, username=$username, hasPassword=${password.isNotEmpty()}, " +
            "continueAs=$continueAs, passwordServerId=$passwordServerId)"

    companion object {
        val NONE = SavedSignIn(serverId = null, username = "", password = "", continueAs = null)
    }
}

/** The account of the current server, as the keys that are not per server hold it. */
internal data class CurrentAccount(
    val url: String,
    val serverId: String?,
    val username: String,
    val password: String
)

/**
 * What the form may offer for the server at [server] whose check gave [serverId], given the
 * [current] server's account and the account [saved] for [serverId]. The server is the current
 * one when the two `server_id`s match, or by address when either is unknown. Only then is the
 * password offered.
 */
internal fun savedSignInFor(
    server: String,
    serverId: String?,
    current: CurrentAccount,
    saved: ServerAccount?,
    nameOf: (ServerAccount) -> String = ::accountName
): SavedSignIn {
    val isCurrent = if (serverId != null && current.serverId != null) {
        serverId == current.serverId
    } else {
        sameServer(current.url, server)
    }
    // The saved password is offered only with the server it belongs to, so it can be bound to
    // that server; with no server known at all it would go to whatever answers.
    val owner = serverId ?: current.serverId
    val password = current.password.takeIf { isCurrent && owner != null }.orEmpty()
    return SavedSignIn(
        serverId = serverId,
        username = saved?.username?.takeIf { it.isNotBlank() } ?: current.username.takeIf { isCurrent }.orEmpty(),
        password = password,
        continueAs = saved?.takeIf { it.hasToken }?.let(nameOf),
        passwordServerId = owner.takeIf { password.isNotEmpty() }
    )
}

/** The name to show for a saved account: the user the token names, else the saved username. */
private fun accountName(account: ServerAccount): String =
    MaTokenInfo.decode(account.token)?.username ?: account.username.ifBlank { "saved account" }

/** Whether two saved or chosen server URLs are the same address; see [normalizedAddress]. */
fun sameServer(a: String, b: String): Boolean =
    normalizedAddress(a).equals(normalizedAddress(b), ignoreCase = true)
