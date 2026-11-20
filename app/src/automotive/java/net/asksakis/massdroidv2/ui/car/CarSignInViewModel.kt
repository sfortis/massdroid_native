package net.asksakis.massdroidv2.ui.car

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.discovery.ServerPicker
import net.asksakis.massdroidv2.data.repository.QueueTogglesCache
import net.asksakis.massdroidv2.data.signin.SavedSignIn
import net.asksakis.massdroidv2.data.signin.SignInController
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.connection.ChosenServer
import net.asksakis.massdroidv2.domain.connection.ServerRow
import net.asksakis.massdroidv2.domain.model.AuthProviderInfo
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PartyRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * The car (AAOS) sign-in and the settings of the car's own player. The sign-in is the phone's
 * [SignInController] and [ServerPicker], so the car picks a server and signs in the way the
 * phone does. It offers a username and password, the account saved for the server, Home
 * Assistant, whose page the car shows inside the app because it has no browser, and a client
 * certificate.
 */
@HiltViewModel
class CarSignInViewModel @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val queueToggles: QueueTogglesCache,
    partyRepository: PartyRepository,
    serverPickerFactory: ServerPicker.Factory,
    signInFactory: SignInController.Factory,
) : ViewModel() {

    // The phone's sign-in form: what to prefill for a server, "Continue as", and the attempt.
    private val signIn: SignInController = signInFactory.create(viewModelScope)

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    /** Step 1 and step 2 of the sign-in, shared with the phone and the TV. */
    val serverPicker: ServerPicker = serverPickerFactory.create(viewModelScope)

    /** The form's error or the last attempt's; see [SignInController.error]. */
    val error: StateFlow<String?> = signIn.error

    /** Whether the server has Music Assistant's Party plugin, which the Party row needs. */
    val partyAvailable: StateFlow<Boolean> = partyRepository.available
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- MassDroid player audio settings ----------------------------------
    // The car IS the Sendspin player, so these tune its own output. Audio
    // format / compression / dithering are app-level (DataStore) and apply to
    // the native output directly; crossfade is per-player MA config on the car's
    // Sendspin player (loaded once connected, null = not available yet).

    /** Sendspin output format (Smart/Opus/FLAC/PCM), app-level. */
    val audioFormat: StateFlow<SendspinAudioFormat> = settingsRepository.sendspinAudioFormat
        .map { SendspinAudioFormat.fromStored(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SendspinAudioFormat.AUTOMATIC)

    /** Output dynamic-range compression level 0..3 (Off/Soft/Medium/Hard), app-level. */
    val compressorLevel: StateFlow<Int> = settingsRepository.sendspinCompressorLevel
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Noise-shaped dither on the 16-bit output, app-level. */
    val dither: StateFlow<Boolean> = settingsRepository.sendspinDither
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Crossfade mode for the car's own player; null until the config loads. */
    private val _crossfade = MutableStateFlow<CrossfadeMode?>(null)
    val crossfade: StateFlow<CrossfadeMode?> = _crossfade.asStateFlow()

    /**
     * Whether this server keeps crossfade on the queue, which MA 2.10 and later do.
     *
     * The car screen shows the same three buttons either way. What differs is where the
     * choice goes: on a queue server the off is the queue's own crossfade toggle and the
     * other two are its crossfade mode, while an older server holds all three in one
     * player config value.
     */
    private var crossfadeOnQueue = false

    init {
        // Load the per-player crossfade ONCE the WS is connected (the Sendspin player
        // config only exists server-side once registered). Guarded on still-null so a
        // later reconnect - common in a car (tunnels, parking) - cannot stomp a value
        // the user just changed before its save round-trips. Retries while still null
        // (e.g. the player wasn't registered yet on the first connect).
        viewModelScope.launch {
            wsClient.connectionState.collect { state ->
                if (state is ConnectionState.Connected && _crossfade.value == null) loadCrossfade()
            }
        }
        // A Home Assistant start that fails says why on the form, as on the phone.
        viewModelScope.launch { signIn.oauthErrors.collect(signIn::setError) }
    }

    fun setAudioFormat(format: SendspinAudioFormat) {
        viewModelScope.launch { settingsRepository.setSendspinAudioFormat(format.name) }
    }

    fun setCompressorLevel(level: Int) {
        viewModelScope.launch { settingsRepository.setSendspinCompressorLevel(level.coerceIn(0, 3)) }
    }

    fun setDither(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSendspinDither(enabled) }
    }

    fun setCrossfade(mode: CrossfadeMode) {
        val previous = _crossfade.value
        _crossfade.value = mode // optimistic; the server save follows
        viewModelScope.launch {
            val id = settingsRepository.sendspinClientId.first()
            if (id == null) {
                _crossfade.value = previous
                return@launch
            }
            val saved = runCatching {
                if (crossfadeOnQueue) saveQueueCrossfade(id, mode) else savePlayerCrossfade(id, mode)
            }.getOrElse {
                Log.w(TAG, "crossfade save failed: ${it.message}")
                false
            }
            if (!saved) _crossfade.value = previous // roll back so the UI does not lie
        }
    }

    /**
     * MA 2.10 and later. Turning it off is a queue command every account may send;
     * choosing the type is queue config and needs an admin, so a refusal there rolls the
     * whole choice back rather than leaving the screen showing a type that was not saved.
     */
    private suspend fun saveQueueCrossfade(queueId: String, mode: CrossfadeMode): Boolean {
        if (mode == CrossfadeMode.DISABLED) {
            musicRepository.setCrossfadeEnabled(queueId, false)
            return true
        }
        val value = if (mode == CrossfadeMode.SMART) SMART_CROSSFADE else STANDARD_CROSSFADE
        if (!musicRepository.setQueueConfigValue(queueId, QueueChoice.KEY_CROSSFADE_MODE, value)) {
            return false
        }
        musicRepository.setCrossfadeEnabled(queueId, true)
        return true
    }

    /** Before MA 2.10, where one player config value carried the off as well as the type. */
    private suspend fun savePlayerCrossfade(playerId: String, mode: CrossfadeMode): Boolean {
        playerRepository.savePlayerConfig(playerId, mapOf("smart_fades_mode" to mode.apiValue))
        return true
    }

    private suspend fun loadCrossfade() {
        val id = settingsRepository.sendspinClientId.first() ?: return
        val queue = runCatching { musicRepository.getQueueSettings(id) }.getOrNull()
        val queueMode = queue?.crossfadeMode
        if (queueMode != null) {
            crossfadeOnQueue = true
            // Left unpublished when the queue's on/off is not known yet: _crossfade stays
            // null, which is what makes the next connect try again.
            resolveQueueCrossfade(id, queueMode)?.let { _crossfade.value = it }
            return
        }
        val config = runCatching { playerRepository.getPlayerConfig(id) }.getOrNull() ?: return
        _crossfade.value = config.crossfadeMode
    }

    /**
     * Fold the queue's two values into the one the car screen shows, or null when the
     * on/off is not known yet.
     *
     * Off wins over the type, because that is what the listener hears; a type set to
     * follow the server-wide default resolves through it, and anything unrecognised reads
     * as standard, which is what the server itself falls back to.
     *
     * The null case matters. The toggle cache seeds on the same connect that brought us
     * here, so it may not hold this queue within the wait. Reporting that as "off" would
     * publish a value, and publishing a value is what stops the screen from ever asking
     * again: a head unit slow to answer would then show Disabled for the rest of the
     * drive over a crossfade that is really on.
     */
    private suspend fun resolveQueueCrossfade(queueId: String, mode: QueueChoice): CrossfadeMode? {
        val enabled = withTimeoutOrNull(QUEUE_SEED_WAIT_MS) {
            queueToggles.crossfadeStates.first { it.containsKey(queueId) }[queueId]
        } ?: return null
        if (!enabled) return CrossfadeMode.DISABLED
        val effective = if (mode.followsGlobal) mode.globalValue else mode.value
        return if (effective == SMART_CROSSFADE) CrossfadeMode.SMART else CrossfadeMode.STANDARD
    }

    /** The selected client certificate's alias, or null; see [SignInController.clientCertAlias]. */
    val certAlias: StateFlow<String?> = signIn.clientCertAlias

    /** The server the app connects to, for the status line. */
    val serverUrl: StateFlow<String> = settingsRepository.serverUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The server the last sign-in went to; the client's connection error belongs to it. */
    val attemptedUrl: StateFlow<String?> = signIn.attemptedUrl

    fun clearError() = signIn.clearError()

    /** Abandons the sign-in in progress when the user leaves its server; see [SignInController.cancelAttempt]. */
    fun cancelAttempt() = signIn.cancelAttempt()

    /**
     * What the form may offer for [server], as on the phone: that server's saved username, the
     * password only for the current server, and "Continue as" when it has a saved token.
     */
    fun savedSignIn(server: ChosenServer): Flow<SavedSignIn> = signIn.savedSignIn(server)

    /** The server the last sign-in saved; the screen closes on it, see [consumeSuccess]. */
    val succeeded: StateFlow<String?> = signIn.succeeded

    fun consumeSuccess() = signIn.consumeSuccess()

    /**
     * Starts a sign-in; false when the input was rejected and nothing was sent. The server is
     * saved only once it accepted the sign-in, and the app returns to the previous server when
     * it does not; see [SignInController.login].
     */
    fun login(server: ChosenServer, username: String, password: String): Boolean =
        signIn.login(server, username, password)

    /** The sign-in providers of the server checked last; see [SignInController.providers]. */
    val providers: StateFlow<List<AuthProviderInfo>> = signIn.providers

    val oauthInProgress: StateFlow<Boolean> = signIn.oauthInProgress

    /** The Home Assistant page to show; see [SignInController.homeAssistantPage]. */
    val homeAssistantPage: StateFlow<String?> = signIn.homeAssistantPage

    fun openHomeAssistantSignIn(server: ChosenServer) = signIn.openHomeAssistantSignIn(server.url)

    fun deliverHomeAssistantCallback(link: String) = signIn.deliverHomeAssistantCallback(link)

    fun cancelHomeAssistantSignIn() = signIn.cancelHomeAssistantSignIn()

    /** Forgets a server used before from the list; see [SignInController.forget]. */
    fun forget(row: ServerRow) = signIn.forget(row)

    /** Signs in to [server] with the account saved for it, the "Continue as" choice. */
    fun continueAs(server: ChosenServer, saved: SavedSignIn) = signIn.continueAs(server, saved)

    /** Uses the certificate picked in the system KeyChain picker; see [SignInController.selectCertificate]. */
    fun onCertificateSelected(alias: String?, context: Context) = signIn.selectCertificate(alias, context)

    fun clearCertificate() = signIn.clearCertificate()

    /** Signs out of the current server; see [SignInController.signOut]. */
    fun signOut() = signIn.signOut()

    /** Loads the saved certificate again, so a re-opened screen keeps mTLS. */
    fun loadSavedCertificate(context: Context) = signIn.loadSavedCertificate(context)

    companion object {
        /** How long to wait for the queue toggles to seed after a connect. */
        private const val QUEUE_SEED_WAIT_MS = 3_000L
        private const val STANDARD_CROSSFADE = "standard_crossfade"
        private const val SMART_CROSSFADE = "smart_crossfade"
        private const val TAG = "CarSignIn"
    }
}
