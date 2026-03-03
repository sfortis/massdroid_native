package net.asksakis.massdroidv2.data.repository

import android.util.Log
import kotlinx.serialization.json.*
import net.asksakis.massdroidv2.data.websocket.*
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val json: Json
) : MusicRepository {

    override suspend fun getArtists(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean): List<Artist> {
        val result = wsClient.sendCommand("music/artists/library_items", buildJsonObject {
            search?.let { put("search", it) }
            put("limit", limit)
            put("offset", offset)
            orderBy?.let { put("order_by", it) }
            if (favoriteOnly) put("favorite", true)
        })
        return parseMediaItems(result).mapNotNull { it.toArtist() }
    }

    override suspend fun getAlbums(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean): List<Album> {
        val result = wsClient.sendCommand("music/albums/library_items", buildJsonObject {
            search?.let { put("search", it) }
            put("limit", limit)
            put("offset", offset)
            orderBy?.let { put("order_by", it) }
            if (favoriteOnly) put("favorite", true)
        })
        return parseMediaItems(result).mapNotNull { it.toAlbum() }
    }

    override suspend fun getTracks(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean): List<Track> {
        val result = wsClient.sendCommand("music/tracks/library_items", buildJsonObject {
            search?.let { put("search", it) }
            put("limit", limit)
            put("offset", offset)
            orderBy?.let { put("order_by", it) }
            if (favoriteOnly) put("favorite", true)
        })
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getPlaylists(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean): List<Playlist> {
        val result = wsClient.sendCommand("music/playlists/library_items", buildJsonObject {
            search?.let { put("search", it) }
            put("limit", limit)
            put("offset", offset)
            orderBy?.let { put("order_by", it) }
            if (favoriteOnly) put("favorite", true)
        })
        return parseMediaItems(result).mapNotNull { it.toPlaylist() }
    }

    override suspend fun getArtist(itemId: String, provider: String, lazy: Boolean): Artist? {
        val result = wsClient.sendCommand("music/artists/get", buildJsonObject {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", provider)
            put("lazy", lazy)
        })
        return result?.let {
            try { json.decodeFromJsonElement<ServerMediaItem>(it).toArtist() } catch (_: Exception) { null }
        }
    }

    override suspend fun getAlbum(itemId: String, provider: String, lazy: Boolean): Album? {
        val result = wsClient.sendCommand("music/albums/get", buildJsonObject {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", provider)
            put("lazy", lazy)
        })
        return result?.let {
            try { json.decodeFromJsonElement<ServerMediaItem>(it).toAlbum() } catch (_: Exception) { null }
        }
    }

    override suspend fun getArtistAlbums(itemId: String, provider: String): List<Album> {
        val result = wsClient.sendCommand("music/artists/artist_albums", buildJsonObject {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", provider)
        })
        return parseMediaItems(result).mapNotNull { it.toAlbum() }
    }

    override suspend fun getArtistTracks(itemId: String, provider: String): List<Track> {
        val result = wsClient.sendCommand("music/artists/artist_tracks", buildJsonObject {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", provider)
        })
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getAlbumTracks(itemId: String, provider: String): List<Track> {
        val result = wsClient.sendCommand("music/albums/album_tracks", buildJsonObject {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", provider)
        })
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getPlaylistTracks(itemId: String, provider: String): List<Track> {
        val result = wsClient.sendCommand("music/playlists/playlist_tracks", buildJsonObject {
            put("item_id", itemId)
            put("provider_instance_id_or_domain", provider)
        })
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun search(query: String, mediaTypes: List<MediaType>?, limit: Int): SearchResult {
        val result = wsClient.sendCommand("music/search", buildJsonObject {
            put("search_query", query)
            put("limit", limit)
            mediaTypes?.let {
                put("media_types", JsonArray(it.map { t -> JsonPrimitive(t.apiValue) }))
            }
        })

        val obj = result?.jsonObject ?: return SearchResult()
        return SearchResult(
            artists = obj["artists"]?.let { parseMediaItems(it) }?.mapNotNull { it.toArtist() } ?: emptyList(),
            albums = obj["albums"]?.let { parseMediaItems(it) }?.mapNotNull { it.toAlbum() } ?: emptyList(),
            tracks = obj["tracks"]?.let { parseMediaItems(it) }?.mapNotNull { it.toTrack() } ?: emptyList(),
            playlists = obj["playlists"]?.let { parseMediaItems(it) }?.mapNotNull { it.toPlaylist() } ?: emptyList()
        )
    }

    override suspend fun getQueueItems(queueId: String, limit: Int, offset: Int): List<QueueItem> {
        val result = wsClient.sendCommand("player_queues/items", buildJsonObject {
            put("queue_id", queueId)
            put("limit", limit)
            put("offset", offset)
        })
        val items = result?.let { json.decodeFromJsonElement<List<ServerQueueItem>>(it) } ?: emptyList()
        return items.map { it.toDomain() }
    }

    override suspend fun playMedia(queueId: String, uri: String, option: String?, radioMode: Boolean) {
        wsClient.sendCommand("player_queues/play_media", buildJsonObject {
            put("queue_id", queueId)
            put("media", JsonArray(listOf(JsonPrimitive(uri))))
            option?.let { put("option", it) }
            if (radioMode) put("radio_mode", true)
        })
    }

    override suspend fun playMedia(queueId: String, uris: List<String>, option: String?, radioMode: Boolean) {
        wsClient.sendCommand("player_queues/play_media", buildJsonObject {
            put("queue_id", queueId)
            put("media", JsonArray(uris.map { JsonPrimitive(it) }))
            option?.let { put("option", it) }
            if (radioMode) put("radio_mode", true)
        })
    }

    override suspend fun shuffleQueue(queueId: String, enabled: Boolean) {
        wsClient.sendCommand("player_queues/shuffle", buildJsonObject {
            put("queue_id", queueId)
            put("shuffle_enabled", enabled)
        })
    }

    override suspend fun repeatQueue(queueId: String, mode: RepeatMode) {
        wsClient.sendCommand("player_queues/repeat", buildJsonObject {
            put("queue_id", queueId)
            put("repeat_mode", mode.apiValue)
        })
    }

    override suspend fun clearQueue(queueId: String) {
        wsClient.sendCommand("player_queues/clear", buildJsonObject {
            put("queue_id", queueId)
        })
    }

    override suspend fun deleteQueueItem(queueId: String, itemIdOrIndex: String) {
        wsClient.sendCommand("player_queues/delete_item", buildJsonObject {
            put("queue_id", queueId)
            put("item_id_or_index", itemIdOrIndex)
        })
    }

    override suspend fun moveQueueItem(queueId: String, queueItemId: String, posShift: Int) {
        wsClient.sendCommand("player_queues/move_item", buildJsonObject {
            put("queue_id", queueId)
            put("queue_item_id", queueItemId)
            put("pos_shift", posShift)
        })
    }

    override suspend fun playQueueIndex(queueId: String, index: Int) {
        wsClient.sendCommand("player_queues/play_index", buildJsonObject {
            put("queue_id", queueId)
            put("index", index)
        })
    }

    override suspend fun setFavorite(uri: String, mediaType: MediaType, itemId: String, favorite: Boolean) {
        if (favorite) {
            wsClient.sendCommand("music/favorites/add_item", buildJsonObject {
                put("item", uri)
            })
        } else {
            val libraryItemId = resolveLibraryItemId(uri, itemId)
            wsClient.sendCommand("music/favorites/remove_item", buildJsonObject {
                put("media_type", mediaType.apiValue)
                put("library_item_id", libraryItemId)
            })
        }
    }

    private suspend fun resolveLibraryItemId(uri: String, itemId: String): Int {
        // If URI is library://, extract the numeric ID directly
        val libraryMatch = Regex("^library://\\w+/(\\d+)$").find(uri)
        if (libraryMatch != null) return libraryMatch.groupValues[1].toInt()
        // Resolve via server using the full provider URI
        val result = wsClient.sendCommand("music/item_by_uri", buildJsonObject {
            put("uri", uri)
        })
        return result?.jsonObject?.get("item_id")?.jsonPrimitive?.intOrNull
            ?: itemId.toInt()
    }

    override suspend fun getRecommendations(): List<RecommendationFolder> {
        val result = wsClient.sendCommand("music/recommendations", null)
        val array = result as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val itemId = obj["item_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val provider = obj["provider"]?.jsonPrimitive?.content ?: "library"
                val itemsArray = obj["items"] as? JsonArray ?: return@mapNotNull null

                val serverItems = itemsArray.mapNotNull { itemEl ->
                    try {
                        json.decodeFromJsonElement<ServerMediaItem>(itemEl)
                    } catch (_: Exception) {
                        null
                    }
                }

                val artists = serverItems.filter { it.mediaType == "artist" }.mapNotNull { it.toArtist() }
                val albums = serverItems.filter { it.mediaType == "album" }.mapNotNull { it.toAlbum() }
                val tracks = serverItems.filter { it.mediaType == "track" }.mapNotNull { it.toTrack() }
                val playlists = serverItems.filter { it.mediaType == "playlist" }.mapNotNull { it.toPlaylist() }

                RecommendationFolder(
                    itemId = itemId,
                    name = name,
                    provider = provider,
                    items = RecommendationItems(artists, albums, tracks, playlists)
                )
            } catch (e: Exception) {
                Log.e("MusicRepo", "Failed to parse recommendation folder", e)
                null
            }
        }
    }

    private fun parseMediaItems(result: JsonElement?): List<ServerMediaItem> {
        if (result == null) return emptyList()
        return when (result) {
            is JsonArray -> {
                if (result.isNotEmpty()) {
                    // Log first item for debugging
                    val firstObj = result[0].jsonObject
                    Log.d("MusicRepo", "Media item keys: ${firstObj.keys}")
                    val imageField = firstObj["image"]?.toString()?.take(200) ?: "null"
                    val metadataImages = firstObj["metadata"]?.jsonObject?.get("images")?.toString()?.take(200) ?: "null"
                    Log.d("MusicRepo", "image=$imageField metadata.images=$metadataImages")
                }
                result.mapNotNull {
                    try { json.decodeFromJsonElement<ServerMediaItem>(it) } catch (_: Exception) { null }
                }
            }
            else -> emptyList()
        }
    }

    private fun ServerMediaItem.toArtist(): Artist? {
        if (mediaType.isNotEmpty() && mediaType != "artist") return null
        return Artist(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            imageUrl = resolveImageUrl(wsClient) ?: wsClient.getImageUrl(uri),
            favorite = favorite,
            description = metadata?.description,
            genres = metadata?.genres ?: emptyList()
        )
    }

    private fun ServerMediaItem.toAlbum(): Album? {
        if (mediaType.isNotEmpty() && mediaType != "album") return null
        return Album(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            artistNames = artists?.joinToString(", ") { it.name } ?: "",
            imageUrl = resolveImageUrl(wsClient) ?: wsClient.getImageUrl(uri),
            favorite = favorite,
            version = version,
            year = year,
            description = metadata?.description,
            genres = metadata?.genres ?: emptyList(),
            label = metadata?.label,
            artists = artists?.mapNotNull { it.toArtist() } ?: emptyList(),
            albumType = albumType
        )
    }

    private fun ServerMediaItem.toTrack(): Track? {
        if (mediaType.isNotEmpty() && mediaType != "track") return null
        return Track(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            duration = duration,
            artistNames = artists?.joinToString(", ") { it.name } ?: "",
            albumName = album?.name ?: "",
            imageUrl = resolveImageUrl(wsClient)
                ?: album?.resolveImageUrl(wsClient)
                ?: wsClient.getImageUrl(uri),
            favorite = favorite,
            position = position,
            artistItemId = artists?.firstOrNull()?.itemId,
            artistProvider = artists?.firstOrNull()?.provider,
            albumItemId = album?.itemId,
            albumProvider = album?.provider,
            artistUri = artists?.firstOrNull()?.uri,
            albumUri = album?.uri,
            genres = metadata?.genres ?: emptyList(),
            year = album?.year ?: year
        )
    }

    private fun ServerMediaItem.toPlaylist(): Playlist? {
        if (mediaType.isNotEmpty() && mediaType != "playlist") return null
        return Playlist(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            imageUrl = resolveImageUrl(wsClient),
            favorite = favorite
        )
    }

    private fun ServerQueueItem.toDomain(): QueueItem = QueueItem(
        queueItemId = queueItemId,
        name = name,
        duration = duration,
        track = mediaItem?.toTrack(),
        imageUrl = mediaItem?.resolveImageUrl(wsClient)
            ?: image?.let { if (it.remotelyAccessible) it.path else wsClient.getImageUrl(it.path) }
    )
}
