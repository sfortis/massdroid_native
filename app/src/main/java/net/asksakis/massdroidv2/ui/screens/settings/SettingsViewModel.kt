package net.asksakis.massdroidv2.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
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
import net.asksakis.massdroidv2.service.SendspinService
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val wsClient: MaWebSocketClient,
    private val sendspinManager: SendspinManager,
    private val playHistoryRepository: PlayHistoryRepository,
    private val smartListeningRepository: SmartListeningRepository
) : ViewModel() {

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
    private val _blockedArtists = MutableStateFlow<List<BlockedArtistInfo>>(emptyList())
    val blockedArtists: StateFlow<List<BlockedArtistInfo>> = _blockedArtists.asStateFlow()

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
            val intent = Intent(appContext, SendspinService::class.java)
            if (enabled) {
                intent.action = SendspinService.ACTION_START
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                intent.action = SendspinService.ACTION_STOP
                appContext.startService(intent)
            }
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
        val genresDef = async { playHistoryRepository.getTopGenres(days = 90, limit = 10) }
        val blockedDef = async { smartListeningRepository.getBlockedArtists() }

        _topArtists.value = artistsDef.await()
        _topTracks.value = tracksDef.await()
        _topAlbums.value = albumsDef.await()
        _topGenres.value = genresDef.await()
        _blockedArtists.value = blockedDef.await()
    }
}
