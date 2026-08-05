package net.asksakis.massdroidv2.domain.player

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of what a volume step does to the selected player.
 *
 * There are two ways a rocker press reaches this app and neither can be
 * removed: with the app in front the key arrives at `MainActivity`'s
 * `dispatchKeyEvent`, and with it in the background (or on the lock screen, or
 * in the car) an active REMOTE-volume MediaSession captures the key system-wide
 * and it surfaces as `RemoteControlPlayer.handleIncreaseDeviceVolume`. Which
 * one runs is not the app's choice.
 *
 * They used to be two independent implementations, and the differences were
 * only visible on a device. Measured on 2026-08-01: the session path had no
 * pacing at all, so holding the key sent 18 `group_volume` commands in 700 ms
 * and took a group from 36 to 0 (and left its only member muted), while the
 * activity path paced them correctly. Same button, same second, different code.
 * Everything a step does now lives here so there is nothing left to diverge.
 */
@Singleton
class VolumeKeyController internal constructor(
    private val playerRepository: PlayerRepository,
    /**
     * Where the sends and the trailing flush run. Injected rather than created
     * here so a test can drive the flush with virtual time instead of waiting
     * out a real 120 ms.
     */
    private val scope: CoroutineScope,
    /**
     * Monotonic milliseconds. Injected for the same reason: under
     * `unitTests.isReturnDefaultValues` the real `SystemClock.uptimeMillis()`
     * answers 0 forever, which would let a test pass while measuring nothing.
     */
    private val now: () -> Long,
) {
    @Inject
    constructor(playerRepository: PlayerRepository) : this(
        playerRepository,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        SystemClock::uptimeMillis,
    )

    // Touched only from the main thread: both entry points are main-thread
    // callbacks and the coroutines below are Main.immediate.
    private var pending: PendingVolume? = null
    // Keyed by player: conflation must never cross players. A single slot meant
    // that switching player while a request was in flight overwrote the level the
    // previous player was still waiting to send, and its trailing flush had
    // already been cancelled - so that level was lost with the screen still
    // showing it. Found in review.
    private val queued = LinkedHashMap<String, PendingVolume>()
    private var sendJob: Job? = null
    private val pacer = VolumeSendPacer()
    private var trailingFlush: Job? = null

    /**
     * One rocker step on the selected player. Returns the resulting level, or
     * null when there is no player to control.
     */
    fun step(up: Boolean): Int? {
        val player = playerRepository.selectedPlayer.value ?: return null
        val isGroup = isGroupPlayer(player)
        val delta = if (up) ROCKER_VOLUME_STEP else -ROCKER_VOLUME_STEP
        // Turning the volume up on something silent has to make it audible.
        // A group reports volume_muted=null while a muted member keeps it
        // silent, so the level climbs on screen with nothing coming out; that
        // is exactly what a listener reported, on a group whose one member sat
        // muted at 0.
        if (up) unmuteSilentMembers(player, isGroup)
        return apply(player, isGroup, remoteVolumeStep(player, isGroup, delta))
    }

    /** An absolute level, from the Android Auto slider or a MediaSession client. */
    fun setLevel(level: Int): Int? {
        val player = playerRepository.selectedPlayer.value ?: return null
        val isGroup = isGroupPlayer(player)
        if (level > 0) unmuteSilentMembers(player, isGroup)
        return apply(player, isGroup, level.coerceIn(0, MAX_PLAYER_VOLUME))
    }

    /**
     * Push whatever the throttle is still holding.
     *
     * Called on a key release. The trailing timer below would land the same
     * value anyway, but a release is a better signal than a timeout: it makes
     * the final level reach the server as soon as the user lets go.
     */
    fun flush() {
        // Carries its own isGroup: recomputing it here read from the player list,
        // which silently answered "not a group" whenever the player was missing
        // from it, and a group sent `volume_set` instead of `group_volume` - a
        // no-op on a group parent, so the final level of a hold vanished.
        val held = pending ?: return
        send(held.playerId, held.isGroup, held.level)
    }

    private fun apply(player: Player, isGroup: Boolean, level: Int): Int {
        // The optimistic write is what makes the next press compute from the
        // level we just chose rather than from a server value that has not
        // caught up yet, so a held key ramps smoothly instead of stuttering.
        playerRepository.applyVolumeOptimistic(player.playerId, level)
        playerRepository.showVolumeOsd(
            playerName = player.displayName,
            volume = level,
            isGroup = isGroup,
            isMuted = false,
        )
        // A held level belongs to the player it was chosen for. Overwriting it
        // when the selection changes mid-hold would drop that player's last
        // step without ever sending it, so it goes out first.
        pending?.takeIf { it.playerId != player.playerId }?.let { send(it.playerId, it.isGroup, it.level) }
        pending = PendingVolume(player.playerId, level, isGroup)

        if (pacer.tryAcquire(now())) {
            send(player.playerId, isGroup, level)
        }
        scheduleTrailingFlush()
        return level
    }

    /**
     * Guarantees the last level lands even when the final steps were all
     * throttled away. Without it a hold that ends inside a throttle window
     * leaves the server one step behind what the screen shows.
     */
    private fun scheduleTrailingFlush() {
        trailingFlush?.cancel()
        trailingFlush = scope.launch {
            delay(VOLUME_REPEAT_THROTTLE_MS)
            flush()
        }
    }

    /**
     * Hands a level to the server, one at a time and newest-wins.
     *
     * Every command waits for a reply, so firing each in its own coroutine put
     * several in flight at once during a hold and let the server apply them in
     * whatever order the replies happened to land. Measured on a device: eight
     * commands went out descending to 30 and the speaker settled on 36, a value
     * from the middle of the sequence, because a later write was overtaken by an
     * earlier one.
     *
     * So at most one is in flight. A level chosen while one is running REPLACES
     * any level already waiting rather than joining a queue: the intermediate
     * steps of a hold are worth nothing once a newer one exists, and sending
     * them would only be more traffic for a server that is single-threaded.
     */
    private fun send(playerId: String, isGroup: Boolean, level: Int) {
        pacer.markSent(now())
        pending = null
        // Newest wins PER PLAYER: the intermediate steps of a hold are worthless
        // once a newer one exists, but another player's pending level is not.
        queued[playerId] = PendingVolume(playerId, level, isGroup)
        if (sendJob?.isActive == true) return
        sendJob = scope.launch {
            while (true) {
                val next = queued.keys.firstOrNull()?.let { queued.remove(it) } ?: break
                runCatching {
                    if (next.isGroup) {
                        playerRepository.setGroupVolume(next.playerId, next.level)
                    } else {
                        playerRepository.setVolume(next.playerId, next.level)
                    }
                }.onFailure { e ->
                    // An unreachable speaker makes MA time out (~30 s). That must
                    // not take the app with it, but it must not be silent either:
                    // without this line a rejected volume looked exactly like a
                    // speaker that ignores the buttons.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Volume push failed for ${next.playerId}: ${e.message}")
                }
            }
        }
    }

    /**
     * A group hands its volume to its members, so the mute that silences it can
     * live on any of them. Unmute whatever is actually muted, not the id the
     * key was aimed at.
     */
    private fun unmuteSilentMembers(player: Player, isGroup: Boolean) {
        val targets = if (isGroup) {
            val members = player.groupChilds.toSet()
            playerRepository.players.value.filter { it.playerId in members }
        } else {
            listOf(player)
        }
        val muted = targets.filter { it.volumeMuted }
        if (muted.isEmpty()) return
        scope.launch {
            for (target in muted) {
                runCatching { playerRepository.toggleMute(target.playerId, false) }
            }
        }
    }

    private fun isGroupPlayer(player: Player): Boolean =
        player.groupChilds.any { it != player.playerId }

    /** A level chosen but not yet sent, with everything needed to send it. */
    private data class PendingVolume(val playerId: String, val level: Int, val isGroup: Boolean)

    private companion object {
        const val TAG = "VolumeKeys"
    }
}

