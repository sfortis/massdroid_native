package net.asksakis.massdroidv2.service

import net.asksakis.massdroidv2.playback.SendspinAudioController

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.auto.AutoBrowseExtras
import net.asksakis.massdroidv2.auto.PackageValidator
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.BrowseItem
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.shortcut.ShortcutAction
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher

class AndroidAutoBrowseController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository,
    private val shortcutDispatcher: ShortcutActionDispatcher,
    private val activeQueueId: () -> String?,
    private val isSendspinActive: () -> Boolean,
    private val sendspinController: () -> SendspinAudioController?,
    private val onCustomCommand: (String) -> Boolean,
    private val isConnected: () -> Boolean = { true },
) {
    // Keyed by the query that produced it: onGetSearchResult must never serve a
    // previous query's results when the host requests a newer one.
    private var cachedSearch: Pair<String, SearchResult>? = null

    // Tracks parentIds the AA host has ever queried or subscribed to during this
    // session. Used by AndroidAutoController to broadcast notifyChildrenChanged
    // on WS reconnect, so AA invalidates any empty results cached during a cold
    // start race (WS not yet connected when AA first browsed). Thread-safe: the
    // Media3 host may call onGetChildren/onSubscribe from arbitrary threads.
    private val knownParentIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Snapshot of parentIds the AA host has queried/subscribed to so far. */
    fun trackedParentIds(): Set<String> = knownParentIds.toSet()

    /** Clear tracked subscriptions on session stop. */
    fun clearTrackedParentIds() = knownParentIds.clear()

    val callback: MediaLibraryService.MediaLibrarySession.Callback =
        object : MediaLibraryService.MediaLibrarySession.Callback {

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                if (!PackageValidator.isKnownCaller(context, controller)) {
                    Log.w(TAG, "onConnect: unknown caller ${controller.packageName} (uid=${controller.uid})")
                }
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(AndroidAutoMediaCommands.ToggleFavorite)
                    .add(AndroidAutoMediaCommands.ToggleShuffle)
                    .build()
                val playerCommands = Player.Commands.Builder().addAllCommands().build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .setMediaButtonPreferences(
                        AndroidAutoMediaCommands.buttons(context, isFavorite = false, shuffleEnabled = false)
                    )
                    .build()
            }

            override fun onPostConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ) {
                // Car: a controller (the AAOS media center) just connected. If we are
                // not signed in / connected to MA yet, surface the standard AAOS
                // sign-in affordance as a non-fatal SessionError carrying a resolution
                // PendingIntent. This is sent per-controller so a late-connecting car
                // (cold start, before the WS is up) still gets it. The browse tree is
                // left valid; the WS-connect invalidation refreshes it once signed in.
                if (net.asksakis.massdroidv2.BuildConfig.IS_AUTOMOTIVE && !isConnected()) {
                    (session as? MediaLibraryService.MediaLibrarySession)?.sendError(controller, buildSignInError())
                }
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val handled = onCustomCommand(customCommand.customAction)
                val result = if (handled) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_NOT_SUPPORTED
                return Futures.immediateFuture(SessionResult(result))
            }

            @OptIn(UnstableApi::class)
            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                if (session.isMediaNotificationController(controllerInfo)) return false

                val ctrl = sendspinController() ?: return true
                if (!isSendspinActive()) return true
                ctrl.sendspinPlayerId ?: return true

                val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                } ?: return false
                if (keyEvent.action != KeyEvent.ACTION_DOWN) return true

                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> ctrl.handlePlay()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> ctrl.handlePause()
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> ctrl.handlePlayPause()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> ctrl.handleNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ctrl.handlePrev()
                    else -> return false
                }
                Log.d(TAG, "BT media button routed to sendspin: ${keyEvent.keyCode}")
                return true
            }

            override fun onGetLibraryRoot(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val root = MediaItem.Builder()
                    .setMediaId(ROOT)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setTitle("MassDroid")
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build()
                    )
                    .build()
                val rootParams = MediaLibraryService.LibraryParams.Builder()
                    .setExtras(AutoBrowseExtras.rootExtras())
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(root, rootParams))
            }

            override fun onGetChildren(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                knownParentIds.add(parentId)
                val effectivePageSize = if (pageSize > 0) pageSize else PAGE_SIZE_DEFAULT
                return scope.future(Dispatchers.IO) {
                    try {
                        val items = children(parentId, page, effectivePageSize)
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    } catch (e: Exception) {
                        // Return an error, NOT an empty list: the AA host caches a
                        // successful empty result and never re-queries, so a
                        // transient RPC failure (timeout, one provider poisoning
                        // music/search) while the WS is still connected would leave
                        // the tab permanently blank. An error lets the host retry.
                        Log.e(TAG, "onGetChildren($parentId) failed", e)
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
                    }
                }
            }

            override fun onSubscribe(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> {
                knownParentIds.add(parentId)
                return Futures.immediateFuture(LibraryResult.ofVoid())
            }

            override fun onSearch(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val result = musicRepository.search(query)
                        cachedSearch = query to result
                        val totalCount = result.artists.size + result.albums.size +
                            result.tracks.size + result.playlists.size
                        session.notifySearchResultChanged(browser, query, totalCount, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "onSearch($query) failed", e)
                    }
                }
                return Futures.immediateFuture(LibraryResult.ofVoid())
            }

            override fun onGetSearchResult(
                session: MediaLibraryService.MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return scope.future(Dispatchers.IO) {
                    val cached = cachedSearch?.takeIf { it.first == query }?.second
                    val result = cached ?: runCatching { musicRepository.search(query) }
                        .onFailure { Log.e(TAG, "onGetSearchResult($query) failed", it) }
                        .getOrDefault(SearchResult())
                    val grouped = buildList {
                        addAll(result.artists.map { it.toBrowsableMediaItem(groupTitle = "Artists") })
                        addAll(result.albums.map { it.toBrowsableMediaItem(groupTitle = "Albums") })
                        addAll(result.tracks.map { it.toPlayableMediaItem(groupTitle = "Tracks") })
                        addAll(result.playlists.map { it.toBrowsableMediaItem(groupTitle = "Playlists") })
                    }
                    val effectivePageSize = if (pageSize > 0) pageSize else PAGE_SIZE_DEFAULT
                    val paged = grouped.drop(page * effectivePageSize).take(effectivePageSize)
                    LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
                }
            }

            @OptIn(UnstableApi::class)
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>
            ): ListenableFuture<List<MediaItem>> {
                val queueId = activeQueueId()
                if (queueId == null) {
                    Log.w(TAG, "onAddMediaItems: no active queue")
                    return Futures.immediateFuture(emptyList())
                }
                scope.launch(Dispatchers.IO) {
                    mediaItems.forEach { item -> handleAddMediaItem(queueId, item) }
                }
                return Futures.immediateFuture(mediaItems)
            }
        }

    private suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        return when (parentId) {
            ROOT -> buildRootCategories()
            "recently_played" -> loadAlbums(page, pageSize, "last_played")
            "artists" -> loadArtists(page, pageSize)
            "albums" -> loadAlbums(page, pageSize)
            "playlists" -> loadPlaylists(page, pageSize)
            "tracks" -> loadTracks(page, pageSize)
            "audiobooks" -> loadAudiobooks(page, pageSize)
            "genres" -> buildGenreList()
            "genre_radio" -> buildGenreRadioList()
            "browse" -> loadServerBrowse(null)
            else -> when {
                parentId.startsWith("browse|") -> {
                    val encoded = parentId.removePrefix("browse|")
                    val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
                    loadServerBrowse(decoded)
                }
                parentId.startsWith("genre|") -> loadGenreArtists(parentId.removePrefix("genre|"))
                else -> loadSubItems(parentId, page, pageSize)
            }
        }
    }

    private suspend fun handleAddMediaItem(queueId: String, item: MediaItem) {
        val mediaId = item.mediaId
        val searchQuery = item.requestMetadata.searchQuery?.trim()
        when {
            !searchQuery.isNullOrBlank() && mediaId.isEmpty() -> handleVoiceSearch(queueId, searchQuery)
            searchQuery != null && mediaId.isEmpty() -> shortcutDispatcher.dispatch(ShortcutAction.SmartMix)
            mediaId == "smart_mix" -> shortcutDispatcher.dispatch(ShortcutAction.SmartMix)
            mediaId.startsWith("genre_radio|") -> {
                shortcutDispatcher.dispatch(ShortcutAction.GenreRadio(mediaId.removePrefix("genre_radio|")))
            }
            else -> {
                val uri = item.requestMetadata.mediaUri?.toString()
                    ?: item.mediaId.takeIf { it.contains("/") }
                    ?: resolveMediaIdToUri(mediaId)
                if (uri == null) {
                    Log.w(TAG, "onAddMediaItems: could not resolve mediaId=$mediaId")
                    return
                }
                try {
                    playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                    musicRepository.playMedia(queueId, uri, option = "replace")
                } catch (e: Exception) {
                    Log.e(TAG, "playMedia failed for $uri", e)
                }
            }
        }
    }

    private suspend fun handleVoiceSearch(queueId: String, query: String) {
        try {
            val results = musicRepository.search(query)
            val targetUri = results.playlists.firstOrNull()?.uri
                ?: results.albums.firstOrNull()?.uri
                ?: results.tracks.firstOrNull()?.uri
                ?: results.artists.firstOrNull()?.let { artist ->
                    musicRepository.getArtistTracks(artist.itemId, artist.provider).firstOrNull()?.uri
                }
            if (targetUri == null) {
                Log.w(TAG, "Voice search: no playable result for '$query'")
                return
            }
            playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
            musicRepository.playMedia(queueId, targetUri, option = "replace")
        } catch (e: Exception) {
            Log.e(TAG, "Voice search failed for '$query'", e)
        }
    }

    private fun resolveMediaIdToUri(mediaId: String): String? {
        val parts = mediaId.split("|")
        if (parts.size != 3) return null
        val (type, provider, itemId) = parts
        return "$provider://$type/$itemId"
    }

    // Flat root list in one logical order (no "More" folder): library content
    // first, then the generators. Rendered as a list (see rootExtras) so every
    // entry is visible without the gearhead grid's 4-tile cap + auto-"More".
    private fun buildRootCategories(): List<MediaItem> = listOf(
        browseFolder(
            "playlists",
            "Playlists",
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            net.asksakis.massdroidv2.auto.R.drawable.ic_tab_playlists,
            listItem = true
        ),
        browseFolder(
            "albums",
            "Albums",
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            net.asksakis.massdroidv2.auto.R.drawable.ic_tab_albums,
            listItem = true
        ),
        browseFolder(
            "artists",
            "Artists",
            MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            net.asksakis.massdroidv2.auto.R.drawable.ic_tab_artists,
            listItem = true
        ),
        browseFolder(
            "audiobooks",
            "Audiobooks",
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            net.asksakis.massdroidv2.auto.R.drawable.ic_tab_audiobooks,
            listItem = true
        ),
        browseFolder(
            "genres",
            "Genres",
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            net.asksakis.massdroidv2.auto.R.drawable.ic_tab_genres,
            listItem = true
        ),
        browseFolder(
            "browse",
            "Browse",
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            net.asksakis.massdroidv2.auto.R.drawable.ic_tab_browse,
            listItem = true
        ),
        playableItem(
            "smart_mix",
            "Smart Mix",
            MediaMetadata.MEDIA_TYPE_PLAYLIST,
            net.asksakis.massdroidv2.auto.R.drawable.ic_tab_smart_mix,
            listItem = true
        ),
        browseFolder(
            "genre_radio",
            "Genre Radio",
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
            net.asksakis.massdroidv2.auto.R.drawable.ic_tab_genre_radio,
            listItem = true
        ),
    )

    private suspend fun loadServerBrowse(path: String?): List<MediaItem> {
        return runCatching { musicRepository.browse(path) }
            .onFailure { Log.w(TAG, "Browse '${path ?: "/"}' failed: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { it.name != ".." }
            .map { it.toBrowseMediaItem() }
    }

    private fun BrowseItem.toBrowseMediaItem(): MediaItem {
        val mediaIdValue = if (isFolder) {
            val pathToken = path ?: uri
            "browse|${java.net.URLEncoder.encode(pathToken, "UTF-8")}"
        } else {
            uri
        }
        val resolvedMediaType = when {
            isFolder -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
            mediaType.equals("track", ignoreCase = true) -> MediaMetadata.MEDIA_TYPE_MUSIC
            mediaType.equals("album", ignoreCase = true) -> MediaMetadata.MEDIA_TYPE_ALBUM
            mediaType.equals("artist", ignoreCase = true) -> MediaMetadata.MEDIA_TYPE_ARTIST
            mediaType.equals("playlist", ignoreCase = true) -> MediaMetadata.MEDIA_TYPE_PLAYLIST
            mediaType.equals("radio", ignoreCase = true) -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
            else -> MediaMetadata.MEDIA_TYPE_MIXED
        }
        return MediaItem.Builder()
            .setMediaId(mediaIdValue)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtworkUri(
                        imageUrl?.let { carArtworkUri(context, it) }
                            ?: AutoBrowseExtras.placeholderArtworkUri(context, name)
                    )
                    .setIsBrowsable(isFolder)
                    .setIsPlayable(!isFolder)
                    .setMediaType(resolvedMediaType)
                    .setExtras(AutoBrowseExtras.listItemExtras())
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(uri))
                    .build()
            )
            .build()
    }

    private suspend fun buildGenreList(): List<MediaItem> {
        return genreRepository.libraryGenres().map { genre ->
            browseFolder(
                "genre|$genre",
                genre.replaceFirstChar { it.uppercase() },
                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                listItem = true
            )
        }
    }

    private suspend fun loadGenreArtists(genre: String): List<MediaItem> {
        return genreRepository.libraryArtistsForGenre(genre).map { artist ->
            browseFolder(
                "artist|${artist.provider}|${artist.itemId}",
                artist.name,
                MediaMetadata.MEDIA_TYPE_ARTIST,
                listItem = true
            )
        }
    }

    private suspend fun buildGenreRadioList(): List<MediaItem> {
        return genreRepository.topGenres(days = 60, limit = 20).map { genreScore ->
            playableItem(
                "genre_radio|${genreScore.genre}",
                genreScore.genre.replaceFirstChar { it.uppercase() },
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                listItem = true
            )
        }
    }

    private fun playableItem(
        mediaId: String,
        title: String,
        mediaType: Int,
        iconResId: Int? = null,
        listItem: Boolean = false
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(mediaType)
        if (iconResId != null) metadata.setArtworkUri(iconArtworkUri(iconResId))
        if (listItem) metadata.setExtras(AutoBrowseExtras.listItemExtras())
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun browseFolder(
        mediaId: String,
        title: String,
        mediaType: Int,
        iconResId: Int? = null,
        groupTitle: String? = null,
        listItem: Boolean = false
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
        if (iconResId != null) {
            metadata.setArtworkUri(iconArtworkUri(iconResId))
        }
        when {
            groupTitle != null -> metadata.setExtras(AutoBrowseExtras.rootTileExtras(groupTitle))
            listItem -> metadata.setExtras(AutoBrowseExtras.listItemExtras())
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.build())
            .build()
    }

    // Category icons: content:// PNG render on the car (the AAOS media center can't
    // decode a cross-package vector android.resource://), raw android.resource on phone/TV.
    private fun iconArtworkUri(iconResId: Int): android.net.Uri = carIconUri(context, iconResId)

    // The AAOS sign-in affordance: a non-fatal SessionError whose resolution extras
    // carry a "Sign in" label + a PendingIntent into the (parked) sign-in screen.
    // The exported MainActivity can be started by this PendingIntent without a
    // LAUNCHER filter, which is why the car build can drop its launcher icon.
    private fun buildSignInError(): androidx.media3.session.SessionError {
        val intent = Intent(context, net.asksakis.massdroidv2.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val extras = Bundle().apply {
            putString(
                androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                "Sign in"
            )
            putParcelable(
                androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                pendingIntent
            )
        }
        return androidx.media3.session.SessionError(
            androidx.media3.session.SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
            "Sign in to Music Assistant",
            extras
        )
    }

    private suspend fun loadArtists(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getArtists(limit = pageSize, offset = page * pageSize, orderBy = "name")
            .map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadAlbums(page: Int, pageSize: Int, orderBy: String = "name"): List<MediaItem> {
        return musicRepository.getAlbums(limit = pageSize, offset = page * pageSize, orderBy = orderBy)
            .map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadPlaylists(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getPlaylists(limit = pageSize, offset = page * pageSize)
            .map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadTracks(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getTracks(limit = pageSize, offset = page * pageSize, orderBy = "last_played")
            .map { it.toPlayableMediaItem() }
    }

    private suspend fun loadAudiobooks(page: Int, pageSize: Int): List<MediaItem> {
        // Each audiobook is one playable item; tapping it starts the book via
        // its real URI, so play goes through playMedia (NEW_STREAM) and never
        // hits the single-item next/previous wedge.
        return musicRepository.getAudiobooks(limit = pageSize, offset = page * pageSize, orderBy = "name")
            .map { it.toAudiobookMediaItem() }
    }

    // The AA host (gearhead) frequently re-sends only the mediaId on
    // onAddMediaItems, stripping requestMetadata.mediaUri. The generic
    // "track|provider|itemId" id used for music then resolves to
    // "provider://track/itemId" — WRONG for an audiobook (type is "track", not
    // "audiobook"), so MA plays a different item. Encode the real URI as the
    // mediaId (it contains "/", so handleAddMediaItem uses it verbatim) so the
    // book resolves correctly even when the host drops the request metadata.
    private fun Track.toAudiobookMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artistNames.ifEmpty { null })
                .setArtworkUri(imageUrl?.let { carArtworkUri(context, it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .setExtras(AutoBrowseExtras.listItemExtras())
                .build()
        )
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(uri)).build())
        .build()

    private suspend fun loadSubItems(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val parts = parentId.split("|")
        if (parts.size != 3) return emptyList()
        val (type, provider, itemId) = parts
        return when (type) {
            "artist" -> musicRepository.getArtistAlbums(itemId, provider).map { it.toBrowsableMediaItem() }
            "album" -> musicRepository.getAlbumTracks(itemId, provider).map { it.toPlayableMediaItem() }
            "playlist" -> musicRepository.getPlaylistTracks(itemId, provider).map { it.toPlayableMediaItem() }
            else -> emptyList()
        }
    }

    private fun Artist.toBrowsableMediaItem(groupTitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId("artist|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtworkUri(imageUrl?.let { carArtworkUri(context, it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                .also { if (groupTitle != null) it.setExtras(AutoBrowseExtras.groupTitleExtras(groupTitle)) }
                .build()
        )
        .build()

    private fun Album.toBrowsableMediaItem(groupTitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId("album|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artistNames.ifEmpty { null })
                .setArtworkUri(imageUrl?.let { carArtworkUri(context, it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .also { if (groupTitle != null) it.setExtras(AutoBrowseExtras.groupTitleExtras(groupTitle)) }
                .build()
        )
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(uri)).build())
        .build()

    private fun Track.toPlayableMediaItem(groupTitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId("track|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artistNames.ifEmpty { null })
                .setAlbumTitle(albumName.ifEmpty { null })
                .setArtworkUri(imageUrl?.let { carArtworkUri(context, it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .also { if (groupTitle != null) it.setExtras(AutoBrowseExtras.groupTitleExtras(groupTitle)) }
                .build()
        )
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(uri)).build())
        .build()

    private fun Playlist.toBrowsableMediaItem(groupTitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId("playlist|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtworkUri(imageUrl?.let { carArtworkUri(context, it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .also { if (groupTitle != null) it.setExtras(AutoBrowseExtras.groupTitleExtras(groupTitle)) }
                .build()
        )
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(uri)).build())
        .build()

    private companion object {
        private const val TAG = "AndroidAutoBrowse"
        private const val ROOT = "root"
        private const val PAGE_SIZE_DEFAULT = 50
    }
}
