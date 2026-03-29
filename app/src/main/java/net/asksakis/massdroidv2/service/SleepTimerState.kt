package net.asksakis.massdroidv2.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared sleep timer state holder. The actual timer runs in PlaybackService,
 * but ViewModels observe state and trigger start/cancel through this bridge.
 */
@Singleton
class SleepTimerBridge @Inject constructor() {

    sealed interface State {
        data object Idle : State
        data class Running(val endTimeMs: Long) : State
        data class FadingOut(val endTimeMs: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Commands from UI to Service
    private val _startCommand = MutableStateFlow<Int?>(null)
    val startCommand: StateFlow<Int?> = _startCommand.asStateFlow()

    private val _cancelCommand = MutableStateFlow(0L)
    val cancelCommand: StateFlow<Long> = _cancelCommand.asStateFlow()

    fun requestStart(minutes: Int) {
        _startCommand.value = minutes
    }

    fun requestCancel() {
        _cancelCommand.value = System.currentTimeMillis()
    }

    fun consumeStartCommand() {
        _startCommand.value = null
    }

    fun updateState(state: State) {
        _state.value = state
    }

    fun remainingMs(): Long {
        return when (val s = _state.value) {
            is State.Running -> (s.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0)
            is State.FadingOut -> (s.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0)
            State.Idle -> 0
        }
    }
}
