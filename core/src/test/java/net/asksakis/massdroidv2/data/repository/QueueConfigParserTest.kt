package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.asksakis.massdroidv2.domain.model.AutoplayConfig
import net.asksakis.massdroidv2.domain.model.QueueChoice
import org.junit.Test

/**
 * Pins the parsing of queue configuration against payloads captured from real Music
 * Assistant 2.10 servers.
 *
 * Autoplay is what "don't stop the music" became. Switching it on and off is still a
 * queue command, but the strategy behind it is queue configuration, and the shape of
 * that configuration has three details that are easy to read wrongly.
 */
class QueueConfigParserTest {

    /**
     * Verbatim from `config/player_queues/get` on 2.10.0 for a queue that has never been
     * configured. Note `value: null` on both entries: the setting in force is the
     * `default_value`, not nothing.
     */
    private val realPayload = """
        {
          "autoplay_label": {
            "key": "autoplay_label", "type": "label", "value": null, "default_value": null
          },
          "autoplay_mode": {
            "key": "autoplay_mode",
            "type": "string",
            "value": null,
            "default_value": "global",
            "required": true,
            "depends_on": null,
            "depends_on_value": null,
            "description": "How Autoplay picks new tracks once your queue runs out.",
            "options": [
              {"value": "auto", "title": "Automatic, similar tracks falling back to your library", "disabled": false, "disabled_reason": null, "description": null},
              {"value": "similar", "title": "Similar to what you played", "disabled": false, "disabled_reason": null, "description": null},
              {"value": "library", "title": "Infinite mix from your library", "disabled": false, "disabled_reason": null, "description": null},
              {"value": "playlist", "title": "Tracks from a playlist", "disabled": false, "disabled_reason": null, "description": null},
              {"value": "global", "title": "Global", "disabled": false, "disabled_reason": null, "description": "Follow the Player Queues default."}
            ]
          },
          "autoplay_playlist": {
            "key": "autoplay_playlist",
            "type": "string",
            "value": null,
            "default_value": null,
            "required": false,
            "depends_on": "autoplay_mode",
            "depends_on_value": "playlist",
            "options": [
              {"value": "library://playlist/12", "title": "500 Random tracks (from library)", "disabled": false, "disabled_reason": null, "description": null},
              {"value": "library://playlist/9", "title": "All favorited tracks", "disabled": false, "disabled_reason": null, "description": null}
            ]
          }
        }
    """.trimIndent()

    private fun parse(json: String) =
        QueueConfigParser.parseAutoplay(Json.parseToJsonElement(json).jsonObject)

    private fun choice(json: String, key: String) =
        QueueConfigParser.parseChoice(Json.parseToJsonElement(json).jsonObject, key)

    @Test
    fun `an unconfigured queue reads as its default, not as unset`() {
        val config = parse(realPayload)
        assertThat(config).isNotNull()
        // `value` is null here, so the mode in force is the declared default.
        assertThat(config!!.mode).isEqualTo("global")
        assertThat(config.selectedModeOption?.title).isEqualTo("Global")
    }

    @Test
    fun `a configured queue reads its own value over the default`() {
        val config = parse(
            """
            {
              "autoplay_mode": {
                "key": "autoplay_mode",
                "value": "library",
                "default_value": "global",
                "options": [
                  {"value": "library", "title": "Infinite mix from your library"},
                  {"value": "global", "title": "Global"}
                ]
              }
            }
            """.trimIndent()
        )
        assertThat(config!!.mode).isEqualTo("library")
        assertThat(config.selectedModeOption?.title).isEqualTo("Infinite mix from your library")
    }

    @Test
    fun `all five modes the server offers survive parsing, in order`() {
        val config = parse(realPayload)
        assertThat(config!!.modeOptions.map { it.value })
            .containsExactly("auto", "similar", "library", "playlist", "global").inOrder()
        assertThat(config.modeOptions.first { it.value == "global" }.description)
            .isEqualTo("Follow the Player Queues default.")
    }

    @Test
    fun `the playlist choice applies only under the mode the server tied it to`() {
        val config = parse(realPayload)
        assertThat(config!!.playlistDependsOnMode).isEqualTo("playlist")
        // Mode is "global" here, so the playlist row must stay hidden.
        assertThat(config.playlistApplies).isFalse()
        assertThat(config.copy(mode = "playlist").playlistApplies).isTrue()
        assertThat(config.copy(mode = "library").playlistApplies).isFalse()
    }