/**
 * Paces how often a stream of volume steps reaches the server.
 *
 * Held keys repeat about every 50 ms and every step is a server command. On a
 * real setup that meant 70 `volume_set` calls in 20 seconds to a Sonos over
 * UPnP, and 18 `group_volume` calls in 700 ms to a sync group - Music Assistant
 * runs a single asyncio loop, often on a Raspberry Pi. Only the network traffic
 * is paced: the on-screen level still moves on every step.
 *
 * Pure and separate from [VolumeKeyController] so the ratio can be pinned in a
 * test without a clock or a coroutine scope.
 */
internal class VolumeSendPacer(private val throttleMs: Long = VOLUME_REPEAT_THROTTLE_MS) {
    private var lastSentAt: Long? = null

    /** True when a step may go out now, recording it. The first one always may. */
    fun tryAcquire(nowMs: Long): Boolean {
        val last = lastSentAt
        if (last != null && nowMs - last < throttleMs) return false
        lastSentAt = nowMs
        return true
    }

    /** Records a send that bypassed the throttle, such as a trailing flush. */
    fun markSent(nowMs: Long) {
        lastSentAt = nowMs
    }
}

/**
 * Volume increment (in MA's 0-100 units) applied per hardware-rocker press,
 * wherever that press comes from. Single source of truth: the phone rocker and
 * the Android Auto rocker used to carry their own and drifted apart (2 vs 3).
 *
 * It must stay equal to `RemoteControlPlayer.VOLUME_SCALE` and divide 100
 * exactly. Android treats one press as one notch of the grid the MediaSession
 * advertises, so a step that does not match that grid makes the system volume
 * bar disagree with the actual volume - which is what a step of 3 against a
 * 0..100 grid did.
 */
const val ROCKER_VOLUME_STEP = 2
