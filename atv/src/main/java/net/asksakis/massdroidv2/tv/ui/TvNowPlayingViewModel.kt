package net.asksakis.massdroidv2.tv.ui

import net.asksakis.massdroidv2.domain.player.VolumeKeyController
import net.asksakis.massdroidv2.domain.player.VolumeKeyTarget
import net.asksakis.massdroidv2.domain.player.volumeKeyTarget
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import net.asksakis.massdroidv2.data.lyrics.LyricsProvider
import net.asksakis.massdroidv2.domain.feedback.DislikeUndo
import net.asksakis.massdroidv2.domain.feedback.TrackDisliker
import net.asksakis.massdroidv2.domain.info.AboutText
import net.asksakis.massdroidv2.domain.info.AboutTextResolver
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueState
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * Controls a single MA player from the TV: transport + volume, targeting the
 * player id passed via navigation. Selecting it also makes it the app's current
 * player so library plays from the home land here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TvNowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val aboutTextResolver: AboutTextResolver,
    private val lyricsProvider: LyricsProvider,
    private val trackDisliker: TrackDisliker,
    private val volumeKeyController: VolumeKeyController,
    settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playerId: String = checkNotNull(savedStateHandle["playerId"])

    val player: StateFlow<Player?> = playerRepository.players
        .map { list -> list.firstOrNull { it.playerId == playerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the volume buttons show: only for a Music Assistant player elsewhere, decided as
     * the remote's volume keys are ([volumeKeyTarget]). This TV's own output, a member of a
     * group included, is left at full level for the TV or the receiver to set: turning it down
     * here as well would attenuate twice. The keys rarely reach the app on a TV (a remote with
     * IR or CEC control sends them straight to the receiver), so these buttons are the way.
     */
    val volumeShown: StateFlow<Boolean> = combine(player, settingsRepository.sendspinClientId) { current, localId ->
        volumeKeyTarget(current, localId) is VolumeKeyTarget.RemotePlayer
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * A step on this player, through the same controller as the volume keys: group volume on
     * a group, unmute on the way up, the OSD. Opening this screen selected the player, which
     * is the one the controller steps.
     */
    fun volumeUp() {
        volumeKeyController.step(up = true)
    }

    fun volumeDown() {
        volumeKeyController.step(up = false)
    }

    // Optimistic seek target: D-pad steps move the bar instantly and accumulate,
    // while the actual seek RPC is debounced (the MA Sendspin path restarts the
    // stream per seek, so we coalesce a burst of presses into one command). The
    // override clears once the server position catches up to it.
    private val _seekTarget = MutableStateFlow<Double?>(null)
    private var seekJob: Job? = null

    /** Live playback position (seconds): the optimistic target if seeking, else the :core ticker. */
    val elapsed: StateFlow<Double> =
        combine(playerRepository.elapsedTime, _seekTarget) { server, target -> target ?: server }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Selected queue state (shuffle/repeat live here, per MA). */
    val queueState: StateFlow<QueueState?> = playerRepository.queueState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A few words about the playing album, or its artist ([AboutTextResolver.follow]). */
    val about: StateFlow<AboutText> = aboutTextResolver
        .follow(playerRepository.queueState.map { it?.currentItem?.track })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AboutText.Loading)

    private val _lyricsShown = MutableStateFlow(false)

    /** Whether the left side shows the lyrics instead of the cover and the about text. */
    val lyricsShown: StateFlow<Boolean> = _lyricsShown.asStateFlow()

    /**
     * The playing track's lyrics while they are shown, fetched for each track as it starts.
     * The track's own lyrics are used when it carries them, as on the phone; otherwise Music
     * Assistant is asked. Nothing is fetched while the lyrics are hidden.
     */
    val lyrics: StateFlow<TvLyrics> = combine(
        _lyricsShown,
        playerRepository.queueState.map { it?.currentItem?.track }.distinctUntilChanged { a, b -> a?.uri == b?.uri }
    ) { shown, track -> if (shown) track else null }
        .distinctUntilChanged { a, b -> a?.uri == b?.uri }
        .transformLatest { track ->
            emit(TvLyrics.Loading)
            if (track != null) emit(loadLyrics(track))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvLyrics.Loading)

    fun toggleLyrics() {
        _lyricsShown.value = !_lyricsShown.value
    }

    private suspend fun loadLyrics(track: Track): TvLyrics {
        if (track.mediaType == MediaType.AUDIOBOOK) return TvLyrics.None
        val embedded = LyricsProvider.normalizeLyricsResult(track.lyrics, track.lrcLyrics)
        if (embedded != LyricsProvider.LyricsContent.None) return TvLyrics.Ready(embedded)
        return when (val result = lyricsProvider.fetchLyrics(track.itemId, track.provider, track.uri)) {
            is LyricsProvider.FetchResult.Found -> TvLyrics.Ready(result.lyrics)
            LyricsProvider.FetchResult.NotFound -> TvLyrics.None
            LyricsProvider.FetchResult.Failed -> TvLyrics.Failed
        }
    }

    private val _dislikeUndo = MutableSharedFlow<DislikeUndo>(extraBufferCapacity = 1)

    /** Emitted after a dislike, so the screen can offer to take it back. */
    val dislikeUndo: SharedFlow<DislikeUndo> = _dislikeUndo.asSharedFlow()

    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A short message for the screen to show, such as a dislike that could not be saved. */
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    /** "Not for me": the shared [TrackDisliker] records it and skips past the track. */
    fun dislikeCurrentTrack() {
        val track = playerRepository.queueState.value?.currentItem?.track ?: return
        viewModelScope.launch {
            when (val outcome = trackDisliker.dislike(track, playerId)) {
                is TrackDisliker.Outcome.Recorded -> _dislikeUndo.tryEmit(outcome.undo)
                // Smart Listening off and a server older than 2.11, which records no dislikes.
                TrackDisliker.Outcome.NothingToRecord ->
                    _notice.tryEmit("Turn on Smart Listening in Settings to use Not for me")
                is TrackDisliker.Outcome.Failed -> _notice.tryEmit("Couldn't save that")
            }
        }
    }

    fun undoDislike(undo: DislikeUndo) {
        viewModelScope.launch {
            try {
                trackDisliker.undo(undo)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.tryEmit("Couldn't undo that")
            }
        }
    }

    init {
        playerRepository.selectPlayer(playerId)
        // Drop the optimistic override once the server position reaches the target.
        viewModelScope.launch {
            playerRepository.elapsedTime.collect { server ->
                val target = _seekTarget.value ?: return@collect
                if (abs(server - target) < SEEK_RECONCILE_TOLERANCE_S) _seekTarget.value = null
            }
        }
    }

    fun toggleShuffle() {
        val queue = queueState.value ?: return
        viewModelScope.launch {
            runCatching { musicRepository.shuffleQueue(queue.queueId, !queue.shuffleEnabled) }
        }
    }

    fun cycleRepeat() {
        val queue = queueState.value ?: return
        val next = when (queue.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        viewModelScope.launch { runCatching { musicRepository.repeatQueue(queue.queueId, next) } }
    }

    // runCatching for the same reason repeatQueue above uses it: these reach a
    // repository that throws when the socket is down, and viewModelScope has no
    // exception handler, so an uncaught one crashes the app. A failed transport
    // command needs no recovery here; the next server echo restores the real state.
    fun playPause() = viewModelScope.launch { runCatching { playerRepository.playPause(playerId) } }
    fun next() = viewModelScope.launch { runCatching { playerRepository.next(playerId) } }
    fun previous() = viewModelScope.launch { runCatching { playerRepository.previous(playerId) } }

    /** Seek by a relative delta (seconds), clamped to the track. */
    fun seekBy(deltaSec: Double) {
        val duration = player.value?.currentMedia?.duration ?: return
        if (duration <= 0.0) return
        // Base off the pending target (set synchronously) so rapid presses
        // accumulate; fall back to the live server position when not seeking.
        val base = _seekTarget.value ?: playerRepository.elapsedTime.value
        val target = (base + deltaSec).coerceIn(0.0, duration)
        _seekTarget.value = target
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(SEEK_DEBOUNCE_MS)
            runCatching { playerRepository.seek(playerId, target) }
            // Safety: stop overriding even if no server position update reconciles it.
            delay(SEEK_OVERRIDE_MAX_MS)
            if (_seekTarget.value == target) _seekTarget.value = null
        }
    }

    private companion object {
        const val SEEK_DEBOUNCE_MS = 350L
        const val SEEK_RECONCILE_TOLERANCE_S = 1.5
        const val SEEK_OVERRIDE_MAX_MS = 4_000L
    }
}



/** The lyrics side of the TV player. */
sealed interface TvLyrics {
    data object Loading : TvLyrics
    data class Ready(val content: LyricsProvider.LyricsContent) : TvLyrics
    data object None : TvLyrics
    data object Failed : TvLyrics
}
