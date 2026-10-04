package net.asksakis.massdroidv2.ui.screens.nowplaying

import net.asksakis.massdroidv2.ui.failureMessage
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import net.asksakis.massdroidv2.playback.SleepTimerBridge
import net.asksakis.massdroidv2.data.sendspin.SyncState
import net.asksakis.massdroidv2.domain.model.Chapter
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.QueueSource
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.playlist.PlaylistMembershipController
import net.asksakis.massdroidv2.domain.player.QueueTransfer
import net.asksakis.massdroidv2.domain.player.QueueTransferOutcome
import net.asksakis.massdroidv2.domain.player.userMessage
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerDiscontinuityCommand
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject
import net.asksakis.massdroidv2.data.proximity.withRoomPlayer

private const val TAG = "NowPlayingVM"
private const val SENDSPIN_UI_DBG = "SendspinUiDbg"

/**
 * Why shuffle and repeat do nothing while the queue is dynamic.
 *
 * A queue is dynamic when one of the things it was filled from feeds it on demand, which
 * means a smart playlist or a radio, and the server then refuses both commands because
 * that source is already deciding the order. It is NOT Don't Stop the Music: the two are
 * separate flags and happened to be on together when this was first written, which is how
 * an earlier version of this message came to name the wrong one.
 *
 * It names the action that was refused and the kind of thing that refused it, and stops
 * there. Three earlier attempts added a clause explaining what the source does instead,
 * and none of them read well: the kind of source is the reason, so saying it twice only
 * made the line longer.
 */
private fun dynamicQueueMessage(action: String, source: QueueSource?): String {
    val kind = when (source?.mediaType) {
        MediaType.RADIO -> "a radio"
        MediaType.PLAYLIST -> "a smart playlist"
        else -> "this queue"
    }
    return "Can't $action $kind"
}

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

/**
 * The last track seen, kept so the screen does not go blank when the Music Assistant
 * connection drops while Sendspin keeps playing.
 *
 * [playerId] is what the holdover belongs to. Without it the cache outlived the reason
 * it exists: selecting an idle speaker showed whatever the previously selected player
 * was playing, artwork and all, because every other source was null and this one was
 * not. A holdover is only ever valid for the player it was captured from.
 */
