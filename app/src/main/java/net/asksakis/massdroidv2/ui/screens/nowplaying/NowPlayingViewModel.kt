package net.asksakis.massdroidv2.ui.screens.nowplaying

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import net.asksakis.massdroidv2.data.lyrics.LyricsProvider
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.service.SleepTimerBridge
import net.asksakis.massdroidv2.data.sendspin.SyncState
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "NowPlayingVM"
private const val SENDSPIN_UI_DBG = "SendspinUiDbg"

/**
 * Compact projection of [QueueState] used as a `distinctUntilChanged`
 * key when computing adjacent artwork. Three fields are enough: the
 * queue identity, the cursor inside it, and the current queue-item id
 * (so a play_index that lands on the same numeric index but a
 * different item still re-emits).
 */
private data class AdjacentPosition(
    val queueId: String?,
    val currentIndex: Int?,
    val currentItemId: String?,
)

data class CachedTrackDisplay(
    val title: String,
    val artist: String,
    val album: String,
    val imageUrl: String?,
    val duration: Double
)

data class SendspinStatusUi(
    val connectionState: SendspinState,
    val syncState: SyncState,
    val codec: String?,
    val configuredFormat: String,
    val networkMode: String,
    val activeBufferMs: Long,
    val bufferBytes: Long,
    val syncDelayMs: Int,
    val outputLatencyMs: Long = 0L,
    val acousticCorrectionMs: Long = 0L,
    val dacSyncErrorMs: Float = 0f,
    val absoluteSyncMs: Float = 0f,
    val syncMuted: Boolean = false,
    // False until the closed-loop DAC ground truth matures; while false the
    // displayed sync error is the blind-anchor fallback, so the UI shows
    // "Measuring" instead of a misleading "Locked".
    val syncTruthAvailable: Boolean = false,
    val audioRoute: String = "",
    // True when the active AudioTrack output is routed to a Bluetooth sink.
    // Drives the streaming-status sheet's latency label: BT routes show
    // calibration state, non-BT routes are labelled "port latency" since
    // they sync at the audio port via the AudioTrack pipeline measurement.
    val isBtRoute: Boolean = false,
    val clockSamples: Int = 0,
    val clockErrorUs: Long = 0L,
    val resyncs: Int = 0,
    val correctionMode: String = "",
    // Actual Sendspin transport output format (post server re-encode):
    // captured from the latest stream/start payload. `outputSampleRate` is in
    // Hz, `outputBitDepth` is 0 for lossy codecs that don't report it.
    val outputSampleRate: Int = 0,
    val outputBitDepth: Int = 0,
)

data class AdjacentArtworkUi(
    val previousImageUrl: String?,
    val nextImageUrl: String?
)

enum class LyricsAvailability {
    UNKNOWN,
    LOADING,
    AVAILABLE,
    UNAVAILABLE
}

