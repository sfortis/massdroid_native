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
 * Tracks the DSTM (Don't Stop The Music) flag per queue across all players, not just the
 * currently selected one. PlayerRepository only owns the selected player's full QueueState,
 * so settings dialogs for other players would read a stale flag without this cache.
 */
@Singleton
class QueueDstmCache @Inject constructor(
    wsClient: MaWebSocketClient,
    private val json: Json
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val states: StateFlow<Map<String, Boolean>> = _states.asStateFlow()

    init {
        scope.launch {
            wsClient.events.collect { event ->
                if (event.event != EventType.QUEUE_UPDATED) return@collect
                val queue = event.data?.let {
                    runCatching { json.decodeFromJsonElement<ServerQueue>(it) }.getOrNull()
                } ?: return@collect
                _states.update { it + (queue.queueId to queue.dontStopTheMusicEnabled) }
            }
        }
        scope.launch {
            wsClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> refreshAll(wsClient)
                    is ConnectionState.Disconnected -> _states.value = emptyMap()
                    else -> {}
                }
            }
        }
    }

    fun dstmFor(queueId: String): Boolean = _states.value[queueId] ?: false

    /** Optimistic update so the UI reflects a toggle immediately, before the server echo. */
    fun setOptimistic(queueId: String, enabled: Boolean) {
        _states.update { it + (queueId to enabled) }
    }

    private suspend fun refreshAll(wsClient: MaWebSocketClient) {
        try {
            val result: JsonElement = wsClient.sendCommand("player_queues/all") ?: return
            val queues = runCatching {
                json.decodeFromJsonElement<List<ServerQueue>>(result)
            }.getOrNull() ?: return
            _states.value = queues.associate { it.queueId to it.dontStopTheMusicEnabled }
            Log.d(TAG, "Seeded DSTM cache with ${queues.size} queues")
        } catch (e: Exception) {
            Log.w(TAG, "refreshAll failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "QueueDstmCache"
    }
}
