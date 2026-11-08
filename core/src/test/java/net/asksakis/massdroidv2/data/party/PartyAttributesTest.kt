package net.asksakis.massdroidv2.data.party

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.data.websocket.ServerQueueItem
import net.asksakis.massdroidv2.domain.model.PartyMark
import org.junit.Test

/**
 * The Party plugin marks the songs its guests add in the queue item's `extra_attributes`.
 * Those attributes are free-form, so whatever arrives there must never fail a queue.
 */
class PartyAttributesTest {

    /** The app's own Json settings, from AppModule. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private fun mark(extraAttributes: String?): PartyMark? {
        val field = extraAttributes?.let { ""","extra_attributes": $it""" }.orEmpty()
        val item = json.decodeFromString<ServerQueueItem>("""{"queue_item_id": "q1"$field}""")
        return PartyAttributes.partyMark(item.extraAttributes)
    }

    @Test
    fun `a guest song is marked guest`() {
        assertThat(mark("""{"party_guest": true}""")).isEqualTo(PartyMark.GUEST)
    }

    @Test
    fun `a boosted song is marked boosted`() {
        assertThat(mark("""{"party_guest": true, "party_boosted": true}""")).isEqualTo(PartyMark.BOOSTED)
    }

    @Test
    fun `boosted wins even without the guest flag`() {
        assertThat(mark("""{"party_boosted": true}""")).isEqualTo(PartyMark.BOOSTED)
    }

    @Test
    fun `false flags mark nothing`() {
        assertThat(mark("""{"party_guest": false, "party_boosted": false}""")).isNull()
    }

    @Test
    fun `a true written as a string still counts`() {
        assertThat(mark("""{"party_guest": "true"}""")).isEqualTo(PartyMark.GUEST)
    }

    @Test
    fun `other attributes and other types mark nothing`() {
        assertThat(mark("""{"party_guest": {"by": "x"}, "something_else": 1}""")).isNull()
    }

    @Test
    fun `missing, empty and null attributes mark nothing`() {
        assertThat(mark(null)).isNull()
        assertThat(mark("{}")).isNull()
        assertThat(mark("null")).isNull()
    }

    @Test
    fun `attributes that are not an object still decode the item`() {
        assertThat(mark("[1, 2]")).isNull()
        assertThat(mark("\"party\"")).isNull()
    }
}