data class CachedTrackDisplay(
    val title: String,
    val artist: String,
    val album: String,
    val imageUrl: String?,
    val duration: Double,
    val playerId: String?
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
    val absoluteSyncMs: Float = 0f,
    val syncMuted: Boolean = false,
    val audioRoute: String = "",
    // True when the active output is routed to a Bluetooth sink.
    // Drives the streaming-status sheet's latency label: BT routes show
    // calibration state, non-BT routes are labelled "port latency" since
    // they sync at the audio port via the AudioTrack pipeline measurement.
    val isBtRoute: Boolean = false,
    val clockSamples: Int = 0,
    val clockErrorUs: Long = 0L,
    val clockRttUs: Long = 0L,
    val clockDriftPpm: Double = 0.0,
    val resyncs: Int = 0,
    val correctionMode: String = "",
    // Actual Sendspin transport output format (post server re-encode):
    // captured from the latest stream/start payload. `outputSampleRate` is in
    // Hz, `outputBitDepth` is 0 for lossy codecs that don't report it.
    val outputSampleRate: Int = 0,
    val outputBitDepth: Int = 0,
    // Native output health (real-time callback): the decoded ring cushion, the
    // cumulative underrun (audible-dropout) count, and the live resampler rate.
    val ringBufferedMs: Long = 0L,
    val underrunFrames: Long = 0L,
    val resampleRate: Double = 1.0,
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
    private val queueTogglesCache: net.asksakis.massdroidv2.data.repository.QueueTogglesCache,
    private val proximityConfigStore: net.asksakis.massdroidv2.data.proximity.ProximityConfigStore
) : ViewModel() {

    val selectedPlayer = playerRepository.selectedPlayer
    val allPlayers: StateFlow<List<net.asksakis.massdroidv2.domain.model.Player>> = playerRepository.players
    val queueState = playerRepository.queueState
    val queueAutoplayStates: StateFlow<Map<String, Boolean>> = queueTogglesCache.autoplayStates

    /** Whether crossfade is on, per queue. Empty on a server before MA 2.10. */
    val queueCrossfadeStates: StateFlow<Map<String, Boolean>> = queueTogglesCache.crossfadeStates
    val elapsedTime = playerRepository.elapsedTime
    val sendspinClientId = settingsRepository.sendspinClientId
    val sendspinAudioFormat = settingsRepository.sendspinAudioFormat
    val sendspinSyncHistory = sendspinManager.syncHistory

    /** Follow Me rooms, so the player settings dialog can show which room this player serves. */
    val proximityConfig = proximityConfigStore.config

    /**
     * Point a Follow Me room at this player. Written to the proximity config immediately, the
     * way the room setup screen writes it. The room keeps its calibration and the rooms this
     * player already served are left alone (see withRoomPlayer).
     */
    fun assignPlayerToRoom(roomId: String, player: net.asksakis.massdroidv2.domain.model.Player) {
        viewModelScope.launch {
            proximityConfigStore.update {
                it.withRoomPlayer(roomId, player.playerId, player.displayName)
            }
        }
    }

    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()
    private val playlistMembership = PlaylistMembershipController(musicRepository, viewModelScope)
    private val queueTransfer = QueueTransfer(musicRepository, playerRepository)
    val playlists: StateFlow<List<Playlist>> = playlistMembership.playlists
    val isLoadingPlaylists: StateFlow<Boolean> = playlistMembership.isLoading
    val addingToPlaylistId: StateFlow<String?> = playlistMembership.pendingPlaylistId
    val playlistContainsTrack: StateFlow<Set<String>> = playlistMembership.containsTrack

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
            // Audiobooks are not songs: never resolve/show lyrics for them.
            .map { it?.currentItem?.track?.takeUnless { t -> t.mediaType == MediaType.AUDIOBOOK } }
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

    /** Emitted after a dislike so the screen can offer to take it back. */
    private val _dislikeUndo = MutableSharedFlow<DislikeUndo>(extraBufferCapacity = 1)
    val dislikeUndo: SharedFlow<DislikeUndo> = _dislikeUndo.asSharedFlow()
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
    /**
     * Audiobook-derived UI state. An audiobook is a single playable queue item
     * whose chapters are seek markers (see [Chapter]). The screen branches on
     * these StateFlows; composables stay dumb (no scattered isAudiobook booleans).
     */
    val isAudiobook: StateFlow<Boolean> = queueState
        .map { it?.currentItem?.track?.mediaType == MediaType.AUDIOBOOK }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val chapters: StateFlow<List<Chapter>> = queueState
        .map { it?.currentItem?.track?.chapters ?: emptyList() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Index into [chapters] of the chapter containing the current position, or -1. */
    val currentChapterIndex: StateFlow<Int> = combine(elapsedTime, chapters) { elapsed, chs ->
        if (chs.isEmpty()) -1 else chs.indexOfLast { elapsed + 0.001 >= it.start }.coerceAtLeast(0)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    var cachedSendspinClientId: String? = null; private set
    var cachedSendspinAudioFormat = SendspinAudioFormat.AUTOMATIC.name; private set
    private var lastSendspinStatusLogAtMs = 0L
    private var lastLoggedSendspinStatusKey: String? = null

    /**
     * Sendspin status for the streaming sheet: the state flows plus a 1 Hz ticker, so that
     * the dynamic metrics (buffer, latency, clock error, drift) refresh while none of the
     * state sources change. Without the ticker the buffer reading froze at whatever it was
     * at the last transition, for example a drained buffer after a Wi-Fi to mobile handover.
     *
     * The ticker runs only while the screen collects. It used to run in the init block for
     * the life of the ViewModel, which is the life of the activity, so it woke every second
     * all night with the screen off and read about twenty native getters each time.
     * Declared below the cached fields it reads.
     */
    val sendspinStatus: StateFlow<SendspinStatusUi?> = combine(
        sendspinManager.connectionState,
        sendspinManager.syncState,
        sendspinManager.streamCodec,
        sendspinManager.networkMode,
        sendspinClientId,
        flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        },
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
            syncDelayMs = sendspinManager.syncDelayMs(),
            outputLatencyMs = sendspinManager.outputLatencyMs(),
            acousticCorrectionMs = sendspinManager.acousticExtraMs(),
            absoluteSyncMs = sendspinManager.absoluteSyncMs(),
            syncMuted = sendspinManager.isSyncMuted(),
            isBtRoute = acoustic.isBtRoute(),
            clockSamples = sendspinManager.clockSampleCount(),
            clockErrorUs = sendspinManager.clockErrorUs(),
            clockRttUs = sendspinManager.clockRttUs(),
            clockDriftPpm = sendspinManager.clockDriftPpm(),
            resyncs = sendspinManager.resyncCount(),
            correctionMode = sendspinManager.correctionModeName(),
            outputSampleRate = fmt?.sampleRate ?: 0,
            outputBitDepth = fmt?.bitDepth ?: 0,
            ringBufferedMs = sendspinManager.ringBufferedMs(),
            underrunFrames = sendspinManager.underrunFrames(),
            resampleRate = sendspinManager.resampleRate(),
        ).also { maybeLogSendspinUiStatus(it) }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _cachedTrackDisplay = MutableStateFlow<CachedTrackDisplay?>(null)

    /**
     * The holdover, but only while it still describes the selected player. Filtered here
     * rather than at the call sites so no screen has to remember the rule.
     */
    val cachedTrackDisplay: StateFlow<CachedTrackDisplay?> =
        combine(_cachedTrackDisplay, playerRepository.selectedPlayer) { cached, player ->
            cached?.takeIf { it.playerId == null || it.playerId == player?.playerId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _adjacentArtwork = MutableStateFlow(AdjacentArtworkUi(previousImageUrl = null, nextImageUrl = null))
    val adjacentArtwork: StateFlow<AdjacentArtworkUi> = _adjacentArtwork.asStateFlow()

    /**
     * A queue position the user has asked for and the server has not confirmed
     * yet. Consecutive steps count from it, and the artwork either side is
     * resolved against it, so the second swipe of a burst reveals the track
     * after the one the first asked for instead of the same one again.
     *
     * Declared above init, which collects it. Properties initialise in source
     * order, so a declaration below init leaves it null while the collectors
     * are already running, which crashed the screen on open.
     */
    private data class PendingQueueJump(val index: Int, val queueItemId: String?, val atMs: Long)

    private val pendingQueueJump = MutableStateFlow<PendingQueueJump?>(null)

    // Optimistic elapsed time: continues ticking during MA disconnect while sendspin plays
    private var optimisticBaseTime = 0.0
    private var optimisticBaseTimestamp = 0L
    private var optimisticDuration = 0.0

    /**
     * The queue item the optimistic base was captured from. A position only means
     * something for the track it was read on, so the projection is offered only
     * while that track is still the one playing.
     *
     * This is what separates the two reasons elapsed can read 0. A dropped MA
     * connection leaves the same item in place and is what the projection is for.
     * A track change also publishes 0 (the repository resets before it publishes
     * the new queue state), and projecting across that one put the PREVIOUS
     * track's position on screen until the ticker climbed past 0 again, which is
     * the jump the seek bar made on every skip.
     */
    private var optimisticBaseItemId: String? = null

    /** Whether the optimistic base still belongs to the track on screen. */
    private fun optimisticBaseIsCurrent(): Boolean =
        optimisticBaseTimestamp > 0L &&
            optimisticBaseItemId == queueState.value?.currentItem?.queueItemId
    private val _optimisticElapsed = MutableStateFlow<Double?>(null)
    val optimisticElapsed: StateFlow<Double?> = _optimisticElapsed.asStateFlow()

    init {
        viewModelScope.launch {
            playlistMembership.errors.collect { _error.tryEmit(it) }
        }
        viewModelScope.launch {
            smartListeningRepository.blockedArtistUris.collect { _blockedArtistUris.value = it }
        }
        // Clear the optimistic chapter target once the live position reaches it.
        viewModelScope.launch {
            currentChapterIndex.collect { if (it == pendingChapterTarget) pendingChapterTarget = -1 }
        }
        // Clear the optimistic queue jump once the server reports the position
        // it asked for. Matching on the item id where we have it, because the
        // numeric index shifts when the queue is reordered under us.
        viewModelScope.launch {
            queueState.collect { qs ->
                val pending = pendingQueueJump.value ?: return@collect
                if (qs == null) {
                    pendingQueueJump.value = null
                    return@collect
                }
                skipInFlight?.let { inFlight ->
                    if (qs.currentItem?.queueItemId != inFlight.fromItemId) onSkipSettled()
                }
                val arrived = pending.queueItemId
                    ?.let { it == qs.currentItem?.queueItemId }
                    ?: (qs.currentIndex == pending.index)
                if (arrived) pendingQueueJump.value = null
            }
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
                    optimisticBaseItemId = queueState.value?.currentItem?.queueItemId
                    optimisticDuration = queueState.value?.currentItem?.duration
                        ?: selectedPlayer.value?.currentMedia?.duration ?: 0.0
                    _optimisticElapsed.value = null // live data available, no need for optimistic
                } else if (sendspinManager.enabled.value && optimisticBaseIsCurrent()) {
                    // Live elapsed reset to 0 on the SAME track: an MA disconnect,
                    // which is what this projection exists for. A track change also
                    // publishes 0 and is excluded above, because the base belongs to
                    // the track that just ended.
                    val elapsed = optimisticBaseTime + (System.currentTimeMillis() - optimisticBaseTimestamp) / 1000.0
                    _optimisticElapsed.value = if (optimisticDuration > 0) elapsed.coerceAtMost(optimisticDuration) else elapsed
                } else {
                    _optimisticElapsed.value = null
                }
            }
        }
        // Optimistic elapsed tick: only runs while live elapsed is 0 and sendspin is playing
        viewModelScope.launch {
            combine(elapsedTime, sendspinManager.enabled, sendspinManager.syncState) { live, enabled, sync ->
                live <= 0.0 && enabled && optimisticBaseIsCurrent() &&
                    (sync == SyncState.SYNCHRONIZED || sync == SyncState.HOLDOVER_PLAYING_FROM_BUFFER)
            }.distinctUntilChanged().collect { shouldTick ->
                if (shouldTick) {
                    while (true) {
                        delay(500)
                        if (elapsedTime.value > 0.0 || !optimisticBaseIsCurrent()) break
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
            combine(positionFlow, playerRepository.queueItems, pendingQueueJump) { position, snapshot, pending ->
                Triple(position, snapshot, pending)
            }.collectLatest { (position, snapshot, pending) ->
                if (position.queueId == null ||
                    position.currentItemId == null ||
                    snapshot == null ||
                    snapshot.queueId != position.queueId
                ) {
                    _adjacentArtwork.value = AdjacentArtworkUi(previousImageUrl = null, nextImageUrl = null)
                    return@collectLatest
                }
                // Resolved against the position the user has stepped to, so the
                // next swipe of a burst reveals the track after the one the last
                // swipe asked for. The server is several steps behind during a
                // burst and resolving against it handed every swipe the same
                // neighbouring cover again.
                //
                // This pair moves as soon as a swipe commits, so the art the
                // swipe is carrying must not read from it any more. The gesture
                // takes its copy at commit time, see SwipeableAlbumArt.
                val idx = (pending?.index ?: position.currentIndex ?: 0).coerceAtLeast(0)
                // The covers either side are the tracks a swipe would REACH, which is not
                // idx - 1 and idx + 1 when something in between is filtered out. A blocked
                // track one step back cannot be deleted server side, and taking the
                // neighbour at face value put its cover under the gesture even though the
                // swipe correctly played the track beyond it.
                _adjacentArtwork.value = resolveAdjacentArtwork(
                    previous = snapshot.items.getOrNull(
                        firstAllowedIndex(snapshot.items, idx, SKIP_BACKWARD)
                    ),
                    next = snapshot.items.getOrNull(
                        firstAllowedIndex(snapshot.items, idx, SKIP_FORWARD)
                    )
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
                    duration = track.duration ?: qs.currentItem?.duration ?: 0.0,
                    playerId = selectedPlayer.value?.playerId
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
                        duration = dur,
                        // Sendspin metadata arrives with no player selected at all, so
                        // this holdover is not tied to one and stays valid.
                        playerId = null
                    )
                    if (dur > 0.0) optimisticDuration = dur
                }
            }
        }
        // Don't clear cache on disconnect/error: keep showing last track info
        // until a new track replaces it (via queueState or serverMetadata collectors)
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
                _error.tryEmit(e.failureMessage("Couldn't change playback"))
            }
        }
    }

    /**
     * The covers either side, taken from the items themselves rather than from offsets into
     * a window. The window arithmetic it replaced assumed the three tracks were adjacent,
     * which stopped being true once the neighbours became the ones a swipe can actually
     * reach.
     */
    private fun resolveAdjacentArtwork(previous: QueueItem?, next: QueueItem?): AdjacentArtworkUi =
        AdjacentArtworkUi(
            previousImageUrl = previous?.let { it.track?.imageUrl ?: it.imageUrl },
            nextImageUrl = next?.let { it.track?.imageUrl ?: it.imageUrl }
        )

    /**
     * Pull fresh active-queue state from the server. Called when the full player
     * opens: MA pushes position/queue events sparsely, so a freshly-opened player
     * must fetch rather than wait for the next event (which can be a minute away).
     */
    fun refreshNowPlaying() {
        viewModelScope.launch {
            try {
                playerRepository.refreshActiveQueue()
            } catch (e: Exception) {
                Log.w(TAG, "refreshNowPlaying failed: ${e.message}")
            }
        }
    }

    /**
     * Skip forward one track. Coalesced with any other steps the user makes in
     * quick succession, see [requestSkip]. Falls back to the server's own
     * "next" when the target position cannot be resolved, which is also what
     * happens at the end of the queue so the server stays in charge of what
     * plays next.
     */
    fun next() {
        if (requestSkip(SKIP_FORWARD)) return
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.next(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "next failed: ${e.message}")
                _error.tryEmit(e.failureMessage("Couldn't skip forward"))
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
                _error.tryEmit(e.failureMessage("Couldn't skip back"))
            }
        }
    }

    /**
     * The first index in [delta]'s direction whose track is not filtered out, or an index
     * past either end when there is none.
     *
     * Blocked tracks are normally deleted from the queue, but MA refuses to delete what the
     * player already holds, so the ones behind the playhead stay there for good. Stepping
     * blindly landed on them: the blocked track started, and the repository's auto-skip moved
     * on a moment later, which is the blocked artist briefly playing on a swipe back.
     */
    private fun firstAllowedIndex(items: List<QueueItem>, from: Int, delta: Int): Int {
        var idx = from + delta
        while (idx in items.indices && playerRepository.isQueueItemFiltered(items[idx])) {
            idx += delta
        }
        return idx
    }

    private fun pendingQueueJumpIfFresh(now: Long): PendingQueueJump? =
        pendingQueueJump.value?.takeIf { now - it.atMs < PENDING_QUEUE_JUMP_TTL_MS }

    /**
     * Always go to previous track via play_index (skip the "restart current"
     * behavior of cmd/previous). Coalesced with any other steps the user makes
     * in quick succession, see [requestSkip]. Falls back to the server's own
     * "previous" only when no position can be resolved at all.
     */
    fun previousTrack() {
        if (requestSkip(SKIP_BACKWARD)) return
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.previous(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "previousTrack failed: ${e.message}")
            }
        }
    }

    /**
     * A command sent to the server that we are waiting to see it act on.
     *
     * [fromItemId] is what was playing when it was sent, and the command counts
     * as acted on once something else is. Waiting instead for the exact item it
     * asked for meant waiting out the timeout nearly every time: the server does
     * not always land where it was sent, because a stream it aborts ends early
     * and it walks on to the next by itself.
     */
    private data class SkipInFlight(
        val queueId: String,
        val index: Int,
        val fromItemId: String?,
        val backwards: Boolean
    )

    private var skipInFlight: SkipInFlight? = null
    private var skipAckJob: Job? = null

    /**
     * Move one step through the queue and tell the server about it, holding back
     * the steps the server has no room for.
     *
     * The optimistic position moves on every step, so the screen and the step
     * after it both count from where the user thinks they are rather than from
     * a queue state that only catches up when the server's QUEUE_UPDATED
     * arrives.
     *
     * The server gets one command at a time. While a command is unanswered the
     * steps that follow only move the target, and the final position is sent as
     * a single command once the server has caught up. This paces itself against
     * whatever the server can manage instead of guessing: when it answers in
     * milliseconds every step goes out on its own, and when it is struggling a
     * burst costs two commands.
     *
     * Pacing matters because MA opens a fresh queue-flow stream per command and
     * the music provider caps concurrent streams. Measured on this server, even
     * one skip per 1.2 seconds made MA abort the stream it had just opened
     * ("Aborting the source of X to free a Deezer stream slot"), treat the
     * aborted stream as a finished track and walk forward on its own, so the
     * next step counted from somewhere the user never asked for.
     *
     * Returns false when the target position cannot be resolved, leaving the
     * caller to send the server's own relative command instead. The end of the
     * queue resolves to nothing on purpose, because what follows the last item
     * is the server's decision (Don't Stop the Music, repeat).
     */
    private fun requestSkip(delta: Int): Boolean {
        val qs = queueState.value ?: return false
        val now = System.currentTimeMillis()
        val snapshot = playerRepository.queueItems.value?.takeIf { it.queueId == qs.queueId }
        val pending = pendingQueueJumpIfFresh(now)
        val resolvedCurrent = when {
            pending != null -> pending.index
            // The snapshot is canonical. QueueState.currentIndex goes stale when
            // the user moves a track above the current one: the server shifts the
            // current track's position to N+1 while the cached index is still N.
            snapshot != null && qs.currentItem?.queueItemId != null ->
                snapshot.items.indexOfFirst { it.queueItemId == qs.currentItem?.queueItemId }
            else -> -1
        }
        val currentIndex = if (resolvedCurrent >= 0) resolvedCurrent else qs.currentIndex
        if (currentIndex < 0) return false
        val items = snapshot?.items ?: return false
        val targetIndex = firstAllowedIndex(items, currentIndex, delta)
        // Already at the first item: there is nothing before it, and saying so
        // here keeps the caller from falling back to the server's "previous",
        // which would restart the track the user is on.
        if (targetIndex < 0) return true
        if (targetIndex > items.lastIndex) return false

        pendingQueueJump.value = PendingQueueJump(
            targetIndex,
            items.getOrNull(targetIndex)?.queueItemId,
            now
        )
        if (skipInFlight == null) {
            dispatchSkip(
                SkipInFlight(qs.queueId, targetIndex, qs.currentItem?.queueItemId, delta < 0)
            )
        }
        return true
    }

    private fun dispatchSkip(skip: SkipInFlight) {
        skipInFlight = skip
        skipAckJob?.cancel()
        skipAckJob = viewModelScope.launch {
            // The server does not always land on the position it was given: a
            // stream it aborts ends early and it walks on by itself. Waiting for
            // an arrival that will never come would wedge every later step, so
            // give up after a bounded wait and carry on from wherever it is.
            delay(SKIP_ACK_TIMEOUT_MS)
            onSkipSettled()
        }
        viewModelScope.launch {
            try {
                // Local playback buffers ahead, so it has to be told the timeline
                // is about to jump. players/cmd/next and cmd/previous do this from
                // inside the repository; a queue jump is sent from here, so it
                // announces it here too. Without it the phone kept playing the old
                // track out of its buffer while the server had already moved on.
                selectedPlayer.value?.playerId?.let {
                    playerRepository.signalDiscontinuity(
                        it,
                        if (skip.backwards) {
                            PlayerDiscontinuityCommand.Kind.PREVIOUS
                        } else {
                            PlayerDiscontinuityCommand.Kind.NEXT
                        }
                    )
                }
                musicRepository.playQueueIndex(skip.queueId, skip.index)
            } catch (e: Exception) {
                Log.w(TAG, "skip to ${skip.index} failed: ${e.message}")
                pendingQueueJump.value = null
                onSkipSettled()
            }
        }
    }

    /**
     * The server has caught up with the command in flight, or has taken long
     * enough that we stop waiting. If the user has stepped further in the
     * meantime, that position goes out now as a single command.
     */
    private fun onSkipSettled() {
        val settled = skipInFlight ?: return
        skipAckJob?.cancel()
        skipAckJob = null
        skipInFlight = null
        val target = pendingQueueJump.value ?: return
        if (target.index == settled.index) return
        Log.d("sendspindbg", "skip coalesced: ${settled.index} -> ${target.index}")
        dispatchSkip(
            SkipInFlight(
                settled.queueId,
                target.index,
                queueState.value?.currentItem?.queueItemId,
                settled.backwards
            )
        )
    }

    companion object {
        /**
         * How long a queue jump may stay unconfirmed before the next press stops
         * counting from it. Measured server round-trips for a track change are
         * 55 to 285 ms, so a jump still unconfirmed here was dropped.
         */
        private const val PENDING_QUEUE_JUMP_TTL_MS = 3000L

        private const val SKIP_FORWARD = 1
        private const val SKIP_BACKWARD = -1

        /**
         * How long to wait for the server to reach a position before deciding it
         * is not going to and moving on. Measured round trips are 55 to 285 ms
         * when it is idle and up to four seconds when it is working through a
         * queue of commands, so this sits past the worst of that.
         *
         * Pacing used to be a fixed quiet time between steps instead, which was
         * the wrong measure twice over: at 250 ms it folded nothing because real
         * swipes are 274 to 639 ms apart, and at one second it still let through
         * a step every 1.2 seconds, which was more than this server could take.
         */
        private const val SKIP_ACK_TIMEOUT_MS = 5000L

        // Seconds into a chapter past which "previous" restarts it instead of
        // jumping to the prior chapter.
        private const val PREVIOUS_CHAPTER_RESTART_THRESHOLD_S = 3.0
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
                _error.tryEmit(e.failureMessage("Couldn't seek"))
            }
        }
    }

    /** Jump to a chapter by seeking to its start. Chapters are markers within one item. */
    fun seekToChapter(chapter: Chapter) = seek(chapter.start)

    /**
     * Relative seek by [deltaSeconds] (negative = back), clamped to the item bounds. Used by the
     * audiobook quick-skip buttons so listeners can nudge a little instead of jumping a whole
     * chapter (the chapter step is coarse). Builds on [seek]; the position ticker reflects it.
     */
    fun seekRelative(deltaSeconds: Int) {
        val durationSec = queueState.value?.currentItem?.duration?.takeIf { it > 0.0 } ?: Double.MAX_VALUE
        val target = (elapsedTime.value + deltaSeconds).coerceIn(0.0, durationSec)
        seek(target)
    }

    // Optimistic chapter target: the position ticker (and thus currentChapterIndex)
    // lags a seek by up to ~500ms + server round-trip, so consecutive prev/next presses
    // would otherwise re-use a stale index and seek to the same chapter. We base the
    // next press on the last sought chapter until the live index catches up.
    @Volatile private var pendingChapterTarget = -1

    private fun chapterNavBaseIndex(chs: List<Chapter>): Int =
        if (pendingChapterTarget in chs.indices) pendingChapterTarget else currentChapterIndex.value

    /**
     * Audiobook "next" = next chapter (not next queue item: the book is one item).
     * No-op past the last chapter.
     */
    fun nextChapter() {
        val chs = chapters.value
        val base = chapterNavBaseIndex(chs)
        if (base < 0 || base + 1 >= chs.size) return
        pendingChapterTarget = base + 1
        seek(chs[base + 1].start)
    }

    /**
     * Audiobook "previous": restart the current chapter if more than a few
     * seconds in, otherwise jump to the previous chapter. Mirrors standard
     * audiobook-player behavior.
     */
    fun previousChapter() {
        val chs = chapters.value
        if (chs.isEmpty()) return
        val hasPending = pendingChapterTarget in chs.indices
        val base = if (hasPending) pendingChapterTarget else currentChapterIndex.value
        if (base < 0) return
        // "Restart current chapter if >3s in" only makes sense against the LIVE position.
        // During an in-flight prev/next seek (hasPending) elapsedTime is still the pre-seek
        // position, so the restart delta would be bogus — just step to the previous chapter.
        val restartCurrent = base > 0 && !hasPending &&
            elapsedTime.value - chs[base].start > PREVIOUS_CHAPTER_RESTART_THRESHOLD_S
        val target = when {
            base == 0 -> 0
            restartCurrent -> base
            else -> base - 1
        }
        pendingChapterTarget = target
        seek(chs[target].start)
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
        if (queue.isDynamic) {
            _error.tryEmit(dynamicQueueMessage("shuffle", queue.source))
            return
        }
        viewModelScope.launch {
            try {
                musicRepository.shuffleQueue(queue.queueId, !queue.shuffleEnabled)
            } catch (e: Exception) {
                Log.w(TAG, "toggleShuffle failed: ${e.message}")
                _error.tryEmit(e.failureMessage("Couldn't change shuffle"))
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
                _error.tryEmit(e.failureMessage("Couldn't save that"))
            }
        }
    }

    /**
     * Reject the playing track outright and move on.
     *
     * The only negative signal the engine does not have to infer. Every other
     * one is read out of behaviour, and a skip in particular is ambiguous:
     * it can mean "not this song", "not right now", or nothing at all. The
     * track is buried, its artist is only brushed, and the whole thing is
     * undoable because it is one tap next to the heart.
     */
    fun dislikeCurrentTrack() {
        val track = queueState.value?.currentItem?.track ?: return
        val player = selectedPlayer.value
        viewModelScope.launch {
            try {
                val artists = trackArtists(track.artistItemId, track.artistUri, track.artistNames)
                val receipt = smartListeningRepository.recordDislike(track, artists)
                if (receipt == null) {
                    _error.tryEmit("Smart Listening is off")
                    return@launch
                }
                _dislikeUndo.tryEmit(DislikeUndo(receipt, track.name))
                // Skipping past it is the point, but the skip must not be filed
                // as a second negative signal on top of the dislike.
                player?.let { playerRepository.skipWithoutSignal(it.playerId) }
            } catch (e: Exception) {
                Log.w(TAG, "dislikeCurrentTrack failed: ${e.message}")
                _error.tryEmit(e.failureMessage("Couldn't save that"))
            }
        }
    }

    fun undoDislike(undo: DislikeUndo) {
        viewModelScope.launch {
            try {
                smartListeningRepository.undoDislike(undo.receipt)
            } catch (e: Exception) {
                Log.w(TAG, "undoDislike failed: ${e.message}")
                _error.tryEmit(e.failureMessage("Couldn't undo that"))
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
                _error.tryEmit(e.failureMessage("Couldn't update the filter"))
            }
        }
    }

    /** Point the add-to-playlist dialog at the track playing now and load the list. */
    fun loadPlaylists(force: Boolean = false) {
        playlistMembership.open(queueState.value?.currentItem?.track?.uri, reload = force)
    }

    /** Resolve the tick marks for the playlist rows currently on screen. */
    fun onPlaylistsVisible(playlistUris: List<String>) {
        playlistMembership.onPlaylistsVisible(playlistUris)
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
        playlistMembership.remove(playlist, onDone)
    }

    fun createPlaylistAndAddTrack(name: String, onDone: () -> Unit = {}) {
        playlistMembership.createAndAdd(name, onDone = onDone)
    }

    fun addCurrentTrackToPlaylist(playlist: Playlist, onDone: () -> Unit = {}) {
        playlistMembership.add(playlist, onDone)
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

    /**
     * Move the queue to [targetPlayerId] and follow it there.
     *
     * The selection has to move with the queue. Leaving it behind left this screen
     * bound to a player that no longer has anything to play, so the transport controls
     * acted on silence while the music came out of the other speaker. The Players
     * screen already switched after its own transfer; only this path did not.
     *
     * [NonCancellable] because the two steps are one action: navigating away or the
     * sheet closing must not leave the queue moved and the selection behind.
     */
    fun transferQueue(targetPlayerId: String) {
        val sourceQueueId = queueState.value?.queueId ?: return
        viewModelScope.launch {
            withContext(NonCancellable) {
                val outcome = queueTransfer.moveAndFollow(sourceQueueId, targetPlayerId)
                if (outcome is QueueTransferOutcome.Failed) Log.w(TAG, "transferQueue failed: ${outcome.cause.message}")
                _error.tryEmit(outcome.userMessage())
            }
        }
    }

    fun cycleRepeat() {
        val queue = queueState.value ?: return
        if (queue.isDynamic) {
            _error.tryEmit(dynamicQueueMessage("repeat", queue.source))
            return
        }
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
                _error.tryEmit(e.failureMessage("Couldn't change repeat"))
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

    fun setAutoplayEnabled(queueId: String, enabled: Boolean) {
        queueTogglesCache.setOptimistic(queueId, enabled)
        viewModelScope.launch {
            try {
                musicRepository.setAutoplayEnabled(queueId, enabled)
            } catch (e: Exception) {
                Log.w(TAG, "setAutoplayEnabled failed: ${e.message}")
            }
        }
    }

    fun setAudioFormat(format: net.asksakis.massdroidv2.domain.model.SendspinAudioFormat) {
        viewModelScope.launch {
            settingsRepository.setSendspinAudioFormat(format.name)
        }
    }

    /**
     * Configuration of one queue, loaded on demand when the settings dialog opens:
     * Autoplay, crossfade type, volume normalization, smart shuffle.
     *
     * Null covers both "this server predates MA 2.10" and "this account may not read
     * queue config", and the dialog leaves those settings out in either case.
     */
    suspend fun getQueueSettings(queueId: String): net.asksakis.massdroidv2.domain.model.QueueSettings? =
        musicRepository.getQueueSettings(queueId)

    /** Change one queue config value, reporting whether the server accepted it. */
    suspend fun setQueueConfigValue(queueId: String, key: String, value: String): Boolean =
        musicRepository.setQueueConfigValue(queueId, key, value)

    /** Turn crossfade on or off for the queue, showing the change before the server echo. */
    fun setCrossfadeEnabled(queueId: String, enabled: Boolean) {
        queueTogglesCache.setCrossfadeOptimistic(queueId, enabled)
        viewModelScope.launch {
            try {
                musicRepository.setCrossfadeEnabled(queueId, enabled)
            } catch (e: Exception) {
                // An RPC error here used to escape the scope and take the process with it,
                // from a switch tap. The command can genuinely be refused (an older server
                // does not have it at all), so put the switch back and say nothing louder.
                Log.w(TAG, "crossfade toggle refused for $queueId: ${e.message}")
                queueTogglesCache.setCrossfadeOptimistic(queueId, !enabled)
            }
        }
    }

    /**
     * Save a new Autoplay strategy and report whether the server took it, so the dialog
     * can put the selector back if a non-admin account was refused.
     */
    suspend fun setAutoplayConfig(queueId: String, mode: String, playlistUri: String?): Boolean =
        musicRepository.setAutoplayConfig(queueId, mode, playlistUri)

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
                _error.tryEmit(e.failureMessage("Couldn't save the settings"))
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

/** A dislike that can still be taken back, with the track name to name it by. */
data class DislikeUndo(
    val receipt: net.asksakis.massdroidv2.domain.repository.DislikeReceipt,
    val trackName: String,
)
