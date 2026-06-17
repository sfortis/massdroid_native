package net.asksakis.massdroidv2.data.websocket

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Verifies the typed command contracts serialize to the exact MA wire shape:
 * snake_case keys, correct value types, and conditional/omitted optional fields.
 */
class MaCommandContractsTest {

    @Test
    fun `PlayMediaArgs emits media array and option, omits radio_mode when false`() {
        val json = PlayMediaArgs(
            queueId = "q1",
            mediaUris = listOf("uri1", "uri2"),
            option = "replace",
            radioMode = false,
        ).toJson()

        assertThat(json["queue_id"]?.jsonPrimitive?.content).isEqualTo("q1")
        assertThat(json["media"]?.jsonArray?.map { it.jsonPrimitive.content })
            .containsExactly("uri1", "uri2").inOrder()
        assertThat(json["option"]?.jsonPrimitive?.content).isEqualTo("replace")
        assertThat(json.containsKey("radio_mode")).isFalse()
    }

    @Test
    fun `PlayMediaArgs emits radio_mode true and omits option when null`() {
        val json = PlayMediaArgs(queueId = "q2", mediaUris = listOf("u"), radioMode = true).toJson()

        assertThat(json["radio_mode"]?.jsonPrimitive?.boolean).isTrue()
        assertThat(json.containsKey("option")).isFalse()
    }

    @Test
    fun `LibraryItemsArgs omits all optional fields when unset`() {
        val json = LibraryItemsArgs(limit = 50, offset = 0).toJson()

        assertThat(json["limit"]?.jsonPrimitive?.int).isEqualTo(50)
        assertThat(json["offset"]?.jsonPrimitive?.int).isEqualTo(0)
        assertThat(json.containsKey("search")).isFalse()
        assertThat(json.containsKey("order_by")).isFalse()
        assertThat(json.containsKey("favorite")).isFalse()
        assertThat(json.containsKey("provider")).isFalse()
    }

    @Test
    fun `LibraryItemsArgs emits snake_case fields when set`() {
        val json = LibraryItemsArgs(
            search = "x",
            limit = 10,
            offset = 5,
            orderBy = "name",
            favoriteOnly = true,
            provider = listOf("spotify", "deezer"),
        ).toJson()

        assertThat(json["search"]?.jsonPrimitive?.content).isEqualTo("x")
        assertThat(json["order_by"]?.jsonPrimitive?.content).isEqualTo("name")
        assertThat(json["favorite"]?.jsonPrimitive?.boolean).isTrue()
        assertThat(json["provider"]?.jsonArray?.map { it.jsonPrimitive.content })
            .containsExactly("spotify", "deezer").inOrder()
    }
}
