package net.asksakis.massdroidv2.data.repository

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.asksakis.massdroidv2.data.websocket.*
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val json: Json,
    private val playerRepository: dagger.Lazy<PlayerRepository>
) : MusicRepository {
    companion object {
        private const val TAG = "MusicRepo"
        private const val FAVORITE_ACK_TIMEOUT_MS = 1_000L
        private const val FAVORITE_MAX_ATTEMPTS = 3
        private const val FAVORITE_RETRY_DELAY_MS = 180L
        private const val LIBRARY_SYNC_COOLDOWN_MS = 45_000L
        private const val LIBRARY_SYNC_TIMEOUT_MS = 1_500L
    }
    private val librarySyncMutex = Mutex()
    private var lastLibrarySyncAtMs = 0L

    override suspend fun getArtists(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean, providerFilter: List<String>?): List<Artist> {
        val result = wsClient.sendCommand(
            MaCommands.Music.ARTISTS_LIBRARY_ITEMS,
            LibraryItemsArgs(search, limit, offset, orderBy, favoriteOnly, providerFilter)
        )
        return parseMediaItems(result).mapNotNull { it.toArtist() }
    }

    override suspend fun getAlbums(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean, providerFilter: List<String>?): List<Album> {
        val result = wsClient.sendCommand(
            MaCommands.Music.ALBUMS_LIBRARY_ITEMS,
            LibraryItemsArgs(search, limit, offset, orderBy, favoriteOnly, providerFilter)
        )
        return parseMediaItems(result).mapNotNull { it.toAlbum() }
    }

    override suspend fun getTracks(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean, providerFilter: List<String>?): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.TRACKS_LIBRARY_ITEMS,
            LibraryItemsArgs(search, limit, offset, orderBy, favoriteOnly, providerFilter)
        )
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getPlaylists(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean, providerFilter: List<String>?): List<Playlist> {
        val result = wsClient.sendCommand(
            MaCommands.Music.PLAYLISTS_LIBRARY_ITEMS,
            LibraryItemsArgs(search, limit, offset, orderBy, favoriteOnly, providerFilter)
        )
        return parseMediaItems(result).mapNotNull { it.toPlaylist() }
    }

    override suspend fun getRadios(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean, providerFilter: List<String>?): List<Radio> {
        val result = wsClient.sendCommand(
            MaCommands.Music.RADIOS_LIBRARY_ITEMS,
            LibraryItemsArgs(search, limit, offset, orderBy, favoriteOnly, providerFilter)
        )
        return parseMediaItems(result).mapNotNull { it.toRadio() }
    }

    override suspend fun getArtist(itemId: String, provider: String, lazy: Boolean): Artist? {
        val result = wsClient.sendCommand(
            MaCommands.Music.ARTISTS_GET,
            ItemRefLazyArgs(itemId = itemId, provider = provider, lazy = lazy)
        )
        return result?.let {
            try { json.decodeFromJsonElement<ServerMediaItem>(it).toArtist() } catch (_: Exception) { null }
        }
    }

    override suspend fun getAlbum(itemId: String, provider: String, lazy: Boolean): Album? {
        val result = wsClient.sendCommand(
            MaCommands.Music.ALBUMS_GET,
            ItemRefLazyArgs(itemId = itemId, provider = provider, lazy = lazy)
        )
        return result?.let {
            try { json.decodeFromJsonElement<ServerMediaItem>(it).toAlbum() } catch (_: Exception) { null }
        }
    }

    override suspend fun getArtistAlbums(itemId: String, provider: String): List<Album> {
        val result = wsClient.sendCommand(
            MaCommands.Music.ARTIST_ALBUMS,
            ItemRefArgs(itemId = itemId, provider = provider)
        )
        return parseMediaItems(result).mapNotNull { it.toAlbum() }
    }

    override suspend fun getArtistTracks(itemId: String, provider: String): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.ARTIST_TRACKS,
            ItemRefArgs(itemId = itemId, provider = provider)
        )
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getAlbumTracks(itemId: String, provider: String): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.ALBUM_TRACKS,
            ItemRefArgs(itemId = itemId, provider = provider)
        )
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getPlaylistTracks(itemId: String, provider: String): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.PLAYLIST_TRACKS,
            ItemRefArgs(itemId = itemId, provider = provider)
        )
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun search(query: String, mediaTypes: List<MediaType>?, limit: Int): SearchResult {
        val result = wsClient.sendCommand(
            MaCommands.Music.SEARCH,
            SearchArgs(query = query, limit = limit, mediaTypes = mediaTypes?.map { it.apiValue })
        )

        val obj = result?.jsonObject ?: return SearchResult()
        return SearchResult(
            artists = obj["artists"]?.let { parseMediaItems(it) }?.mapNotNull { it.toArtist() } ?: emptyList(),
            albums = obj["albums"]?.let { parseMediaItems(it) }?.mapNotNull { it.toAlbum() } ?: emptyList(),
            tracks = obj["tracks"]?.let { parseMediaItems(it) }?.mapNotNull { it.toTrack() } ?: emptyList(),
            playlists = obj["playlists"]?.let { parseMediaItems(it) }?.mapNotNull { it.toPlaylist() } ?: emptyList(),
            radios = obj["radio"]?.let { parseMediaItems(it) }?.mapNotNull { it.toRadio() } ?: emptyList()
        )
    }

    override suspend fun getQueueItems(queueId: String, limit: Int, offset: Int): List<QueueItem> {
        val result = wsClient.sendCommand(
            MaCommands.PlayerQueues.ITEMS,
            QueueItemsArgs(queueId = queueId, limit = limit, offset = offset)
        )
        val items = result?.let { json.decodeFromJsonElement<List<ServerQueueItem>>(it) } ?: emptyList()
        return items.map { it.toDomain() }
    }

    override suspend fun playMedia(
        queueId: String,
        uri: String,
        option: String?,
        radioMode: Boolean,
        awaitResponse: Boolean
    ) {
        if (option == null || option == "play" || option == "replace") {
            playerRepository.get().notifyQueueReplacement(queueId)
        }
        if (option == null || option == "play") {
            playerRepository.get().notifyPlaybackIntent(true)
        }
        wsClient.sendCommand(
            MaCommands.PlayerQueues.PLAY_MEDIA,
            PlayMediaArgs(queueId = queueId, mediaUris = listOf(uri), option = option, radioMode = radioMode),
            awaitResponse = awaitResponse
        )
    }

    override suspend fun playMedia(
        queueId: String,
        uris: List<String>,
        option: String?,
        radioMode: Boolean,
        awaitResponse: Boolean,
        timeoutMs: Long?
    ) {
        if (option == null || option == "play" || option == "replace") {
            playerRepository.get().notifyQueueReplacement(queueId)
        }
        if (option == null || option == "play") {
            playerRepository.get().notifyPlaybackIntent(true)
        }
        wsClient.sendCommand(
            MaCommands.PlayerQueues.PLAY_MEDIA,
            PlayMediaArgs(queueId = queueId, mediaUris = uris, option = option, radioMode = radioMode),
            awaitResponse = awaitResponse,
            timeoutMs = timeoutMs ?: 30_000
        )
    }

    override suspend fun createPlaylist(name: String): Playlist {
        val result = wsClient.sendCommand(
            MaCommands.Music.PLAYLISTS_CREATE,
            buildJsonObject { put("name", name) }
        )
        val json = result?.jsonObject ?: throw Exception("Failed to create playlist")
        return Playlist(
            itemId = json["item_id"]?.jsonPrimitive?.content ?: "",
            provider = json["provider"]?.jsonPrimitive?.content ?: "library",
            name = json["name"]?.jsonPrimitive?.content ?: name,
            uri = json["uri"]?.jsonPrimitive?.content ?: "",
            isEditable = true
        )
    }

    override suspend fun addTrackToPlaylist(playlist: Playlist, trackUri: String) {
        val dbPlaylistId = resolvePlaylistDbId(playlist)
        wsClient.sendCommand(
            MaCommands.Music.PLAYLISTS_ADD_TRACKS,
            AddPlaylistTracksArgs(
                dbPlaylistId = dbPlaylistId,
                uris = listOf(trackUri)
            )
        )
    }

    override suspend fun removeTrackFromPlaylist(playlist: Playlist, position: Int) {
        val dbPlaylistId = resolvePlaylistDbId(playlist)
        wsClient.sendCommand(
            MaCommands.Music.PLAYLISTS_REMOVE_TRACKS,
            RemovePlaylistTracksArgs(
                dbPlaylistId = dbPlaylistId,
                positionsToRemove = listOf(position)
            )
        )
    }

    override suspend fun shuffleQueue(queueId: String, enabled: Boolean) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.SHUFFLE,
            ShuffleArgs(queueId = queueId, enabled = enabled)
        )
    }

    override suspend fun repeatQueue(queueId: String, mode: RepeatMode) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.REPEAT,
            RepeatArgs(queueId = queueId, repeatMode = mode.apiValue)
        )
    }

    override suspend fun removeFromLibrary(mediaType: MediaType, libraryItemId: String) {
        wsClient.sendCommand(
            MaCommands.Music.LIBRARY_REMOVE_ITEM,
            LibraryRemoveItemArgs(mediaType = mediaType.apiValue, libraryItemId = libraryItemId)
        )
    }

    override suspend fun addToLibrary(uri: String) {
        wsClient.sendCommand(
            MaCommands.Music.LIBRARY_ADD_ITEM,
            FavoriteAddArgs(item = uri)
        )
    }

    override suspend fun setDontStopTheMusic(queueId: String, enabled: Boolean) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.DONT_STOP_THE_MUSIC,
            DontStopTheMusicArgs(queueId = queueId, enabled = enabled)
        )
    }

    override suspend fun browse(path: String?): List<BrowseItem> {
        val result = wsClient.sendCommand(
            MaCommands.Music.BROWSE,
            BrowseArgs(path)
        )
        return parseMediaItems(result).map { it.toBrowseItem() }
    }

    private fun ServerMediaItem.toBrowseItem(): BrowseItem = BrowseItem(
        itemId = itemId,
        provider = provider,
        name = name.ifBlank { translationKey?.replaceFirstChar { it.uppercase() } ?: itemId },
        uri = uri,
        path = path ?: uri.ifBlank { null },
        imageUrl = resolveImageUrl(wsClient),
        isFolder = mediaType == "folder",
        mediaType = mediaType,
        isPlayable = isPlayable ?: (uri.isNotBlank() && mediaType != "folder")
    )

    override suspend fun clearQueue(queueId: String) {
        wsClient.sendCommand(MaCommands.PlayerQueues.CLEAR, QueueIdArgs(queueId))
    }

    override suspend fun saveQueueAsPlaylist(queueId: String, name: String) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.SAVE_AS_PLAYLIST,
            buildJsonObject {
                put("queue_id", queueId)
                put("name", name)
            }
        )
    }

    override suspend fun transferQueue(sourceQueueId: String, targetQueueId: String) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.TRANSFER,
            TransferQueueArgs(sourceQueueId = sourceQueueId, targetQueueId = targetQueueId, autoPlay = true)
        )
    }

    override suspend fun deleteQueueItem(queueId: String, itemIdOrIndex: String) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.DELETE_ITEM,
            DeleteQueueItemArgs(queueId = queueId, itemIdOrIndex = itemIdOrIndex)
        )
    }

    override suspend fun moveQueueItem(queueId: String, queueItemId: String, posShift: Int) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.MOVE_ITEM,
            MoveQueueItemArgs(queueId = queueId, queueItemId = queueItemId, posShift = posShift)
        )
    }

    override suspend fun playQueueIndex(queueId: String, index: Int) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.PLAY_INDEX,
            PlayIndexArgs(queueId = queueId, index = index),
            awaitResponse = false
        )
    }

    override suspend fun requestLibrarySync(force: Boolean): Boolean {
        val now = System.currentTimeMillis()
        return librarySyncMutex.withLock {
            if (!force && now - lastLibrarySyncAtMs < LIBRARY_SYNC_COOLDOWN_MS) {
                Log.d(TAG, "Skipping music/sync due cooldown")
                return@withLock false
            }
            try {
                wsClient.sendCommand(
                    command = MaCommands.Music.SYNC,
                    awaitResponse = true,
                    timeoutMs = LIBRARY_SYNC_TIMEOUT_MS
                )
                lastLibrarySyncAtMs = now
                Log.d(TAG, "Triggered MA library sync")
                true
            } catch (e: Exception) {
                Log.w(TAG, "music/sync failed: ${e.message}")
                false
            }
        }
    }

    override suspend fun refreshItemByUri(uri: String): Boolean {
        return try {
            val mediaItem = wsClient.sendCommand(
                command = MaCommands.Music.ITEM_BY_URI,
                args = ItemByUriArgs(uri),
                awaitResponse = true,
                timeoutMs = 5_000L
            ) ?: return false

            wsClient.sendCommand(
                command = MaCommands.Music.REFRESH_ITEM,
                args = RefreshItemArgs(mediaItem),
                awaitResponse = true,
                timeoutMs = 8_000L
            )
            Log.d(TAG, "Refreshed item via ${MaCommands.Music.REFRESH_ITEM}: $uri")
            true
        } catch (e: Exception) {
            Log.w(TAG, "${MaCommands.Music.REFRESH_ITEM} failed for '$uri': ${e.message}")
            false
        }
    }

    override suspend fun setFavorite(uri: String, mediaType: MediaType, itemId: String, favorite: Boolean) {
        if (favorite) {
            sendFavoriteCommandWithRetry(MaCommands.Music.FAVORITES_ADD, FavoriteAddArgs(item = uri))
        } else {
            val libraryItemId = resolveLibraryItemId(uri, itemId)
            sendFavoriteCommandWithRetry(
                MaCommands.Music.FAVORITES_REMOVE,
                FavoriteRemoveArgs(mediaType = mediaType.apiValue, libraryItemId = libraryItemId)
            )
        }
    }

    private suspend fun sendFavoriteCommandWithRetry(command: String, args: MaCommandArgs) {
        var attempt = 1
        var lastError: MaApiException? = null
        while (attempt <= FAVORITE_MAX_ATTEMPTS) {
            try {
                wsClient.sendCommand(
                    command = command,
                    args = args,
                    awaitResponse = true,
                    timeoutMs = FAVORITE_ACK_TIMEOUT_MS
                )
                return
            } catch (e: MaApiException) {
                lastError = e
                if (!isTransientFavoriteError(e) || attempt >= FAVORITE_MAX_ATTEMPTS) {
                    throw e
                }
                Log.w(TAG, "Favorite '$command' transient failure (attempt $attempt), retrying")
                delay(FAVORITE_RETRY_DELAY_MS)
                attempt++
            }
        }
        throw lastError ?: MaApiException("Favorite command failed", -1)
    }

    private fun isTransientFavoriteError(e: MaApiException): Boolean {
        if (e.code == -1) return true
        val msg = e.message?.lowercase().orEmpty()
        return msg.contains("timed out") ||
            msg.contains("not connected") ||
            msg.contains("connection") ||
            msg.contains("closed")
    }

    private suspend fun resolveLibraryItemId(uri: String, itemId: String): String {
        // If URI is library://, extract the MA library item ID directly.
        val libraryMatch = Regex("^library://\\w+/(.+)$").find(uri)
        if (libraryMatch != null) return libraryMatch.groupValues[1]
        // Resolve via server using the full provider URI.
        val result = wsClient.sendCommand(MaCommands.Music.ITEM_BY_URI, ItemByUriArgs(uri))
        val resolved = result?.jsonObject?.get("item_id")?.jsonPrimitive?.contentOrNull
        if (!resolved.isNullOrBlank()) return resolved
        if (itemId.isNotBlank()) return itemId
        throw MaApiException("Could not resolve library_item_id for '$uri'", -1)
    }

    override suspend fun getRecommendations(): List<RecommendationFolder> {
        val result = wsClient.sendCommand(MaCommands.Music.RECOMMENDATIONS, null)
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
                Log.e(TAG, "Failed to parse recommendation folder", e)
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
                    Log.d(TAG, "Media item keys: ${firstObj.keys}")
                    val imageField = firstObj["image"]?.toString()?.take(200) ?: "null"
                    val metadataImages = firstObj["metadata"]?.jsonObject?.get("images")?.toString()?.take(200) ?: "null"
                    Log.d(TAG, "image=$imageField metadata.images=$metadataImages")
                }
                result.mapNotNull {
                    try { json.decodeFromJsonElement<ServerMediaItem>(it) } catch (_: Exception) { null }
                }
            }
            else -> emptyList()
        }
    }

    private fun ServerMediaItem.extractProviderDomains(): List<String> =
        providerMappings.filter { it.available }.map { it.providerDomain }.distinct()

    private fun ServerMediaItem.toArtist(): Artist? {
        if (mediaType.isNotEmpty() && mediaType != "artist") return null
        return Artist(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            imageUrl = resolveImageWithUriFallback(wsClient),
            favorite = favorite,
            description = metadata?.description,
            genres = metadata?.genres ?: emptyList(),
            providerDomains = extractProviderDomains()
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
            imageUrl = resolveImageWithUriFallback(wsClient),
            favorite = favorite,
            version = version,
            year = sanitizeYear(year),
            description = metadata?.description,
            genres = metadata?.genres ?: emptyList(),
            label = metadata?.label,
            artists = artists?.mapNotNull { it.toArtist() } ?: emptyList(),
            albumType = albumType,
            providerDomains = extractProviderDomains()
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
            imageUrl = resolveImageWithUriFallback(wsClient),
            favorite = favorite,
            position = position,
            artistItemId = artists?.firstOrNull()?.itemId,
            artistProvider = artists?.firstOrNull()?.provider,
            albumItemId = album?.itemId,
            albumProvider = album?.provider,
            artistUri = MediaIdentity.canonicalArtistKey(
                itemId = artists?.firstOrNull()?.itemId,
                uri = artists?.firstOrNull()?.uri
            ),
            artistUris = artists
                ?.mapNotNull { artist ->
                    MediaIdentity.canonicalArtistKey(itemId = artist.itemId, uri = artist.uri)
                }
                ?.distinct()
                ?: emptyList(),
            albumUri = MediaIdentity.canonicalAlbumKey(
                itemId = album?.itemId,
                uri = album?.uri
            ),
            genres = metadata?.genres ?: emptyList(),
            year = sanitizeYear(album?.year ?: year),
            providerDomains = extractProviderDomains()
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
            favorite = favorite,
            isEditable = isEditable != false,
            providerDomains = extractProviderDomains()
        )
    }

    private fun ServerMediaItem.toRadio(): Radio? {
        if (mediaType.isNotEmpty() && mediaType != "radio") return null
        return Radio(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            imageUrl = resolveImageUrl(wsClient),
            favorite = favorite,
            providerDomains = extractProviderDomains()
        )
    }

    private fun resolvePlaylistDbId(playlist: Playlist): String {
        val libraryMatch = Regex("^library://playlist/(.+)$").find(playlist.uri)
        if (libraryMatch != null) return libraryMatch.groupValues[1]
        if (playlist.itemId.isNotBlank()) return playlist.itemId
        throw MaApiException("Could not resolve playlist DB id for '${playlist.name}'", -1)
    }

    private fun ServerQueueItem.toDomain(): QueueItem = QueueItem(
        queueItemId = queueItemId,
        name = name,
        duration = duration,
        track = mediaItem?.toTrack(),
        imageUrl = mediaItem?.resolveImageUrl(wsClient)
            ?: image?.resolveUrl(wsClient)
    )

    private fun sanitizeYear(year: Int?): Int? = year?.takeIf { it > 0 }
}
