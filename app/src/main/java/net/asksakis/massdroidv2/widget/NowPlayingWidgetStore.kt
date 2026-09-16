package net.asksakis.massdroidv2.widget

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.domain.widget.NowPlayingWidgetSnapshot

/**
 * The last snapshot the widget was given, kept in a file so the widget can be drawn
 * by a process that has just been started for that purpose and has no connection yet.
 */
@Singleton
class NowPlayingWidgetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)

    // Null until the file has been read once; the widget composition observes this, so a
    // save re-draws every placed widget without a new render session being needed.
    private val state = MutableStateFlow<NowPlayingWidgetSnapshot?>(null)

    /** Every snapshot from now on, starting with the current one. */
    val snapshots: Flow<NowPlayingWidgetSnapshot> = state.filterNotNull()

    suspend fun load(): NowPlayingWidgetSnapshot {
        state.value?.let { return it }
        val fromFile = withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) NowPlayingWidgetSnapshot.Empty
                else json.decodeFromString(NowPlayingWidgetSnapshot.serializer(), file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot unreadable, starting empty: ${e.message}")
                NowPlayingWidgetSnapshot.Empty
            }
        }
        state.compareAndSet(null, fromFile)
        return state.value ?: fromFile
    }

    suspend fun save(snapshot: NowPlayingWidgetSnapshot) {
        state.value = snapshot
        withContext(Dispatchers.IO) {
            try {
                file.writeText(json.encodeToString(NowPlayingWidgetSnapshot.serializer(), snapshot))
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot not saved: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "NowPlayingWidget"
        private const val FILE_NAME = "now_playing_widget.json"
    }
}
