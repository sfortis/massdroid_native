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
    val powered: Boolean = true,
    @SerialName("volume_level") val volumeLevel: Int = 0,
    @SerialName("group_volume") val groupVolume: Int? = null,
    @SerialName("volume_muted") val volumeMuted: Boolean = false,
    @SerialName("active_source") val activeSource: String? = null,
    @SerialName("active_group") val activeGroup: String? = null,
    @SerialName("synced_to") val syncedTo: String? = null,
    @SerialName("group_childs") val groupChilds: List<String> = emptyList(),
    @SerialName("static_group_members") val staticGroupMembers: List<String> = emptyList(),
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
    // Server-side UTC timestamp (seconds since epoch, fractional) of the
    // moment the `elapsed_time` field above was captured. This is what the
    // official MA frontend reads when interpolating positions on screen, so
    // skipping it leaves us computing `now - System.currentTimeMillis()`
    // (which is always ~0) and effectively pretending every elapsed reading
    // is "right now" — that's how stale events look like spikes in our UI.
    @SerialName("elapsed_time_last_updated") val elapsedTimeLastUpdated: Double? = null,
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
    val authors: List<String>? = null,
    val narrators: List<String>? = null,
    @SerialName("fully_played") val fullyPlayed: Boolean? = null,
    @SerialName("resume_position_ms") val resumePositionMs: Long? = null,
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
)

@Serializable
data class MediaItemMetadata(
    val images: List<MediaItemImage>? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val label: String? = null,
    val links: List<MediaItemLink>? = null,
    val lyrics: String? = null,
    @SerialName("lrc_lyrics") val lrcLyrics: String? = null,
    val chapters: List<ServerChapter>? = null
)

@Serializable
data class ServerChapter(
    val position: Int = 0,
    val name: String = "",
    val start: Double = 0.0,
    val end: Double? = null
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
    @SerialName("remotely_accessible") val remotelyAccessible: Boolean = false,
    // MA 2.9+ (API schema 31, server PR #3960): an opaque server-side imageproxy id, populated
    // only for non-publicly-accessible images. The canonical, SSRF-safe way to fetch the art.
    @SerialName("proxy_id") val proxyId: String? = null
)

// Image URL resolution lives in ImageUrlResolver (data/image). The MediaItemImage / ServerMediaItem
// models stay pure data; callers resolve through the injected resolver.

@Serializable
data class ProviderMapping(
    @SerialName("provider_domain") val providerDomain: String,
    @SerialName("provider_instance") val providerInstance: String = "",
    // The artist's id WITHIN this provider (e.g. the Deezer artist id), needed to query that
    // provider's discography. A library artist's own item_id is the library id, not the
    // provider's, so artist_albums must be asked of the mapping's item_id + provider.
    @SerialName("item_id") val itemId: String = "",
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
