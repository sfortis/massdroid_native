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
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.update.AppUpdateChecker
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.AlbumScore
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.BlockedArtistInfo
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import net.asksakis.massdroidv2.domain.repository.TrackScore
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
    private val lastFmGenreResolver: LastFmGenreResolver,
    private val lastFmLibraryEnricher: net.asksakis.massdroidv2.data.lastfm.LastFmLibraryEnricher,
    private val genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository
) : ViewModel() {

    val enrichmentProgress = lastFmLibraryEnricher.progress

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

    val sendspinState = sendspinManager.connectionState
    val sendspinEnabled = settingsRepository.sendspinEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val smartListeningEnabled = settingsRepository.smartListeningEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
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

    val lastFmApiKey = settingsRepository.lastFmApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val savedUsername = settingsRepository.username
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val savedPassword = settingsRepository.password
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        refreshRecommendationData()
    }

    // Token persistence is handled by MassDroidApp's connectionState observer

    fun login(url: String, username: String, password: String) {
        if (url.isBlank() || username.isBlank() || password.isBlank()) {
            _loginError.value = "Fill in all fields"
            return
        }
        _loginError.value = null
        viewModelScope.launch {
            settingsRepository.setServerUrl(url)
            settingsRepository.setUsername(username)
            settingsRepository.setPassword(password)
        }
        wsClient.setSavedCredentials(username, password)
        wsClient.connectWithLogin(url, username, password) { token ->
            viewModelScope.launch {
                settingsRepository.setAuthToken(token)
            }
        }
    }

    fun connectWithToken(url: String? = null) {
        val connectUrl = url ?: serverUrl.value
        val token = authToken.value
        if (connectUrl.isNotBlank() && token.isNotBlank()) {
            viewModelScope.launch { settingsRepository.setServerUrl(connectUrl) }
            wsClient.connect(connectUrl, token)
        }
    }

    fun disconnect() {
        wsClient.disconnect()
    }

    fun clearLoginError() {
        _loginError.value = null
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
                    _updateUiState.update {
                        it.copy(
                            availableUpdate = null,
                            isChecking = false,
                            message = result.message
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
                _updateUiState.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        message = error.message ?: "Failed to download update"
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

    fun toggleSmartListening(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSmartListeningEnabled(enabled)
        }
    }

    private val _lastFmValidation = MutableStateFlow<LastFmValidation>(LastFmValidation.Idle)
    val lastFmValidation: StateFlow<LastFmValidation> = _lastFmValidation.asStateFlow()

    fun setLastFmApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            Log.d(TAG, "Last.fm API key cleared")
            viewModelScope.launch { settingsRepository.setLastFmApiKey("") }
            _lastFmValidation.value = LastFmValidation.Idle
            return
        }
        Log.d(TAG, "Validating Last.fm API key...")
        _lastFmValidation.value = LastFmValidation.Validating
        viewModelScope.launch {
            val error = lastFmGenreResolver.validateApiKey(trimmed)
            if (error == null) {
                Log.d(TAG, "Last.fm API key validated, saving")
                settingsRepository.setLastFmApiKey(trimmed)
                _lastFmValidation.value = LastFmValidation.Valid
            } else {
                Log.w(TAG, "Last.fm API key validation failed: $error")
                _lastFmValidation.value = LastFmValidation.Invalid(error)
            }
        }
    }

    fun clearLastFmValidation() {
        _lastFmValidation.value = LastFmValidation.Idle
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
                _recommendationMessage.value = "Failed to load recommendation stats"
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
                loadRecommendationData()
                _recommendationMessage.value = "Recommendation DB reset completed"
            } catch (e: Exception) {
                Log.e(TAG, "resetRecommendationDatabase failed: ${e.message}")
                _recommendationMessage.value = "Failed to reset recommendation DB"
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
                _recommendationMessage.value = "Failed to unblock artist"
            } finally {
                _recommendationBusy.value = false
            }
        }
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

sealed interface LastFmValidation {
    data object Idle : LastFmValidation
    data object Validating : LastFmValidation
    data object Valid : LastFmValidation
    data class Invalid(val reason: String) : LastFmValidation
}
