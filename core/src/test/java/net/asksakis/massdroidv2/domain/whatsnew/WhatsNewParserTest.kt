package net.asksakis.massdroidv2.domain.whatsnew

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The release notes are the only copy of this text, so the app reads the same file the
 * GitHub release page does. Everything worth asserting is about that file surviving the
 * trip: the headings become the groups, the bullets keep their wording, and the
 * bookkeeping a maintainer writes for themselves does not reach the screen.
 */
class WhatsNewParserTest {

    private fun parse(markdown: String) = WhatsNewParser.parse(markdown, versionName = "2.37.0")

    @Test
    fun `headings become sections in the order written`() {
        val release = parse(
            """
            ### Added
            - Audiobooks in search.

            ### Fixed
            - The library no longer comes up empty.
            - Covers load from outside your network.
            """.trimIndent()
        )

        assertThat(release.sections.map { it.change })
            .containsExactly(WhatsNewChange.ADDED, WhatsNewChange.FIXED).inOrder()
        assertThat(release.sections[1].entries).hasSize(2)
        assertThat(release.versionName).isEqualTo("2.37.0")
    }

    @Test
    fun `a heading is matched whatever its level or case`() {
        val release = parse(
            """
            # improved
            - Faster startup.
            """.trimIndent()
        )

        assertThat(release.sections.single().change).isEqualTo(WhatsNewChange.IMPROVED)
    }

    @Test
    fun `an unknown heading keeps its entries and carries no kind`() {
        val release = parse(
            """
            ### Removed
            - The beta update switch.
            """.trimIndent()
        )

        val section = release.sections.single()
        assertThat(section.change).isNull()
        assertThat(section.heading).isEqualTo("Removed")
        assertThat(section.entries.map { it.text }).containsExactly("The beta update switch.")
    }

    @Test
    fun `entries written before any heading are kept`() {
        val release = parse("- Something that changed.")

        val section = release.sections.single()
        assertThat(section.heading).isNull()
        assertThat(section.entries.map { it.text }).containsExactly("Something that changed.")
    }

    @Test
    fun `an issue number and the space in front of it are dropped`() {
        val release = parse("- The library no longer comes up empty. (#77)")

        assertThat(release.sections.single().entries.map { it.text })
            .containsExactly("The library no longer comes up empty.")
    }

    @Test
    fun `a contributor keeps their credit when the issue number goes`() {
        val release = parse("- Sleep timer on the player. (#71, thanks @someone)")

        assertThat(release.sections.single().entries.map { it.text })
            .containsExactly("Sleep timer on the player. (thanks @someone)")
    }

    @Test
    fun `a wrapped entry is read as one line`() {
        val release = parse(
            """
            - The full player opens to the top of the
              screen however you reach it.
            """.trimIndent()
        )

        assertThat(release.sections.single().entries.map { it.text })
            .containsExactly("The full player opens to the top of the screen however you reach it.")
    }

    @Test
    fun `a heading with nothing under it produces no section`() {
        val release = parse(
            """
            ### Added

            ### Fixed
            - One thing.
            """.trimIndent()
        )

        assertThat(release.sections.map { it.change }).containsExactly(WhatsNewChange.FIXED)
    }

    @Test
    fun `an empty file is an empty release`() {
        assertThat(parse("").isEmpty).isTrue()
    }

    @Test
    fun `an animation tag belongs to the entry written under it`() {
        val release = parse(
            """
            ### Added
            <!-- animation: nfc-tag.webp -->
            - Tap an NFC tag to switch player.
            - Something else entirely.
            """.trimIndent()
        )

        val entries = release.sections.single().entries
        assertThat(entries[0].animation).isEqualTo("nfc-tag.webp")
        assertThat(entries[0].text).isEqualTo("Tap an NFC tag to switch player.")
        assertThat(entries[1].animation).isNull()
    }

    @Test
    fun `an animation tag is not a heading and does not open a section`() {
        val release = parse(
            """
            ### Added
            - One thing.
            <!-- animation: nfc-tag.webp -->
            - Another thing.
            """.trimIndent()
        )

        val section = release.sections.single()
        assertThat(section.change).isEqualTo(WhatsNewChange.ADDED)
        assertThat(section.entries.map { it.animation }).containsExactly(null, "nfc-tag.webp").inOrder()
    }

    @Test
    fun `an ordinary comment is not read as an animation`() {
        val release = parse(
            """
            <!-- written on release day -->
            - One thing.
            """.trimIndent()
        )

        assertThat(release.sections.single().entries.single().animation).isNull()
    }
}
