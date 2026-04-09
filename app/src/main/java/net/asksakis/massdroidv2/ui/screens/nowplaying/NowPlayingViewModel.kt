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
    val staticDelayMs: Int,
    val deviceBiasUs: Long = 0L,
    val outputLatencyMs: Long = 0L,
    val dacSyncErrorMs: Float = 0f
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
    val sleepTimerBridge: SleepTimerBridge
) : ViewModel() {

    val selectedPlayer = playerRepository.selectedPlayer
    val allPlayers: StateFlow<List<net.asksakis.massdroidv2.domain.model.Player>> = playerRepository.players
    val queueState = playerRepository.queueState
    val elapsedTime = playerRepository.elapsedTime
    val sendspinClientId = settingsRepository.sendspinClientId
    val sendspinAudioFormat = settingsRepository.sendspinAudioFormat
    val sendspinStaticDelayMs = settingsRepository.sendspinStaticDelayMs
    val sendspinStreamCodec = sendspinManager.streamCodec
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
        combine(
            queueState.map { it?.currentItem?.track },
            selectedPlayer.map { it?.currentMedia?.uri }
        ) { queueTrack: Track?, playerMediaUri: String? ->
            when {
                queueTrack == null -> null
                playerMediaUri.isNullOrBlank() -> queueTrack
                queueTrack.uri == playerMediaUri -> queueTrack
                else -> null
            }
        }
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
    val isSendspinPlayer: StateFlow<Boolean> = _sendspinStatus.map { it != null }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), false)
    var cachedSendspinClientId: String? = null; private set
    var cachedSendspinAudioFormat = "SMART"; private set
    private var cachedSendspinStaticDelayMs = 0
    private var lastSendspinStatusLogAtMs = 0L
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
            sendspinStaticDelayMs.collect { cachedSendspinStaticDelayMs = it }
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
            queueState
                .map { qs -> Triple(qs?.queueId, qs?.currentIndex, qs?.currentItem?.queueItemId) }
                .distinctUntilChanged()
                .collectLatest { (queueId, currentIndex, currentItemId) ->
                    if (queueId == null || currentItemId == null) {
                        _adjacentArtwork.value = AdjacentArtworkUi(previousImageUrl = null, nextImageUrl = null)
                        return@collectLatest
                    }
                    val offset = (currentIndex ?: 0).coerceAtLeast(0) - 1
                    val safeOffset = offset.coerceAtLeast(0)
                    val items = try {
                        musicRepository.getQueueItems(queueId, limit = 3, offset = safeOffset)
                    } catch (_: Exception) {
                        _adjacentArtwork.value = AdjacentArtworkUi(previousImageUrl = null, nextImageUrl = null)
                        return@collectLatest
                    }
                    _adjacentArtwork.value = resolveAdjacentArtwork(
                        items = items,
                        safeOffset = safeOffset,
                        currentIndex = currentIndex ?: 0
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
        // Flow-based sendspin status: only samples buffer when active, distinctUntilChanged
        viewModelScope.launch {
            combine(
                sendspinManager.connectionState,
                sendspinManager.syncState,
                sendspinManager.streamCodec,
                sendspinManager.networkMode,
                sendspinClientId
            ) { values: Array<*> ->
                val conn = values[0] as SendspinState
                val sync = values[1] as SyncState
                val codec = values[2] as String?
                val netMode = values[3] as String
                val clientId = values[4] as String?
                if (clientId == null) {
                    lastSendspinStatusLogAtMs = 0L
                    return@combine null
                }
                SendspinStatusUi(
                    connectionState = conn,
                    syncState = sync,
                    codec = codec,
                    configuredFormat = cachedSendspinAudioFormat,
                    networkMode = netMode,
                    activeBufferMs = sendspinManager.bufferedAudioMs().coerceAtLeast(0L),
                    bufferBytes = sendspinManager.bufferedAudioBytes().coerceAtLeast(0L),
                    staticDelayMs = cachedSendspinStaticDelayMs,
                    deviceBiasUs = sendspinManager.deviceBiasUs(),
                    outputLatencyMs = sendspinManager.outputLatencyMs(),
                    dacSyncErrorMs = sendspinManager.dacSyncErrorMs()
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

    /** Always go to previous track via play_index (skip the "restart current" behavior). */
    fun previousTrack() {
        val qs = queueState.value ?: return
        val prevIndex = qs.currentIndex - 1
        if (prevIndex < 0) return
        viewModelScope.launch {
            try {
                musicRepository.playQueueIndex(qs.queueId, prevIndex)
            } catch (e: Exception) {
                Log.w(TAG, "previousTrack failed: ${e.message}")
            }
        }
    }

    fun seek(position: Double) {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.seek(player.playerId, position)
            } catch (e: Exception) {
                Log.w(TAG, "seek failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun setSendspinStaticDelayMs(delayMs: Int) {
        viewModelScope.launch {
            settingsRepository.setSendspinStaticDelayMs(delayMs)
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
        viewModelScope.launch {
            try {
                playerRepository.setVolume(player.playerId, level)
            } catch (e: Exception) {
                Log.w(TAG, "setVolume failed: ${e.message}")
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
        val now = System.currentTimeMillis()
        if (now - lastSendspinStatusLogAtMs < 1000L) return
        lastSendspinStatusLogAtMs = now
        Log.d(
            SENDSPIN_UI_DBG,
            "transport=${status.connectionState} playback=${status.syncState} codec=${status.codec ?: "unknown"} " +
                "mode=${status.configuredFormat} delay=${status.staticDelayMs}ms " +
                "buf=${String.format(java.util.Locale.US, "%.1f", status.activeBufferMs / 1000f)}s " +
                "bytes=${status.bufferBytes}"
        )
    }
}
