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
     * playing" next to a playing speaker until the next event. Publish the current state
     * first; the parent then redraws from the store.
     */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val publisher = EntryPointAccessors.fromApplication(context, Dependencies::class.java).nowPlayingWidgetPublisher()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                publisher.publishCurrent()
            } finally {
                pending.finish()
            }
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
