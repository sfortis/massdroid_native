package net.asksakis.massdroidv2.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.asksakis.massdroidv2.data.websocket.*
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.EverythingBlockedException
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SEARCHABLE_MEDIA_TYPES
import net.asksakis.massdroidv2.domain.repository.SearchResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val wsClient: MaWebSocketClient,
    private val imageResolver: net.asksakis.massdroidv2.data.image.ImageUrlResolver,
    private val json: Json,
    private val playerRepository: dagger.Lazy<PlayerRepository>
) : MusicRepository {
    companion object {
        private const val TAG = "MusicRepo"
        private const val MUSICBRAINZ_ARTIST_ID = "musicbrainz_artistid"
        private const val FAVORITE_ACK_TIMEOUT_MS = 1_000L
        private const val FAVORITE_MAX_ATTEMPTS = 3
        private const val FAVORITE_RETRY_DELAY_MS = 180L
        private const val LIBRARY_SYNC_COOLDOWN_MS = 45_000L
        private const val LIBRARY_SYNC_TIMEOUT_MS = 1_500L

        /**
         * The first schema that understands `sort_by` on `player_queues/play_media`, which
         * arrived with MA 2.10.0.
         *
         * An older server does not reject the argument, it drops it: `parse_arguments`
         * defaults to `strict = False`. So the gate has to be here rather than left to the
         * server to enforce, or an old server would silently play its own order.
         */
        private const val SORT_BY_MIN_SCHEMA = 63

        /**
         * How long one search attempt waits before it gives up.
         *
         * Shorter than the 30 s default because someone is watching a spinner while
         * it runs. A measured MA search takes about a second cold and 0.15 s once
         * the provider has cached it, so five seconds is well clear of a slow answer.
         *
         * This is the budget per attempt, not per search. A search is a read, so
         * [isRetryableCommand] lets it be sent a second time after a timeout, and
         * the listener waits for both attempts before the failure reaches the
         * screen. Measured on 2026-09-20 with both radios off: at 10 s per attempt
         * the error arrived 20 s after the send, by which time the user had given
         * up and left the screen. Five seconds keeps that total at about ten while
         * the second attempt still covers a server that was briefly slow.
         */
        private const val SEARCH_TIMEOUT_MS = 5_000L
    }
    private val librarySyncMutex = Mutex()
    private var lastLibrarySyncAtMs = 0L

    override val mediaItemUpdates: Flow<MediaItemUpdate> = wsClient.events.mapNotNull { event ->
        if (event.event != EventType.MEDIA_ITEM_UPDATED) return@mapNotNull null
        val root = event.data as? JsonObject ?: return@mapNotNull null
        // The updated item is the payload itself, or nested under media_item on some events.
        val itemJson = if ("media_type" in root) root else root["media_item"] as? JsonObject
        val serverItem = itemJson?.let {
            runCatching { json.decodeFromJsonElement<ServerMediaItem>(it) }.getOrNull()
        } ?: return@mapNotNull null
        when (serverItem.mediaType) {
            "artist" -> serverItem.toArtist()?.let { MediaItemUpdate.ArtistUpdated(it) }
            "album" -> serverItem.toAlbum()?.let { MediaItemUpdate.AlbumUpdated(it) }
            "track" -> serverItem.toTrack()?.let { MediaItemUpdate.TrackUpdated(it) }
            "audiobook" -> serverItem.toTrack()?.let { MediaItemUpdate.AudiobookUpdated(it) }
            "playlist" -> serverItem.toPlaylist()?.let { MediaItemUpdate.PlaylistUpdated(it) }
            "radio" -> serverItem.toRadio()?.let { MediaItemUpdate.RadioUpdated(it) }
            else -> null
        }
        // Library syncs emit media_item_updated in large bursts; keep the per-event JSON decode
        // off the collector's (Main) dispatcher.
    }.flowOn(Dispatchers.Default)

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

    override suspend fun getAudiobooks(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean, providerFilter: List<String>?): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.AUDIOBOOKS_LIBRARY_ITEMS,
            LibraryItemsArgs(search, limit, offset, orderBy, favoriteOnly, providerFilter)
        )
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getPodcasts(search: String?, limit: Int, offset: Int, orderBy: String?, favoriteOnly: Boolean, providerFilter: List<String>?): List<Podcast> {
        val result = wsClient.sendCommand(
            MaCommands.Music.PODCASTS_LIBRARY_ITEMS,
            LibraryItemsArgs(search, limit, offset, orderBy, favoriteOnly, providerFilter)
        )
        return parseMediaItems(result).mapNotNull { it.toPodcast() }
    }

    override suspend fun getPodcast(itemId: String, provider: String): Podcast? {
        val result = wsClient.sendCommand(
            MaCommands.Music.PODCASTS_GET,
            ItemRefArgs(itemId = itemId, provider = provider)
        )
        return result?.let {
            try { json.decodeFromJsonElement<ServerMediaItem>(it).toPodcast() } catch (_: Exception) { null }
        }
    }

    override suspend fun getPodcastEpisodes(podcastItemId: String, provider: String): List<PodcastEpisode> {
        val result = wsClient.sendCommand(
            MaCommands.Music.PODCAST_EPISODES,
            ItemRefArgs(itemId = podcastItemId, provider = provider)
        )
        return parseMediaItems(result).mapNotNull { it.toPodcastEpisode() }
    }

    override suspend fun setPodcastEpisodePlayed(itemId: String, provider: String, played: Boolean) {
        // mark_played/unplayed require the FULL media_item object, so re-fetch the
        // episode from the server and pass it through verbatim.
        val raw = wsClient.sendCommand(
            MaCommands.Music.PODCAST_EPISODE_GET,
            ItemRefArgs(itemId = itemId, provider = provider)
        ) ?: return
        if (played) {
            wsClient.sendCommand(MaCommands.Music.MARK_PLAYED, MarkPlayedArgs(raw, fullyPlayed = true))
        } else {
            wsClient.sendCommand(MaCommands.Music.MARK_UNPLAYED, MarkUnplayedArgs(raw))
        }
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

    override suspend fun getArtistDiscography(itemId: String, provider: String): List<Album> {
        // A provider artist: its own (item_id, provider) already IS the provider catalogue.
        if (!provider.equals("library", ignoreCase = true) && !provider.equals("builtin", ignoreCase = true)) {
            return getArtistAlbums(itemId, provider)
        }
        // A library artist: artist_albums(library) returns only in-library albums (often none on
        // MA 2.9+). Resolve the default provider mapping the way the MA web UI does - dedupe by
        // provider instance, drop unavailable / library / builtin, sort by provider for a stable
        // default - then query that provider's discography.
        val raw = wsClient.sendCommand(
            MaCommands.Music.ARTISTS_GET,
            ItemRefLazyArgs(itemId = itemId, provider = provider, lazy = false)
        ) ?: return emptyList()
        val server = try {
            json.decodeFromJsonElement<ServerMediaItem>(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val mapping = server.providerMappings
            .filter {
                it.available && it.itemId.isNotBlank() &&
                    !it.providerDomain.equals("library", ignoreCase = true) &&
                    !it.providerDomain.equals("builtin", ignoreCase = true)
            }
            .distinctBy { it.providerInstance.ifEmpty { it.providerDomain } }
            .minByOrNull { it.providerInstance.ifEmpty { it.providerDomain } }
            ?: return emptyList()
        return getArtistAlbums(mapping.itemId, mapping.providerInstance.ifEmpty { mapping.providerDomain })
    }

    override suspend fun getArtistTracks(itemId: String, provider: String): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.ARTIST_TRACKS,
            ItemRefArgs(itemId = itemId, provider = provider)
        )
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getSimilarArtists(itemId: String, provider: String, limit: Int): List<Artist> {
        val result = wsClient.sendCommand(
            MaCommands.Music.SIMILAR_ARTISTS,
            ItemRefLimitArgs(itemId = itemId, provider = provider, limit = limit)
        )
        return parseMediaItems(result).mapNotNull { it.toArtist() }
    }

    override suspend fun getSimilarTracks(itemId: String, provider: String, limit: Int): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.SIMILAR_TRACKS,
            ItemRefLimitArgs(itemId = itemId, provider = provider, limit = limit)
        )
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun getArtistTopTracks(itemId: String, provider: String, limit: Int): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.ARTIST_TOP_TRACKS,
            ItemRefLimitArgs(itemId = itemId, provider = provider, limit = limit)
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

    override suspend fun getPlaylistTracks(
        itemId: String,
        provider: String,
        forceRefresh: Boolean
    ): List<Track> {
        val result = wsClient.sendCommand(
            MaCommands.Music.PLAYLIST_TRACKS,
            PlaylistTracksArgs(itemId = itemId, provider = provider, forceRefresh = forceRefresh)
        )
        return parseMediaItems(result).mapNotNull { it.toTrack() }
    }

    override suspend fun search(query: String, mediaTypes: List<MediaType>?, limit: Int): SearchResult {
        val result = wsClient.sendCommand(
            MaCommands.Music.SEARCH,
            SearchArgs(
                query = query,
                limit = limit,
                mediaTypes = (mediaTypes ?: SEARCHABLE_MEDIA_TYPES).map { it.apiValue }
            ),
            timeoutMs = SEARCH_TIMEOUT_MS
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

    /**
     * Whether this server can be asked for [key].
     *
     * The playlist's own order asks for nothing, so it works on every server and must not be
     * gated: gating it sent the whole track list on Music Assistant 2.9 for the default sort,
     * which is the one case that used to take the container path on any version.
     *
     * The rest need the argument, which arrived in schema 63. An older server does not refuse
     * it, it drops it (`parse_arguments` defaults to `strict = False`), and would then play
     * its own order while the screen showed another.
     */
    override fun supportsServerSideSort(key: PlaylistSortKey): Boolean =
        key.serverKey == null || (wsClient.serverSchemaVersion() ?: 0) >= SORT_BY_MIN_SCHEMA

    override suspend fun playMedia(
        queueId: String,
        uri: String,
        option: String?,
        radioMode: Boolean,
        awaitResponse: Boolean,
        sortKey: PlaylistSortKey?
    ) {
        Log.d(TAG, "playMedia uri=$uri hasBlocked=${playerRepository.get().hasBlockedArtists()}")
        var startItem: String? = null
        when (val plan = blockedPlanFor(uri, sortKey)) {
            BlockedPlan.PlayWhole -> Unit
            BlockedPlan.PlayNothing -> {
                Log.d(TAG, "All tracks blocked, skipping play: $uri")
                throw EverythingBlockedException(uri)
            }
            is BlockedPlan.StartAt -> {
                Log.d(TAG, "Blocked head on $uri; starting the container at ${plan.trackUri}")
                startItem = plan.trackUri
            }
        }
        // Dropped rather than sent to a server that would silently ignore it. The caller asks
        // supportsServerSideSort first and sends a track list in that case, so this only
        // guards against a caller that did not.
        val sortBy = sortKey?.takeIf { supportsServerSideSort(it) }?.serverKey

        val shouldNotifyReplacement = option == "replace"
        val shouldNotifyPlayback = option == "play" || option == "replace"
        if (!awaitResponse) {
            if (shouldNotifyReplacement) {
                playerRepository.get().notifyQueueReplacement(queueId)
            }
            if (shouldNotifyPlayback) {
                playerRepository.get().notifyPlaybackIntent(true)
            }
        }
        wsClient.sendCommand(
            MaCommands.PlayerQueues.PLAY_MEDIA,
            PlayMediaArgs(
                queueId = queueId,
                mediaUris = listOf(uri),
                option = option,
                radioMode = radioMode,
                sortBy = sortBy,
                startItem = startItem
            ),
            awaitResponse = awaitResponse
        )
        if (awaitResponse) {
            if (shouldNotifyReplacement) {
                playerRepository.get().notifyQueueReplacement(queueId)
            }
            if (shouldNotifyPlayback) {
                playerRepository.get().notifyPlaybackIntent(true)
            }
        }
    }

    /**
     * Whether the server regenerates this playlist on play (`is_dynamic`).
     *
     * Asked only when there are blocked artists to filter and the item is a playlist, so
     * it costs a round trip in that case alone. An unknown answer counts as yes: leaving
     * the container intact keeps the server's own behaviour, where guessing wrong the
     * other way silently replaces a generated playlist with a stale copy of itself.
     */
    private suspend fun isRebuiltOnPlay(uri: String): Boolean = try {
        val item = wsClient.sendCommand(MaCommands.Music.ITEM_BY_URI, ItemByUriArgs(uri))?.jsonObject
        if (item == null) {
            // No answer about this playlist, so nothing is known: leave it whole.
            true
        } else {
            // An ABSENT field is an answer, not silence. A server that predates the
            // feature never sends it, and on one of those no playlist is rebuilt on play,
            // so expanding it to filter blocked artists is right there. Reading absence as
            // "dynamic" would quietly switch that filtering off for every older server.
            item["is_dynamic"]?.jsonPrimitive?.booleanOrNull == true
        }
    } catch (e: Exception) {
        Log.d(TAG, "could not tell if $uri is rebuilt on play (${e.message}); leaving it whole")
        true
    }

    /**
     * How a container has to be played so that no blocked artist is heard.
     *
     * Blocking is the app's own idea: Music Assistant has no notion of a blocked artist, so
     * nothing the server builds on its own will leave those tracks out.
     */
    private sealed interface BlockedPlan {
        /** Nothing is blocked, or the block cannot be applied here. Play the container. */
        data object PlayWhole : BlockedPlan

        /**
         * Play the container, but tell the server to begin at this track.
         *
         * Used when only the head of a container is blocked. The server drops what precedes
         * the start item, and the blocked tracks further down are removed from the queue by
         * `PlayerRepository`'s own cleanup long before playback reaches them.
         */
        data class StartAt(val trackUri: String) : BlockedPlan

        /** Everything here is blocked. */
        data object PlayNothing : BlockedPlan
    }

    private fun Track.hasBlockedArtist(repo: PlayerRepository): Boolean {
        val primaryUri = artistUri ?: return false
        val primaryName = artistNames.split(",").firstOrNull()?.trim().orEmpty()
        return repo.isArtistBlocked(primaryName, primaryUri)
    }

    /**
     * Decide how [uri] must be played, given the artists the listener has blocked.
     *
     * A playlist is deliberately NOT expanded into its tracks any more. Expanding it meant
     * the server resolved every track URI one by one, which for a 260-track Deezer playlist
     * measured 100 seconds before the first track played, and the cost fell on the largest
     * playlists for the sake of four or five blocked tracks buried in them. Handing over the
     * container and starting past any blocked head keeps the resolve to a single paged pass
     * while still never playing a blocked artist.
     */
    private suspend fun blockedPlanFor(uri: String, sortKey: PlaylistSortKey?): BlockedPlan {
        val repo = playerRepository.get()
        if (!repo.hasBlockedArtists()) return BlockedPlan.PlayWhole

        // Parse URI: {provider}://{type}/{id}
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd < 0) return BlockedPlan.PlayWhole
        val provider = uri.substring(0, schemeEnd)
        val parts = uri.substring(schemeEnd + 3).split("/", limit = 2)
        if (parts.size != 2) return BlockedPlan.PlayWhole
        val (type, itemId) = parts

        return when (type) {
            "artist" ->
                if (repo.isArtistUriBlocked(uri)) BlockedPlan.PlayNothing else BlockedPlan.PlayWhole
            "album" -> {
                val tracks = try {
                    getAlbumTracks(itemId, provider)
                } catch (_: Exception) {
                    return BlockedPlan.PlayWhole
                }
                // Handed over as a container, the same way a playlist is. Dropping the
                // blocked tracks and sending what is left would work too, but the server
                // then has no container to record as what filled the queue, and the queue
                // screen has nothing to name itself after.
                startPlanFor(tracks, repo)
            }
            "playlist" -> {
                // A playlist the server rebuilds on play draws a fresh set of tracks, so the
                // list read here says nothing about what will play and no start item can be
                // chosen from it. That is issue #67.
                if (isRebuiltOnPlay(uri)) return BlockedPlan.PlayWhole
                val tracks = try {
                    getPlaylistTracks(itemId, provider)
                } catch (_: Exception) {
                    return BlockedPlan.PlayWhole
                }
                // Read in the order the server will play it, so that "the first track" means
                // the same thing here and there. Tracks no provider can serve are dropped
                // first, because the server drops them too while it resolves the container:
                // naming one as the start item finds nothing and returns an EMPTY queue
                // (`get_playlist_tracks` in media_resolver.py returns [] when the start item
                // is not found), which would be worse than the blocked track it avoids.
                startPlanFor(sortKey?.let { tracks.sortedForListing(it) } ?: tracks, repo)
            }
            else -> BlockedPlan.PlayWhole
        }
    }

    /**
     * Where a container should start, given what is blocked inside it.
     *
     * The container is played whole whenever its first track is clean, because anything
     * blocked further down is out of earshot by the time the queue cleanup runs. A blocked
     * head is stepped over with a start item instead, which keeps the container intact.
     *
     * [ordered] must be in the order the server will play, so that "the first track" means
     * the same thing here and there. Tracks no provider can serve are dropped first,
     * because the server drops them too while it resolves the container: naming one as the
     * start item finds nothing and returns an EMPTY queue (`get_playlist_tracks` in
     * media_resolver.py returns [] when the start item is not found), which would be worse
     * than the blocked track it avoids.
     */
    private fun startPlanFor(ordered: List<Track>, repo: PlayerRepository): BlockedPlan {
        val playable = ordered.filter { it.available }
        // Nothing playable is not the same as everything blocked, and saying so would put
        // the wrong message on screen. Hand it over and let the server answer.
        if (playable.isEmpty()) return BlockedPlan.PlayWhole
        return when (val first = playable.indexOfFirst { !it.hasBlockedArtist(repo) }) {
            0 -> BlockedPlan.PlayWhole
            -1 -> BlockedPlan.PlayNothing
            else -> BlockedPlan.StartAt(playable[first].uri)
        }
    }

    override suspend fun playMedia(
        queueId: String,
        uris: List<String>,
        option: String?,
        radioMode: Boolean,
        awaitResponse: Boolean,
        timeoutMs: Long?
    ) {
        val shouldNotifyReplacement = option == "replace"
        val shouldNotifyPlayback = option == "play" || option == "replace"
        if (!awaitResponse) {
            if (shouldNotifyReplacement) {
                playerRepository.get().notifyQueueReplacement(queueId)
            }
            if (shouldNotifyPlayback) {
                playerRepository.get().notifyPlaybackIntent(true)
            }
        }
        wsClient.sendCommand(
            MaCommands.PlayerQueues.PLAY_MEDIA,
            PlayMediaArgs(queueId = queueId, mediaUris = uris, option = option, radioMode = radioMode),
            awaitResponse = awaitResponse,
            timeoutMs = timeoutMs ?: 30_000
        )
        if (awaitResponse) {
            if (shouldNotifyReplacement) {
                playerRepository.get().notifyQueueReplacement(queueId)
            }
            if (shouldNotifyPlayback) {
                playerRepository.get().notifyPlaybackIntent(true)
            }
        }
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

    override suspend fun removeFromLibrary(mediaType: MediaType, uri: String, itemId: String) {
        val libraryItemId = resolveLibraryItemId(uri, itemId)
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

    override suspend fun setAutoplayEnabled(queueId: String, enabled: Boolean) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.SET_AUTOPLAY_ENABLED,
            SetAutoplayEnabledArgs(queueId = queueId, enabled = enabled)
        )
    }

    override suspend fun getQueueSettings(queueId: String): QueueSettings? {
        val values = try {
            wsClient.sendCommand(
                MaCommands.ConfigPlayerQueues.GET,
                ConfigQueueGetArgs(queueId = queueId)
            )?.jsonObject?.get("values")?.jsonObject
        } catch (e: Exception) {
            // Absent before MA 2.10, and refused for an account without
            // CONFIG_PLAYERS_READ. Neither is worth an error to the user: the caller
            // shows nothing instead of the settings that come from here.
            Log.d(TAG, "queue config unavailable for $queueId: ${e.message}")
            return null
        }
        val settings = QueueConfigParser.parse(values)
        return withGlobalValues(settings)
    }

    /**
     * Fill in what each setting left on "global" currently resolves to.
     *
     * Read in one `config/core/get` rather than a call per key: a queue that has never
     * been configured follows the global value for all four, and four round trips would
     * be four times the wait for one dialog. Needs only CONFIG_CORE_READ, which every
     * account role holds, and a failure is tolerated: without it the "Global" options
     * simply do not say what they resolve to.
     */
    private suspend fun withGlobalValues(settings: QueueSettings): QueueSettings {
        val needsGlobal = settings.autoplay?.mode == AutoplayConfig.MODE_GLOBAL ||
            listOf(settings.crossfadeMode, settings.volumeNormalization, settings.smartShuffle)
                .any { it?.followsGlobal == true }
        if (!needsGlobal) return settings
        val core = try {
            wsClient.sendCommand(
                MaCommands.ConfigCore.GET,
                ConfigCoreGetArgs(domain = MaCommands.ConfigCore.DOMAIN_PLAYER_QUEUES)
            )?.jsonObject?.get("values")?.jsonObject
        } catch (e: Exception) {
            Log.d(TAG, "server-wide queue defaults unavailable: ${e.message}")
            return settings
        }
        return settings.copy(
            autoplay = settings.autoplay?.copy(
                globalMode = QueueConfigParser.coreValue(core, AutoplayConfig.KEY_MODE)
            ),
            crossfadeMode = settings.crossfadeMode?.withGlobal(core),
            volumeNormalization = settings.volumeNormalization?.withGlobal(core),
            smartShuffle = settings.smartShuffle?.withGlobal(core)
        )
    }

    private fun QueueChoice.withGlobal(core: JsonObject?): QueueChoice =
        copy(globalValue = QueueConfigParser.coreValue(core, key))

    override suspend fun setAutoplayConfig(
        queueId: String,
        mode: String,
        playlistUri: String?
    ): Boolean = saveQueueConfig(queueId, "autoplay") {
        put(AutoplayConfig.KEY_MODE, mode)
        // Only sent alongside the mode it belongs to. Writing it under another mode
        // would store a playlist the server will not use and cannot show.
        if (playlistUri != null) put(AutoplayConfig.KEY_PLAYLIST, playlistUri)
    }

    override suspend fun setQueueConfigValue(
        queueId: String,
        key: String,
        value: String
    ): Boolean = saveQueueConfig(queueId, key) { put(key, value) }

    /**
     * Partial save of a queue's configuration. The server merges what is sent, so each
     * caller sends only the keys it changed.
     *
     * Returns false rather than throwing when the server refuses: writing queue config
     * needs CONFIG_PLAYERS_WRITE, which only an admin holds, so a refusal is an expected
     * outcome for an ordinary account. The WebSocket client already raises the account
     * notice for it, and the caller reverts what it optimistically showed.
     */
    private suspend fun saveQueueConfig(
        queueId: String,
        what: String,
        values: JsonObjectBuilder.() -> Unit
    ): Boolean = try {
        wsClient.sendCommand(
            MaCommands.ConfigPlayerQueues.SAVE,
            ConfigQueueSaveArgs(queueId = queueId, values = buildJsonObject(values))
        ) != null
    } catch (e: Exception) {
        Log.w(TAG, "$what save refused for $queueId: ${e.message}")
        false
    }

    override suspend fun setCrossfadeEnabled(queueId: String, enabled: Boolean) {
        wsClient.sendCommand(
            MaCommands.PlayerQueues.SET_CROSSFADE_ENABLED,
            SetCrossfadeEnabledArgs(queueId = queueId, enabled = enabled)
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
        imageUrl = imageResolver.resolveItem(this),
        isFolder = mediaType == "folder",
        mediaType = mediaType,
        isPlayable = ! (mediaType == "folder") && isPlayable == true
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
        Log.d("sendspindbg", "WS>>> play_index($queueId, $index)")
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
            imageUrl = imageResolver.resolveItemWithUriFallback(this),
            favorite = favorite,
            description = metadata?.description,
            genres = metadata?.genres ?: emptyList(),
            providerDomains = extractProviderDomains(),
            mbid = externalIds
                .firstOrNull { it.size >= 2 && it[0] == MUSICBRAINZ_ARTIST_ID }
                ?.get(1)
                ?.takeIf { it.isNotBlank() },
            providerUris = artistProviderUris()
        )
    }

    /**
     * The uris of this artist under each provider that carries them.
     *
     * A library artist's own uri says nothing about how it will arrive from a
     * queue event, where the provider's uri is what turns up. `provider_mappings`
     * is the server's own statement that these are the same artist, so it beats
     * matching on the name, which is not unique.
     */
    private fun ServerMediaItem.artistProviderUris(): List<String> =
        providerMappings
            .filter { it.providerInstance.isNotBlank() && it.itemId.isNotBlank() }
            .map { "${it.providerInstance}://artist/${it.itemId}" }
            .filter { it != uri }
            .distinct()

    private fun ServerMediaItem.toAlbum(): Album? {
        if (mediaType.isNotEmpty() && mediaType != "album") return null
        return Album(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            artistNames = artists?.joinToString(", ") { it.name } ?: "",
            imageUrl = imageResolver.resolveItemWithUriFallback(this),
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
        // Tracks plus audiobooks (a single playable item with chapters). Library/search track
        // endpoints only ever return "track"; the audiobook path is the queue's current item.
        if (mediaType.isNotEmpty() && mediaType != "track" && mediaType != "audiobook") return null
        if (isPlayable == false) {
            Log.w(TAG, "Dropping unplayable track '$name' uri=$uri path=$path")
            return null
        }
        return Track(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            duration = duration,
            artistNames = artists?.joinToString(", ") { it.name } ?: "",
            albumName = album?.name ?: "",
            imageUrl = imageResolver.resolveItemWithUriFallback(this),
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
            providerDomains = extractProviderDomains(),
            lyrics = metadata?.lyrics,
            lrcLyrics = metadata?.lrcLyrics,
            dateAdded = dateAdded,
            mediaType = MediaType.fromApi(mediaType) ?: MediaType.TRACK,
            chapters = toChapters(),
            authors = authors ?: emptyList(),
            narrators = narrators ?: emptyList(),
            // Resolved here, not at the call site, so nothing downstream has to remember
            // that the server falls back to the plain name when it has no sort name.
            // No provider can serve it. The server leaves these out of a queue it builds
            // itself, so anything choosing a track for the server has to leave them out too.
            available = providerMappings.isEmpty() || providerMappings.any { it.available },
            sortName = sort_name.orEmpty().ifBlank { name },
            primaryArtistSortName = artists?.firstOrNull()
                ?.let { it.sort_name.orEmpty().ifBlank { it.name } }
                .orEmpty(),
            albumSortName = album?.let { it.sort_name.orEmpty().ifBlank { it.name } }.orEmpty()
        )
    }

    private fun ServerMediaItem.toPlaylist(): Playlist? {
        if (mediaType.isNotEmpty() && mediaType != "playlist") return null
        return Playlist(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            imageUrl = imageResolver.resolveItem(this),
            favorite = favorite,
            isEditable = isEditable != false,
            isDynamic = isDynamic == true,
            owner = owner.orEmpty(),
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
            imageUrl = imageResolver.resolveItem(this),
            favorite = favorite,
            providerDomains = extractProviderDomains()
        )
    }

    private fun ServerMediaItem.toPodcast(): Podcast? {
        if (mediaType.isNotEmpty() && mediaType != "podcast") return null
        return Podcast(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            imageUrl = imageResolver.resolveItemWithUriFallback(this),
            publisher = publisher,
            totalEpisodes = totalEpisodes,
            favorite = favorite,
            description = metadata?.description,
            providerDomains = extractProviderDomains()
        )
    }

    private fun ServerMediaItem.toPodcastEpisode(): PodcastEpisode? {
        if (mediaType.isNotEmpty() && mediaType != "podcast_episode") return null
        return PodcastEpisode(
            itemId = itemId,
            provider = provider,
            name = name,
            uri = uri,
            imageUrl = imageResolver.resolveItemWithUriFallback(this),
            duration = duration ?: 0.0,
            position = position,
            description = metadata?.description,
            fullyPlayed = fullyPlayed ?: false,
            resumePositionMs = resumePositionMs ?: 0L,
            favorite = favorite,
            podcastName = album?.name
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
        imageUrl = mediaItem?.let { imageResolver.resolveItem(it) }
            ?: image?.let { imageResolver.resolve(it) }
    )

    private fun sanitizeYear(year: Int?): Int? = year?.takeIf { it > 0 }
}
