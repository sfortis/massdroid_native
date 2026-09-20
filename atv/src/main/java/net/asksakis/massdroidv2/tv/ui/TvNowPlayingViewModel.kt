package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueState
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * Controls a single MA player from the TV: transport + volume, targeting the
 * player id passed via navigation. Selecting it also makes it the app's current
 * player so library plays from the home land here.
 */
@HiltViewModel
class TvNowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playerId: String = checkNotNull(savedStateHandle["playerId"])

    val player: StateFlow<Player?> = playerRepository.players
        .map { list -> list.firstOrNull { it.playerId == playerId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    fun volumeUp() = changeVolume(VOLUME_STEP)
    fun volumeDown() = changeVolume(-VOLUME_STEP)

    private fun changeVolume(delta: Int) {
        val current = player.value?.volumeLevel ?: return
        viewModelScope.launch {
            runCatching { playerRepository.setVolume(playerId, (current + delta).coerceIn(0, 100)) }
        }
    }

    private companion object {
        const val VOLUME_STEP = 5
        const val SEEK_DEBOUNCE_MS = 350L
        const val SEEK_RECONCILE_TOLERANCE_S = 1.5
        const val SEEK_OVERRIDE_MAX_MS = 4_000L
    }
}
