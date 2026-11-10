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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.widget.NowPlayingWidgetSnapshot
import net.asksakis.massdroidv2.domain.widget.settledWidgetChanges

/**
 * Keeps the home screen widget in step with the selected player.
 *
 * Runs for the life of the process, whichever component started it: the snapshot is
 * derived from the selected player and the connection state, written to the store
 * and pushed to every placed widget. Nothing is done while no widget is placed.
 *
 * The phone's own player, whether its stream is still playing, and a pause of it MA
 * has not confirmed are read as well, so play/pause follows the phone's output through a
 * dropped connection.
 */
@Singleton
class NowPlayingWidgetPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository,
    private val wsClient: MaWebSocketClient,
    private val sendspinManager: SendspinManager,
    private val settingsRepository: SettingsRepository,
    private val store: NowPlayingWidgetStore
) {
    private data class Inputs(
        val player: net.asksakis.massdroidv2.domain.model.Player?,
        val connected: Boolean,
        val localPlayerId: String?,
        val localPausePending: Boolean,
        val localStreamActive: Boolean,
    ) {
        /** What the widget would show for these inputs; equal values draw the same card. */
        fun shown(): NowPlayingWidgetSnapshot? = NowPlayingWidgetSnapshot.next(
            NowPlayingWidgetSnapshot.Empty, player, connected, localPlayerId, localPausePending, localStreamActive,
        )
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                playerRepository.selectedPlayer,
                wsClient.connectionState,
                settingsRepository.sendspinClientId,
                sendspinManager.localPausePending,
                sendspinManager.streamActive,
            ) { player, state, localPlayerId, localPausePending, localStreamActive ->
                Inputs(player, state is ConnectionState.Connected, localPlayerId, localPausePending, localStreamActive)
            }
                .settledWidgetChanges(shown = Inputs::shown)
                .collect { publish(it) }
        }
    }

    /** A widget was just placed: give it the current state instead of waiting for a change. */
    suspend fun publishCurrent() {
        publish(
            Inputs(
                player = playerRepository.selectedPlayer.value,
                connected = wsClient.connectionState.value is ConnectionState.Connected,
                localPlayerId = settingsRepository.sendspinClientId.first(),
                localPausePending = sendspinManager.localPausePending.value,
                localStreamActive = sendspinManager.streamActive.value,
            ),
            force = true
        )
    }

    /**
     * The selected player changes far more often than anything the widget shows (elapsed
     * time, volume, every server update), so a snapshot equal to the stored one is not
     * published: each update opens a Glance session through WorkManager, and in one day
     * 761 of 1108 updates redrew exactly the same card. [force] is for a newly placed
     * widget, which has to be drawn whatever the store holds.
     */
    private suspend fun publish(inputs: Inputs, force: Boolean = false) {
        val previous = store.load()
        val snapshot = NowPlayingWidgetSnapshot.next(
            previous,
            inputs.player,
            inputs.connected,
            inputs.localPlayerId,
            inputs.localPausePending,
            inputs.localStreamActive,
        ) ?: return
        if (!force && snapshot == previous) return
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
