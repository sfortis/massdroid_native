package net.asksakis.massdroidv2.domain.party

import net.asksakis.massdroidv2.domain.model.PartyPlayerSetting
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.QueueConfigOption

/** The party player selector: its choices, and how the current choice reads on the card. */
object PartyPlayerChoices {

    const val AUTO_TITLE = "Auto"
    const val OFFLINE_TITLE = "A player that is offline"

    /**
     * Auto first, then the players the plugin offers, in its own order (it sorts them by name).
     * Auto is listed even when the plugin's list lacks it, because it is the plugin's default.
     * This device's own player gets [ownLabel] after its name, so it can be told apart from a
     * player of the same name elsewhere.
     */
    fun options(setting: PartyPlayerSetting, ownPlayerId: String?, ownLabel: String): List<QueueConfigOption> {
        val players = setting.options
            .filter { it.value != PartyPlayerSetting.AUTO }
            .map { if (it.value == ownPlayerId) it.copy(title = "${it.title} ($ownLabel)") else it }
        return listOf(QueueConfigOption(PartyPlayerSetting.AUTO, AUTO_TITLE)) + players
    }

    /**
     * [setting] with its fixed player among the options. The plugin offers only the players that
     * are online, so a fixed player that went offline would have no name on the card and no
     * selected row. It is added after the plugin's players, under its name from [players], or
     * as [OFFLINE_TITLE] when the app does not know it.
     */
    fun withCurrent(setting: PartyPlayerSetting, players: List<Player>): PartyPlayerSetting {
        if (setting.isAuto || setting.options.any { it.value == setting.value }) return setting
        val title = players.firstOrNull { it.playerId == setting.value }?.displayName ?: OFFLINE_TITLE
        return setting.copy(options = setting.options + QueueConfigOption(setting.value, title))
    }

    /** Auto, with the player it resolves to now when there is one, for example "Auto (now: Kitchen)". */
    fun autoTitle(resolvedName: String?): String =
        resolvedName?.let { "$AUTO_TITLE (now: $it)" } ?: AUTO_TITLE
}
