package net.asksakis.massdroidv2.data.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Centralized MA command names + typed argument payloads used by the app. */
object MaCommands {
    object Auth {
        const val LOGIN = "auth/login"
        const val AUTH = "auth"
    }

    object Music {
        const val ARTISTS_LIBRARY_ITEMS = "music/artists/library_items"
        const val ALBUMS_LIBRARY_ITEMS = "music/albums/library_items"
        const val TRACKS_LIBRARY_ITEMS = "music/tracks/library_items"
        const val TRACKS_GET = "music/tracks/get"
        const val PLAYLISTS_LIBRARY_ITEMS = "music/playlists/library_items"
        const val PLAYLISTS_ADD_TRACKS = "music/playlists/add_playlist_tracks"
        const val PLAYLISTS_REMOVE_TRACKS = "music/playlists/remove_playlist_tracks"
        const val ARTISTS_GET = "music/artists/get"
        const val ALBUMS_GET = "music/albums/get"
        const val ARTIST_ALBUMS = "music/artists/artist_albums"
        const val ARTIST_TRACKS = "music/artists/artist_tracks"
        const val ALBUM_TRACKS = "music/albums/album_tracks"
        const val PLAYLIST_TRACKS = "music/playlists/playlist_tracks"
        const val SEARCH = "music/search"
        const val ITEM_BY_URI = "music/item_by_uri"
        const val REFRESH_ITEM = "music/refresh_item"
        const val RECOMMENDATIONS = "music/recommendations"
        const val FAVORITES_ADD = "music/favorites/add_item"
        const val FAVORITES_REMOVE = "music/favorites/remove_item"
        const val SYNC = "music/sync"
    }

    object PlayerQueues {
        const val ITEMS = "player_queues/items"
        const val PLAY_MEDIA = "player_queues/play_media"
        const val SHUFFLE = "player_queues/shuffle"
        const val REPEAT = "player_queues/repeat"
        const val CLEAR = "player_queues/clear"
        const val TRANSFER = "player_queues/transfer"
        const val DELETE_ITEM = "player_queues/delete_item"
        const val MOVE_ITEM = "player_queues/move_item"
        const val PLAY_INDEX = "player_queues/play_index"
        const val GET_ACTIVE_QUEUE = "player_queues/get_active_queue"
    }

    object Players {
        const val ALL = "players/all"
        private const val CMD_PREFIX = "players/cmd"
        const val CMD_PLAY = "$CMD_PREFIX/play"
        const val CMD_PAUSE = "$CMD_PREFIX/pause"
        const val CMD_PLAY_PAUSE = "$CMD_PREFIX/play_pause"
        const val CMD_NEXT = "$CMD_PREFIX/next"
        const val CMD_PREVIOUS = "$CMD_PREFIX/previous"
        const val CMD_SEEK = "$CMD_PREFIX/seek"
        const val CMD_VOLUME_SET = "players/cmd/volume_set"
        const val CMD_VOLUME_MUTE = "players/cmd/volume_mute"

        fun cmd(command: String): String = "$CMD_PREFIX/$command"
    }

    object ConfigPlayers {
        const val GET = "config/players/get"
        const val SAVE = "config/players/save"
    }
}

interface MaCommandArgs {
    fun toJson(): JsonObject
}

data class LibraryItemsArgs(
    val search: String? = null,
    val limit: Int,
    val offset: Int,
    val orderBy: String? = null,
    val favoriteOnly: Boolean = false
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        search?.let { put("search", it) }
        put("limit", limit)
        put("offset", offset)
        orderBy?.let { put("order_by", it) }
        if (favoriteOnly) put("favorite", true)
    }
}

data class ItemRefArgs(
    val itemId: String,
    val provider: String
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("item_id", itemId)
        put("provider_instance_id_or_domain", provider)
    }
}

data class ItemRefLazyArgs(
    val itemId: String,
    val provider: String,
    val lazy: Boolean
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("item_id", itemId)
        put("provider_instance_id_or_domain", provider)
        put("lazy", lazy)
    }
}

data class SearchArgs(
    val query: String,
    val limit: Int,
    val mediaTypes: List<String>? = null
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("search_query", query)
        put("limit", limit)
        mediaTypes?.let { types ->
            put("media_types", JsonArray(types.map { JsonPrimitive(it) }))
        }
    }
}

data class QueueItemsArgs(
    val queueId: String,
    val limit: Int,
    val offset: Int
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("queue_id", queueId)
        put("limit", limit)
        put("offset", offset)
    }
}

