package net.asksakis.massdroidv2.domain.info

/**
 * Tells a provider description that is someone's contact details rather than a few words about
 * the music. An artist writes their own bio on a streaming service, and one on Deezer read
 * "Queen of Texas // text me +1 (858) 386-4193 for updates" (2026-10-24); under the cover it
 * showed a stranger's phone number.
 *
 * Only a phone number or an email address counts. A link does not: many real bios end with one,
 * such as Last.fm's "Read more on Last.fm".
 */
internal object PromotionalText {

    private val patterns = listOf(
        // International: +1 858 386 4193, +30 210-1234567.
        Regex("""\+\d[\d\s().-]{7,}\d"""),
        // North American: (858) 386-4193.
        Regex("""\(\d{3}\)\s?\d{3}[-.\s]\d{4}"""),
        // 858-386-4193, 858.386.4193.
        Regex("""\b\d{3}[-.]\d{3}[-.]\d{4}\b"""),
        Regex("""[\w.+-]+@[\w-]+\.[\w.-]*\w"""),
    )

    fun hasContactDetails(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
}
