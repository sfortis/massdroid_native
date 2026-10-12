package net.asksakis.massdroidv2.data.sendspin

import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig

/** Which setting moves a remote member of a Sendspin group in time. */
sealed interface MemberDelay {
    /**
     * The config entry [key] holds the delay, read as [control]. When [restartsPlayback] is
     * true, MA stops and restarts the whole group each time the value is saved.
     */
    data class Adjustable(val key: String, val control: DelayControl, val restartsPlayback: Boolean) : MemberDelay

    /** MA lists the delay only while the speaker plays (Sendspin over Cast). */
    data object WaitsForPlayback : MemberDelay

    /** The client's firmware does not take a delay from the server (ESPHome, for one). */
    data object OnDevice : MemberDelay
}

/** A remote group member as the Sync speakers sheet shows it: its config and the delay it resolves to. */
data class SyncMemberSetup(val config: PlayerConfig?, val delay: MemberDelay)

/**
 * Finds the delay of a remote group member from its outputs and its config.
 *
 * A member joins a Sendspin group through a Sendspin output. When that output is derived from
 * an AirPlay output, the bridge applies the AirPlay output's `sync_adjust` and the Sendspin
 * output has no delay of its own. MA marks sync_adjust as needing a reload, so a save stops and
 * restarts the group's playback (seen on MA 2.10.6, 2026-10-11). Every other Sendspin output
 * carries its own sync delay or static delay.
 */
object MemberDelayResolver {
    private const val SENDSPIN_DOMAIN = "sendspin"
    private const val AIRPLAY_DOMAIN = "airplay"

    // MA's CONF_ENTRY_SYNC_ADJUST range.
    private const val SYNC_ADJUST_MIN_MS = -500
    private const val SYNC_ADJUST_MAX_MS = 500

    fun resolve(player: Player, config: PlayerConfig?): MemberDelay {
        if (config == null) return MemberDelay.OnDevice
        airPlayBaseOf(player)?.let { baseId ->
            config.syncAdjust[baseId]?.let { setting ->
                return MemberDelay.Adjustable(
                    key = setting.key,
                    control = DelayControl.Signed(setting.value, SYNC_ADJUST_MIN_MS, SYNC_ADJUST_MAX_MS),
                    restartsPlayback = true,
                )
            }
        }
        config.sendspinSyncDelayKey?.let { key ->
            return MemberDelay.Adjustable(key, DelayControl.Signed(config.sendspinSyncDelayMs ?: 0), restartsPlayback = false)
        }
        config.sendspinStaticDelayKey?.let { key ->
            return MemberDelay.Adjustable(key, DelayControl.Static(config.sendspinStaticDelayMs ?: 0), restartsPlayback = false)
        }
        return if (config.sendspinDelayWaitsForPlayback) MemberDelay.WaitsForPlayback else MemberDelay.OnDevice
    }

    /** The id of the AirPlay output the member's Sendspin output runs on, or null. */
    private fun airPlayBaseOf(player: Player): String? {
        val outputs = player.outputProtocols
        return outputs
            .filter { it.domain == SENDSPIN_DOMAIN && it.derivedFrom != null }
            .firstNotNullOfOrNull { sendspin ->
                outputs.firstOrNull { it.id == sendspin.derivedFrom && it.domain == AIRPLAY_DOMAIN }?.id
            }
    }
}