sealed interface LyricsEntry {
    data object Unresolved : LyricsEntry
    data object Loading : LyricsEntry
    data object Unavailable : LyricsEntry
    data object Failed : LyricsEntry
    data class Ready(val content: LyricsProvider.LyricsContent) : LyricsEntry
}

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val settingsRepository: net.asksakis.massdroidv2.domain.repository.SettingsRepository,
    private val wsClient: MaWebSocketClient,
    private val lyricsProvider: LyricsProvider,
    private val sendspinManager: net.asksakis.massdroidv2.data.sendspin.SendspinManager,
    private val volumeCoordinator: net.asksakis.massdroidv2.data.sendspin.SendspinVolumeCoordinator,
    val acoustic: net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator,
    val sleepTimerBridge: SleepTimerBridge,
    private val queueDstmCache: net.asksakis.massdroidv2.data.repository.QueueDstmCache
) : ViewModel() {

    val selectedPlayer = playerRepository.selectedPlayer
    val allPlayers: StateFlow<List<net.asksakis.massdroidv2.domain.model.Player>> = playerRepository.players
    val queueState = playerRepository.queueState
    val queueDstmStates: StateFlow<Map<String, Boolean>> = queueDstmCache.states
    val elapsedTime = playerRepository.elapsedTime
    val sendspinClientId = settingsRepository.sendspinClientId
    val sendspinAudioFormat = settingsRepository.sendspinAudioFormat
    val sendspinSyncDelayMs = settingsRepository.sendspinSyncDelayMs
    val sendspinSyncHistory = sendspinManager.syncHistory
    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _isLoadingPlaylists = MutableStateFlow(false)
    val isLoadingPlaylists: StateFlow<Boolean> = _isLoadingPlaylists.asStateFlow()
    private val _addingToPlaylistId = MutableStateFlow<String?>(null)
    val addingToPlaylistId: StateFlow<String?> = _addingToPlaylistId.asStateFlow()
    private val _playlistContainsTrack = MutableStateFlow<Set<String>>(emptySet())
    val playlistContainsTrack: StateFlow<Set<String>> = _playlistContainsTrack.asStateFlow()

    private val _lyricsEntries = MutableStateFlow<Map<String, LyricsEntry>>(emptyMap())
    private val _lyricsTimingOffsetMs = MutableStateFlow(0)
    val lyricsTimingOffsetMs: StateFlow<Int> = _lyricsTimingOffsetMs.asStateFlow()
    private var lyricsLoadJob: Job? = null
    private var inFlightLyricsUri: String? = null

    private val currentLyricsTrackFlow: StateFlow<Track?> =
        // Source the lyrics track from the queue alone. The previous
        // combine also gated on selectedPlayer.currentMedia.uri to
        // catch the case where the player is mid-switch and the
        // queue's "current" track no longer matches what's actually
        // streaming. In practice the player object emits stale or
        // briefly null currentMedia values on every server-side seek
        // (PLAYER_UPDATED -> intermediate state -> PLAYER_UPDATED),
        // which made the lyrics flow drop to null for ~1s and the
        // lyrics icon flash UNKNOWN -> AVAILABLE on each scrub. The
        // queue's currentItem.track is the authoritative identity for
        // lyrics ownership, so sticking to it keeps the icon stable
        // while the actual playback path settles.
        queueState
            .map { it?.currentItem?.track }
            .distinctUntilChanged { old, new -> old?.uri == new?.uri }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentLyricsTrackUri: StateFlow<String?> =
        currentLyricsTrackFlow
            .map { it?.uri }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentLyricsEntry: StateFlow<LyricsEntry> =
        combine(currentLyricsTrackUri, _lyricsEntries) { uri, entries ->
            uri?.let { entries[it] } ?: LyricsEntry.Unresolved
        }.stateIn(viewModelScope, SharingStarted.Eagerly, LyricsEntry.Unresolved)

    val lyrics: StateFlow<LyricsProvider.LyricsContent> =
        currentLyricsEntry
            .map { entryToContent(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                LyricsProvider.LyricsContent.None
            )

    val lyricsAvailability: StateFlow<LyricsAvailability> =
        currentLyricsEntry
            .map { entryToAvailability(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, LyricsAvailability.UNKNOWN)

    val isLoadingLyrics: StateFlow<Boolean> =
        currentLyricsEntry
            .map { it is LyricsEntry.Unresolved || it is LyricsEntry.Loading }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()
    private val _sendspinStatus = MutableStateFlow<SendspinStatusUi?>(null)
    val sendspinStatus: StateFlow<SendspinStatusUi?> = _sendspinStatus.asStateFlow()
    /**
     * True when the currently selected player **is** our local Sendspin
     * client. This used to be `status != null`, which evaluated to true
     * whenever the Sendspin manager was alive — including while the UI was
     * showing a remote player like Chromecast or Snapcast. That caused the
     * "Streaming Status" tap target on the quality badge to open sync
     * stats for the local Sendspin even when the user was looking at a
     * different player. The status sheet's content is Sendspin-only, so
     * the surface is hidden unless the local Sendspin is actually selected.
     */
    val isSendspinPlayer: StateFlow<Boolean> = combine(
        selectedPlayer,
        sendspinClientId,
    ) { player, clientId ->
        clientId != null && player?.playerId == clientId
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), false)
    var cachedSendspinClientId: String? = null; private set
    var cachedSendspinAudioFormat = "SMART"; private set
    private var cachedSendspinSyncDelayMs = 0
    private var lastSendspinStatusLogAtMs = 0L
    private var lastLoggedSendspinStatusKey: String? = null
    private val _cachedTrackDisplay = MutableStateFlow<CachedTrackDisplay?>(null)
    val cachedTrackDisplay: StateFlow<CachedTrackDisplay?> = _cachedTrackDisplay.asStateFlow()
    private val _adjacentArtwork = MutableStateFlow(AdjacentArtworkUi(previousImageUrl = null, nextImageUrl = null))
    val adjacentArtwork: StateFlow<AdjacentArtworkUi> = _adjacentArtwork.asStateFlow()

    // Optimistic elapsed time: continues ticking during MA disconnect while sendspin plays
    private var optimisticBaseTime = 0.0
    private var optimisticBaseTimestamp = 0L
    private var optimisticDuration = 0.0
    private val _optimisticElapsed = MutableStateFlow<Double?>(null)
    val optimisticElapsed: StateFlow<Double?> = _optimisticElapsed.asStateFlow()

    init {
        viewModelScope.launch {
            smartListeningRepository.blockedArtistUris.collect { _blockedArtistUris.value = it }
        }
        viewModelScope.launch {
            sendspinClientId.collect { cachedSendspinClientId = it }
        }
        // Cache elapsed time for optimistic tick during MA disconnect
        viewModelScope.launch {
            elapsedTime.collect { time ->
                if (time > 0.0) {
                    optimisticBaseTime = time
                    optimisticBaseTimestamp = System.currentTimeMillis()
                    optimisticDuration = queueState.value?.currentItem?.duration
                        ?: selectedPlayer.value?.currentMedia?.duration ?: 0.0
                    _optimisticElapsed.value = null // live data available, no need for optimistic
                } else if (optimisticBaseTimestamp > 0L && sendspinManager.enabled.value) {
                    // Live elapsed reset to 0 (MA disconnect), start optimistic immediately
                    val elapsed = optimisticBaseTime + (System.currentTimeMillis() - optimisticBaseTimestamp) / 1000.0
                    _optimisticElapsed.value = if (optimisticDuration > 0) elapsed.coerceAtMost(optimisticDuration) else elapsed
                }
            }
        }
        // Optimistic elapsed tick: only runs while live elapsed is 0 and sendspin is playing
        viewModelScope.launch {
            combine(elapsedTime, sendspinManager.enabled, sendspinManager.syncState) { live, enabled, sync ->
                live <= 0.0 && enabled && optimisticBaseTimestamp > 0L &&
                    (sync == SyncState.SYNCHRONIZED || sync == SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
            }.distinctUntilChanged().collect { shouldTick ->
                if (shouldTick) {
                    while (true) {
                        delay(500)
                        if (elapsedTime.value > 0.0) break
                        val elapsed = optimisticBaseTime + (System.currentTimeMillis() - optimisticBaseTimestamp) / 1000.0
                        _optimisticElapsed.value = if (optimisticDuration > 0) elapsed.coerceAtMost(optimisticDuration) else elapsed
                    }
                }
            }
        }
        viewModelScope.launch {
            sendspinAudioFormat.collect { cachedSendspinAudioFormat = it }
        }
        viewModelScope.launch {
            sendspinSyncDelayMs.collect { cachedSendspinSyncDelayMs = it }
        }
        viewModelScope.launch {
            currentLyricsTrackFlow.collectLatest { track: Track? ->
                val targetUri = track?.uri ?: return@collectLatest
                _lyricsTimingOffsetMs.value = 0

                val currentEntry = _lyricsEntries.value[targetUri]
                if (currentEntry is LyricsEntry.Ready ||
                    currentEntry is LyricsEntry.Unavailable ||
                    currentEntry is LyricsEntry.Loading
                ) {
                    return@collectLatest
                }

                delay(300) // debounce quick skips and transient queue/currentMedia mismatch
                val stableTrack = currentLyricsTrackFlow.value?.takeIf { it.uri == targetUri } ?: return@collectLatest
                loadLyricsInternal(stableTrack)
            }
        }
        viewModelScope.launch {
            // Adjacent artwork (previous/next) is sourced from the
            // canonical queue snapshot maintained by
            // QueueItemsCoordinator instead of a dedicated
            // limit=3/offset=N RPC per track change. We combine with
            // queueState so we always render against the current item's
            // position. When the snapshot's queueId hasn't caught up to
            // queueState yet we surface a null adjacent pair; the next
            // snapshot emission will fill it in.
            val positionFlow = queueState
                .map { qs ->
                    AdjacentPosition(
                        queueId = qs?.queueId,
                        currentIndex = qs?.currentIndex,
                        currentItemId = qs?.currentItem?.queueItemId,
                    )
                }
                .distinctUntilChanged()
            combine(positionFlow, playerRepository.queueItems) { position, snapshot ->
                position to snapshot
            }.collectLatest { (position, snapshot) ->
                if (position.queueId == null ||
                    position.currentItemId == null ||
                    snapshot == null ||
                    snapshot.queueId != position.queueId
                ) {
                    _adjacentArtwork.value = AdjacentArtworkUi(previousImageUrl = null, nextImageUrl = null)
                    return@collectLatest
                }
                val idx = (position.currentIndex ?: 0).coerceAtLeast(0)
                val window = listOfNotNull(
                    snapshot.items.getOrNull(idx - 1),
                    snapshot.items.getOrNull(idx),
                    snapshot.items.getOrNull(idx + 1),
                )
                val safeOffset = (idx - 1).coerceAtLeast(0)
                _adjacentArtwork.value = resolveAdjacentArtwork(
                    items = window,
                    safeOffset = safeOffset,
                    currentIndex = idx,
                )
            }
        }
        // Cache track display for holdover (MA WS may disconnect while Sendspin still plays)
        viewModelScope.launch {
            queueState.collect { qs ->
                val track = qs?.currentItem?.track ?: return@collect
                _cachedTrackDisplay.value = CachedTrackDisplay(
                    title = track.name, artist = track.artistNames,
                    album = track.albumName,
                    imageUrl = track.imageUrl ?: qs.currentItem?.imageUrl,
                    duration = track.duration ?: qs.currentItem?.duration ?: 0.0
                )
            }
        }
        viewModelScope.launch {
            sendspinManager.serverMetadata.collect { meta ->
                if (meta?.title?.isNotBlank() == true && selectedPlayer.value == null) {
                    val dur = (meta.progress?.trackDuration?.toDouble() ?: 0.0) / 1000.0
                    _cachedTrackDisplay.value = CachedTrackDisplay(
                        title = meta.title ?: "", artist = meta.artist ?: "",
                        album = meta.album ?: "", imageUrl = meta.artworkUrl,
                        duration = dur
                    )
                    if (dur > 0.0) optimisticDuration = dur
                }
            }
        }
        // Don't clear cache on disconnect/error: keep showing last track info
        // until a new track replaces it (via queueState or serverMetadata collectors)
        // Flow-based sendspin status: combines state flows with a 1Hz ticker so that
        // dynamic metrics (buffer, latency, clock error, DAC drift) refresh in the UI
        // while none of the state sources are changing. Without the ticker, the
        // combine only emits on connection/sync/codec/network/id transitions, and
        // the buffer reading stays frozen at whatever it was at the last transition
        // — visible after WiFi↔mobile handover where the snapshot caught a drained
        // buffer that has since refilled. distinctUntilChanged downstream still
        // deduplicates ticks where nothing actually changed.
        val sendspinUiTicker = flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        }
        viewModelScope.launch {
            combine(
                sendspinManager.connectionState,
                sendspinManager.syncState,
                sendspinManager.streamCodec,
                sendspinManager.networkMode,
                sendspinClientId,
                sendspinUiTicker,
                sendspinManager.streamFormat,
            ) { values: Array<*> ->
                val conn = values[0] as SendspinState
                val sync = values[1] as SyncState
                val codec = values[2] as String?
                val netMode = values[3] as String
                val clientId = values[4] as String?
                if (clientId == null) {
                    lastSendspinStatusLogAtMs = 0L
                    lastLoggedSendspinStatusKey = null
                    return@combine null
                }
                val fmt = values[6] as net.asksakis.massdroidv2.data.sendspin.SendspinManager.StreamFormatSnapshot?
                SendspinStatusUi(
                    connectionState = conn,
                    syncState = sync,
                    codec = codec,
                    configuredFormat = cachedSendspinAudioFormat,
                    networkMode = netMode,
                    activeBufferMs = sendspinManager.bufferedAudioMs().coerceAtLeast(0L),
                    bufferBytes = sendspinManager.bufferedAudioBytes().coerceAtLeast(0L),
                    syncDelayMs = cachedSendspinSyncDelayMs,
                    outputLatencyMs = sendspinManager.outputLatencyMs(),
                    acousticCorrectionMs = sendspinManager.acousticExtraMs(),
                    dacSyncErrorMs = sendspinManager.dacSyncErrorMs(),
                    absoluteSyncMs = sendspinManager.absoluteSyncMs(),
                    syncMuted = sendspinManager.isSyncMuted(),
                    syncTruthAvailable = sendspinManager.hasSyncTruth(),
                    isBtRoute = acoustic.isBtRoute(),
                    clockSamples = sendspinManager.clockSampleCount(),
                    clockErrorUs = sendspinManager.clockErrorUs(),
                    resyncs = sendspinManager.resyncCount(),
                    correctionMode = sendspinManager.correctionModeName(),
                    outputSampleRate = fmt?.sampleRate ?: 0,
                    outputBitDepth = fmt?.bitDepth ?: 0,
                ).also { maybeLogSendspinUiStatus(it) }
            }
                .distinctUntilChanged()
                .collect { _sendspinStatus.value = it }
        }
    }

    fun playPause() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                if (player.state == PlaybackState.PLAYING) {
                    playerRepository.pause(player.playerId)
                } else {
                    playerRepository.play(player.playerId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "playPause failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    private fun resolveAdjacentArtwork(
        items: List<QueueItem>,
        safeOffset: Int,
        currentIndex: Int
    ): AdjacentArtworkUi {
        fun imageAt(absoluteIndex: Int): String? {
            val localIndex = absoluteIndex - safeOffset
            return items.getOrNull(localIndex)?.track?.imageUrl ?: items.getOrNull(localIndex)?.imageUrl
        }
        return AdjacentArtworkUi(
            previousImageUrl = if (currentIndex > 0) imageAt(currentIndex - 1) else null,
            nextImageUrl = imageAt(currentIndex + 1)
        )
    }

    fun next() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.next(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "next failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun previous() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.previous(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "previous failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    private var previousTrackInFlight = false
    private var lastPreviousTrackAtMs = 0L

    /**
     * Always go to previous track via play_index (skip the "restart current"
     * behavior of cmd/previous). Resolves the previous item against the
     * canonical queue snapshot, not [QueueState.currentIndex], because the
     * latter goes stale when the user moves a track above the current one:
     * the server shifts the current track's numeric position to N+1, but
     * the cached index is still N, so naive `currentIndex - 1` would land
     * on the wrong slot. Falls back to local index math only if the
     * snapshot is missing.
     *
     * Guarded against rapid double-fire (button press storms) with a short
     * in-flight + cooldown gate so a held tap doesn't queue ten identical
     * play_index commands to the server.
     */
    fun previousTrack() {
        val now = System.currentTimeMillis()
        if (previousTrackInFlight) return
        if (now - lastPreviousTrackAtMs < PREVIOUS_TRACK_COOLDOWN_MS) return
        val qs = queueState.value ?: return
        val currentItemId = qs.currentItem?.queueItemId
        val snapshot = playerRepository.queueItems.value
            ?.takeIf { it.queueId == qs.queueId }
        val resolvedIndex = if (snapshot != null && currentItemId != null) {
            val currentIdx = snapshot.items.indexOfFirst { it.queueItemId == currentItemId }
            if (currentIdx > 0) currentIdx - 1 else -1
        } else {
            // No fresh snapshot: fall back to the cached index. Stale-window
            // risk remains (the issue this method exists to fix), but at
            // least preserves working behavior when canonical data is
            // unavailable.
            (qs.currentIndex - 1).coerceAtLeast(-1)
        }
        if (resolvedIndex < 0) return
        lastPreviousTrackAtMs = now
        previousTrackInFlight = true
        viewModelScope.launch {
            try {
                musicRepository.playQueueIndex(qs.queueId, resolvedIndex)
            } catch (e: Exception) {
                Log.w(TAG, "previousTrack failed: ${e.message}")
            } finally {
                previousTrackInFlight = false
            }
        }
    }

    companion object {
        // Match the typical server round-trip for play_index + new track
        // metadata so a user that taps twice quickly doesn't fire two
        // play_index commands back-to-back.
        private const val PREVIOUS_TRACK_COOLDOWN_MS = 400L
    }

    /**
     * Fire the seek directly. We used to coalesce rapid scrubs through a
     * SharedFlow + debounce to limit outbound RPCs, but measurements on
     * the actual MA Sendspin path showed the round-trip is bottlenecked
     * server-side (stream/end + new stream/start per seek), and the
     * 250 ms client wait was making the slider feel laggy without giving
     * the server meaningful headroom — back-to-back taps still queued at
     * the server's stream pipeline. Removing the wait keeps the slider
     * responsive; the proper fix lives in the upstream Sendspin provider
     * using stream/clear instead of full stream restarts.
     */
    fun seek(position: Double) {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.seek(player.playerId, position)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "seek failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun setSendspinSyncDelayMs(delayMs: Int) {
        viewModelScope.launch {
            settingsRepository.setSendspinSyncDelayMs(delayMs)
        }
    }

    fun setPlayerPower(playerId: String, powered: Boolean) {
        viewModelScope.launch {
            try {
                playerRepository.setPower(playerId, powered)
            } catch (_: Exception) {}
        }
    }

    fun setLyricsTimingOffsetMs(offsetMs: Int) {
        _lyricsTimingOffsetMs.value = offsetMs.coerceIn(-10_000, 10_000)
    }

    fun adjustLyricsTimingOffsetBy(deltaMs: Int) {
        _lyricsTimingOffsetMs.value = (_lyricsTimingOffsetMs.value + deltaMs).coerceIn(-10_000, 10_000)
    }

    fun setVolume(level: Int) {
        val player = selectedPlayer.value ?: return
        // Sendspin slider: route through the volume coordinator so the push
        // is recorded as a local intent and the resulting MA echo is
        // suppressed within the echo window. This protects against the
        // race where a hardware key press lands between the slider's WS
        // send and the server echo: without the recordLocalPush() that
        // onUiSliderChanged() performs, the slider's stale echo could
        // overwrite the hardware key's STREAM_MUSIC setting once the
        // 1.5 s window expires. For non-Sendspin remote players the
        // coordinator does not apply and we go through the repo directly.
        if (cachedSendspinClientId != null && player.playerId == cachedSendspinClientId) {
            volumeCoordinator.onUiSliderChanged(level)
        } else {
            viewModelScope.launch {
                try {
                    playerRepository.setVolume(player.playerId, level)
                } catch (e: Exception) {
                    Log.w(TAG, "setVolume failed: ${e.message}")
                }
            }
        }
    }

    fun toggleMute() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.toggleMute(player.playerId, !player.volumeMuted)
            } catch (e: Exception) {
                Log.w(TAG, "toggleMute failed: ${e.message}")
            }
        }
    }

    fun toggleShuffle() {
        val queue = queueState.value ?: return
        viewModelScope.launch {
            try {
                musicRepository.shuffleQueue(queue.queueId, !queue.shuffleEnabled)
            } catch (e: Exception) {
                Log.w(TAG, "toggleShuffle failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun toggleFavorite() {
        val track = queueState.value?.currentItem?.track ?: return
        val newFavorite = !track.favorite
        viewModelScope.launch {
            try {
                // Optimistic UI update so heart icon responds instantly.
                playerRepository.updateCurrentTrackFavorite(newFavorite)
                musicRepository.setFavorite(track.uri, MediaType.TRACK, track.itemId, newFavorite)
                val artists = trackArtists(track.artistItemId, track.artistUri, track.artistNames)
                if (newFavorite) {
                    smartListeningRepository.recordLike(track, artists)
                } else {
                    smartListeningRepository.recordUnlike(track, artists)
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
                // Roll back only if we're still on the same track.
                if (queueState.value?.currentItem?.track?.uri == track.uri) {
                    playerRepository.updateCurrentTrackFavorite(track.favorite)
                }
                _error.tryEmit("Failed to update favorite")
            }
        }
    }

    fun toggleCurrentArtistBlocked() {
        val track = queueState.value?.currentItem?.track ?: return
        val artistUri = MediaIdentity.canonicalArtistKey(track.artistItemId, track.artistUri) ?: return
        val artistName = track.artistNames
            .split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { "Artist" }

        viewModelScope.launch {
            val wasBlocked = _blockedArtistUris.value.contains(artistUri)
            val optimistic = if (wasBlocked) {
                _blockedArtistUris.value - artistUri
            } else {
                _blockedArtistUris.value + artistUri
            }
            _blockedArtistUris.value = optimistic
            try {
                smartListeningRepository.setArtistBlocked(
                    artistUri = artistUri,
                    artistName = artistName,
                    blocked = !wasBlocked
                )
            } catch (e: Exception) {
                Log.w(TAG, "toggleCurrentArtistBlocked failed: ${e.message}")
                _blockedArtistUris.value = if (wasBlocked) {
                    _blockedArtistUris.value + artistUri
                } else {
                    _blockedArtistUris.value - artistUri
                }
                _error.tryEmit("Failed to update artist filter")
            }
        }
    }

    fun loadPlaylists(force: Boolean = false) {
        if (_isLoadingPlaylists.value) return
        if (!force && _playlists.value.isNotEmpty()) return
        viewModelScope.launch {
            _isLoadingPlaylists.value = true
            try {
                val loaded = musicRepository.getPlaylists(limit = 200)
                    .filter { it.isEditable }
                _playlists.value = loaded
                checkTrackInPlaylists(loaded)
            } catch (e: Exception) {
                Log.w(TAG, "loadPlaylists failed: ${e.message}")
                _error.tryEmit("Failed to load playlists")
            } finally {
                _isLoadingPlaylists.value = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun checkTrackInPlaylists(playlists: List<Playlist>) {
        val trackUri = queueState.value?.currentItem?.track?.uri ?: return
        val containing = mutableSetOf<String>()
        for (playlist in playlists) {
            try {
                val tracks = musicRepository.getPlaylistTracks(playlist.itemId, playlist.provider)
                if (tracks.any { it.uri == trackUri }) {
                    containing += playlist.uri
                }
            } catch (_: Exception) { }
        }
        _playlistContainsTrack.value = containing
    }

    fun preloadLyrics() {
        when (currentLyricsEntry.value) {
            is LyricsEntry.Ready,
            LyricsEntry.Unavailable,
            LyricsEntry.Loading -> Unit
            LyricsEntry.Unresolved,
            LyricsEntry.Failed -> loadLyrics()
        }
    }

    fun loadLyrics() {
        val track = currentLyricsTrack() ?: return
        when (_lyricsEntries.value[track.uri]) {
            is LyricsEntry.Ready,
            LyricsEntry.Unavailable,
            LyricsEntry.Loading -> return
            LyricsEntry.Unresolved,
            LyricsEntry.Failed,
            null -> Unit
        }
        loadLyricsInternal(track)
    }

    private fun loadLyricsInternal(track: Track) {
        val entry = _lyricsEntries.value[track.uri]
        val sameTrackInFlight = track.uri == inFlightLyricsUri && lyricsLoadJob?.isActive == true
        if (sameTrackInFlight || entry is LyricsEntry.Loading) return
        lyricsLoadJob?.cancel()
        lyricsLoadJob = viewModelScope.launch {
            fetchAndStoreLyrics(track)
        }
    }

    private suspend fun fetchAndStoreLyrics(track: Track) {
        val expectedTrackUri = track.uri
        inFlightLyricsUri = expectedTrackUri
        try {
            Log.d(TAG, "lyrics load start uri=${track.uri}")
            val embeddedPlain = track.lyrics?.takeIf { it.isNotBlank() }
            val embeddedLrc = track.lrcLyrics?.takeIf { it.isNotBlank() }
            if (embeddedPlain != null || embeddedLrc != null) {
                val normalized = LyricsProvider.normalizeLyricsResult(
                    plain = embeddedPlain,
                    lrc = embeddedLrc
                )
                backfillCurrentTrackLyrics(normalized)
                if (normalized == LyricsProvider.LyricsContent.None) {
                    putLyricsEntry(expectedTrackUri, LyricsEntry.Unavailable)
                } else {
                    putLyricsEntry(expectedTrackUri, LyricsEntry.Ready(normalized))
                }
                Log.d(TAG, "lyrics availability=${entryToAvailability(_lyricsEntries.value[expectedTrackUri] ?: LyricsEntry.Unresolved)} uri=${track.uri} ${describeLyricsContent(normalized)} source=embedded")
                return
            }
            putLyricsEntry(expectedTrackUri, LyricsEntry.Loading)
            when (val result = lyricsProvider.fetchLyrics(track.itemId, track.provider, track.uri)) {
                is LyricsProvider.FetchResult.Found -> {
                    backfillCurrentTrackLyrics(result.lyrics)
                    putLyricsEntry(expectedTrackUri, LyricsEntry.Ready(result.lyrics))
                    Log.d(TAG, "lyrics availability=AVAILABLE uri=${track.uri} ${describeLyricsContent(result.lyrics)}")
                }
                LyricsProvider.FetchResult.NotFound -> {
                    putLyricsEntry(expectedTrackUri, LyricsEntry.Unavailable)
                    Log.d(TAG, "lyrics availability=UNAVAILABLE uri=${track.uri}")
                }
                LyricsProvider.FetchResult.Failed -> {
                    putLyricsEntry(expectedTrackUri, LyricsEntry.Failed)
                    Log.d(TAG, "lyrics availability=UNKNOWN uri=${track.uri} reason=failed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadLyrics failed: ${e.message}")
            putLyricsEntry(expectedTrackUri, LyricsEntry.Failed)
            Log.d(TAG, "lyrics availability=UNKNOWN uri=$expectedTrackUri reason=exception")
        } finally {
            if (inFlightLyricsUri == expectedTrackUri) {
                inFlightLyricsUri = null
            }
        }
    }

    private fun putLyricsEntry(trackUri: String, entry: LyricsEntry) {
        val updated = LinkedHashMap(_lyricsEntries.value)
        updated.remove(trackUri)
        updated[trackUri] = entry
        while (updated.size > 24) {
            val iterator = updated.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        _lyricsEntries.value = updated
    }

    private fun describeLyricsContent(content: LyricsProvider.LyricsContent): String = when (content) {
        LyricsProvider.LyricsContent.None -> "plain=false synced=false"
        is LyricsProvider.LyricsContent.Plain -> "plain=true synced=false"
        is LyricsProvider.LyricsContent.Synced -> "plain=false synced=true"
    }

    private fun backfillCurrentTrackLyrics(content: LyricsProvider.LyricsContent) {
        when (content) {
            LyricsProvider.LyricsContent.None -> {
                playerRepository.updateCurrentTrackLyrics(
                    plainLyrics = null,
                    lrcLyrics = null
                )
            }
            is LyricsProvider.LyricsContent.Plain -> {
                playerRepository.updateCurrentTrackLyrics(
                    plainLyrics = content.text,
                    lrcLyrics = null
                )
            }
            is LyricsProvider.LyricsContent.Synced -> {
                playerRepository.updateCurrentTrackLyrics(
                    plainLyrics = null,
                    lrcLyrics = content.rawLrc
                )
            }
        }
    }

    private fun currentLyricsTrack(): Track? {
        return currentLyricsTrackFlow.value
    }

    private fun entryToAvailability(entry: LyricsEntry): LyricsAvailability = when (entry) {
        LyricsEntry.Unresolved,
        LyricsEntry.Failed -> LyricsAvailability.UNKNOWN
        LyricsEntry.Loading -> LyricsAvailability.LOADING
        LyricsEntry.Unavailable -> LyricsAvailability.UNAVAILABLE
        is LyricsEntry.Ready -> LyricsAvailability.AVAILABLE
    }

    private fun entryToContent(entry: LyricsEntry): LyricsProvider.LyricsContent = when (entry) {
        is LyricsEntry.Ready -> entry.content
        LyricsEntry.Unresolved,
        LyricsEntry.Loading,
        LyricsEntry.Unavailable,
        LyricsEntry.Failed -> LyricsProvider.LyricsContent.None
    }

    fun removeCurrentTrackFromPlaylist(playlist: Playlist, onDone: () -> Unit = {}) {
        val track = queueState.value?.currentItem?.track ?: return
        if (_addingToPlaylistId.value != null) return
        viewModelScope.launch {
            _addingToPlaylistId.value = playlist.itemId
            try {
                val tracks = musicRepository.getPlaylistTracks(playlist.itemId, playlist.provider)
                val position = tracks.indexOfFirst { it.uri == track.uri }
                if (position >= 0) {
                    musicRepository.removeTrackFromPlaylist(playlist, position)
                    _playlistContainsTrack.value = _playlistContainsTrack.value - playlist.uri
                }
                onDone()
            } catch (e: Exception) {
                Log.w(TAG, "removeCurrentTrackFromPlaylist failed: ${e.message}")
                _error.tryEmit("Failed to remove track from playlist")
            } finally {
                _addingToPlaylistId.value = null
            }
        }
    }

    fun createPlaylistAndAddTrack(name: String, onDone: () -> Unit = {}) {
        val track = queueState.value?.currentItem?.track ?: return
        viewModelScope.launch {
            try {
                val playlist = musicRepository.createPlaylist(name)
                musicRepository.addTrackToPlaylist(playlist, track.uri)
                _playlists.value = _playlists.value + playlist
                _playlistContainsTrack.value = _playlistContainsTrack.value + playlist.uri
                onDone()
            } catch (e: Exception) {
                Log.w(TAG, "createPlaylistAndAddTrack failed: ${e.message}")
                _error.tryEmit("Failed to create playlist")
            }
        }
    }

    fun addCurrentTrackToPlaylist(playlist: Playlist, onDone: () -> Unit = {}) {
        val track = queueState.value?.currentItem?.track ?: return
        if (_addingToPlaylistId.value != null) return
        viewModelScope.launch {
            _addingToPlaylistId.value = playlist.itemId
            try {
                musicRepository.addTrackToPlaylist(playlist, track.uri)
                _playlistContainsTrack.value = _playlistContainsTrack.value + playlist.uri
                onDone()
            } catch (e: Exception) {
                Log.w(TAG, "addCurrentTrackToPlaylist failed: ${e.message}")
                if (isPlaylistWriteUnsupported(e)) {
                    _playlists.value = _playlists.value.filterNot { it.uri == playlist.uri }
                    _error.tryEmit("This playlist is read-only")
                } else {
                    _error.tryEmit("Failed to add track to playlist")
                }
            } finally {
                _addingToPlaylistId.value = null
            }
        }
    }

    private fun isPlaylistWriteUnsupported(error: Exception): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return error is MaApiException && (
            message.contains("read-only") ||
                message.contains("readonly") ||
                message.contains("not supported") ||
                message.contains("unsupported") ||
                message.contains("cannot add") ||
                message.contains("auto") ||
                message.contains("generated")
            )
    }

    private fun trackArtists(artistItemId: String?, artistUri: String?, artistNames: String): List<Pair<String, String>> {
        val uri = MediaIdentity.canonicalArtistKey(itemId = artistItemId, uri = artistUri) ?: return emptyList()
        val name = artistNames
            .split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { "Artist" }
        return listOf(uri to name)
    }

    fun transferQueue(targetPlayerId: String) {
        val sourceQueueId = queueState.value?.queueId ?: return
        viewModelScope.launch {
            try {
                musicRepository.transferQueue(sourceQueueId, targetPlayerId)
            } catch (e: Exception) {
                Log.w(TAG, "transferQueue failed: ${e.message}")
                _error.tryEmit("Failed to transfer queue")
            }
        }
    }

    fun cycleRepeat() {
        val queue = queueState.value ?: return
        val nextMode = when (queue.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        viewModelScope.launch {
            try {
                musicRepository.repeatQueue(queue.queueId, nextMode)
            } catch (e: Exception) {
                Log.w(TAG, "cycleRepeat failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    suspend fun getPlayerConfig(playerId: String): PlayerConfig? {
        return try {
            playerRepository.getPlayerConfig(playerId)
        } catch (e: Exception) {
            Log.w(TAG, "getPlayerConfig failed: ${e.message}")
            null
        }
    }

    fun startSongRadio(trackUri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.RADIO_SMART)
                musicRepository.playMedia(queueId, trackUri, radioMode = true)
            } catch (e: Exception) {
                Log.w(TAG, "startSongRadio failed: ${e.message}")
            }
        }
    }

    fun setDontStopTheMusic(queueId: String, enabled: Boolean) {
        queueDstmCache.setOptimistic(queueId, enabled)
        viewModelScope.launch {
            try {
                musicRepository.setDontStopTheMusic(queueId, enabled)
            } catch (e: Exception) {
                Log.w(TAG, "setDontStopTheMusic failed: ${e.message}")
            }
        }
    }

    fun setAudioFormat(format: net.asksakis.massdroidv2.domain.model.SendspinAudioFormat) {
        viewModelScope.launch {
            settingsRepository.setSendspinAudioFormat(format.name)
        }
    }

    fun savePlayerConfig(playerId: String, values: Map<String, Any>) {
        viewModelScope.launch {
            try {
                playerRepository.savePlayerConfig(playerId, values)
                val newName = values["name"] as? String
                if (newName != null) {
                    playerRepository.renamePlayer(playerId, newName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "savePlayerConfig failed: ${e.message}")
                _error.tryEmit("Failed to save player settings")
            }
        }
    }

    private fun maybeLogSendspinUiStatus(status: SendspinStatusUi) {
        // Log on transitions of state-shaped fields (connection, sync, codec,
        // mode, correction, syncMuted) immediately; otherwise heartbeat once
        // every 30s. Before the 1Hz UI ticker existed, this was a 1s rate
        // limit that almost never tripped during steady state. With the ticker
        // calling this on every emit, the rate limit alone produced a log per
        // second — useless noise. Keying on state fields keeps useful
        // transitions verbose while idle playback stays quiet.
        val key = "${status.connectionState}|${status.syncState}|${status.codec ?: ""}|" +
            "${status.configuredFormat}|${status.correctionMode ?: ""}|${status.syncMuted}"
        val now = System.currentTimeMillis()
        val stateChanged = key != lastLoggedSendspinStatusKey
        if (!stateChanged && now - lastSendspinStatusLogAtMs < 30_000L) return
        lastSendspinStatusLogAtMs = now
        lastLoggedSendspinStatusKey = key
        Log.d(
            SENDSPIN_UI_DBG,
            "transport=${status.connectionState} playback=${status.syncState} codec=${status.codec ?: "unknown"} " +
                "mode=${status.configuredFormat} syncDelay=${status.syncDelayMs}ms " +
                "buf=${String.format(java.util.Locale.US, "%.1f", status.activeBufferMs / 1000f)}s " +
                "bytes=${status.bufferBytes}"
        )
    }
}
