package net.asksakis.massdroidv2.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.ServerQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the on/off toggles of every queue, not just the currently selected player's.
 * PlayerRepository only owns the selected player's full QueueState, so settings dialogs
 * for other players would read a stale flag without this cache.
 *
 * Autoplay and crossfade are both queue properties rather than configuration, both arrive
 * on the same QUEUE_UPDATED event, and both are seeded from the same `player_queues/all`,
 * so one cache serves them instead of two doing the same work.
 */
@Singleton
class QueueTogglesCache @Inject constructor(
    wsClient: MaWebSocketClient,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _autoplay = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val autoplayStates: StateFlow<Map<String, Boolean>> = _autoplay.asStateFlow()

    /**
     * Whether crossfade is on, per queue. Empty on a server before MA 2.10, which does not
     * send the field, and the caller then shows its older player-config control instead.
     */
    private val _crossfade = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val crossfadeStates: StateFlow<Map<String, Boolean>> = _crossfade.asStateFlow()

    init {
        scope.launch {
            wsClient.events.collect { event ->
                if (event.event != EventType.QUEUE_UPDATED) return@collect
                val queue = event.data?.let {
                    runCatching { json.decodeFromJsonElement<ServerQueue>(it) }.getOrNull()
                } ?: return@collect
                _autoplay.update { it + (queue.queueId to queue.autoplayEnabled) }
                _crossfade.update { it + (queue.queueId to queue.crossfadeEnabled) }
            }
        }
        scope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> refreshAll(wsClient)
                    is ConnectionState.Disconnected -> {
                        _autoplay.value = emptyMap()
                        _crossfade.value = emptyMap()
                    }
                    else -> {}
                }
            }
        }
    }

    fun dstmFor(queueId: String): Boolean = _autoplay.value[queueId] ?: false

    /** Optimistic update so the UI reflects a toggle immediately, before the server echo. */
    fun setOptimistic(queueId: String, enabled: Boolean) {
        _autoplay.update { it + (queueId to enabled) }
    }

    /** Optimistic update of the crossfade toggle, for the same reason. */
    fun setCrossfadeOptimistic(queueId: String, enabled: Boolean) {
        _crossfade.update { it + (queueId to enabled) }
    }

    private suspend fun refreshAll(wsClient: MaWebSocketClient) {
        try {
            val result: JsonElement = wsClient.sendCommand("player_queues/all") ?: return
            val queues = runCatching {
                json.decodeFromJsonElement<List<ServerQueue>>(result)
            }.getOrNull() ?: return
            _autoplay.value = queues.associate { it.queueId to it.autoplayEnabled }
            _crossfade.value = queues.associate { it.queueId to it.crossfadeEnabled }
            Log.d(TAG, "Seeded queue toggles from ${queues.size} queues")
        } catch (e: Exception) {
            Log.w(TAG, "refreshAll failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "QueueTogglesCache"
    }
}
