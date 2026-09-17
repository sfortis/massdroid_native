package net.asksakis.massdroidv2.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.widget.NowPlayingWidgetSnapshot

/**
 * Keeps the home screen widget in step with the selected player.
 *
 * Runs for the life of the process, whichever component started it: the snapshot is
 * derived from the selected player and the connection state, written to the store
 * and pushed to every placed widget. Nothing is done while no widget is placed.
 */
@Singleton
class NowPlayingWidgetPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val store: NowPlayingWidgetStore
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(playerRepository.selectedPlayer, wsClient.connectionState) { player, state ->
                player to (state is ConnectionState.Connected)
            }
                .distinctUntilChanged()
                .collect { (player, connected) -> publish(player, connected) }
        }
    }

    /** A widget was just placed: give it the current state instead of waiting for a change. */
    suspend fun publishCurrent() {
        publish(playerRepository.selectedPlayer.value, wsClient.connectionState.value is ConnectionState.Connected)
    }

    private suspend fun publish(player: net.asksakis.massdroidv2.domain.model.Player?, connected: Boolean) {
        val snapshot = NowPlayingWidgetSnapshot.next(store.load(), player, connected) ?: return
        val placed = try {
            GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Widget manager unavailable: ${e.message}")
            false
        }
        if (!placed) return
        Log.d(TAG, "publish playing=${snapshot.isPlaying} connected=${snapshot.connected} player=${snapshot.playerName} title=${snapshot.title}")
        store.save(snapshot)
        try {
            NowPlayingWidget().updateAll(context)
        } catch (e: Exception) {
            Log.w(TAG, "Widget update failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NowPlayingWidget"
    }
}
