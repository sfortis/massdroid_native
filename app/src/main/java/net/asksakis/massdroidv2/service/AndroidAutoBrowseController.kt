package net.asksakis.massdroidv2.service

import android.content.Context
import android.content.Intent
import android.os.Build
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
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.ui.ShortcutAction
import net.asksakis.massdroidv2.ui.ShortcutActionDispatcher

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
) {
    private var cachedSearchResults: SearchResult? = null

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
                val playerCommands = Player.Commands.Builder().addAllCommands().build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
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
                val effectivePageSize = if (pageSize > 0) pageSize else PAGE_SIZE_DEFAULT
                return scope.future(Dispatchers.IO) {
                    val items = try {
                        children(parentId, page, effectivePageSize)
                    } catch (e: Exception) {
                        Log.e(TAG, "onGetChildren($parentId) failed", e)
                        emptyList()
                    }
                    LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                }
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
                        cachedSearchResults = result
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
                    val result = cachedSearchResults ?: runCatching { musicRepository.search(query) }
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
            "more" -> buildMoreCategories()
            "recently_played" -> loadAlbums(page, pageSize, "last_played")
            "artists" -> loadArtists(page, pageSize)
            "albums" -> loadAlbums(page, pageSize)
            "playlists" -> loadPlaylists(page, pageSize)
            "tracks" -> loadTracks(page, pageSize)
            "genres" -> buildGenreList()
            "genre_radio" -> buildGenreRadioList()
            else -> when {
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

    private fun buildRootCategories(): List<MediaItem> = listOf(
        browseFolder("playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, net.asksakis.massdroidv2.auto.R.drawable.ic_tab_playlists),
        browseFolder("artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, net.asksakis.massdroidv2.auto.R.drawable.ic_tab_artists),
        browseFolder("albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, net.asksakis.massdroidv2.auto.R.drawable.ic_tab_albums),
        browseFolder("more", "More", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, net.asksakis.massdroidv2.auto.R.drawable.ic_tab_more),
    )

    private fun buildMoreCategories(): List<MediaItem> = listOf(
        browseFolder("recently_played", "Recent", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browseFolder("tracks", "Tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        browseFolder("genres", "Genres", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        playableItem("smart_mix", "Smart Mix", MediaMetadata.MEDIA_TYPE_PLAYLIST),
        browseFolder("genre_radio", "Genre Radio", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
    )

    private suspend fun buildGenreList(): List<MediaItem> {
        return genreRepository.libraryGenres().map { genre ->
            browseFolder("genre|$genre", genre.replaceFirstChar { it.uppercase() }, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
        }
    }

    private suspend fun loadGenreArtists(genre: String): List<MediaItem> {
        return genreRepository.libraryArtistsForGenre(genre).map { artist ->
            browseFolder("artist|${artist.provider}|${artist.itemId}", artist.name, MediaMetadata.MEDIA_TYPE_ARTIST)
        }
    }

    private suspend fun buildGenreRadioList(): List<MediaItem> {
        return genreRepository.topGenres(days = 60, limit = 20).map { genreScore ->
            playableItem(
                "genre_radio|${genreScore.genre}",
                genreScore.genre.replaceFirstChar { it.uppercase() },
                MediaMetadata.MEDIA_TYPE_PLAYLIST
            )
        }
    }

    private fun playableItem(mediaId: String, title: String, mediaType: Int): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()
    }

    private fun browseFolder(
        mediaId: String,
        title: String,
        mediaType: Int,
        iconResId: Int? = null
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
        if (iconResId != null) {
            metadata.setArtworkUri(
                android.net.Uri.Builder()
                    .scheme("android.resource")
                    .authority(context.packageName)
                    .appendPath(iconResId.toString())
                    .build()
            )
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.build())
            .build()
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
                .setArtworkUri(imageUrl?.let { android.net.Uri.parse(it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
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
                .setArtworkUri(imageUrl?.let { android.net.Uri.parse(it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
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
                .setArtworkUri(imageUrl?.let { android.net.Uri.parse(it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
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
                .setArtworkUri(imageUrl?.let { android.net.Uri.parse(it) } ?: AutoBrowseExtras.placeholderArtworkUri(context, name))
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
