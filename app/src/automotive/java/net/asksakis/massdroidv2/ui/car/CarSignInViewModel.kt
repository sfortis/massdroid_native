package net.asksakis.massdroidv2.ui.car

import android.content.Context
import android.security.KeyChain
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.repository.QueueTogglesCache
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.repository.MaAuthRepository
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Minimal car (AAOS) sign-in / server-config view model. Reuses the shared
 * :core [MaWebSocketClient] login flow exactly like the phone SettingsViewModel
 * and the TV onboarding screen, but stays intentionally small (username/password
 * + optional client certificate) so the parked car screen never pulls in the
 * full phone settings surface. OAuth is deliberately omitted (it needs a browser
 * round-trip that is not reliable in a car).
 */
@HiltViewModel
class CarSignInViewModel @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val authRepository: MaAuthRepository,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val queueToggles: QueueTogglesCache,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ---- MassDroid player audio settings ----------------------------------
    // The car IS the Sendspin player, so these tune its own output. Audio
    // format / compression / dithering are app-level (DataStore) and apply to
    // the native output directly; crossfade is per-player MA config on the car's
    // Sendspin player (loaded once connected, null = not available yet).

    /** Sendspin output format (Smart/Opus/FLAC/PCM), app-level. */
    val audioFormat: StateFlow<SendspinAudioFormat> = settingsRepository.sendspinAudioFormat
        .map { SendspinAudioFormat.fromStored(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SendspinAudioFormat.SMART)

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

    /** Currently selected mTLS client-certificate alias, or null. */
    val certAlias: StateFlow<String?> = settingsRepository.clientCertAlias
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Saved server URL + username, surfaced once to pre-fill the form. */
    val prefill: StateFlow<Prefill?> = combine(
        settingsRepository.serverUrl,
        settingsRepository.username,
    ) { url, username -> Prefill(url, username) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    data class Prefill(val url: String, val username: String)

    fun login(rawUrl: String, username: String, password: String) {
        val url = rawUrl.trim().trimEnd('/')
        val user = username.trim()
        val pass = password.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _error.value = "Add http:// or https:// to the URL"
            return
        }
        if (user.isBlank() || pass.isBlank()) {
            _error.value = "Enter username and password"
            return
        }
        _error.value = null
        viewModelScope.launch {
            settingsRepository.setServerUrl(url)
            settingsRepository.setUsername(user)
            settingsRepository.setPassword(pass)
        }
        wsClient.setSavedCredentials(user, pass)
        wsClient.connectWithLogin(url, user, pass) { token ->
            viewModelScope.launch { settingsRepository.setAuthToken(token) }
        }
    }

    /**
     * Apply a client certificate picked from the Android KeyChain. Mirrors the
     * phone settings cert flow: persist the alias, load the key/chain off-main,
     * and reconfigure the shared OkHttp/WebSocket client for mTLS.
     */
    fun onCertificateSelected(alias: String?, context: Context) {
        if (alias == null) return
        viewModelScope.launch {
            settingsRepository.setClientCertAlias(alias)
            loadCertificate(alias, context.applicationContext)
        }
    }

    fun clearCertificate() {
        viewModelScope.launch {
            settingsRepository.setClientCertAlias(null)
            wsClient.clearMtls()
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
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
                    settingsRepository.setClientCertAlias(null)
                    wsClient.clearMtls()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cert: ${e.message}")
                settingsRepository.setClientCertAlias(null)
                wsClient.clearMtls()
            }
        }
    }

    /** One-shot: load the saved cert (if any) so a re-opened screen keeps mTLS. */
    fun loadSavedCertificate(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val alias = settingsRepository.clientCertAlias.first() ?: return@launch
            loadCertificate(alias, appContext)
        }
    }

    companion object {
        /** How long to wait for the queue toggles to seed after a connect. */
        private const val QUEUE_SEED_WAIT_MS = 3_000L
        private const val STANDARD_CROSSFADE = "standard_crossfade"
        private const val SMART_CROSSFADE = "smart_crossfade"
        private const val TAG = "CarSignIn"
    }
}