    @Test
    fun `a dependency on some other key is not treated as the playlist dependency`() {
        val config = parse(realPayload.replace("\"depends_on\": \"autoplay_mode\"", "\"depends_on\": \"crossfade_mode\""))
        assertThat(config!!.playlistDependsOnMode).isNull()
        // With no dependency the playlist row never applies, rather than always applying.
        assertThat(config.copy(mode = "playlist").playlistApplies).isFalse()
    }

    @Test
    fun `a disabled option is kept with its reason, not dropped`() {
        // The server disables `similar` when no provider supplies similar tracks. Keeping
        // it visible with the reason explains more than an option that is simply absent.
        val config = parse(
            realPayload.replace(
                """{"value": "similar", "title": "Similar to what you played", "disabled": false, "disabled_reason": null, "description": null}""",
                """{"value": "similar", "title": "Similar to what you played", "disabled": true, "disabled_reason": "No provider can supply similar tracks", "description": null}"""
            )
        )
        val similar = config!!.modeOptions.first { it.value == "similar" }
        assertThat(similar.disabled).isTrue()
        assertThat(similar.disabledReason).isEqualTo("No provider can supply similar tracks")
        assertThat(config.modeOptions).hasSize(5)
    }

    @Test
    fun `the playlist uri and its title both come through`() {
        val config = parse(realPayload)
        assertThat(config!!.playlistOptions.map { it.value })
            .containsExactly("library://playlist/12", "library://playlist/9")
        assertThat(config.playlistOptions.first().title).isEqualTo("500 Random tracks (from library)")
    }

    @Test
    fun `a server without autoplay yields null instead of an empty config`() {
        // Anything before MA 2.10 has no such entries, and the caller leaves the whole
        // section out rather than showing an empty selector.
        assertThat(parse("""{"crossfade_mode": {"key": "crossfade_mode", "value": "disabled"}}""")).isNull()
        assertThat(parse("{}")).isNull()
        assertThat(QueueConfigParser.parseAutoplay(null)).isNull()
    }

    @Test
    fun `a mode entry with neither value nor default is not a usable config`() {
        assertThat(parse("""{"autoplay_mode": {"key": "autoplay_mode", "value": null, "default_value": null}}"""))
            .isNull()
    }

    @Test
    fun `options given as bare strings still parse`() {
        // Not what 2.10 sends for these keys, but other config entries do, and the
        // parser is shared tolerance rather than per-key special casing.
        val config = parse(
            """{"autoplay_mode": {"key": "autoplay_mode", "value": "auto", "options": ["auto", "library"]}}"""
        )
        assertThat(config!!.modeOptions.map { it.value }).containsExactly("auto", "library")
        assertThat(config.modeOptions.first().title).isEqualTo("auto")
    }

    @Test
    fun `global names the source it resolves to, so the option is not self-referential`() {
        // On its own the server's description for "global" says "Follow the Player Queues
        // default" without ever saying what that default is, which is the one thing worth
        // knowing before choosing it. The global value is read separately and shown here.
        val config = parse(realPayload)!!.copy(globalMode = "auto")
        assertThat(config.globalModeTitle)
            .isEqualTo("Automatic, similar tracks falling back to your library")
    }

    @Test
    fun `an unknown or absent global value shows nothing rather than a raw code`() {
        val config = parse(realPayload)!!
        assertThat(config.globalModeTitle).isNull()
        assertThat(config.copy(globalMode = "some_future_mode").globalModeTitle).isNull()
    }

    @Test
    fun `a global pointing at global is not shown, which would say nothing`() {
        val config = parse(realPayload)!!.copy(globalMode = AutoplayConfig.MODE_GLOBAL)
        assertThat(config.globalModeTitle).isNull()
    }

    @Test
    fun `the config keys match what the server expects on save`() {
        assertThat(AutoplayConfig.KEY_MODE).isEqualTo("autoplay_mode")
        assertThat(AutoplayConfig.KEY_PLAYLIST).isEqualTo("autoplay_playlist")
    }

