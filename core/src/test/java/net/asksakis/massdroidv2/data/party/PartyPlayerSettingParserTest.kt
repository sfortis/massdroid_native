package net.asksakis.massdroidv2.data.party

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.asksakis.massdroidv2.domain.model.PartyPlayerSetting
import org.junit.Test

/** The Party plugin's player setting, read from a `config/providers/get` answer. */
class PartyPlayerSettingParserTest {

    private fun parse(values: String) =
        PartyPlayerSettingParser.parse(Json.parseToJsonElement("""{"values": $values}""") as JsonObject)

    private val options = """
        [{"value": "__auto__", "title": "Auto (last active player)"},
         {"value": "kitchen", "title": "Kitchen"}]
    """

    @Test
    fun `a stored player is the current value, with the plugin's options in order`() {
        val setting = parse("""{"player": {"value": "kitchen", "default_value": "__auto__", "options": $options}}""")!!

        assertThat(setting.value).isEqualTo("kitchen")
        assertThat(setting.isAuto).isFalse()
        assertThat(setting.options.map { it.value }).containsExactly("__auto__", "kitchen").inOrder()
        assertThat(setting.options[1].title).isEqualTo("Kitchen")
    }

    @Test
    fun `a value never stored is the entry's default`() {
        val setting = parse("""{"player": {"value": null, "default_value": "kitchen", "options": $options}}""")!!

        assertThat(setting.value).isEqualTo("kitchen")
    }

    @Test
    fun `without a stored value or a default the plugin's own default, auto, applies`() {
        val setting = parse("""{"player": {"options": $options}}""")!!

        assertThat(setting.value).isEqualTo(PartyPlayerSetting.AUTO)
        assertThat(setting.isAuto).isTrue()
    }

    @Test
    fun `no player entry means no setting to change`() {
        assertThat(parse("""{"mode": {"value": "remote"}}""")).isNull()
        assertThat(PartyPlayerSettingParser.parse(null)).isNull()
    }
}
