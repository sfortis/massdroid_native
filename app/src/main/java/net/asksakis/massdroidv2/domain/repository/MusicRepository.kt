package net.asksakis.massdroidv2.domain.repository

import net.asksakis.massdroidv2.domain.model.*

interface MusicRepository {
    suspend fun getRecommendations(): List<RecommendationFolder>
    suspend fun getArtists(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Artist>
    suspend fun getAlbums(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Album>
    suspend fun getTracks(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Track>
    suspend fun getPlaylists(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Playlist>
    suspend fun getRadios(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Radio>

    suspend fun getArtist(itemId: String, provider: String, lazy: Boolean = true): Artist?
    suspend fun getAlbum(itemId: String, provider: String, lazy: Boolean = true): Album?

    suspend fun getArtistAlbums(itemId: String, provider: String): List<Album>
    suspend fun getArtistTracks(itemId: String, provider: String): List<Track>
    suspend fun getAlbumTracks(itemId: String, provider: String): List<Track>
    suspend fun getPlaylistTracks(itemId: String, provider: String): List<Track>

    suspend fun search(query: String, mediaTypes: List<MediaType>? = null, limit: Int = 25): SearchResult
    suspend fun getQueueItems(queueId: String, limit: Int = 100, offset: Int = 0): List<QueueItem>

    suspend fun playMedia(
        queueId: String,
        uri: String,
        option: String? = null,
        radioMode: Boolean = false,
        awaitResponse: Boolean = false
    )
    suspend fun playMedia(
        queueId: String,
        uris: List<String>,
        option: String? = null,
        radioMode: Boolean = false,
        awaitResponse: Boolean = false,
        timeoutMs: Long? = null
    )
    suspend fun createPlaylist(name: String): Playlist
    suspend fun addTrackToPlaylist(playlist: Playlist, trackUri: String)
    suspend fun removeTrackFromPlaylist(playlist: Playlist, position: Int)
    suspend fun shuffleQueue(queueId: String, enabled: Boolean)
    suspend fun repeatQueue(queueId: String, mode: RepeatMode)
    suspend fun clearQueue(queueId: String)
    suspend fun saveQueueAsPlaylist(queueId: String, name: String)
    suspend fun transferQueue(sourceQueueId: String, targetQueueId: String)
    suspend fun deleteQueueItem(queueId: String, itemIdOrIndex: String)
    suspend fun moveQueueItem(queueId: String, queueItemId: String, posShift: Int)
    suspend fun playQueueIndex(queueId: String, index: Int)

    suspend fun requestLibrarySync(force: Boolean = false): Boolean
    suspend fun refreshItemByUri(uri: String): Boolean
    suspend fun setFavorite(uri: String, mediaType: MediaType, itemId: String, favorite: Boolean)
    suspend fun removeFromLibrary(mediaType: MediaType, libraryItemId: String)
    suspend fun addToLibrary(uri: String)
    suspend fun setDontStopTheMusic(queueId: String, enabled: Boolean)
    suspend fun browse(path: String? = null): List<BrowseItem>
}

data class SearchResult(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val radios: List<Radio> = emptyList()
)
