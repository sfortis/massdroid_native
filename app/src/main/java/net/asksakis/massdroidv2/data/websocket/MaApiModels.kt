package net.asksakis.massdroidv2.data.websocket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class CommandMessage(
    val command: String,
    @SerialName("message_id") val messageId: String,
    val args: JsonObject? = null
)

@Serializable
data class ServerInfo(
    @SerialName("server_id") val serverId: String,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("base_url") val baseUrl: String
)

@Serializable
data class ServerEvent(
    val event: String,
    @SerialName("object_id") val objectId: String? = null,
    val data: JsonElement? = null
)

@Serializable
data class ServerPlayer(
    @SerialName("player_id") val playerId: String,
    val provider: String = "",
    val type: String = "player",
    val available: Boolean = true,
    val enabled: Boolean = true,
    @SerialName("display_name") val displayName: String = "",
    val name: String = "",
    @SerialName("playback_state") val state: String = "idle",
    @SerialName("volume_level") val volumeLevel: Int = 0,
    @SerialName("volume_muted") val volumeMuted: Boolean = false,
    @SerialName("active_source") val activeSource: String? = null,
    @SerialName("active_group") val activeGroup: String? = null,
    @SerialName("group_childs") val groupChilds: List<String> = emptyList(),
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList(),
    @SerialName("can_group_with") val canGroupWith: List<String> = emptyList(),
    @SerialName("current_media") val currentMedia: CurrentMedia? = null,
    val icon: String? = null
)

@Serializable
data class CurrentMedia(
    @SerialName("queue_id") val queueId: String? = null,
    val uri: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val image: MediaItemImage? = null,
    val duration: Double? = null,
    @SerialName("elapsed_time") val elapsedTime: Double? = null
)

@Serializable
data class ServerQueue(
    @SerialName("queue_id") val queueId: String,
    val available: Boolean = true,
    @SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false,
    @SerialName("repeat_mode") val repeatMode: String = "off",
    @SerialName("elapsed_time") val elapsedTime: Double = 0.0,
    @SerialName("current_item") val currentItem: ServerQueueItem? = null,
    @SerialName("current_index") val currentIndex: Int = 0,
    @SerialName("dont_stop_the_music_enabled") val dontStopTheMusicEnabled: Boolean = false
)

@Serializable
data class ServerQueueItem(
    @SerialName("queue_item_id") val queueItemId: String,
    val name: String = "",
    val duration: Double = 0.0,
    val streamdetails: StreamDetails? = null,
    @SerialName("media_item") val mediaItem: ServerMediaItem? = null,
    val image: MediaItemImage? = null
)

@Serializable
data class StreamDetails(
    @SerialName("audio_format") val audioFormat: AudioFormat? = null
)

@Serializable
data class AudioFormat(
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    @SerialName("bit_depth") val bitDepth: Int? = null,
    @SerialName("bit_rate") val bitRate: Int? = null,
    val channels: Int? = null
)

@Serializable
data class ServerMediaItem(
    @SerialName("item_id") val itemId: String,
    val provider: String = "",
    val name: String = "",
    @SerialName("media_type") val mediaType: String = "",
    val uri: String = "",
    val image: MediaItemImage? = null,
    val favorite: Boolean = false,
    val duration: Double? = null,
    val artists: List<ServerMediaItem>? = null,
    val album: ServerMediaItem? = null,
    val metadata: MediaItemMetadata? = null,
    val sort_name: String? = null,
    val version: String? = null,
    val position: Int? = null,
    val year: Int? = null,
    @SerialName("is_editable") val isEditable: Boolean? = null,
    @SerialName("album_type") val albumType: String? = null,
    @SerialName("is_playable") val isPlayable: Boolean? = null,
    val path: String? = null,
    @SerialName("translation_key") val translationKey: String? = null,
    @SerialName("date_added") val dateAdded: String? = null,
    @SerialName("provider_mappings") val providerMappings: List<ProviderMapping> = emptyList()
) {
    /** Get the best image: direct image field, or first thumb from metadata.images. */
    fun resolveImageUrl(wsClient: MaWebSocketClient): String? {
        // 1. Direct image field
        image?.resolveUrl(wsClient)?.let { return it }
        // 2. From metadata.images - prefer thumb type
        val images = metadata?.images ?: return null
        val thumb = images.firstOrNull { it.type.equals("thumb", ignoreCase = true) }
            ?: images.firstOrNull()
            ?: return null
        return thumb.resolveUrl(wsClient)
    }

    /** Image with album fallback (for tracks). */
    fun resolveImageWithAlbumFallback(wsClient: MaWebSocketClient): String? =
        resolveImageUrl(wsClient) ?: album?.resolveImageUrl(wsClient)

    /** Image with album fallback, then URI-based imageproxy as last resort. */
    fun resolveImageWithUriFallback(wsClient: MaWebSocketClient): String? =
        resolveImageUrl(wsClient)
            ?: album?.resolveImageUrl(wsClient)
            ?: wsClient.getImageUrl(uri)
}

@Serializable
data class MediaItemMetadata(
    val images: List<MediaItemImage>? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val label: String? = null,
    val links: List<MediaItemLink>? = null,
    val lyrics: String? = null,
    @SerialName("lrc_lyrics") val lrcLyrics: String? = null
)

@Serializable
data class MediaItemLink(
    val type: String = "",
    val url: String = ""
)

@Serializable
data class MediaItemImage(
    val type: String = "",
    val path: String = "",
    @SerialName("provider") val imageProvider: String = "builtin",
    @SerialName("remotely_accessible") val remotelyAccessible: Boolean = false
)

fun MediaItemImage.resolveUrl(wsClient: MaWebSocketClient): String? {
    val p = path.trim()
    if (p.isEmpty()) return null
    if (p.equals("none", ignoreCase = true) || p.equals("null", ignoreCase = true)) return null
    if (remotelyAccessible) return p
    return wsClient.getImageUrl(p, provider = imageProvider) ?: p
}

@Serializable
data class ProviderMapping(
    @SerialName("provider_domain") val providerDomain: String,
    @SerialName("provider_instance") val providerInstance: String = "",
    val available: Boolean = true
)

@Serializable
data class ProviderInstance(
    @SerialName("instance_id") val instanceId: String,
    val domain: String,
    val name: String,
    val type: String = "",
    @SerialName("supported_features") val supportedFeatures: List<String> = emptyList()
)

@Serializable
data class ProviderManifest(
    val domain: String,
    val name: String,
    val icon: String? = null,
    @SerialName("icon_svg") val iconSvg: String? = null,
    @SerialName("icon_svg_dark") val iconSvgDark: String? = null
)

object EventType {
    const val PLAYER_UPDATED = "player_updated"
    const val PLAYER_ADDED = "player_added"
    const val PLAYER_REMOVED = "player_removed"
    const val QUEUE_UPDATED = "queue_updated"
    const val QUEUE_TIME_UPDATED = "queue_time_updated"
    const val QUEUE_ITEMS_UPDATED = "queue_items_updated"
    const val MEDIA_ITEM_ADDED = "media_item_added"
    const val MEDIA_ITEM_UPDATED = "media_item_updated"
    const val MEDIA_ITEM_DELETED = "media_item_deleted"
}
