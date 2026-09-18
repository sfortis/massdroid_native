package net.asksakis.massdroidv2.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun nowPlayingWidgetPublisher(): NowPlayingWidgetPublisher
    }

    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()

    /**
     * The first draw after placing the widget happens before the publisher has seen a
     * change, so the store would still be empty and the widget would say "Nothing
     * playing" next to a playing speaker until the next event. Publishing the current
     * state writes the store and redraws every placed widget itself.
     *
     * The publish deliberately does not hold the broadcast open with goAsync. A
     * broadcast hands out exactly one PendingResult, and the parent's onUpdate claims
     * it for its own work; taking it here left the parent finishing a null result,
     * which killed the process on every widget update.
     */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val publisher = EntryPointAccessors.fromApplication(context, Dependencies::class.java).nowPlayingWidgetPublisher()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { publisher.publishCurrent() }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
