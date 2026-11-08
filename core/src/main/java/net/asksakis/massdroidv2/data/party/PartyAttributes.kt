package net.asksakis.massdroidv2.data.party

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.asksakis.massdroidv2.domain.model.PartyMark

/** Reads the marks the Party plugin puts in a queue item's `extra_attributes`. */
internal object PartyAttributes {
    private const val GUEST = "party_guest"
    private const val BOOSTED = "party_boosted"

    /**
     * The guest mark of a queue item, or null for an item no guest added. Anything other than
     * a true value counts as not set: a missing key, another type, or attributes that are not
     * an object at all.
     */
    fun partyMark(extraAttributes: JsonElement?): PartyMark? {
        val attributes = extraAttributes as? JsonObject ?: return null
        return when {
            attributes.isTrue(BOOSTED) -> PartyMark.BOOSTED
            attributes.isTrue(GUEST) -> PartyMark.GUEST
            else -> null
        }
    }

    private fun JsonObject.isTrue(key: String): Boolean =
        (get(key) as? JsonPrimitive)?.booleanOrNull == true
}
