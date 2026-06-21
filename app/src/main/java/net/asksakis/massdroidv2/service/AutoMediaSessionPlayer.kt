package net.asksakis.massdroidv2.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.BitmapLoader
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import net.asksakis.massdroidv2.auto.AaMetrics
import net.asksakis.massdroidv2.domain.model.Chapter
import net.asksakis.massdroidv2.domain.model.displayName

/**
 * Synchronous BitmapLoader that decodes artwork immediately.
 * Avoids the async decode race in Media3's default CacheBitmapLoader
 * which uses reference equality on cloned byte arrays (cache always misses).
 */
@OptIn(UnstableApi::class)
class SyncBitmapLoader : BitmapLoader {
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
    val durationMs: Long,
    val artworkUri: Uri? = null,
)

fun String.toStableLongId(): Long {
    var hash = 1125899906842597L
    for (char in this) {
        hash = 31L * hash + char.code.toLong()
    }
    return hash
}

/**
 * Immutable description of the current playback state pushed to AA. Position is intentionally not
 * part of the snapshot; it is kept separately so position-only updates do not rebuild the queue.
 */
data class AutoPlaybackSnapshot(
    val isPlaying: Boolean,
    val audioFlowing: Boolean,
    val queueItemId: String?,
    val trackUri: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val currentIndex: Int,
    val artworkData: ByteArray?,
    val volumeLevel: Int,
    val isMuted: Boolean,
    val isRemotePlayback: Boolean,
    // Non-empty only for the current audiobook: chapters are seek targets within
    // the single playable item, surfaced to AA as the queue/timeline so the user
    // can see and jump between them.
    val chapters: List<Chapter> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AutoPlaybackSnapshot) return false
        return isPlaying == other.isPlaying &&
            audioFlowing == other.audioFlowing &&
            queueItemId == other.queueItemId &&
            trackUri == other.trackUri &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            durationMs == other.durationMs &&
            currentIndex == other.currentIndex &&
            volumeLevel == other.volumeLevel &&
            isMuted == other.isMuted &&
            isRemotePlayback == other.isRemotePlayback &&
            chapters == other.chapters &&
            ((artworkData == null && other.artworkData == null) ||
                (artworkData != null && other.artworkData != null &&
                    artworkData.contentEquals(other.artworkData)))
    }

    override fun hashCode(): Int {
        var r = isPlaying.hashCode()
        r = 31 * r + audioFlowing.hashCode()
        r = 31 * r + (queueItemId?.hashCode() ?: 0)
        r = 31 * r + (trackUri?.hashCode() ?: 0)
        r = 31 * r + title.hashCode()
        r = 31 * r + artist.hashCode()
        r = 31 * r + album.hashCode()
        r = 31 * r + durationMs.hashCode()
        r = 31 * r + currentIndex
        r = 31 * r + (artworkData?.size ?: 0)
        r = 31 * r + volumeLevel
        r = 31 * r + isMuted.hashCode()
        r = 31 * r + isRemotePlayback.hashCode()
        r = 31 * r + chapters.hashCode()
        return r
    }

    companion object {
        val Empty = AutoPlaybackSnapshot(
            isPlaying = false, audioFlowing = false, queueItemId = null, trackUri = null,
            title = "", artist = "", album = "",
            durationMs = 0L, currentIndex = 0, artworkData = null,
            volumeLevel = 0, isMuted = false, isRemotePlayback = true,
            chapters = emptyList(),
        )
    }
}

data class AutoQueueSnapshot(
    val queueId: String?,
    val entries: List<QueueEntry>,
) {
    companion object {
        val Empty = AutoQueueSnapshot(queueId = null, entries = emptyList())
    }
}

