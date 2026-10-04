package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import net.asksakis.massdroidv2.domain.model.*

/**
 * Nothing in the requested album or playlist can be played, because every track belongs to an
 * artist the listener has blocked.
 *
 * Thrown rather than returned quietly: the request was understood and refused on the listener's
 * own instruction, which is worth saying. A silent return read as a dead button, and on this
 * library twelve albums are credited to a blocked artist in full.
 */
class EverythingBlockedException(val uri: String) :
    Exception("Every track in $uri is by a blocked artist")

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
    /**
     * Tracks of a playlist. [forceRefresh] makes the server re-read them from the
     * provider instead of its cache, which is what a playlist the provider generates
     * needs before it will show anything new.
     */
    suspend fun getPlaylistTracks(
        itemId: String,
        provider: String,
        forceRefresh: Boolean = false
    ): List<Track>

    suspend fun search(query: String, mediaTypes: List<MediaType>? = null, limit: Int = 25): SearchResult
    suspend fun getQueueItems(queueId: String, limit: Int = 100, offset: Int = 0): List<QueueItem>

    suspend fun playMedia(
        queueId: String,
        uri: String,
        option: String? = null,
        radioMode: Boolean = false,
        awaitResponse: Boolean = false,
        /**
         * The order a container should play in, when the caller is showing that container
         * sorted. The server resolves the container in one pass and orders it itself, which
         * is what a playlist of any size needs: handing over the expanded track list instead
         * makes the server resolve every URI one by one.
         *
         * Pass it only when [supportsServerSideSort] agrees, otherwise the caller has to send
         * the track list so that what plays matches what is on screen.
         */
        sortKey: PlaylistSortKey? = null
    )

    /**
     * Whether this server can sort a container for us, so that it may be handed over whole
     * instead of being expanded into a track list.
     *
     * Every [PlaylistSortKey] is an order Music Assistant has a key for, so this only answers
     * whether the server is new enough to know the argument. The protocol knowledge sits here
     * rather than in a ViewModel, which only knows what it is showing.
     */
    fun supportsServerSideSort(key: PlaylistSortKey): Boolean
    suspend fun playMedia(
        queueId: String,
        uris: List<String>,
        option: String? = null,
        radioMode: Boolean = false,
        awaitResponse: Boolean = false,
        timeoutMs: Long? = null
    )

    /**
     * Play [trackUri] now and let the dynamic playlist [feedUri] carry on after it.
     *
     * The server ignores `start_item` for a dynamic playlist, so the two go as separate
     * commands: the track with the option the server uses for a playlist, then the
     * playlist added, which turns the queue into the playlist's feed behind the track.
     */
    suspend fun playTrackThenFeed(queueId: String, trackUri: String, feedUri: String)
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
    suspend fun setAutoplayEnabled(queueId: String, enabled: Boolean)

    /**
     * Configuration of one queue, or null on a server that does not expose it (queue
     * config arrived with MA 2.10) or when the account may not read it.
     *
     * Individual settings inside are also nullable: a server may have Autoplay but not
     * smart shuffle, and the caller shows only what it was actually given.
     */
    suspend fun getQueueSettings(queueId: String): QueueSettings?

    /**
     * Change how Autoplay refills [queueId]. [playlistUri] is only sent when the chosen
     * mode is the one the server said it depends on.
     *
     * Returns false when the server refused the change, which for a non-admin account is
     * the expected outcome: reading queue config needs `CONFIG_PLAYERS_READ` but writing
     * it needs `CONFIG_PLAYERS_WRITE`, and only an admin holds that.
     */
    suspend fun setAutoplayConfig(queueId: String, mode: String, playlistUri: String? = null): Boolean

    /**
     * Change one queue config value, such as [QueueChoice.KEY_CROSSFADE_MODE].
     *
     * Returns false when the server refused, which for a non-admin account is the
     * expected outcome, as with [setAutoplayConfig].
     */
    suspend fun setQueueConfigValue(queueId: String, key: String, value: String): Boolean

    /**
     * Turn crossfade on or off for [queueId].
     *
     * This is a queue property rather than configuration, so unlike the mode it needs no
     * admin rights. It only exists from MA 2.10; before that, crossfade was turned off by
     * setting the player's crossfade mode to disabled.
     */
    suspend fun setCrossfadeEnabled(queueId: String, enabled: Boolean)

    suspend fun browse(path: String? = null): List<BrowseItem>
}

/**
 * The media types a [SearchResult] can hold, and therefore the only ones worth asking for.
 *
 * A search that names no types is not a search for everything. Music Assistant asks each
 * provider for whatever it considers the default, and Deezer then labels the same records
 * as audiobooks: "gruselkabinett folge 12:" returned 25 audiobooks and no albums, while the
 * same query with these types named returned those 25 as albums. Naming them also skips the
 * genres, audiobooks, podcasts and sound effects a [SearchResult] would drop anyway, which
 * measured about a quarter faster on the same query.
 */
val SEARCHABLE_MEDIA_TYPES = listOf(
    MediaType.ARTIST,
    MediaType.ALBUM,
    MediaType.TRACK,
    MediaType.PLAYLIST,
    MediaType.RADIO,
    MediaType.AUDIOBOOK,
    MediaType.PODCAST
)

data class SearchResult(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val radios: List<Radio> = emptyList(),
    /** Modelled as [Track], the same as the library tab does: one playable item with chapters. */
    val audiobooks: List<Track> = emptyList(),
    val podcasts: List<Podcast> = emptyList()
) {
    /** True when the server matched nothing at all, in any category. */
    val isEmpty: Boolean
        get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() &&
            playlists.isEmpty() && radios.isEmpty() && audiobooks.isEmpty() && podcasts.isEmpty()
}
