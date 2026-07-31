package net.asksakis.massdroidv2.data.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What was lost when Room could not migrate the database and rebuilt it empty.
 *
 * [fromVersion] is read from the file BEFORE Room opens it, because by the time
 * the destructive-migration callback runs the old version is already gone.
 * Zero means the version could not be read at all.
 */
data class DatabaseResetInfo(
    val fromVersion: Int,
    val toVersion: Int,
    val appVersion: String
)

/**
 * Reports that the listening history was wiped by a failed migration.
 *
 * `fallbackToDestructiveMigration()` keeps the app usable when a migration is
 * missing or wrong, but on its own it does so SILENTLY: months of play history,
 * the artist scores built from it and the blocked-artist list all disappear and
 * the user's only clue is that recommendations suddenly know nothing about them.
 * That is the worst of both worlds, since the person who could report the bug
 * never learns there was one.
 *
 * So the fallback stays (an unusable app helps nobody) and this makes it loud:
 * the app shows the version it failed to migrate from, which is the single piece
 * of information needed to write the missing migration.
 */
@Singleton
class DatabaseResetReporter @Inject constructor() {

    private val _reset = MutableStateFlow<DatabaseResetInfo?>(null)

    /** Non-null exactly once per process, after a destructive rebuild. */
    val reset: StateFlow<DatabaseResetInfo?> = _reset.asStateFlow()

    fun report(info: DatabaseResetInfo) {
        Log.e(
            TAG,
            "Database was REBUILT EMPTY: could not migrate v${info.fromVersion} -> " +
                "v${info.toVersion} on app ${info.appVersion}. All play history is gone."
        )
        _reset.value = info
    }

    /** Called once the user has acknowledged the message. */
    fun acknowledge() {
        _reset.value = null
    }

    private companion object {
        const val TAG = "DatabaseReset"
    }
}
