package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import net.asksakis.massdroidv2.domain.model.GroupProviderOption
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.QueueItemsSnapshot
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

data class PlaybackPosition(
    val queueId: String?,
    val queueItemId: String?,
    val trackUri: String?,
    val position: Double
)

data class PlayerSelectionLock(
    val playerId: String,
    val reason: String
)

interface PlayerRepository {
    val players: StateFlow<List<Player>>
    val selectedPlayer: StateFlow<Player?>
    val selectionLock: StateFlow<PlayerSelectionLock?>
    val queueState: StateFlow<QueueState?>
    val elapsedTime: StateFlow<Double>
    val playbackPosition: StateFlow<PlaybackPosition?>

    /**
     * Emits the same PlaybackPosition that lands in [playbackPosition], but only
     * for **server-confirmed** events (QUEUE_TIME_UPDATED) — not for the local
     * 500ms interpolation ticker. Subscribers that need to know "the server just
     * told us the true position" (e.g. AndroidAutoController, to push an
     * invalidate to the AA host) should observe this flow instead of polling
     * the StateFlow.
     */
    val serverPositionUpdates: SharedFlow<PlaybackPosition>

    /** Emits immediately when a playback command is issued, before server round-trip. */
    val playbackIntent: SharedFlow<Boolean>

    /** Emits when an action requires a selected player but none is available. */
    val noPlayerSelectedEvent: SharedFlow<Unit>

    /**
     * Emits when a play/next command is rejected because the queue has no more
     * tracks (MA error code 11). The server keeps the last track as current_media
     * for display, so the UI shows a track + play button that silently fails;
     * this lets the UI surface a "nothing left to play" notice instead.
     */
    val queueEndedEvent: SharedFlow<Unit>

    /** Emits the queue ID whenever QUEUE_ITEMS_UPDATED or QUEUE_UPDATED fires for the selected player. */
    val queueItemsChanged: SharedFlow<String>

    /**
     * Canonical snapshot of the items in the selected queue. Updated
     * by a single debounced fetcher inside the data layer so multiple
     * consumers (AA, NowPlaying, Queue screen, blocked-artist cleanup)
     * share one `player_queues/items` RPC per queue change instead of
     * each issuing their own. `null` means we have not fetched yet for
     * the current queue (or the queue cleared); subscribers should
     * render a loading/empty state.
     */
    val queueItems: StateFlow<QueueItemsSnapshot?>

    /**
     * Force an immediate refresh of [queueItems] for [queueId],
     * bypassing the debounce. Used by error-recovery paths in the UI
     * (e.g. a failed queue move that needs an authoritative re-sync).
     * Concurrent callers share the in-flight RPC.
     */
    suspend fun refreshQueueItems(queueId: String)

    /**
     * Pull the authoritative active-queue state (current item + position) for the
     * selected player from the server. Used so a surface like the full player can
     * fetch fresh state on open instead of waiting for the next (sparse) push
     * event. No-op when no player is selected.
     */
    suspend fun refreshActiveQueue()

    /** Emits explicit discontinuities like next/previous/seek so buffered local playback can reset policy. */
    val discontinuityCommands: SharedFlow<PlayerDiscontinuityCommand>

    /**
     * Transient on-screen volume display. The phone's system volume bar covers
     * STREAM_MUSIC adjustments for local playback, but remote MA players have
     * no system surface — when the user adjusts their volume via hardware keys
     * or AA controls, the app shows its own overlay populated from this flow.
     * The repository auto-clears the value ~2.5 s after the latest update so
     * the overlay fades out by itself.
     */
    val volumeOsd: StateFlow<VolumeOsdState?>

    /** Show or refresh the volume overlay. Resets the auto-hide timer. */
    fun showVolumeOsd(playerName: String, volume: Int, isGroup: Boolean = false, isMuted: Boolean = false)

    data class VolumeOsdState(
        val playerName: String,
        val volume: Int,
        val isGroup: Boolean,
        val isMuted: Boolean,
        /**
         * Monotonically increasing token. The UI reads this to retrigger the
         * fade-in animation when rapid key presses arrive (otherwise
         * AnimatedVisibility would stay visible without a new "enter" cue).
         */
        val token: Long,
    )

    enum class QueueFilterMode {
        NORMAL,
        SMART_GENERATED,
        RADIO_SMART
    }

    fun requireSelectedPlayerId(): String?
    suspend fun refreshPlayers()
    fun selectPlayer(playerId: String)
    fun setSelectionLock(lock: PlayerSelectionLock?)

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
    /**
     * Synchronous optimistic volume update. Suppresses server echoes until the
     * server confirms the value (prevents UI flicker). [holdMs] overrides the
     * default confirmation cap (0 = default). [sticky] keeps preserving the value
     * until the cap even AFTER a matching confirm — used for the car-audio restore,
     * where the player is idle and a late/out-of-order stale server volume must not
     * bounce the slider back to the pinned 100%.
     */
    fun applyVolumeOptimistic(playerId: String, volumeLevel: Int, holdMs: Long = 0L, sticky: Boolean = false)
    suspend fun setVolume(playerId: String, volumeLevel: Int)
    suspend fun setGroupVolume(parentId: String, volume: Int)
    fun updateGroupMemberOffset(parentId: String, memberId: String, volume: Int)
    suspend fun toggleMute(playerId: String, muted: Boolean)
    suspend fun setPower(playerId: String, powered: Boolean)
    suspend fun updatePlayerIcon(playerId: String, icon: String)
    suspend fun renamePlayer(playerId: String, name: String)
    suspend fun getPlayerConfig(playerId: String): PlayerConfig?
    suspend fun savePlayerConfig(playerId: String, values: Map<String, Any>)
    fun updateCurrentTrackFavorite(favorite: Boolean)
    fun updateCurrentTrackLyrics(plainLyrics: String?, lrcLyrics: String?)
    fun setQueueFilterMode(playerId: String, mode: QueueFilterMode)
    fun notifyQueueReplacement(queueId: String)

    /** Returns player-provider instances that expose the CREATE_GROUP_PLAYER feature. */
    suspend fun getGroupCapableProviders(): List<GroupProviderOption>
    suspend fun createGroupPlayer(provider: String, name: String, memberIds: List<String>)
    suspend fun setGroupMembers(targetPlayerId: String, addIds: List<String>? = null, removeIds: List<String>? = null)
    suspend fun removeGroupPlayer(playerId: String)
    /** Break any protocol-level sync this player is currently part of. */
    suspend fun ungroupPlayer(playerId: String)
    suspend fun ungroupPlayers(playerIds: List<String>)
}
