package net.asksakis.massdroidv2.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.domain.model.AutoplayConfig
import net.asksakis.massdroidv2.domain.model.QueueConfigOption

/**
 * Turns the `values` block of `config/player_queues/get` into an [AutoplayConfig].
 *
 * Separate from the repository and free of any client so the parsing rules can be
 * tested against real server payloads. Three of them are easy to get wrong:
 *
 * An entry carries both `value` (what is set) and `default_value` (what applies while
 * nothing is set), and a queue that has never been configured sends `value: null` with
 * `default_value: "global"`. Reading only `value` would show no selection at all.
 *
 * The playlist entry declares its own dependency (`depends_on: "autoplay_mode"`,
 * `depends_on_value: "playlist"`) instead of the app assuming which mode needs it.
 *
 * Options may be objects or bare strings, and an option can be disabled with a reason.
 */
object AutoplayConfigParser {

    fun parse(values: JsonObject?): AutoplayConfig? {
        val modeEntry = values?.get(AutoplayConfig.KEY_MODE)?.let {
            it as? JsonObject ?: return null
        } ?: return null
        val playlistEntry = values[AutoplayConfig.KEY_PLAYLIST] as? JsonObject

        val mode = modeEntry.currentOrDefault() ?: return null
        return AutoplayConfig(
            mode = mode,
            modeOptions = modeEntry.options(),
            playlistUri = playlistEntry?.currentOrDefault(),
            playlistOptions = playlistEntry?.options() ?: emptyList(),
            playlistDependsOnMode = playlistEntry?.dependsOnValueOf(AutoplayConfig.KEY_MODE)
        )
    }

    /** What is set, falling back to what applies while nothing is set. */
    private fun JsonObject.currentOrDefault(): String? =
        (this["value"] as? JsonPrimitive)?.contentOrNull
            ?: (this["default_value"] as? JsonPrimitive)?.contentOrNull

    /** The value of [key] that makes this entry apply, if it declares that dependency. */
    private fun JsonObject.dependsOnValueOf(key: String): String? =
        (this["depends_on_value"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { (this["depends_on"] as? JsonPrimitive)?.contentOrNull == key }

    private fun JsonObject.options(): List<QueueConfigOption> =
        (this["options"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { opt ->
            when (opt) {
                is JsonObject -> {
                    val value = (opt["value"] as? JsonPrimitive)?.contentOrNull
                        ?: return@mapNotNull null
                    QueueConfigOption(
                        value = value,
                        title = (opt["title"] as? JsonPrimitive)?.contentOrNull ?: value,
                        disabled = (opt["disabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        disabledReason = (opt["disabled_reason"] as? JsonPrimitive)?.contentOrNull,
                        description = (opt["description"] as? JsonPrimitive)?.contentOrNull
                    )
                }
                is JsonPrimitive -> opt.contentOrNull?.let { QueueConfigOption(it, it) }
                else -> null
            }
        } ?: emptyList()
}