data class PlayMediaArgs(
    val queueId: String,
    val mediaUris: List<String>,
    val option: String? = null,
    val radioMode: Boolean = false
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("queue_id", queueId)
        put("media", JsonArray(mediaUris.map { JsonPrimitive(it) }))
        option?.let { put("option", it) }
        if (radioMode) put("radio_mode", true)
    }
}

data class ShuffleArgs(
    val queueId: String,
    val enabled: Boolean
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("queue_id", queueId)
        put("shuffle_enabled", enabled)
    }
}

data class RepeatArgs(
    val queueId: String,
    val repeatMode: String
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("queue_id", queueId)
        put("repeat_mode", repeatMode)
    }
}

data class QueueIdArgs(val queueId: String) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("queue_id", queueId)
    }
}

data class TransferQueueArgs(
    val sourceQueueId: String,
    val targetQueueId: String,
    val autoPlay: Boolean = true
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("source_queue_id", sourceQueueId)
        put("target_queue_id", targetQueueId)
        put("auto_play", autoPlay)
    }
}

data class DeleteQueueItemArgs(
    val queueId: String,
    val itemIdOrIndex: String
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("queue_id", queueId)
        put("item_id_or_index", itemIdOrIndex)
    }
}

data class MoveQueueItemArgs(
    val queueId: String,
    val queueItemId: String,
    val posShift: Int
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("queue_id", queueId)
        put("queue_item_id", queueItemId)
        put("pos_shift", posShift)
    }
}

data class PlayIndexArgs(
    val queueId: String,
    val index: Int
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("queue_id", queueId)
        put("index", index)
    }
}

data class ItemByUriArgs(val uri: String) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("uri", uri)
    }
}

data class RefreshItemArgs(
    val mediaItem: JsonElement
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("media_item", mediaItem)
    }
}

data class FavoriteAddArgs(val item: String) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("item", item)
    }
}

data class FavoriteRemoveArgs(
    val mediaType: String,
    val libraryItemId: String
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("media_type", mediaType)
        put("library_item_id", libraryItemId)
    }
}

data class AddPlaylistTracksArgs(
    val dbPlaylistId: String,
    val uris: List<String>
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("db_playlist_id", dbPlaylistId)
        put("uris", JsonArray(uris.map(::JsonPrimitive)))
    }
}

data class RemovePlaylistTracksArgs(
    val dbPlaylistId: String,
    val positionsToRemove: List<Int>
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("db_playlist_id", dbPlaylistId)
        put("positions_to_remove", JsonArray(positionsToRemove.map(::JsonPrimitive)))
    }
}

data class PlayersAllArgs(
    val returnUnavailable: Boolean? = null,
    val returnDisabled: Boolean? = null
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        returnUnavailable?.let { put("return_unavailable", it) }
        returnDisabled?.let { put("return_disabled", it) }
    }
}

data class ActiveQueueArgs(val playerId: String) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("player_id", playerId)
    }
}

data class PlayerIdArgs(val playerId: String) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("player_id", playerId)
    }
}

data class SeekArgs(
    val playerId: String,
    val position: Double
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("player_id", playerId)
        put("position", position)
    }
}

data class VolumeSetArgs(
    val playerId: String,
    val volumeLevel: Int
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("player_id", playerId)
        put("volume_level", volumeLevel)
    }
}

data class VolumeMuteArgs(
    val playerId: String,
    val muted: Boolean
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("player_id", playerId)
        put("muted", muted)
    }
}

data class ConfigPlayerGetArgs(
    val playerId: String
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("player_id", playerId)
    }
}

data class ConfigPlayerSaveArgs(
    val playerId: String,
    val values: JsonObject
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        put("player_id", playerId)
        put("values", values)
    }
}

data class SyncArgs(
    val mediaTypes: List<String>? = null,
    val providers: List<String>? = null
) : MaCommandArgs {
    override fun toJson(): JsonObject = buildJsonObject {
        mediaTypes?.let { put("media_types", JsonArray(it.map(::JsonPrimitive))) }
        providers?.let { put("providers", JsonArray(it.map(::JsonPrimitive))) }
    }
}

suspend fun MaWebSocketClient.sendCommand(
    command: String,
    args: MaCommandArgs,
    awaitResponse: Boolean = true,
    timeoutMs: Long = 30_000
): JsonElement? = sendCommand(
    command = command,
    args = args.toJson(),
    awaitResponse = awaitResponse,
    timeoutMs = timeoutMs
)
