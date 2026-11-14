package net.asksakis.massdroidv2.domain.party

import net.asksakis.massdroidv2.domain.model.PartyPlayerSetting
import net.asksakis.massdroidv2.domain.model.Player

/**
 * What the Party screen shows after the server acknowledged a new party player, from the reads
 * made after the save. A read that fails keeps what is on screen, as [PartyTargetFollower] does,
 * so a read during the plugin's reload does not blank the card.
 */
object PartyPlayerSaved {

    /** The target read after the save, or [shown] when that read failed. */
    fun target(shown: PartyTargetSnapshot, read: Result<String?>, players: List<Player>): PartyTargetSnapshot =
        read.fold(onSuccess = { PartyTargetSnapshot(it, players) }, onFailure = { shown })

    /**
     * The setting read after the save, or [shown] with the [saved] value when that read failed or
     * still gives another value: the server acknowledged [saved], so a different value is an
     * older read.
     */
    fun setting(shown: PartyPlayerSetting, read: Result<PartyPlayerSetting?>, saved: String): PartyPlayerSetting =
        read.getOrNull()?.takeIf { it.value == saved } ?: shown.copy(value = saved)
}
