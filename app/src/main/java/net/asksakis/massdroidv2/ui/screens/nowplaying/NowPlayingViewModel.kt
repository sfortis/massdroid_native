package net.asksakis.massdroidv2.ui.screens.nowplaying

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "NowPlayingVM"

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val musicRepository: MusicRepository,
    private val smartListeningRepository: SmartListeningRepository
) : ViewModel() {

    val selectedPlayer = playerRepository.selectedPlayer
    val queueState = playerRepository.queueState
    val elapsedTime = playerRepository.elapsedTime
    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    init {
        viewModelScope.launch {
            smartListeningRepository.blockedArtistUris.collect { _blockedArtistUris.value = it }
        }
    }

    fun playPause() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.playPause(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "playPause failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun next() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.next(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "next failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun previous() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.previous(player.playerId)
            } catch (e: Exception) {
                Log.w(TAG, "previous failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun seek(position: Double) {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.seek(player.playerId, position)
            } catch (e: Exception) {
                Log.w(TAG, "seek failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun setVolume(level: Int) {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.setVolume(player.playerId, level)
            } catch (e: Exception) {
                Log.w(TAG, "setVolume failed: ${e.message}")
            }
        }
    }

    fun toggleMute() {
        val player = selectedPlayer.value ?: return
        viewModelScope.launch {
            try {
                playerRepository.toggleMute(player.playerId, !player.volumeMuted)
            } catch (e: Exception) {
                Log.w(TAG, "toggleMute failed: ${e.message}")
            }
        }
    }

    fun toggleShuffle() {
        val queue = queueState.value ?: return
        viewModelScope.launch {
            try {
                musicRepository.shuffleQueue(queue.queueId, !queue.shuffleEnabled)
            } catch (e: Exception) {
                Log.w(TAG, "toggleShuffle failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun toggleFavorite() {
        val track = queueState.value?.currentItem?.track ?: return
        val newFavorite = !track.favorite
        viewModelScope.launch {
            try {
                // Optimistic UI update so heart icon responds instantly.
                playerRepository.updateCurrentTrackFavorite(newFavorite)
                musicRepository.setFavorite(track.uri, MediaType.TRACK, track.itemId, newFavorite)
                val artists = trackArtists(track.artistItemId, track.artistUri, track.artistNames)
                if (newFavorite) {
                    smartListeningRepository.recordLike(track, artists)
                } else {
                    smartListeningRepository.recordUnlike(track, artists)
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
                // Roll back only if we're still on the same track.
                if (queueState.value?.currentItem?.track?.uri == track.uri) {
                    playerRepository.updateCurrentTrackFavorite(track.favorite)
                }
                _error.tryEmit("Failed to update favorite")
            }
        }
    }

    fun toggleCurrentArtistBlocked() {
        val track = queueState.value?.currentItem?.track ?: return
        val artistUri = MediaIdentity.canonicalArtistKey(track.artistItemId, track.artistUri) ?: return
        val artistName = track.artistNames
            .split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { "Artist" }

        viewModelScope.launch {
            val wasBlocked = _blockedArtistUris.value.contains(artistUri)
            val optimistic = if (wasBlocked) {
                _blockedArtistUris.value - artistUri
            } else {
                _blockedArtistUris.value + artistUri
            }
            _blockedArtistUris.value = optimistic
            try {
                smartListeningRepository.setArtistBlocked(
                    artistUri = artistUri,
                    artistName = artistName,
                    blocked = !wasBlocked
                )
            } catch (e: Exception) {
                Log.w(TAG, "toggleCurrentArtistBlocked failed: ${e.message}")
                _blockedArtistUris.value = if (wasBlocked) {
                    _blockedArtistUris.value + artistUri
                } else {
                    _blockedArtistUris.value - artistUri
                }
                _error.tryEmit("Failed to update artist filter")
            }
        }
    }

    private fun trackArtists(artistItemId: String?, artistUri: String?, artistNames: String): List<Pair<String, String>> {
        val uri = MediaIdentity.canonicalArtistKey(itemId = artistItemId, uri = artistUri) ?: return emptyList()
        val name = artistNames
            .split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { "Artist" }
        return listOf(uri to name)
    }

    fun cycleRepeat() {
        val queue = queueState.value ?: return
        val nextMode = when (queue.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        viewModelScope.launch {
            try {
                musicRepository.repeatQueue(queue.queueId, nextMode)
            } catch (e: Exception) {
                Log.w(TAG, "cycleRepeat failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }
}
