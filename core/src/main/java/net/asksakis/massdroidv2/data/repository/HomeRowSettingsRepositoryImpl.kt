package net.asksakis.massdroidv2.data.repository

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.discover.HomeRowLayout
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.repository.HomeRowSettingsRepository

/**
 * Reads and writes the `discover.rows` entry of the MA user's preferences, the same entry the
 * MA frontend uses for its home rows.
 *
 * The server replaces the whole preferences object on every update, so a change sends all the
 * preferences back with only this entry changed. Inside the entry only `hidden`, `shown` and
 * `order` are written; anything the frontend adds later is kept.
 *
 * `auth/me` answers with the user as it was loaded when the connection signed in, and
 * `auth/user/update` does not refresh it (MA 2.10.9, `websocket_client.py`). Reading
 * `auth/me` before each change therefore undid the change before it: hiding one row showed
 * the row hidden a moment earlier again (2026-11-01). The preferences are read once per
 * connection and then kept from the update's own answer, and the changes run one at a time.
 */
class HomeRowSettingsRepositoryImpl(
    private val wsClient: MaWebSocketClient
) : HomeRowSettingsRepository {

    private val mutex = Mutex()
    private var known: Pair<ConnectionState.Connected, JsonObject>? = null

    override suspend fun load(): HomeRowLayout? = mutex.withLock {
        try {
            parseLayout(preferences()[ROWS_KEY])
        } catch (e: MaApiException) {
            // A refusal means a server without user preferences; a timeout or a lost
            // connection says nothing, so it is passed on rather than read as "no setting".
            if (e.mayHaveTakenEffect) throw e
            Log.d(TAG, "No home row setting on this server: ${e.message}")
            null
        }
    }

    override suspend fun setVisible(row: RecommendationFolder, visible: Boolean): HomeRowLayout =
        update { it.withVisibility(row, visible) }

    override suspend fun setOrder(rows: List<RecommendationFolder>, newSequence: List<String>): HomeRowLayout =
        update { it.withOrder(rows, newSequence) }

    /** Applies [change] to the setting and stores it, one change at a time. */
    private suspend fun update(change: (HomeRowLayout) -> HomeRowLayout): HomeRowLayout = mutex.withLock {
        val connection = wsClient.connectionState.value as? ConnectionState.Connected
        val preferences = preferences()
        val rowsEntry = preferences[ROWS_KEY] as? JsonObject ?: JsonObject(emptyMap())
        val updated = change(parseLayout(rowsEntry))
        val newEntry = JsonObject(
            rowsEntry + mapOf(
                "hidden" to updated.hidden.toJson(),
                "shown" to updated.shown.toJson(),
                "order" to updated.order.toJson()
            )
        )
        val newPreferences = JsonObject(preferences + (ROWS_KEY to newEntry))
        val answer = wsClient.sendCommand(MaCommands.Auth.USER_UPDATE, buildJsonObject { put("preferences", newPreferences) })
        val stored = (answer as? JsonObject)?.get("preferences") as? JsonObject ?: newPreferences
        if (connection != null) known = connection to stored
        parseLayout(stored[ROWS_KEY])
    }

    /** The preferences this app last knew for the current connection, read once per connection. */
    private suspend fun preferences(): JsonObject {
        val connection = wsClient.connectionState.value as? ConnectionState.Connected
        known?.let { (knownConnection, prefs) -> if (knownConnection === connection) return prefs }
        val user = wsClient.sendCommand(MaCommands.Auth.ME) as? JsonObject
        val prefs = user?.get("preferences") as? JsonObject ?: JsonObject(emptyMap())
        if (connection != null) known = connection to prefs
        return prefs
    }

    private fun parseLayout(entry: JsonElement?): HomeRowLayout {
        val obj = entry as? JsonObject ?: return HomeRowLayout()
        return HomeRowLayout(
            hidden = obj.strings("hidden").toSet(),
            shown = obj.strings("shown").toSet(),
            order = obj.strings("order")
        )
    }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun Collection<String>.toJson() = JsonArray(map { JsonPrimitive(it) })

    private companion object {
        const val TAG = "HomeRowSettings"
        const val ROWS_KEY = "discover.rows"
    }
}
