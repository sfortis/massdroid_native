package net.asksakis.massdroidv2.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ShortcutAction {
    data object SmartMix : ShortcutAction
    data object PlayNow : ShortcutAction
}

@Singleton
class ShortcutActionDispatcher @Inject constructor() {
    private val _pendingAction = MutableStateFlow<ShortcutAction?>(null)
    val pendingAction: StateFlow<ShortcutAction?> = _pendingAction.asStateFlow()

    fun dispatch(action: ShortcutAction) {
        _pendingAction.value = action
    }

    fun consume() {
        _pendingAction.value = null
    }
}
