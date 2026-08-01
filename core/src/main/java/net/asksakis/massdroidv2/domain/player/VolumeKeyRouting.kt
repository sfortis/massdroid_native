package net.asksakis.massdroidv2.domain.player

import net.asksakis.massdroidv2.domain.model.Player

/**
 * Where a hardware volume key press should go.
 *
 * Extracted from the Activity so the decision can be tested: it depends on the
 * selected player, on whether that player is this phone's own Sendspin client,
 * and on group membership, and getting it wrong is invisible until someone
 * presses a button on a real device.
 */
sealed interface VolumeKeyTarget {
    /** Nothing selected: leave the key to the system. */
    data object System : VolumeKeyTarget

    /**
     * This phone is the output, so the OS stream is the real control and the
     * system's own volume UI should show. Mirroring to Music Assistant happens
     * from the resulting STREAM_MUSIC change, not from here.
     */
    data object LocalStream : VolumeKeyTarget

    /** A player elsewhere: send it a level. [isGroup] picks group vs player volume. */
    data class RemotePlayer(val playerId: String, val isGroup: Boolean) : VolumeKeyTarget
}

/**
 * Decide what a volume key controls.
 *
 * The phone's own Sendspin output is driven through the OS stream even when it
 * is only a MEMBER of a group: the keys then adjust this phone's contribution
 * rather than the whole group, which is what someone holding the phone expects.
 */
fun volumeKeyTarget(player: Player?, sendspinClientId: String?): VolumeKeyTarget {
    if (player == null) return VolumeKeyTarget.System
    if (sendspinClientId != null && player.playerId == sendspinClientId) {
        return VolumeKeyTarget.LocalStream
    }
    val isGroup = player.groupChilds.any { it != player.playerId }
    val localIsGroupMember = sendspinClientId != null &&
        isGroup &&
        player.groupChilds.any { it == sendspinClientId }
    if (localIsGroupMember) return VolumeKeyTarget.LocalStream
    return VolumeKeyTarget.RemotePlayer(player.playerId, isGroup)
}

/**
 * The level to send a remote player, from its current basis.
 *
 * A group parent reports its own [Player.volumeLevel] separately from the group
 * level, so a group press must move the group's.
 */
fun remoteVolumeStep(player: Player, isGroup: Boolean, delta: Int): Int {
    val basis = if (isGroup) player.groupVolume ?: player.volumeLevel else player.volumeLevel
    return (basis + delta).coerceIn(0, MAX_PLAYER_VOLUME)
}

private const val MAX_PLAYER_VOLUME = 100

/** What to do with one volume key event. */
enum class VolumeKeyStep {
    /** Move the level and push it to the server now. */
    SEND_NOW,

    /** Move the level, but only push if the throttle window has elapsed. */
    SEND_THROTTLED,

    /** This press was already counted on its DOWN edge; push the final level. */
    FLUSH_ONLY,

    /** Not a press we act on. */
    IGNORE
}

/**
 * Decide how to treat a volume key event.
 *
 * Both edges have to work, because which one arrives depends on the situation:
 * with the app in front, Android delivers ACTION_DOWN plus a repeat stream plus
 * ACTION_UP (measured: repeat counts 0..63 over a three-second hold); in other
 * states only ACTION_UP reaches the activity (measured: 47 UP, zero DOWN, while
 * the launcher was in front). Acting on one edge alone therefore either loses
 * press-and-hold or loses the key entirely.
 *
 * Acting on both naively would double every press, so [downTime] - identical for
 * the DOWN and UP of one physical press - identifies the press: an UP whose
 * press was already counted only flushes the final level.
 *
 * The repeats are throttled because they arrive about every 50 ms. Each one is a
 * server command, and on a real setup that meant 70 volume_set calls in 20
 * seconds to a Sonos over UPnP; Music Assistant runs one asyncio loop, often on
 * a Raspberry Pi. The level still moves on every event, so the on-screen
 * response is unchanged - only the network traffic is paced.
 */
@Suppress("LongParameterList")
fun volumeKeyStep(
    isDown: Boolean,
    repeatCount: Int,
    downTime: Long,
    lastCountedDownTime: Long,
    nowMs: Long,
    lastSentAtMs: Long,
    throttleMs: Long = VOLUME_REPEAT_THROTTLE_MS
): VolumeKeyStep = when {
    // First edge of a press: always immediate, so a single tap feels instant.
    isDown && repeatCount == 0 -> VolumeKeyStep.SEND_NOW
    isDown -> if (nowMs - lastSentAtMs >= throttleMs) {
        VolumeKeyStep.SEND_NOW
    } else {
        VolumeKeyStep.SEND_THROTTLED
    }
    // Release of a press we already counted: the final level must always land.
    downTime == lastCountedDownTime -> VolumeKeyStep.FLUSH_ONLY
    // Release of a press whose DOWN never reached us: count it here.
    else -> VolumeKeyStep.SEND_NOW
}

/** Repeats arrive about every 50 ms; this paces the server commands, not the UI. */
const val VOLUME_REPEAT_THROTTLE_MS = 120L
