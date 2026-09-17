package net.asksakis.massdroidv2.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import net.asksakis.massdroidv2.domain.player.TransportCommand
import net.asksakis.massdroidv2.service.PlaybackService

/**
 * A transport button on the widget. The command is handed to the playback service,
 * which connects if it has to and routes it the same way as an external controller,
 * so the widget can never play on a player the car or the phone would not have chosen.
 */
class WidgetTransportAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val name = parameters[COMMAND] ?: return
        val command = TransportCommand.entries.firstOrNull { it.name == name } ?: return
        val intent = Intent(context, PlaybackService::class.java)
            .setAction(ACTION_TRANSPORT)
            .putExtra(EXTRA_COMMAND, command.name)
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not reach the playback service: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NowPlayingWidget"
        val COMMAND = ActionParameters.Key<String>("command")
        const val ACTION_TRANSPORT = "net.asksakis.massdroidv2.widget.TRANSPORT"
        const val EXTRA_COMMAND = "command"

        /** The command carried by a service intent, or null when the intent is not ours. */
        fun commandFrom(intent: Intent?): TransportCommand? {
            if (intent?.action != ACTION_TRANSPORT) return null
            val name = intent.getStringExtra(EXTRA_COMMAND) ?: return null
            return TransportCommand.entries.firstOrNull { it.name == name }
        }
    }
}
