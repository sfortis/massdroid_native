package net.asksakis.massdroidv2.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.ui.MainActivity
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "PlaybackSvc"
    }

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var sendspinManager: SendspinManager

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var remotePlayer: RemoteControlPlayer? = null
    private var sendspinActive = false

    override fun onCreate() {
        super.onCreate()
        remotePlayer = createRemotePlayer()
        createMediaSession()
        observePlayerState()
        observeSendspinState()
    }

    private fun createRemotePlayer(): RemoteControlPlayer {
        return RemoteControlPlayer(
            Looper.getMainLooper(),
            onPlay = {
                Log.d(TAG, "RemotePlayer onPlay")
                val id = playerRepository.selectedPlayer.value?.playerId ?: return@RemoteControlPlayer
                scope.launch { playerRepository.play(id) }
            },
            onPause = {
                Log.d(TAG, "RemotePlayer onPause")
                val id = playerRepository.selectedPlayer.value?.playerId ?: return@RemoteControlPlayer
                scope.launch { playerRepository.pause(id) }
            },
            onNext = {
                val id = playerRepository.selectedPlayer.value?.playerId ?: return@RemoteControlPlayer
                scope.launch { playerRepository.next(id) }
            },
            onPrevious = {
                val id = playerRepository.selectedPlayer.value?.playerId ?: return@RemoteControlPlayer
                scope.launch { playerRepository.previous(id) }
            },
            onSeek = { positionMs ->
                val id = playerRepository.selectedPlayer.value?.playerId ?: return@RemoteControlPlayer
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
        mediaSession = MediaSession.Builder(this, remotePlayer!!)
            .setSessionActivity(pendingIntent)
            .build()
        Log.d(TAG, "MediaSession created")
    }

    private fun releaseMediaSession() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        remotePlayer = null
        Log.d(TAG, "MediaSession released (sendspin active)")
    }

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

                remotePlayer?.updateState(
                    isPlaying = player.state == PlaybackState.PLAYING,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = (duration * 1000).toLong(),
                    positionMs = (elapsed * 1000).toLong()
                )
            }
        }
    }

    private fun observeSendspinState() {
        scope.launch {
            sendspinManager.enabled.collect { active ->
                if (active && !sendspinActive) {
                    sendspinActive = true
                    releaseMediaSession()
                } else if (!active && sendspinActive) {
                    sendspinActive = false
                    remotePlayer = createRemotePlayer()
                    createMediaSession()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
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

    fun updateState(
        isPlaying: Boolean,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        positionMs: Long
    ) {
        _isPlaying = isPlaying
        _title = title
        _artist = artist
        _album = album
        _durationMs = durationMs
        _positionMs = positionMs
        invalidateState()
    }

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle(_title)
            .setArtist(_artist)
            .setAlbumTitle(_album)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaMetadata(metadata)
            .build()

        val playlistItem = MediaItemData.Builder(mediaItem.hashCode().toLong())
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(if (_durationMs > 0) _durationMs * 1000 else C_TIME_UNSET)
            .build()

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
            .setPlaylist(if (_title.isNotEmpty()) ImmutableList.of(playlistItem) else ImmutableList.of())
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
