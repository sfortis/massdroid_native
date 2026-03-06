package net.asksakis.massdroidv2.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.BitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.ui.MainActivity
import okhttp3.Request
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "PlaybackSvc"
        private const val PAGE_SIZE_DEFAULT = 50
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var sendspinManager: SendspinManager
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var wsClient: MaWebSocketClient
    @Inject lateinit var settingsRepository: SettingsRepository

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var remotePlayer: RemoteControlPlayer? = null
    private var sendspinActive = false
    private var sendspinPlayerId: String? = null
    private var cachedSearchResults: SearchResult? = null
    private var cachedArtworkUrl: String? = null
    @Volatile private var cachedArtworkData: ByteArray? = null

    override fun onCreate() {
        super.onCreate()
        remotePlayer = createRemotePlayer()
        createMediaSession()
        observePlayerState()
        observeQueueItems()
        observeSendspinState()
        observeSendspinPlayerState()
        loadSendspinPlayerId()
    }

    private fun activePlayerId(): String? {
        return if (sendspinActive) sendspinPlayerId
        else playerRepository.selectedPlayer.value?.playerId
    }

    private fun createRemotePlayer(): RemoteControlPlayer {
        return RemoteControlPlayer(
            Looper.getMainLooper(),
            onPlay = {
                Log.d(TAG, "RemotePlayer onPlay (sendspin=$sendspinActive)")
                val id = activePlayerId() ?: return@RemoteControlPlayer
                if (sendspinActive) sendspinManager.resumeAudio()
                scope.launch { playerRepository.play(id) }
            },
            onPause = {
                Log.d(TAG, "RemotePlayer onPause (sendspin=$sendspinActive)")
                val id = activePlayerId() ?: return@RemoteControlPlayer
                if (sendspinActive) sendspinManager.pauseAudio()
                scope.launch { playerRepository.pause(id) }
            },
            onNext = {
                val id = activePlayerId() ?: return@RemoteControlPlayer
                scope.launch { playerRepository.next(id) }
            },
            onPrevious = {
                val id = activePlayerId() ?: return@RemoteControlPlayer
                scope.launch { playerRepository.previous(id) }
            },
            onSeek = { positionMs ->
                val id = activePlayerId() ?: return@RemoteControlPlayer
                scope.launch { playerRepository.seek(id, positionMs / 1000.0) }
            }
        )
    }

    private fun createMediaSession() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaLibrarySession = MediaLibrarySession.Builder(this, remotePlayer!!, libraryCallback)
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(SyncBitmapLoader())
            .build()
        Log.d(TAG, "MediaLibrarySession created")
    }

    private fun loadSendspinPlayerId() {
        scope.launch {
            sendspinPlayerId = settingsRepository.sendspinClientId.first()
        }
    }

    /** Observe selected player state (when sendspin is NOT active). */
    private fun observePlayerState() {
        scope.launch {
            combine(
                playerRepository.selectedPlayer,
                playerRepository.queueState,
                playerRepository.elapsedTime
            ) { player, queue, elapsed ->
                Triple(player, queue, elapsed)
            }.collect { (player, queue, elapsed) ->
                if (player == null || sendspinActive) return@collect

                val currentTrack = queue?.currentItem?.track
                val title = currentTrack?.name ?: player.currentMedia?.title ?: ""
                val artist = currentTrack?.artistNames ?: player.currentMedia?.artist ?: ""
                val album = currentTrack?.albumName ?: player.currentMedia?.album ?: ""
                val duration = currentTrack?.duration ?: queue?.currentItem?.duration
                    ?: player.currentMedia?.duration ?: 0.0
                val imageUrl = currentTrack?.imageUrl
                    ?: queue?.currentItem?.imageUrl
                    ?: player.currentMedia?.imageUrl

                if (cachedArtworkUrl == null && imageUrl != null) {
                    Log.d(TAG, "First artwork URL: $imageUrl (track=${currentTrack?.imageUrl != null}, qi=${queue?.currentItem?.imageUrl != null}, media=${player.currentMedia?.imageUrl != null})")
                }

                updateArtwork(imageUrl)

                remotePlayer?.updateState(
                    isPlaying = player.state == PlaybackState.PLAYING,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = (duration * 1000).toLong(),
                    positionMs = (elapsed * 1000).toLong(),
                    artworkData = cachedArtworkData
                )
            }
        }
    }

    /** Fetch full queue items when queue changes (for car display queue list). */
    private fun observeQueueItems() {
        // Selected player queue changes (non-sendspin)
        scope.launch {
            playerRepository.queueItemsChanged.collect { queueId ->
                if (!sendspinActive) fetchQueueItems(queueId)
            }
        }
        // Sendspin player: fetch queue when track changes
        scope.launch {
            playerRepository.players
                .map { list -> list.find { it.playerId == sendspinPlayerId }?.currentMedia?.uri }
                .distinctUntilChanged()
                .collect { uri ->
                    val ssId = sendspinPlayerId
                    if (sendspinActive && ssId != null && uri != null) {
                        fetchQueueItems(ssId)
                    }
                }
        }
    }

    private fun fetchQueueItems(queueId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val items = musicRepository.getQueueItems(queueId)
                val entries = items.map { qi ->
                    QueueEntry(
                        id = qi.queueItemId.toStableLongId(),
                        title = qi.track?.name ?: qi.name,
                        artist = qi.track?.artistNames ?: "",
                        album = qi.track?.albumName ?: "",
                        durationMs = ((qi.track?.duration ?: qi.duration) * 1000).toLong()
                    )
                }
                withContext(Dispatchers.Main) {
                    remotePlayer?.updateQueue(entries)
                }
                Log.d(TAG, "Queue updated: ${entries.size} items for $queueId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load queue items for $queueId", e)
            }
        }
    }

    /** Observe sendspin player state from the players list (when sendspin IS active). */
    private fun observeSendspinPlayerState() {
        scope.launch {
            playerRepository.players
                .map { list -> list.find { it.playerId == sendspinPlayerId } }
                .distinctUntilChanged()
                .collect { player ->
                    if (!sendspinActive || player == null) return@collect
                    val media = player.currentMedia ?: return@collect

                    val imageUrl = media.imageUrl
                    updateArtwork(imageUrl)

                    remotePlayer?.updateState(
                        isPlaying = player.state == PlaybackState.PLAYING,
                        title = media.title,
                        artist = media.artist,
                        album = media.album,
                        durationMs = (media.duration * 1000).toLong(),
                        positionMs = (media.elapsedTime * 1000).toLong(),
                        artworkData = cachedArtworkData
                    )
                }
        }
    }

    private fun updateArtwork(imageUrl: String?) {
        if (imageUrl != null && imageUrl != cachedArtworkUrl) {
            Log.d(TAG, "Artwork URL changed: $imageUrl")
            cachedArtworkUrl = imageUrl
            cachedArtworkData = null
            scope.launch(Dispatchers.IO) { downloadArtwork(imageUrl) }
        } else if (imageUrl == null && cachedArtworkUrl != null) {
            Log.d(TAG, "Artwork URL cleared")
            cachedArtworkUrl = null
            cachedArtworkData = null
        }
    }

    private fun downloadArtwork(url: String) {
        try {
            val request = Request.Builder().url(url).build()
            val (code, contentType, rawBytes) = wsClient.getImageClient().newCall(request).execute().use { response ->
                Triple(response.code, response.header("Content-Type"), response.body?.bytes())
            }
            Log.d(TAG, "Artwork HTTP $code, type=$contentType, bytes=${rawBytes?.size ?: 0} for $url")
            if (url != cachedArtworkUrl) {
                Log.d(TAG, "Ignoring stale artwork response for $url (current=$cachedArtworkUrl)")
                return
            }
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                val resized = resizeArtwork(rawBytes)
                if (url != cachedArtworkUrl) {
                    Log.d(TAG, "Ignoring stale resized artwork for $url (current=$cachedArtworkUrl)")
                    return
                }
                cachedArtworkData = resized
                Log.d(TAG, "Artwork decoded+resized: ${resized.size} bytes, setting on player")
                scope.launch {
                    if (url != cachedArtworkUrl) {
                        Log.d(TAG, "Skipping setArtwork for stale URL $url")
                        return@launch
                    }
                    remotePlayer?.setArtwork(resized)
                    Log.d(TAG, "Artwork setArtwork() called on Main thread")
                }
            } else {
                Log.w(TAG, "Artwork response empty for $url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Artwork download failed: $url", e)
        }
    }

    private fun resizeArtwork(rawBytes: ByteArray, maxSize: Int = 320): ByteArray {
        val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
        val scale = maxSize.toFloat() / maxOf(original.width, original.height)
        if (scale >= 1f) {
            original.recycle()
            return rawBytes
        }
        val scaled = Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt(),
            (original.height * scale).toInt(),
            true
        )
        original.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
        scaled.recycle()
        return out.toByteArray()
    }

    /** Track sendspin state without releasing the session. */
    private fun observeSendspinState() {
        scope.launch {
            sendspinManager.enabled.collect { active ->
                sendspinActive = active
                Log.d(TAG, "Sendspin active=$active")
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        scope.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    // region Browse / Search callbacks

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("MassDroid")
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val effectivePageSize = if (pageSize > 0) pageSize else PAGE_SIZE_DEFAULT
            return scope.future(Dispatchers.IO) {
                val items = try {
                    when (parentId) {
                        "root" -> buildRootCategories()
                        "recently_played" -> loadAlbums(page, effectivePageSize, "last_played")
                        "artists" -> loadArtists(page, effectivePageSize)
                        "albums" -> loadAlbums(page, effectivePageSize)
                        "playlists" -> loadPlaylists(page, effectivePageSize)
                        "tracks" -> loadTracks(page, effectivePageSize)
                        else -> loadSubItems(parentId, page, effectivePageSize)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onGetChildren($parentId) failed", e)
                    emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
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
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return scope.future(Dispatchers.IO) {
                val result = cachedSearchResults ?: try {
                    musicRepository.search(query)
                } catch (e: Exception) {
                    Log.e(TAG, "onGetSearchResult($query) failed", e)
                    SearchResult()
                }
                val allItems = result.artists.map { it.toBrowsableMediaItem() } +
                    result.albums.map { it.toBrowsableMediaItem() } +
                    result.tracks.map { it.toPlayableMediaItem() } +
                    result.playlists.map { it.toBrowsableMediaItem() }
                val effectivePageSize = if (pageSize > 0) pageSize else PAGE_SIZE_DEFAULT
                val paged = allItems.drop(page * effectivePageSize).take(effectivePageSize)
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
            }
        }

        @OptIn(UnstableApi::class)
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val queueId = activePlayerId()
                ?: playerRepository.queueState.value?.queueId
            if (queueId == null) {
                Log.w(TAG, "onAddMediaItems: no active queue")
                return Futures.immediateFuture(emptyList())
            }
            scope.launch(Dispatchers.IO) {
                for (item in mediaItems) {
                    val uri = item.requestMetadata.mediaUri?.toString()
                        ?: item.mediaId.takeIf { it.contains("/") }
                    if (uri != null) {
                        try {
                            musicRepository.playMedia(queueId, uri, option = "replace")
                        } catch (e: Exception) {
                            Log.e(TAG, "playMedia failed for $uri", e)
                        }
                    }
                }
            }
            return Futures.immediateFuture(mediaItems)
        }
    }

    // endregion

    // region Content tree builders

    private fun buildRootCategories(): List<MediaItem> = listOf(
        browseFolder("recently_played", "Recently Played", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browseFolder("artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        browseFolder("albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        browseFolder("playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        browseFolder("tracks", "Tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    private fun browseFolder(mediaId: String, title: String, mediaType: Int): MediaItem {
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()
    }

    private suspend fun loadArtists(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getArtists(
            limit = pageSize,
            offset = page * pageSize,
            orderBy = "name"
        ).map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadAlbums(
        page: Int,
        pageSize: Int,
        orderBy: String = "name"
    ): List<MediaItem> {
        return musicRepository.getAlbums(
            limit = pageSize,
            offset = page * pageSize,
            orderBy = orderBy
        ).map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadPlaylists(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getPlaylists(
            limit = pageSize,
            offset = page * pageSize
        ).map { it.toBrowsableMediaItem() }
    }

    private suspend fun loadTracks(page: Int, pageSize: Int): List<MediaItem> {
        return musicRepository.getTracks(
            limit = pageSize,
            offset = page * pageSize,
            orderBy = "last_played"
        ).map { it.toPlayableMediaItem() }
    }

    private suspend fun loadSubItems(
        parentId: String,
        page: Int,
        pageSize: Int
    ): List<MediaItem> {
        val parts = parentId.split("|")
        if (parts.size != 3) return emptyList()
        val (type, provider, itemId) = parts
        return when (type) {
            "artist" -> musicRepository.getArtistAlbums(itemId, provider)
                .map { it.toBrowsableMediaItem() }
            "album" -> musicRepository.getAlbumTracks(itemId, provider)
                .map { it.toPlayableMediaItem() }
            "playlist" -> musicRepository.getPlaylistTracks(itemId, provider)
                .map { it.toPlayableMediaItem() }
            else -> emptyList()
        }
    }

    // endregion

    // region Domain -> MediaItem converters

    private fun Artist.toBrowsableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("artist|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                .build()
        )
        .build()

    private fun Album.toBrowsableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("album|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artistNames.ifEmpty { null })
                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .build()
        )
        .build()

    private fun Track.toPlayableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("track|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artistNames.ifEmpty { null })
                .setAlbumTitle(albumName.ifEmpty { null })
                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .build()
        )
        .build()

    private fun Playlist.toBrowsableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId("playlist|$provider|$itemId")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                .setIsBrowsable(true)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .build()
        )
        .build()

    // endregion
}

/**
 * Synchronous BitmapLoader that decodes artwork immediately.
 * Avoids the async decode race in Media3's default CacheBitmapLoader
 * which uses reference equality on cloned byte arrays (cache always misses).
 */
@OptIn(UnstableApi::class)
private class SyncBitmapLoader : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bitmap != null) Futures.immediateFuture(bitmap)
            else Futures.immediateFailedFuture(IllegalArgumentException("Cannot decode bitmap"))
        } catch (e: Exception) {
            Futures.immediateFailedFuture(e)
        }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return Futures.immediateFailedFuture(UnsupportedOperationException("URI loading not supported"))
    }
}

data class QueueEntry(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

private fun String.toStableLongId(): Long {
    var hash = 1125899906842597L
    for (char in this) {
        hash = 31L * hash + char.code.toLong()
    }
    return hash
}

@OptIn(UnstableApi::class)
class RemoteControlPlayer(
    looper: Looper,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeek: (Long) -> Unit
) : SimpleBasePlayer(looper) {

    private var _isPlaying = false
    private var _title = ""
    private var _artist = ""
    private var _album = ""
    private var _durationMs = 0L
    private var _positionMs = 0L
    private var _artworkData: ByteArray? = null
    private var _queueEntries: List<QueueEntry> = emptyList()

    fun updateState(
        isPlaying: Boolean,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        positionMs: Long,
        artworkData: ByteArray? = null
    ) {
        _isPlaying = isPlaying
        _title = title
        _artist = artist
        _album = album
        _durationMs = durationMs
        _positionMs = positionMs
        if (artworkData != null) _artworkData = artworkData
        invalidateState()
    }

    fun setArtwork(data: ByteArray) {
        _artworkData = data
        invalidateState()
    }

    fun updateQueue(entries: List<QueueEntry>) {
        _queueEntries = entries
        invalidateState()
    }

    override fun getState(): State {
        val currentMetadataBuilder = MediaMetadata.Builder()
            .setTitle(_title)
            .setArtist(_artist)
            .setAlbumTitle(_album)
        _artworkData?.let {
            currentMetadataBuilder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        val currentMetadata = currentMetadataBuilder.build()

        val playlist = if (_queueEntries.isNotEmpty()) {
            ImmutableList.copyOf(_queueEntries.mapIndexed { index, entry ->
                val meta = if (index == 0) currentMetadata else {
                    MediaMetadata.Builder()
                        .setTitle(entry.title)
                        .setArtist(entry.artist.ifEmpty { null })
                        .setAlbumTitle(entry.album.ifEmpty { null })
                        .build()
                }
                val item = MediaItem.Builder().setMediaMetadata(meta).build()
                MediaItemData.Builder(entry.id)
                    .setMediaItem(item)
                    .setMediaMetadata(meta)
                    .setDurationUs(if (entry.durationMs > 0) entry.durationMs * 1000 else C_TIME_UNSET)
                    .build()
            })
        } else if (_title.isNotEmpty()) {
            val item = MediaItem.Builder().setMediaMetadata(currentMetadata).build()
            val single = MediaItemData.Builder(item.hashCode().toLong())
                .setMediaItem(item)
                .setMediaMetadata(currentMetadata)
                .setDurationUs(if (_durationMs > 0) _durationMs * 1000 else C_TIME_UNSET)
                .build()
            ImmutableList.of(single)
        } else {
            ImmutableList.of()
        }

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        COMMAND_PLAY_PAUSE,
                        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_METADATA,
                        COMMAND_GET_CURRENT_MEDIA_ITEM,
                        COMMAND_GET_TIMELINE
                    )
                    .build()
            )
            .setPlayWhenReady(_isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (_title.isNotEmpty()) STATE_READY else STATE_IDLE)
            .setContentPositionMs(if (_positionMs > 0) _positionMs else 0)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) onPlay() else onPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onNext()
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onPrevious()
            else -> onSeek(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val C_TIME_UNSET = Long.MIN_VALUE + 1
    }
}
