package net.asksakis.massdroidv2.data.discovery

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.connection.AnnouncedServer
import net.asksakis.massdroidv2.domain.connection.ChosenServer
import net.asksakis.massdroidv2.domain.connection.HomeAssistantAddress
import net.asksakis.massdroidv2.domain.connection.ServerAddress
import net.asksakis.massdroidv2.domain.connection.ServerAddressInput
import net.asksakis.massdroidv2.domain.connection.ServerCheck
import net.asksakis.massdroidv2.domain.connection.ServerRow
import net.asksakis.massdroidv2.domain.connection.displayAddressOf
import net.asksakis.massdroidv2.domain.connection.serverRows
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * The state of the two-step sign-in that the phone, the TV and the car share.
 *
 * Step 1 lists the server used last and the servers found on the network, and offers an
 * address of the user's own, which is checked while it is typed. Choosing a server checks it
 * as well, so step 2 shows its version, or why it cannot be used, before anybody types a
 * password. Each ViewModel creates one with its own scope through [Factory], so the state
 * lives as long as the screen's ViewModel.
 */
class ServerPicker private constructor(
    private val scope: CoroutineScope,
    private val finder: MaServerFinder,
    private val auth: MaAuthRepository,
    settings: SettingsRepository
) {
    class Factory @Inject constructor(
        private val finder: MaServerFinder,
        private val auth: MaAuthRepository,
        private val settings: SettingsRepository
    ) {
        fun create(scope: CoroutineScope): ServerPicker = ServerPicker(scope, finder, auth, settings)
    }

    /** The rows of step 1. Network discovery runs only while this is collected. */
    val rows: StateFlow<List<ServerRow>> = combine(
        settings.serverUrl,
        finder.servers()
            .catch { e ->
                Log.w(TAG, "Server discovery failed: ${e.message}")
                emit(emptyList())
            }
            .onStart { emit(emptyList()) }
    ) { saved, found ->
        serverRows(saved, found.map { AnnouncedServer(it.url, it.name, it.version, it.isHomeAssistantAddon) })
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _chosen = MutableStateFlow<ChosenServer?>(null)

    /** The server step 2 signs in to, or null while step 1 shows. */
    val chosen: StateFlow<ChosenServer?> = _chosen.asStateFlow()

    private val _typedCheck = MutableStateFlow<ServerCheck>(ServerCheck.Idle)

    /** What the address typed under Other server leads to. */
    val typedCheck: StateFlow<ServerCheck> = _typedCheck.asStateFlow()

    private var chosenJob: Job? = null
    private var typedJob: Job? = null

    /** Goes to step 2 with the server at [url] and checks it. */
    fun choose(url: String, name: String) {
        chosenJob?.cancel()
        _chosen.value = ChosenServer(url, name, ServerCheck.Checking)
        chosenJob = scope.launch {
            val result = auth.checkServer(url)
            _chosen.update { current -> if (current?.url == url) current.copy(check = result) else current }
        }
    }

    /** Checks the chosen server again, after it did not answer. */
    fun checkAgain() {
        _chosen.value?.let { choose(it.url, it.name) }
    }

    /** Back to step 1. */
    fun changeServer() {
        chosenJob?.cancel()
        _chosen.value = null
    }

    /**
     * Checks a typed address once the typing pauses. Every keystroke resets the answer, so a
     * half-typed address never shows an error.
     */
    fun checkTyped(input: ServerAddressInput) {
        typedJob?.cancel()
        _typedCheck.value = ServerCheck.Idle
        typedJob = scope.launch {
            delay(TYPING_PAUSE_MS)
            _typedCheck.value = when (val address = input.toServerAddress()) {
                ServerAddress.Empty -> ServerCheck.Idle
                is ServerAddress.Invalid -> ServerCheck.Problem(address.message)
                is ServerAddress.Valid -> checkValid(address)
            }
        }
    }

    private suspend fun checkValid(address: ServerAddress.Valid): ServerCheck {
        val homeAssistant = address.homeAssistant
        if (homeAssistant != null) {
            // Home Assistant never answers as Music Assistant, so the useful answer is where
            // Music Assistant is, when it runs next to it.
            val instead = if (homeAssistant == HomeAssistantAddress.HOME_ASSISTANT) {
                finder.musicAssistantInsteadOf(address)
            } else {
                null
            }
            return ServerCheck.Problem(homeAssistant.hint, suggestion = instead)
        }
        _typedCheck.value = ServerCheck.Checking
        return auth.checkServer(address.url)
    }

    /**
     * Goes to step 2 with the typed server, which already answered, so it is not checked a
     * second time. Returns false while the typed address has not answered.
     */
    fun useTyped(): Boolean {
        val found = _typedCheck.value as? ServerCheck.Found ?: return false
        chosenJob?.cancel()
        _chosen.value = ChosenServer(found.url, displayAddressOf(found.url), found)
        clearTyped()
        return true
    }

    /** Forgets the typed address's answer, when Other server closes. */
    fun clearTyped() {
        typedJob?.cancel()
        _typedCheck.value = ServerCheck.Idle
    }

    private companion object {
        const val TAG = "ServerPicker"
        const val STOP_TIMEOUT_MS = 5_000L
        const val TYPING_PAUSE_MS = 500L
    }
}
