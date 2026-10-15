package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.discover.DiscoverFeedStore
import net.asksakis.massdroidv2.domain.discover.MixLauncher
import net.asksakis.massdroidv2.presentation.discover.userMessage
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.shortcut.ShortcutAction
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher
import javax.inject.Inject

private const val TAG = "DiscoverVM"
private const val CONNECTION_PING_SAMPLES = 3
private const val CONNECTION_PING_TIMEOUT_MS = 3_000L
private const val CONNECTION_PING_INTERVAL_MS = 1_200L
private const val CONNECTION_PING_HISTORY_LIMIT = 24

data class ConnectionProbeState(
    val inProgress: Boolean = false,
    val samplesMs: List<Long> = emptyList(),
    val historyMs: List<Long> = emptyList(),
    val failedSamples: Int = 0,
    val probeMethod: String? = null,
    val error: String? = null,
    val updatedAtMs: Long = 0L
)

/**
 * The phone's Home. The feed comes from the shared [DiscoverFeedStore] and the mixes start
 * through the shared [MixLauncher], the same ones the TV and Android Auto use; this class keeps
 * only what belongs to the phone screen: the connect on launch, the update check, the
 * connection probe, the app shortcuts and the message under the Smart Mix button.
 */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val savedCredentialsConnector: net.asksakis.massdroidv2.data.websocket.SavedCredentialsConnector,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val wsClient: MaWebSocketClient,
    private val feedStore: DiscoverFeedStore,
    private val mixLauncher: MixLauncher,
    private val shortcutDispatcher: ShortcutActionDispatcher,
    private val appUpdateChecker: net.asksakis.massdroidv2.data.update.AppUpdateChecker,
    private val sessionEventBus: SessionEventBus,
) : ViewModel() {

    val sections: StateFlow<List<DiscoverSection>> = feedStore.state.map { it.sections }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), feedStore.state.value.sections)
    val isLoading: StateFlow<Boolean> = feedStore.state.map { it.isLoading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), feedStore.state.value.isLoading)
    val isRefreshing: StateFlow<Boolean> = feedStore.state.map { it.isRefreshing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    // The mix this screen started, if it is still building or its outcome is still owed. Home
    // shows the overlay and the message only for its own mixes, not for one started from the car.
    private val ownMix = MutableStateFlow<MixLauncher.Mix?>(null)
    private val ownActivity = combine(mixLauncher.activity, ownMix) { building, own ->
        building?.takeIf { it == own }
    }
    val radioOverlayGenre: StateFlow<String?> = ownActivity
        .map { (it as? MixLauncher.Mix.GenreRadio)?.genre }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val isBuildingSmartMix: StateFlow<Boolean> = ownActivity
        .map { it == MixLauncher.Mix.SmartMix }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _smartMixMessage = MutableStateFlow<String?>(null)
    val smartMixMessage: StateFlow<String?> = _smartMixMessage.asStateFlow()
    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState
    private val _connectionProbe = MutableStateFlow(ConnectionProbeState())
    val connectionProbe: StateFlow<ConnectionProbeState> = _connectionProbe.asStateFlow()

    private var connectionProbeJob: Job? = null

    init {
        viewModelScope.launch { savedCredentialsConnector.connectIfNeeded() }
        feedStore.activate()
        viewModelScope.launch { checkForUpdatesOnConnect() }
        viewModelScope.launch {
            mixLauncher.outcomes.collect { outcome ->
                if (outcome.mix != ownMix.value) return@collect
                ownMix.value = null
                outcome.userMessage()?.let { _smartMixMessage.value = it }
            }
        }
        viewModelScope.launch {
            sessionEventBus.resets.collect {
                connectionProbeJob?.cancel()
                ownMix.value = null
                _smartMixMessage.value = null
                _connectionProbe.value = ConnectionProbeState()
            }
        }
        viewModelScope.launch {
            shortcutDispatcher.pendingAction
                .filterNotNull()
                .collect { action ->
                    when (action) {
                        is ShortcutAction.SmartMix -> {
                            shortcutDispatcher.consume()
                            makePlaylistForMe()
                        }
                        is ShortcutAction.GenreRadio -> {
                            shortcutDispatcher.consume()
                            startGenreRadio(action.genre)
                        }
                        else -> {}
                    }
                }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun checkForUpdatesOnConnect() {
        if (!net.asksakis.massdroidv2.BuildConfig.ENABLE_UPDATE_CHECK) return
        wsClient.connectionState.collect { state ->
            if (state !is ConnectionState.Connected) return@collect
            try {
                val beta = settingsRepository.includeBetaUpdates.first()
                appUpdateChecker.checkForUpdates(force = false, includePrerelease = beta)
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    fun refresh() = feedStore.refresh()

    fun makePlaylistForMe() {
        val queueId = playerRepository.selectedPlayer.value?.playerId
        if (queueId.isNullOrBlank()) {
            _smartMixMessage.value = "Select a player first"
            return
        }
        claimOwnMix(MixLauncher.Mix.SmartMix) { mixLauncher.startSmartMix(queueId) }
    }

    fun startGenreRadio(genre: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        claimOwnMix(MixLauncher.Mix.GenreRadio(genre)) { mixLauncher.startGenreRadio(queueId, genre) }
    }

    // Recorded before the start, because a refusal (no matching artists, no player) is published
    // at once; an ignored press (another mix is building) puts back the mix this screen owned.
    private inline fun claimOwnMix(mix: MixLauncher.Mix, start: () -> Boolean) {
        val previous = ownMix.value
        ownMix.value = mix
        if (!start()) ownMix.value = previous
    }

    fun clearSmartMixMessage() {
        _smartMixMessage.value = null
    }

    fun startContinuousConnectionProbe(sampleCount: Int = CONNECTION_PING_SAMPLES) {
        connectionProbeJob?.cancel()
        connectionProbeJob = viewModelScope.launch {
            while (true) {
                val currentState = wsClient.connectionState.value
                if (currentState !is ConnectionState.Connected) {
                    _connectionProbe.value = _connectionProbe.value.copy(
                        inProgress = false,
                        samplesMs = emptyList(),
                        failedSamples = 0,
                        error = "Not connected",
                        updatedAtMs = System.currentTimeMillis()
                    )
                    delay(CONNECTION_PING_INTERVAL_MS)
                    continue
                }

                _connectionProbe.value = _connectionProbe.value.copy(
                    inProgress = true,
                    error = null
                )

                val samples = mutableListOf<Long>()
                var failed = 0
                val probeCommand = "players/all"
                val probeMethod = "WS RPC"
                repeat(sampleCount.coerceAtLeast(1)) { index ->
                    if (index > 0) delay(140)
                    val startNs = System.nanoTime()
                    try {
                        wsClient.sendCommand(
                            command = probeCommand,
                            awaitResponse = true,
                            timeoutMs = CONNECTION_PING_TIMEOUT_MS
                        )
                        samples += ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                    } catch (e: Exception) {
                        failed++
                        Log.w(TAG, "probeConnection failed: ${e.message}")
                    }
                }

                val updatedHistory = (
                    _connectionProbe.value.historyMs +
                        samples +
                        List(failed) { 0L }
                    ).takeLast(CONNECTION_PING_HISTORY_LIMIT)

                _connectionProbe.value = ConnectionProbeState(
                    inProgress = false,
                    samplesMs = samples,
                    historyMs = updatedHistory,
                    failedSamples = failed,
                    probeMethod = probeMethod,
                    error = if (samples.isEmpty()) "Ping failed" else null,
                    updatedAtMs = System.currentTimeMillis()
                )
                delay(CONNECTION_PING_INTERVAL_MS)
            }
        }
    }

    fun stopContinuousConnectionProbe() {
        connectionProbeJob?.cancel()
        connectionProbeJob = null
        _connectionProbe.value = _connectionProbe.value.copy(inProgress = false)
    }

    fun playTrack(track: Track) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, track.uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play track", e)
            }
        }
    }
}
