package net.asksakis.massdroidv2.data.party

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.asksakis.massdroidv2.data.repository.QueueConfigParser
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.domain.model.PartyPlayerSetting

/** Reads the Party plugin's player setting from a `config/providers/get` answer. */
object PartyPlayerSettingParser {

    /**
     * The setting, or null when the answer has no player entry, which is the case in the
     * plugin's remote mode on some versions and on a plugin that failed to load. A value that
     * was never stored is the entry's default, which is Auto.
     */
    fun parse(providerConfig: JsonObject?): PartyPlayerSetting? {
        val values = providerConfig?.get("values") as? JsonObject ?: return null
        val entry = values[MaCommands.Party.PLAYER_KEY] as? JsonObject ?: return null
        val value = entry.string("value") ?: entry.string("default_value") ?: PartyPlayerSetting.AUTO
        return PartyPlayerSetting(value, QueueConfigParser.entryOptions(entry))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
