package net.asksakis.massdroidv2.ui.screens.settings

import android.content.Context
import android.security.KeyChain
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SyncProbeOutcome
import net.asksakis.massdroidv2.data.update.AppUpdateChecker
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.whatsnew.WhatsNewRepository
import net.asksakis.massdroidv2.domain.repository.AlbumScore
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.BlockedArtistInfo
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import net.asksakis.massdroidv2.domain.repository.TrackScore
import net.asksakis.massdroidv2.domain.shortcut.ShortcutAction
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher
import net.asksakis.massdroidv2.domain.whatsnew.WhatsNewRelease
import net.asksakis.massdroidv2.ui.failureMessage
import javax.inject.Inject

data class UpdateUiState(
    val appVersion: String,
    val includeBetaUpdates: Boolean = false,
    val availableUpdate: AppUpdateChecker.UpdateInfo? = null,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int? = null,
    val message: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val wsClient: MaWebSocketClient,
    private val appUpdateChecker: AppUpdateChecker,
    private val sendspinManager: SendspinManager,
    private val playHistoryRepository: PlayHistoryRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val libraryGenreEnricher: net.asksakis.massdroidv2.data.genre.LibraryGenreEnricher,
    private val genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository,
    private val maAuthRepository: net.asksakis.massdroidv2.domain.repository.MaAuthRepository,
    private val whatsNewRepository: WhatsNewRepository,
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val shortcutDispatcher: ShortcutActionDispatcher
) : ViewModel() {

    /**
     * The release notes that shipped with this build, for the list About shows in place.
     *
     * Read once per screen rather than held somewhere longer lived, because it is a small
     * file and About is not a screen anybody sits on. Null until the read finishes, and
     * null as well when the build carries no notes.
     */
    val whatsNew: StateFlow<WhatsNewRelease?> = flow { emit(whatsNewRepository.load()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val availableAuthProviders = maAuthRepository.availableProviders
    val oauthInProgress = maAuthRepository.oauthInProgress
    val oauthErrors = maAuthRepository.oauthErrors

    /**
     * Resolved identity of the currently authenticated user, or null when not
     * signed in. Derived from the saved JWT (preferred) or falls back to the
     * saved built-in username so the UI can still show "Signed in as X" even
     * if the token couldn't be parsed.
     */
    val currentUser: StateFlow<net.asksakis.massdroidv2.domain.repository.CurrentUser?> =
        kotlinx.coroutines.flow.combine(
            settingsRepository.authToken,
            settingsRepository.username
        ) { token, username ->
            net.asksakis.massdroidv2.data.websocket.MaTokenInfo.decode(token)
                ?: username.takeIf { it.isNotBlank() }?.let {
                    net.asksakis.massdroidv2.domain.repository.CurrentUser(
                        username = it,
                        authMethod = "Username & password"
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Triggered when the user's typed server URL stabilises. Refreshes the
     * provider list so the UI can show "Sign in with Home Assistant" if the
     * server advertises it.
     */
    fun probeAuthProviders(url: String) {
        viewModelScope.launch {
            maAuthRepository.probeProviders(normalizeUrl(url))
        }
    }

    /**
     * Returns the URL to open in a browser tab to begin the HA OAuth flow,
     * or null on failure (errors are emitted to [oauthErrors]).
     */
    suspend fun startHomeAssistantOAuth(url: String): String? {
        val normalized = normalizeUrl(url)
        if (normalized.isBlank()) {
            _loginError.value = "Couldn't sign in with Home Assistant. Enter the server URL."
            return null
        }
        // No message of its own: the URL field already says the scheme is missing and
        // offers the fix, so a second copy under the form only repeated it.
        if (!isUrlValid(normalized)) return null
        viewModelScope.launch { settingsRepository.setServerUrl(normalized) }
        return maAuthRepository.startHomeAssistantOAuth(normalized)
    }

    fun cancelOAuth() {
        maAuthRepository.cancelOAuth()
    }

    fun signOut() {
        viewModelScope.launch {
            maAuthRepository.signOut()
        }
    }

    val enrichmentProgress = libraryGenreEnricher.progress

    companion object {
        private const val TAG = "SettingsVM"
    }

    val serverUrl = settingsRepository.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val authToken = settingsRepository.authToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val clientCertAlias = settingsRepository.clientCertAlias
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val connectionState = wsClient.connectionState
    val isReconnecting = wsClient.isReconnecting

    val sendspinState = sendspinManager.connectionState
    val sendspinEnabled = settingsRepository.sendspinEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val knownBtDevices = settingsRepository.knownBtDevices
    val carAudioBtDevices = settingsRepository.carAudioBtDevices

    fun setCarAudioBtDevice(routeKey: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCarAudioBtDevice(routeKey, enabled)
        }
    }

    val sendspinCompressorLevel = settingsRepository.sendspinCompressorLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setSendspinCompressorLevel(level: Int) {
        viewModelScope.launch {
            settingsRepository.setSendspinCompressorLevel(level)
        }
    }

    val sendspinDither = settingsRepository.sendspinDither
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setSendspinDither(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSendspinDither(enabled)
        }
    }

    // Phone-as-speaker output format (stored as the enum name). SendspinCoordinator
    // writes a change to this player's server config, same as the per-player dialog.
    val sendspinAudioFormat = settingsRepository.sendspinAudioFormat
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            net.asksakis.massdroidv2.domain.model.SendspinAudioFormat.AUTOMATIC.name,
        )

    fun setSendspinAudioFormat(format: net.asksakis.massdroidv2.domain.model.SendspinAudioFormat) {
        viewModelScope.launch {
            settingsRepository.setSendspinAudioFormat(format.name)
        }
    }
    val smartListeningEnabled = settingsRepository.smartListeningEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val smartMixVariety = settingsRepository.smartMixVariety
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.5f)
    val smartMixDiscovery = settingsRepository.smartMixDiscovery
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.5f)
    val smartMixLength = settingsRepository.smartMixLength
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.5f)
    val smartMixStrictness = settingsRepository.smartMixStrictness
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.5f)
    val includeBetaUpdates = settingsRepository.includeBetaUpdates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()
    private val _recommendationBusy = MutableStateFlow(false)
    val recommendationBusy: StateFlow<Boolean> = _recommendationBusy.asStateFlow()
    private val _recommendationMessage = MutableStateFlow<String?>(null)
    val recommendationMessage: StateFlow<String?> = _recommendationMessage.asStateFlow()
    private val _topArtists = MutableStateFlow<List<ArtistScore>>(emptyList())
    val topArtists: StateFlow<List<ArtistScore>> = _topArtists.asStateFlow()
    private val _topTracks = MutableStateFlow<List<TrackScore>>(emptyList())
    val topTracks: StateFlow<List<TrackScore>> = _topTracks.asStateFlow()
    private val _topAlbums = MutableStateFlow<List<AlbumScore>>(emptyList())
    val topAlbums: StateFlow<List<AlbumScore>> = _topAlbums.asStateFlow()
    private val _topGenres = MutableStateFlow<List<GenreScore>>(emptyList())
    val topGenres: StateFlow<List<GenreScore>> = _topGenres.asStateFlow()
    val themeMode = settingsRepository.themeMode
    fun setThemeMode(mode: String) { viewModelScope.launch { settingsRepository.setThemeMode(mode) } }

    private val _blockedArtists = MutableStateFlow<List<BlockedArtistInfo>>(emptyList())
    val blockedArtists: StateFlow<List<BlockedArtistInfo>> = _blockedArtists.asStateFlow()
    private val _updateUiState = MutableStateFlow(
        UpdateUiState(appVersion = appUpdateChecker.getCurrentVersion())
    )
    val updateUiState: StateFlow<UpdateUiState> = combine(
        _updateUiState,
        includeBetaUpdates
    ) { state, includeBeta ->
        state.copy(includeBetaUpdates = includeBeta)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UpdateUiState(appVersion = appUpdateChecker.getCurrentVersion())
    )

    val savedUsername = settingsRepository.username
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val savedPassword = settingsRepository.password
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        refreshRecommendationData()
    }

    // Token persistence is handled by MassDroidApp's connectionState observer

    /**
     * Light shaping for the URL: trim whitespace and strip a trailing slash.
     * No silent scheme prepend — the UI surfaces a validation hint with
     * suggestion chips so the user explicitly picks http:// or https://.
     */
    private fun normalizeUrl(raw: String): String {
        return raw.trim().trimEnd('/')
    }

    private fun isUrlValid(url: String): Boolean = url.contains("://")

    fun login(url: String, username: String, password: String) {
        val u = normalizeUrl(url)
        val user = username.trim()
        val pass = password.trim()
        if (u.isBlank() || user.isBlank() || pass.isBlank()) {
            _loginError.value = "Couldn't sign in. Fill in the server, username and password."
            return
        }
        // The URL field already says the scheme is missing (see startHomeAssistantOAuth).
        if (!isUrlValid(u)) return
        _loginError.value = null
        viewModelScope.launch {
            settingsRepository.setServerUrl(u)
            settingsRepository.setUsername(user)
            settingsRepository.setPassword(pass)
        }
        wsClient.setSavedCredentials(user, pass)
        wsClient.connectWithLogin(u, user, pass) { token ->
            viewModelScope.launch {
                settingsRepository.setAuthToken(token)
            }
        }
    }

    fun connectWithToken(url: String? = null) {
        val connectUrl = normalizeUrl((url ?: serverUrl.value))
        val token = authToken.value
        if (connectUrl.isBlank()) {
            _loginError.value = "Couldn't sign in. Enter the server URL."
            return
        }
        // The URL field already says the scheme is missing (see startHomeAssistantOAuth).
        if (!isUrlValid(connectUrl)) return
        if (token.isBlank()) {
            _loginError.value = "Couldn't sign in with the saved token. Sign in with your username and password."
            return
        }
        _loginError.value = null
        viewModelScope.launch { settingsRepository.setServerUrl(connectUrl) }
        wsClient.connect(connectUrl, token)
    }

    fun disconnect() {
        wsClient.disconnect()
    }

    fun clearLoginError() {
        _loginError.value = null
    }

    fun setLoginError(message: String) {
        _loginError.value = message
    }

    fun clearUpdateMessage() {
        _updateUiState.update { it.copy(message = null) }
    }

    fun toggleIncludeBetaUpdates(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setIncludeBetaUpdates(enabled)
        }
    }

    fun dismissUpdateDialog() {
        _updateUiState.update { it.copy(availableUpdate = null) }
    }

    fun checkForUpdates(force: Boolean = true) {
        // No in-app updater in the fdroid flavor (F-Droid handles updates).
        if (!net.asksakis.massdroidv2.BuildConfig.ENABLE_UPDATE_CHECK) return
        if (_updateUiState.value.isChecking || _updateUiState.value.isDownloading) return
        viewModelScope.launch {
            _updateUiState.update {
                it.copy(
                    isChecking = true,
                    isDownloading = false,
                    downloadProgress = null,
                    message = null
                )
            }
            when (val result = appUpdateChecker.checkForUpdates(force = force, includePrerelease = includeBetaUpdates.value)) {
                is AppUpdateChecker.CheckResult.UpdateAvailable -> {
                    _updateUiState.update {
                        it.copy(
                            availableUpdate = result.info,
                            isChecking = false
                        )
                    }
                }
                is AppUpdateChecker.CheckResult.UpToDate -> {
                    _updateUiState.update {
                        it.copy(
                            availableUpdate = null,
                            isChecking = false,
                            message = "You're on the latest version"
                        )
                    }
                }
                is AppUpdateChecker.CheckResult.Error -> {
                    // The checker's text is a log line ("GitHub returned 403"), so it goes
                    // to the log and the row says what did not happen.
                    Log.w(TAG, "Update check failed: ${result.message}")
                    _updateUiState.update {
                        it.copy(
                            availableUpdate = null,
                            isChecking = false,
                            message = "Couldn't check for updates"
                        )
                    }
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val info = _updateUiState.value.availableUpdate ?: return
        if (_updateUiState.value.isChecking || _updateUiState.value.isDownloading) return
        viewModelScope.launch {
            _updateUiState.update {
                it.copy(
                    isChecking = false,
                    isDownloading = true,
                    downloadProgress = 0,
                    message = null
                )
            }
            val result = appUpdateChecker.downloadUpdate(info) { progress ->
                _updateUiState.update { state ->
                    state.copy(downloadProgress = progress)
                }
            }
            result.onSuccess { file ->
                _updateUiState.update {
                    it.copy(
                        availableUpdate = null,
                        isDownloading = false,
                        downloadProgress = null
                    )
                }
                if (!appContext.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${appContext.packageName}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(settingsIntent)
                    _updateUiState.update { state ->
                        state.copy(message = "Allow app installs, then check for updates again")
                    }
                    return@onSuccess
                }
                appContext.startActivity(appUpdateChecker.buildInstallIntent(file))
            }.onFailure { error ->
                // No reason appended: the checker's messages are log lines ("Download failed
                // with 404"), and failureMessage would describe a network error as the Music
                // Assistant server being unreachable, which this download never talks to.
                Log.w(TAG, "Update download failed: ${error.message}")
                _updateUiState.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        message = "Couldn't download the update"
                    )
                }
            }
        }
    }

    fun onCertificateSelected(alias: String?, context: Context) {
        if (alias == null) return
        viewModelScope.launch {
            settingsRepository.setClientCertAlias(alias)
            loadCertificate(alias, context)
        }
    }

    fun loadSavedCertificate(context: Context) {
        viewModelScope.launch {
            val alias = settingsRepository.clientCertAlias.first()
            if (alias != null) {
                loadCertificate(alias, context)
            }
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

    fun clearCertificate() {
        viewModelScope.launch {
            settingsRepository.setClientCertAlias(null)
            wsClient.clearMtls()
        }
    }

    fun toggleSendspin(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSendspinEnabled(enabled)
        }
    }

    sealed interface SyncProbeUiState {
        data object Idle : SyncProbeUiState
        data object Running : SyncProbeUiState
        data class Finished(val outcome: SyncProbeOutcome) : SyncProbeUiState
    }

    private val _syncProbe = MutableStateFlow<SyncProbeUiState>(SyncProbeUiState.Idle)
    val syncProbe: StateFlow<SyncProbeUiState> = _syncProbe.asStateFlow()

    /** Debug builds only: the caller has already been granted RECORD_AUDIO. */
    fun runSyncProbe() {
        if (_syncProbe.value == SyncProbeUiState.Running) return
        _syncProbe.value = SyncProbeUiState.Running
        viewModelScope.launch {
            _syncProbe.value = SyncProbeUiState.Finished(sendspinManager.runSyncProbe())
        }
    }

    fun dismissSyncProbe() {
        if (_syncProbe.value is SyncProbeUiState.Finished) _syncProbe.value = SyncProbeUiState.Idle
    }

    fun setSmartMixVariety(value: Float) {
        viewModelScope.launch {
            settingsRepository.setSmartMixVariety(value)
        }
    }

    fun setSmartMixDiscovery(value: Float) {
        viewModelScope.launch {
            settingsRepository.setSmartMixDiscovery(value)
        }
    }

    fun setSmartMixLength(value: Float) {
        viewModelScope.launch {
            settingsRepository.setSmartMixLength(value)
        }
    }

    fun setSmartMixStrictness(value: Float) {
        viewModelScope.launch {
            settingsRepository.setSmartMixStrictness(value)
        }
    }

    fun resetSmartMixTuning() {
        viewModelScope.launch {
            settingsRepository.resetSmartMixTuning()
        }
    }

    fun toggleSmartListening(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSmartListeningEnabled(enabled)
        }
    }

    fun clearRecommendationMessage() {
        _recommendationMessage.value = null
    }

    fun refreshRecommendationData() {
        if (_recommendationBusy.value) return
        viewModelScope.launch {
            _recommendationBusy.value = true
            try {
                loadRecommendationData()
            } catch (e: Exception) {
                Log.e(TAG, "refreshRecommendationData failed: ${e.message}")
                _recommendationMessage.value = "Couldn't load the recommendation stats"
            } finally {
                _recommendationBusy.value = false
            }
        }
    }

    fun resetRecommendationDatabase() {
        if (_recommendationBusy.value) return
        viewModelScope.launch {
            _recommendationBusy.value = true
            try {
                playHistoryRepository.clearRecommendationData()
                // The cool-down windows live in DataStore, not in the database, so
                // clearing the history alone left them behind: tracks would stay
                // suppressed from mixes although the plays that suppressed them were
                // gone. Same for the cluster rotation.
                settingsRepository.setRecentMixTrackUris(emptyList())
                settingsRepository.setRecentMixArtistKeys(emptyList())
                settingsRepository.setRecentMixGenres(emptyList())
                settingsRepository.setRecentSeedClusterGenres(emptyList())
                loadRecommendationData()
                _recommendationMessage.value = "Recommendation data reset"
            } catch (e: Exception) {
                Log.e(TAG, "resetRecommendationDatabase failed: ${e.message}")
                _recommendationMessage.value = "Couldn't reset the recommendation data"
            } finally {
                _recommendationBusy.value = false
            }
        }
    }

    /**
     * Clears the blocked-artist list only, leaving the learned history and scores in
     * place. Separate from [resetRecommendationDatabase] because the two are
     * different kinds of data: one the listener stated, the other the engine guessed.
     */
    fun resetBlockedArtists() {
        if (_recommendationBusy.value) return
        viewModelScope.launch {
            _recommendationBusy.value = true
            try {
                smartListeningRepository.clearBlockedArtists()
                loadRecommendationData()
                _recommendationMessage.value = "All artists unblocked"
            } catch (e: Exception) {
                Log.e(TAG, "resetBlockedArtists failed: ${e.message}")
                _recommendationMessage.value = "Couldn't unblock all artists"
            } finally {
                _recommendationBusy.value = false
            }
        }
    }

    fun unblockArtist(artistUri: String, artistName: String?) {
        if (_recommendationBusy.value) return
        viewModelScope.launch {
            _recommendationBusy.value = true
            try {
                smartListeningRepository.setArtistBlocked(artistUri, artistName, blocked = false)
                loadRecommendationData()
                _recommendationMessage.value = "Artist unblocked"
            } catch (e: Exception) {
                Log.e(TAG, "unblockArtist failed: ${e.message}")
                _recommendationMessage.value = "Couldn't unblock that artist"
            } finally {
                _recommendationBusy.value = false
            }
        }
    }

    /**
     * Plays a track from the Insights ranking on the selected player, the same primary play
     * Search uses. No option is sent, so the server applies its default for a track, which
     * is "play": the track starts now and the rest of the queue is kept.
     */
    fun playInsightsTrack(trackUri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, trackUri)
            } catch (e: Exception) {
                Log.w(TAG, "playInsightsTrack failed: ${e.message}")
                _recommendationMessage.value = e.failureMessage("Couldn't play that track")
            }
        }
    }

    /**
     * Starts the Genre Radio for a genre from the Insights ranking. It goes through the
     * shortcut dispatcher, as Android Auto does, because DiscoverViewModel owns the radio
     * (the player check, the spam guard and the overlay) and a second start path here
     * would bypass them.
     */
    fun startInsightsGenreRadio(genre: String) {
        shortcutDispatcher.dispatch(ShortcutAction.GenreRadio(genre))
    }

    private suspend fun loadRecommendationData() = coroutineScope {
        val artistsDef = async { playHistoryRepository.getTopArtists(days = 90, limit = 10) }
        val tracksDef = async { playHistoryRepository.getTopTracks(days = 90, limit = 10) }
        val albumsDef = async { playHistoryRepository.getTopAlbums(days = 90, limit = 10) }
        val genresDef = async { genreRepository.topGenres(days = 90, limit = 10) }
        val blockedDef = async { smartListeningRepository.getBlockedArtists() }

        _topArtists.value = artistsDef.await()
        _topTracks.value = tracksDef.await()
        _topAlbums.value = albumsDef.await()
        _topGenres.value = genresDef.await()
        _blockedArtists.value = blockedDef.await()
    }
}
