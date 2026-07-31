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
