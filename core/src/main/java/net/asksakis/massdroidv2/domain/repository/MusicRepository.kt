package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import net.asksakis.massdroidv2.domain.model.*

interface MusicRepository {
    /**
     * Server `media_item_updated` events mapped to domain items, for in-place patching of
     * already-loaded lists (artwork/metadata refreshes) without a reload or scroll reset.
     */
    val mediaItemUpdates: Flow<MediaItemUpdate>

    suspend fun getRecommendations(): List<RecommendationFolder>
    suspend fun getArtists(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Artist>
    suspend fun getAlbums(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Album>
    suspend fun getTracks(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Track>
    suspend fun getPlaylists(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Playlist>
    suspend fun getRadios(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Radio>
    suspend fun getAudiobooks(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Track>
    suspend fun getPodcasts(search: String? = null, limit: Int = 50, offset: Int = 0, orderBy: String? = null, favoriteOnly: Boolean = false, providerFilter: List<String>? = null): List<Podcast>

    suspend fun getArtist(itemId: String, provider: String, lazy: Boolean = true): Artist?
    suspend fun getAlbum(itemId: String, provider: String, lazy: Boolean = true): Album?
    suspend fun getPodcast(itemId: String, provider: String): Podcast?
    suspend fun getPodcastEpisodes(podcastItemId: String, provider: String): List<PodcastEpisode>
    /** Mark a podcast episode fully played ([played] = true) or unplayed on the server. */
    suspend fun setPodcastEpisodePlayed(itemId: String, provider: String, played: Boolean)

    suspend fun getArtistAlbums(itemId: String, provider: String): List<Album>

    /**
     * The artist's full discography from a single provider (the MA web UI behaviour), as opposed
     * to [getArtistAlbums] which, for a `library` artist, returns only the albums actually in the
     * library (often none on MA 2.9+). For a library artist this resolves the default provider
     * mapping (first available real provider) and queries its catalogue; for a provider artist it
     * is that provider's catalogue directly. Empty when the artist has no usable provider mapping.
     */
    suspend fun getArtistDiscography(itemId: String, provider: String): List<Album>
    suspend fun getArtistTracks(itemId: String, provider: String): List<Track>

    /**
     * Artists the provider considers similar, as playable MA items.
     *
     * This is the discovery source for generated mixes. Unlike the Last.fm path it
     * replaces, the results already carry uris, so no name-resolution search is
     * needed. Reliable for `library` artists; a provider item may return empty
     * when its provider does not implement the feature.
     */
    suspend fun getSimilarArtists(itemId: String, provider: String, limit: Int = 25): List<Artist>

    /** The artist's most-played tracks, as playable MA items. */
    /** Tracks the provider considers similar to this one. Includes the seed itself; callers filter it. */
    suspend fun getSimilarTracks(itemId: String, provider: String, limit: Int): List<Track>
    suspend fun getArtistTopTracks(itemId: String, provider: String, limit: Int = 10): List<Track>
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
    suspend fun removeFromLibrary(mediaType: MediaType, uri: String, itemId: String)
    suspend fun addToLibrary(uri: String)
    suspend fun setDontStopTheMusic(queueId: String, enabled: Boolean)

    /**
     * Autoplay settings of one queue, or null on a server that does not expose them
     * (they arrived with MA 2.10) or when the account may not read queue config.
     */
    suspend fun getAutoplayConfig(queueId: String): AutoplayConfig?

    /**
     * Change how Autoplay refills [queueId]. [playlistUri] is only sent when the chosen
     * mode is the one the server said it depends on.
     *
     * Returns false when the server refused the change, which for a non-admin account is
     * the expected outcome: reading queue config needs `CONFIG_PLAYERS_READ` but writing
     * it needs `CONFIG_PLAYERS_WRITE`, and only an admin holds that.
     */
    suspend fun setAutoplayConfig(queueId: String, mode: String, playlistUri: String? = null): Boolean

    suspend fun browse(path: String? = null): List<BrowseItem>
}

data class SearchResult(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val radios: List<Radio> = emptyList()
)
