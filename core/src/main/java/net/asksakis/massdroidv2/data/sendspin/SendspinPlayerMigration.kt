package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.websocket.ConfigPlayerGetArgs
import net.asksakis.massdroidv2.data.websocket.ConfigPlayerSaveArgs
import net.asksakis.massdroidv2.data.websocket.ConfigPlayerValueArgs
import net.asksakis.massdroidv2.data.websocket.ConfigQueueGetArgs
import net.asksakis.massdroidv2.data.websocket.ConfigQueueSaveArgs
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.PlayerIdArgs
import net.asksakis.massdroidv2.data.websocket.sendCommand
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves the settings of the player this phone used before its Sendspin Noise identity onto the
 * new player ([migrate]), and later removes the old one ([removeOldPlayer]). It runs once, after
 * the first pairing.
 *
 * The name, every player value apart from identity and bridge fields, and the queue configuration
 * (Don't Stop the Music among it) are copied. Group memberships are not.
 *
 * The values are read one by one with `config/players/get_value` for every key the new player
 * (connected, same provider) has an entry for. That command reads storage first. For a key never
 * stored it answers the entry's default while the old player is still registered, and the
 * sentinel `default` once it is gone, because MA then builds its config without entries. Only
 * values that differ from the new player's default are saved.
 *
 * The removal is separate because MA refuses it while the old player is registered. An app update
 * ends the old connection without a goodbye, and aiosendspin keeps such a client for 180 seconds
 * in case it reconnects, so the first Noise connection always arrives inside that window.
 */
@Singleton
class SendspinPlayerMigration @Inject constructor(
    private val wsClient: MaWebSocketClient,
) {
    enum class Outcome {
        MOVED,

        /** This server never knew the old player. */
        NO_OLD_PLAYER,

        /** The new player has no config yet; try again on a later connection. */
        NEW_PLAYER_NOT_READY,
    }

    enum class RemoveOutcome {
        REMOVED,

        /** MA has no config for the old player any more. */
        GONE,

        /** MA still has the old player registered and refused. */
        STILL_REGISTERED,
    }

    /**
     * @throws MaApiException when a read or a save fails; nothing is removed then, so a later
     *   attempt starts over.
     */
    suspend fun migrate(fromId: String, toId: String): Outcome {
        val stored = wsClient.sendCommand(MaCommands.ConfigPlayers.LIST)
            ?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["player_id"]?.jsonPrimitive?.contentOrNull == fromId }
            ?: return Outcome.NO_OLD_PLAYER

        val target = wsClient.sendCommand(MaCommands.ConfigPlayers.GET, ConfigPlayerGetArgs(toId))?.jsonObject
            ?: return Outcome.NEW_PLAYER_NOT_READY
        val defaults = entryDefaults(target)
        val values = buildJsonObject {
            stored["name"]?.jsonPrimitive?.contentOrNull?.let { put("name", JsonPrimitive(it)) }
            for (key in migratableKeys(target)) {
                val value = wsClient.sendCommand(
                    MaCommands.ConfigPlayers.GET_VALUE,
                    ConfigPlayerValueArgs(fromId, key, MISSING),
                )
                if (value == null || value is JsonNull || value == JsonPrimitive(MISSING)) continue
                if (value != defaults[key]) put(key, value)
            }
        }
        if (values.isNotEmpty()) {
            wsClient.sendCommand(MaCommands.ConfigPlayers.SAVE, ConfigPlayerSaveArgs(toId, values))
        }

        val queue = wsClient.sendCommand(MaCommands.ConfigPlayerQueues.GET, ConfigQueueGetArgs(fromId))?.jsonObject
        val queueValues = queue?.let(::changedValues) ?: JsonObject(emptyMap())
        if (queueValues.isNotEmpty()) {
            wsClient.sendCommand(MaCommands.ConfigPlayerQueues.SAVE, ConfigQueueSaveArgs(toId, queueValues))
        }
        Log.i(TAG, "Moved ${values.keys} and queue ${queueValues.keys} from $fromId to $toId")
        return Outcome.MOVED
    }

    /**
     * Removes the old player's config.
     *
     * @throws MaApiException for a failure other than the two MA answers [RemoveOutcome] names.
     */
    suspend fun removeOldPlayer(playerId: String): RemoveOutcome {
        val known = wsClient.sendCommand(MaCommands.ConfigPlayers.LIST)
            ?.jsonArray.orEmpty()
            .any { it.jsonObject["player_id"]?.jsonPrimitive?.contentOrNull == playerId }
        if (!known) return RemoveOutcome.GONE
        return try {
            wsClient.sendCommand(MaCommands.ConfigPlayers.REMOVE, PlayerIdArgs(playerId))
            RemoveOutcome.REMOVED
        } catch (e: MaApiException) {
            // MA answers ActionUnavailable ("Can not remove config for an active player!").
            if (e.isTimeout) throw e
            Log.i(TAG, "Old player $playerId not removed yet: ${e.message}")
            RemoveOutcome.STILL_REGISTERED
        }
    }

    companion object {
        private const val TAG = "SendspinMigration"
        private const val MISSING = "__massdroid_missing__"

        /** Values that describe the device or its bridge, not a choice the user made. */
        private val IDENTITY_KEYS = setOf(
            "protocol_parent_id",
            "underlying_player_id",
            "cached_arp_mac",
            "reported_mac",
            "protocol_experimental_note",
        )

        /** Entry types that are only shown, never stored. */
        private val NON_VALUE_TYPES = setOf("label", "divider", "action", "alert", "url", "pairing_code", "image")

        /** The default of each of the target's config entries. */
        internal fun entryDefaults(config: JsonObject): Map<String, JsonElement> =
            config["values"]?.jsonObject.orEmpty().mapNotNull { (key, entry) ->
                (entry as? JsonObject)?.get("default_value")?.let { key to it }
            }.toMap()

        /** Keys of the target's config entries that can hold a user's value. */
        internal fun migratableKeys(config: JsonObject): List<String> =
            config["values"]?.jsonObject.orEmpty().mapNotNull { (key, entry) ->
                val type = (entry as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
                key.takeIf { isUserValue(it) && type !in NON_VALUE_TYPES }
            }

        /** The values of a parsed config that differ from their defaults. */
        internal fun changedValues(config: JsonObject): JsonObject = buildJsonObject {
            config["values"]?.jsonObject.orEmpty().forEach { (key, entry) ->
                val obj = entry as? JsonObject ?: return@forEach
                val value = obj["value"] ?: return@forEach
                if (!isUserValue(key) || value is JsonNull || value == obj["default_value"]) return@forEach
                put(key, value)
            }
        }

        // Protocol- and plugin-prefixed keys mirror another config; MA refuses to store them here.
        private fun isUserValue(key: String) = key !in IDENTITY_KEYS && "||" !in key
    }
}
