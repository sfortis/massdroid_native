package net.asksakis.massdroidv2.data.repository

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

/**
 * Clears the cached MA answers (similar artists, similar tracks, top tracks and the mix engine's
 * artist tracks) when the app connects to another MA server than the one they came from. They are
 * keyed by that server's library ids, which name other artists on another server, so a mix would
 * expand the wrong seeds. They hold no user data.
 *
 * It reads the server id of every connection, not only a switch made in the sign-in, so a saved
 * address that now answers as another server clears them too. The first connection after the
 * update, with no server recorded yet, clears them once.
 */
@Singleton
class MaAnswerCacheGuard internal constructor(
    private val wsClient: MaWebSocketClient,
    private val settings: SettingsRepository,
    private val dao: PlayHistoryDao,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        wsClient: MaWebSocketClient,
        settings: SettingsRepository,
        dao: PlayHistoryDao
    ) : this(wsClient, settings, dao, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val started = AtomicBoolean(false)

    /** Starts watching the connections. Called once by the application. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            wsClient.connectionState.collect { state ->
                if (state is ConnectionState.Connected) onConnected(state.serverInfo.serverId)
            }
        }
    }

    // One collector calls this, so reading, clearing and then recording cannot interleave. The
    // server is recorded only after the clear, so a clear that fails is tried again on the next
    // connection instead of being forgotten.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun onConnected(serverId: String) {
        try {
            val previous = settings.maCacheServer.first()
            if (previous == serverId) return
            // Nothing recorded yet, on the first start after the update: the caches may hold
            // the answers of servers used before, so they are cleared once and refill.
            dao.clearMaAnswerCaches()
            Log.d(TAG, "Cached MA answers cleared: they belonged to another server or to none recorded")
            settings.setMaCacheServer(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept running: one failed read or delete must not stop the guard for the process.
            Log.w(TAG, "Cached MA answers not checked: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "MaAnswerCache"
    }
}
