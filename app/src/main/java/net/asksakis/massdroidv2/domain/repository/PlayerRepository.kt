package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.QueueState

data class PlayerDiscontinuityCommand(
    val playerId: String,
    val kind: Kind
) {
    enum class Kind {
        NEXT,
        PREVIOUS,
        SEEK
    }
}

interface PlayerRepository {
    val players: StateFlow<List<Player>>
    val selectedPlayer: StateFlow<Player?>
    val queueState: StateFlow<QueueState?>
    val elapsedTime: StateFlow<Double>

    /** Emits immediately when a playback command is issued, before server round-trip. */
    val playbackIntent: SharedFlow<Boolean>

    /** Emits when an action requires a selected player but none is available. */
    val noPlayerSelectedEvent: SharedFlow<Unit>

    /** Emits the queue ID whenever QUEUE_ITEMS_UPDATED or QUEUE_UPDATED fires for the selected player. */
    val queueItemsChanged: SharedFlow<String>

    /** Emits explicit discontinuities like next/previous/seek so buffered local playback can reset policy. */
    val discontinuityCommands: SharedFlow<PlayerDiscontinuityCommand>

    enum class QueueFilterMode {
        NORMAL,
        SMART_GENERATED,
        RADIO_SMART
    }

    fun requireSelectedPlayerId(): String?
    suspend fun refreshPlayers()
    fun selectPlayer(playerId: String)

    suspend fun play(playerId: String)
    suspend fun pause(playerId: String)
    suspend fun playPause(playerId: String)
    fun notifyPlaybackIntent(willPlay: Boolean)
    fun isArtistUriBlocked(artistUri: String): Boolean
    fun isArtistBlocked(artistName: String, artistUri: String): Boolean
    fun hasBlockedArtists(): Boolean
    suspend fun next(playerId: String)
    suspend fun previous(playerId: String)
    suspend fun seek(playerId: String, position: Double)
    /** Synchronous optimistic volume update + cooldown. Prevents UI flicker from server echoes. */
    fun applyVolumeOptimistic(playerId: String, volumeLevel: Int)
    suspend fun setVolume(playerId: String, volumeLevel: Int)
    suspend fun setGroupVolume(parentId: String, volume: Int)
    fun updateGroupMemberOffset(parentId: String, memberId: String, volume: Int)
    suspend fun toggleMute(playerId: String, muted: Boolean)
    suspend fun updatePlayerIcon(playerId: String, icon: String)
    suspend fun renamePlayer(playerId: String, name: String)
    suspend fun getPlayerConfig(playerId: String): PlayerConfig?
    suspend fun savePlayerConfig(playerId: String, values: Map<String, Any>)
    fun updateCurrentTrackFavorite(favorite: Boolean)
    fun updateCurrentTrackLyrics(plainLyrics: String?, lrcLyrics: String?)
    fun setQueueFilterMode(playerId: String, mode: QueueFilterMode)
    fun notifyQueueReplacement(queueId: String)

    suspend fun createGroupPlayer(name: String, memberIds: List<String>)
    suspend fun setGroupMembers(targetPlayerId: String, addIds: List<String>? = null, removeIds: List<String>? = null)
    suspend fun removeGroupPlayer(playerId: String)
}
