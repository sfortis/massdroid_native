package net.asksakis.massdroidv2.data.websocket

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-cutting signal that the active MA account changed (sign out or OAuth
 * completion bringing in a different server/user). Subscribers wipe in-memory
 * caches and per-account UI state so nothing from the previous identity leaks
 * into the new session. Persistent on-disk Room data is intentionally left
 * alone; user history travels with the device, not the account.
 */
@Singleton
class SessionEventBus @Inject constructor() {
    private val _resets = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val resets: SharedFlow<Unit> = _resets.asSharedFlow()

    fun emitReset() {
        _resets.tryEmit(Unit)
    }
}
