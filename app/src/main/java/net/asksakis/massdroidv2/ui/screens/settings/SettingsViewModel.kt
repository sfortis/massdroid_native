package net.asksakis.massdroidv2.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SyncProbeOutcome
import net.asksakis.massdroidv2.data.discovery.ServerPicker
import net.asksakis.massdroidv2.data.signin.SignInController
import net.asksakis.massdroidv2.data.update.AppUpdateChecker
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.whatsnew.WhatsNewRepository
import net.asksakis.massdroidv2.domain.repository.BlockedArtistInfo
import net.asksakis.massdroidv2.domain.repository.InsightsAlbum
import net.asksakis.massdroidv2.domain.repository.InsightsArtist
import net.asksakis.massdroidv2.domain.repository.InsightsGenre
import net.asksakis.massdroidv2.domain.repository.InsightsTrack
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import net.asksakis.massdroidv2.domain.whatsnew.WhatsNewRelease
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
    private val sendspinNoiseConnector: net.asksakis.massdroidv2.data.sendspin.SendspinNoiseConnector,
    private val playHistoryRepository: PlayHistoryRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val libraryGenreEnricher: net.asksakis.massdroidv2.data.genre.LibraryGenreEnricher,
    private val genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository,
    private val maAuthRepository: net.asksakis.massdroidv2.domain.repository.MaAuthRepository,
    private val whatsNewRepository: WhatsNewRepository,
    serverPickerFactory: ServerPicker.Factory,
    signInFactory: SignInController.Factory
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

    /** Step 1 and step 2 of the sign-in form: the server list, the chosen server, the typed address. */
    val serverPicker: ServerPicker = serverPickerFactory.create(viewModelScope)

    /** Signing in to the chosen server, shared with the onboarding. */
    val signIn: SignInController = signInFactory.create(viewModelScope)

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

    val connectionState = wsClient.connectionState
    val isReconnecting = wsClient.isReconnecting

    val sendspinState = sendspinManager.connectionState
    val sendspinLinkSecurity = sendspinManager.linkSecurity
    /** The Sendspin server that unpaired this phone, while the phone waits to be asked to pair again. */
    val sendspinPairingHeld = sendspinNoiseConnector.pairingHeld
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

    // Unset means the build's default, which MassDroidApp applies the same way.
    val persistentLogEnabled = settingsRepository.persistentLogEnabled
        .map { it ?: net.asksakis.massdroidv2.BuildConfig.DEBUG }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), net.asksakis.massdroidv2.BuildConfig.DEBUG)

    private val _recommendationBusy = MutableStateFlow(false)
    val recommendationBusy: StateFlow<Boolean> = _recommendationBusy.asStateFlow()
    private val _recommendationMessage = MutableStateFlow<String?>(null)
    val recommendationMessage: StateFlow<String?> = _recommendationMessage.asStateFlow()
    private val _topArtists = MutableStateFlow<List<InsightsArtist>>(emptyList())
    val topArtists: StateFlow<List<InsightsArtist>> = _topArtists.asStateFlow()
    private val _topTracks = MutableStateFlow<List<InsightsTrack>>(emptyList())
    val topTracks: StateFlow<List<InsightsTrack>> = _topTracks.asStateFlow()
    private val _topAlbums = MutableStateFlow<List<InsightsAlbum>>(emptyList())
    val topAlbums: StateFlow<List<InsightsAlbum>> = _topAlbums.asStateFlow()
    private val _topGenres = MutableStateFlow<List<InsightsGenre>>(emptyList())
    val topGenres: StateFlow<List<InsightsGenre>> = _topGenres.asStateFlow()
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

    init {
        refreshRecommendationData()
    }

    fun disconnect() {
        wsClient.disconnect()
    }

    fun clearUpdateMessage() {
        _updateUiState.update { it.copy(message = null) }
    }

    fun toggleIncludeBetaUpdates(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setIncludeBetaUpdates(enabled)
        }
    }

    fun setPersistentLogEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPersistentLogEnabled(enabled) }
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

    fun toggleSendspin(enabled: Boolean) {
        viewModelScope.launch {
            // Switching it on is the user's go-ahead to pair again with a server that unpaired it.
            if (enabled) settingsRepository.clearSendspinUnpairedServers()
            settingsRepository.setSendspinEnabled(enabled)
        }
    }

    fun pairSendspinAgain() = sendspinNoiseConnector.pairAgain()

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
                loadBlockedArtists()
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
                loadBlockedArtists()
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
     * Loads only the blocked artists, for the Blocked artists screen. The Insights lists
     * are not reloaded here: Insights reloads them itself when it is shown again.
     */
    fun refreshBlockedArtists() {
        viewModelScope.launch {
            try {
                loadBlockedArtists()
            } catch (e: Exception) {
                Log.e(TAG, "refreshBlockedArtists failed: ${e.message}")
                _recommendationMessage.value = "Couldn't load the blocked artists"
            }
        }
    }

    private suspend fun loadBlockedArtists() {
        _blockedArtists.value = smartListeningRepository.getBlockedArtists()
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