    /**
     * Verbatim from `config/player_queues/get` on 2.10.1, trimmed to the fields the app
     * reads. All three of these settings share the shape: a string select whose default
     * is `global`, with `value: null` until the queue is configured.
     */
    private val choicesPayload = """
        {
          "crossfade_mode": {
            "key": "crossfade_mode",
            "type": "string",
            "value": null,
            "default_value": "global",
            "options": [
              {"value": "standard_crossfade", "title": "Standard crossfade", "disabled": false, "disabled_reason": null},
              {"value": "smart_crossfade", "title": "Smart crossfade (beat-matched)", "disabled": true, "disabled_reason": "Not available on this server: smart crossfade needs the smart fades analysis provider and a larger audio buffer (balanced or higher)."},
              {"value": "global", "title": "Global", "disabled": false, "description": "Follow the Player Queues default."}
            ],
            "label": "Crossfade mode"
          },
          "volume_normalization": {
            "key": "volume_normalization",
            "type": "string",
            "value": "enabled",
            "default_value": "global",
            "options": [
              {"value": "enabled", "title": "Enabled"},
              {"value": "disabled", "title": "Disabled"},
              {"value": "global", "title": "Global"}
            ]
          },
          "smart_shuffle_enabled": {
            "key": "smart_shuffle_enabled",
            "type": "string",
            "value": null,
            "default_value": "global",
            "options": [
              {"value": "enabled", "title": "Enabled"},
              {"value": "disabled", "title": "Disabled"},
              {"value": "global", "title": "Global"}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `each queue setting reads its value, or the default while unset`() {
        val crossfade = choice(choicesPayload, QueueChoice.KEY_CROSSFADE_MODE)
        assertThat(crossfade!!.value).isEqualTo("global")
        assertThat(crossfade.followsGlobal).isTrue()
        // This one has been set, so its own value wins over the default.
        val normalization = choice(choicesPayload, QueueChoice.KEY_VOLUME_NORMALIZATION)
        assertThat(normalization!!.value).isEqualTo("enabled")
        assertThat(normalization.followsGlobal).isFalse()
        assertThat(normalization.selectedOption?.title).isEqualTo("Enabled")
    }

    @Test
    fun `an unavailable crossfade type is kept with its reason, not dropped`() {
        // Smart crossfade needs a provider this server does not have. Showing it greyed
        // out with the reason says more than leaving the listener one option.
        val crossfade = choice(choicesPayload, QueueChoice.KEY_CROSSFADE_MODE)!!
        assertThat(crossfade.options.map { it.value })
            .containsExactly("standard_crossfade", "smart_crossfade", "global").inOrder()
        val smart = crossfade.options.first { it.value == "smart_crossfade" }
        assertThat(smart.disabled).isTrue()
        assertThat(smart.disabledReason).contains("smart fades analysis provider")
    }

    @Test
    fun `global says what it resolves to, and only when it is what applies`() {
        val crossfade = choice(choicesPayload, QueueChoice.KEY_CROSSFADE_MODE)!!
            .copy(globalValue = "standard_crossfade")
        assertThat(crossfade.globalTitle).isEqualTo("Standard crossfade")
        // A server-wide value the options do not describe cannot be named.
        assertThat(crossfade.copy(globalValue = "something_new").globalTitle).isNull()
        // "Global" resolving to "global" would say nothing at all.
        assertThat(crossfade.copy(globalValue = QueueChoice.VALUE_GLOBAL).globalTitle).isNull()
        assertThat(crossfade.copy(globalValue = null).globalTitle).isNull()
    }

    @Test
    fun `a setting the server does not send is absent rather than defaulted`() {
        // Everything before MA 2.10 keeps these on the player, so the queue config has no
        // such entries and each control hides instead of claiming a value.
        assertThat(choice(choicesPayload, "playback_speed")).isNull()
        assertThat(choice("{}", QueueChoice.KEY_CROSSFADE_MODE)).isNull()
        assertThat(QueueConfigParser.parseChoice(null, QueueChoice.KEY_CROSSFADE_MODE)).isNull()
        val settings = QueueConfigParser.parse(null)
        assertThat(settings.autoplay).isNull()
        assertThat(settings.crossfadeMode).isNull()
        assertThat(settings.volumeNormalization).isNull()
        assertThat(settings.smartShuffle).isNull()
    }

    @Test
    fun `the server-wide defaults are read the same way as a queue's own`() {
        // config/core/get returns entries of the same shape, so "set value, else default"
        // has to hold there too: the global crossfade mode below has never been changed.
        val core = """
            {
              "crossfade_mode": {"key": "crossfade_mode", "value": null, "default_value": "standard_crossfade"},
              "volume_normalization": {"key": "volume_normalization", "value": "disabled", "default_value": "enabled"}
            }
        """.trimIndent()
        val values = Json.parseToJsonElement(core).jsonObject
        assertThat(QueueConfigParser.coreValue(values, "crossfade_mode")).isEqualTo("standard_crossfade")
        assertThat(QueueConfigParser.coreValue(values, "volume_normalization")).isEqualTo("disabled")
        assertThat(QueueConfigParser.coreValue(values, "smart_shuffle_enabled")).isNull()
        assertThat(QueueConfigParser.coreValue(null, "crossfade_mode")).isNull()
    }
}