@OptIn(UnstableApi::class)
class RemoteControlPlayer(
    looper: Looper,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeekToMediaItem: (Int) -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onVolumeUp: () -> Unit = {},
    private val onVolumeDown: () -> Unit = {},
    private val onVolumeSet: (Int) -> Unit = {}
) : SimpleBasePlayer(looper) {

    private var playback: AutoPlaybackSnapshot = AutoPlaybackSnapshot.Empty
    private var queue: AutoQueueSnapshot = AutoQueueSnapshot.Empty
    private var positionMs: Long = 0L
    private var playlist: ImmutableList<MediaItemData> = ImmutableList.of()

    fun updatePlayback(snapshot: AutoPlaybackSnapshot, positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0L)
        if (snapshot == playback) return
        val rebuildPlaylist = snapshot.playlistContentDiffersFrom(playback)
        playback = snapshot
        if (rebuildPlaylist) playlist = buildPlaylist()
        AaMetrics.onUpdateState()
        AaMetrics.onInvalidate()
        invalidateState()
    }

    fun updateQueue(snapshot: AutoQueueSnapshot) {
        if (snapshot == queue) return
        queue = snapshot
        playlist = buildPlaylist()
        AaMetrics.onInvalidate()
        invalidateState()
    }

    /** One-shot position publish for explicit seeks while periodic Media3 updates are disabled. */
    fun publishPosition(positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0L)
        AaMetrics.onInvalidate()
        invalidateState()
    }

    /** Keep AA's internal clock aligned with MA elapsed without notifying controllers. */
    fun syncPosition(positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0L)
    }

    /**
     * For an audiobook the AA timeline is the chapter list, so "current item" is
     * the chapter containing the whole-book position (positionMs), not the MA
     * queue index. Falls back to the queue index when there are no chapters.
     */
    private fun activeChapterIndex(): Int {
        val chapters = playback.chapters
        if (chapters.isEmpty()) return playback.currentIndex
        val posSec = positionMs / 1000.0
        return chapters.indexOfLast { posSec + CHAPTER_EPSILON_S >= it.start }.coerceAtLeast(0)
    }

    override fun getState(): State {
        val chaptered = playback.chapters.isNotEmpty()
        val rawIndex = if (chaptered) activeChapterIndex() else playback.currentIndex
        val effectiveIndex = rawIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        // AA renders the progress bar as position-within-current-item. With a
        // chapter timeline the current item is one chapter, so report the
        // position relative to that chapter's start (the per-item duration in
        // buildPlaylist is the chapter length); otherwise the whole-book position.
        val contentPosition = if (chaptered) {
            val startMs = ((playback.chapters.getOrNull(effectiveIndex)?.start ?: 0.0) * 1000).toLong()
            (positionMs - startMs).coerceAtLeast(0L)
        } else {
            positionMs
        }
        val playbackType = if (playback.isRemotePlayback) {
            DeviceInfo.PLAYBACK_TYPE_REMOTE
        } else {
            DeviceInfo.PLAYBACK_TYPE_LOCAL
        }

        val commandsBuilder = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_MEDIA_ITEM,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_GET_METADATA,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_SET_MEDIA_ITEM
            )
        if (playback.isRemotePlayback) {
            commandsBuilder.addAll(
                COMMAND_GET_DEVICE_VOLUME,
                COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS
            )
        }

        AaMetrics.onGetState(currentIndex = effectiveIndex, playlistSize = playlist.size)
        AaMetrics.traceGetState(
            currentIndex = effectiveIndex,
            playlistSize = playlist.size,
            contentPositionMs = positionMs,
            isPlaying = playback.isPlaying
        )
        // State semantics for AA:
        //   IDLE     = nothing to play (no title yet).
        //   BUFFERING = user wants play but audio isn't actually flowing. The AA
        //              host stops the 1x interpolation of the progress bar and
        //              renders a loading indicator. playWhenReady stays true so
        //              the play/pause toggle still reads as "playing".
        //   READY    = nominal playing or paused state. AA interpolates the
        //              position at 1x while playWhenReady is true.
        //
        // This lets seek transitions, track changes, Sendspin re-syncs, and
        // brief network blips all show as a momentary "loading" instead of
        // letting the AA bar march forward against silence.
        val effectiveState = when {
            playback.title.isEmpty() -> STATE_IDLE
            playback.isPlaying && !playback.audioFlowing -> STATE_BUFFERING
            else -> STATE_READY
        }
        return State.Builder()
            .setAvailableCommands(commandsBuilder.build())
            .setPlayWhenReady(playback.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(effectiveState)
            .setContentPositionMs(contentPosition)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(effectiveIndex)
            // 1:1 with the MA player volume (0..100): the device-volume scale IS the
            // MA scale, so every value round-trips exactly with no integer-division
            // rounding. (The old 0..20 scale truncated any non-multiple-of-5 MA value.)
            .setDeviceInfo(DeviceInfo.Builder(playbackType).setMinVolume(0).setMaxVolume(MAX_VOLUME).build())
            .setDeviceVolume(playback.volumeLevel)
            .setIsDeviceMuted(playback.isMuted)
            .build()
    }

    private fun buildPlaylist(): ImmutableList<MediaItemData> {
        AaMetrics.onPlaylistRebuild()
        // AA host renders the artist field as the subtitle line and ignores MediaMetadata.subtitle,
        // so combine "Artist • Album" inside artist for the Now Playing line.
        val currentArtistLine = artistLine(playback.artist, playback.album)
        val currentMetadata = MediaMetadata.Builder()
            .setTitle(playback.title)
            .setArtist(currentArtistLine)
            .setAlbumTitle(playback.album)
            .also { b ->
                playback.artworkData?.let { b.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()

        // Audiobook: the timeline IS the chapter list (one playable item, many
        // seek targets). Each chapter is a row with its own length so the AA bar
        // scales per chapter (getState reports chapter-relative position). The
        // book cover (shared byte[]) rides every row.
        val chapters = playback.chapters
        if (chapters.isNotEmpty()) {
            val bookDurationMs = playback.durationMs
            return ImmutableList.copyOf(chapters.mapIndexed { i, ch ->
                val startMs = (ch.start * 1000).toLong()
                val endMs = ch.end?.let { (it * 1000).toLong() }
                    ?: chapters.getOrNull(i + 1)?.let { (it.start * 1000).toLong() }
                    ?: bookDurationMs
                val durMs = (endMs - startMs).coerceAtLeast(0L)
                val title = ch.displayName(i)
                val meta = MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(playback.title.ifEmpty { null })
                    .also { b -> playback.artworkData?.let { b.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                    .build()
                val id = "${playback.trackUri.orEmpty()}#ch$i".toStableLongId()
                val item = MediaItem.Builder().setMediaId(id.toString()).setMediaMetadata(meta).build()
                MediaItemData.Builder(id)
                    .setMediaItem(item)
                    .setMediaMetadata(meta)
                    .setDurationUs(if (durMs > 0) durMs * 1000 else C_TIME_UNSET)
                    .build()
            })
        }

        val entries = queue.entries
        // The now-playing metadata (currentMetadata) is the single source of truth for
        // the current track and comes from the playback snapshot. The playlist entries
        // come from a separate queueItems snapshot, so playback.currentIndex and the
        // entries list can momentarily diverge during queue churn. Clamp activeIdx the
        // same way getState() clamps currentMediaItemIndex, so the item the car reads as
        // "current" always carries the live title instead of a stale entry's metadata.
        val activeIdx = playback.currentIndex.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        if (entries.isNotEmpty()) {
            return ImmutableList.copyOf(entries.mapIndexed { index, entry ->
                val meta = if (index == activeIdx) currentMetadata else {
                    MediaMetadata.Builder()
                        .setTitle(entry.title)
                        .setArtist(artistLine(entry.artist, entry.album))
                        .setAlbumTitle(entry.album.ifEmpty { null })
                        .setArtworkUri(entry.artworkUri)
                        .build()
                }
                val durMs = if (index == activeIdx && playback.durationMs > 0) {
                    playback.durationMs
                } else {
                    entry.durationMs
                }
                val item = MediaItem.Builder()
                    .setMediaId(entry.id.toString())
                    .setMediaMetadata(meta)
                    .build()
                MediaItemData.Builder(entry.id)
                    .setMediaItem(item)
                    .setMediaMetadata(meta)
                    .setDurationUs(if (durMs > 0) durMs * 1000 else C_TIME_UNSET)
                    .build()
            })
        }
        if (playback.title.isNotEmpty()) {
            val singleId = playback.stableSingleItemId()
            val item = MediaItem.Builder()
                .setMediaId(singleId.toString())
                .setMediaMetadata(currentMetadata)
                .build()
            return ImmutableList.of(
                MediaItemData.Builder(singleId)
                    .setMediaItem(item)
                    .setMediaMetadata(currentMetadata)
                    .setDurationUs(if (playback.durationMs > 0) playback.durationMs * 1000 else C_TIME_UNSET)
                    .build()
            )
        }
        return ImmutableList.of()
    }

    private fun AutoPlaybackSnapshot.playlistContentDiffersFrom(other: AutoPlaybackSnapshot): Boolean {
        return trackUri != other.trackUri ||
            queueItemId != other.queueItemId ||
            title != other.title ||
            artist != other.artist ||
            album != other.album ||
            durationMs != other.durationMs ||
            currentIndex != other.currentIndex ||
            chapters != other.chapters ||
            !artworkData.sameContentAs(other.artworkData)
    }

    private fun ByteArray?.sameContentAs(other: ByteArray?): Boolean = when {
        this === other -> true
        this == null || other == null -> false
        else -> contentEquals(other)
    }

    private fun AutoPlaybackSnapshot.stableSingleItemId(): Long {
        val key = listOf(trackUri.orEmpty(), title, artist, album, durationMs.toString()).joinToString("|")
        return key.toStableLongId()
    }

    /**
     * Compose the subtitle line shown by the AA host. The host renders MediaMetadata.artist as
     * the second-line label and ignores MediaMetadata.subtitle, so we pack "Artist • Album"
     * into artist itself. Returns null when both fields are empty so the line stays clean.
     */
    private fun artistLine(artist: String, album: String): String? {
        val parts = listOfNotNull(
            artist.takeIf { it.isNotBlank() },
            album.takeIf { it.isNotBlank() }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
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
            COMMAND_SEEK_TO_MEDIA_ITEM -> onSeekToMediaItem(mediaItemIndex)
            else -> onSeek(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        Log.d("RemoteControlPlayer", "handleIncreaseDeviceVolume flags=$flags")
        onVolumeUp()
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        Log.d("RemoteControlPlayer", "handleDecreaseDeviceVolume flags=$flags")
        onVolumeDown()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(volume: Int, flags: Int): ListenableFuture<*> {
        Log.d("RemoteControlPlayer", "handleSetDeviceVolume volume=$volume flags=$flags")
        val maVolume = volume.coerceIn(0, MAX_VOLUME)
        onVolumeSet(maVolume)
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val C_TIME_UNSET = Long.MIN_VALUE + 1
        internal const val MAX_VOLUME = 100
        // Tolerance so a position sampled at a chapter boundary resolves to that
        // chapter, not the one before it.
        private const val CHAPTER_EPSILON_S = 0.001
    }
}
