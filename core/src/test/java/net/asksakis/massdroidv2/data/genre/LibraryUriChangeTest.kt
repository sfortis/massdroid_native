package net.asksakis.massdroidv2.data.genre

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.database.ArtistIdentityRow
import net.asksakis.massdroidv2.domain.model.Artist
import org.junit.Test

/**
 * Pins when the library sync treats a library uri as handed to another artist.
 *
 * A reuse moves or drops the previous artist's plays, feedback and block, so
 * mistaking a spelling change for a new artist would throw away that artist's
 * history. A real reuse left alone would credit one artist's plays to another:
 * on a real server `library://artist/41` was stored as Savages while the server
 * had Lindstrøm there.
 */
class LibraryUriChangeTest {

    private fun local(name: String, mbid: String? = null) = ArtistIdentityRow(URI, name, mbid)

    private fun server(name: String, mbid: String? = null) =
        Artist(itemId = "41", provider = "library", name = name, uri = URI, mbid = mbid)

    @Test
    fun `a different artist on the same uri is a reuse`() {
        assertThat(libraryUriChange(local("Savages"), server("Lindstrøm")))
            .isEqualTo(LibraryUriChange.REUSED)
    }

    @Test
    fun `an accent added to the name is a rename, not a new artist`() {
        assertThat(libraryUriChange(local("Beyonce"), server("Beyoncé")))
            .isEqualTo(LibraryUriChange.RENAMED)
    }

    @Test
    fun `a change of letter case or punctuation is a rename`() {
        assertThat(libraryUriChange(local("AC DC"), server("AC/DC")))
            .isEqualTo(LibraryUriChange.RENAMED)
        assertThat(libraryUriChange(local("the xx"), server("The xx")))
            .isEqualTo(LibraryUriChange.RENAMED)
    }

    @Test
    fun `the same MusicBrainz id is the same artist whatever the name says`() {
        assertThat(libraryUriChange(local("Prince", MBID), server("The Artist", MBID)))
            .isEqualTo(LibraryUriChange.RENAMED)
    }

    @Test
    fun `different MusicBrainz ids with different names are a reuse`() {
        assertThat(libraryUriChange(local("Annie", MBID), server("Lindstrøm", OTHER_MBID)))
            .isEqualTo(LibraryUriChange.REUSED)
    }

    @Test
    fun `an unchanged artist is left alone`() {
        assertThat(libraryUriChange(local("Savages"), server("Savages")))
            .isEqualTo(LibraryUriChange.SAME)
        assertThat(libraryUriChange(local("Savages", MBID), server("Savages", MBID)))
            .isEqualTo(LibraryUriChange.SAME)
    }

    private companion object {
        const val URI = "library://artist/41"
        const val MBID = "11111111-2222-3333-4444-555555555555"
        const val OTHER_MBID = "66666666-7777-8888-9999-000000000000"
    }

    // Each MA server numbers its own library and the table has no server column, so after a
    // switch nearly every id names someone else. Repointing those wiped the plays and feedback
    // of every colliding artist (S25, 2026-11-02: artist/5 "Rick Miller" read as reused for
    // "Broke For Free" on the other server, and back again after the switch back).

    @Test
    fun `a reused id is repointed while every sync ran against this server`() {
        assertThat(repointsReusedLibraryIds(setOf("home"), "home")).isTrue()
    }

    @Test
    fun `a reused id is left alone once a second server was synced`() {
        assertThat(repointsReusedLibraryIds(setOf("home", "test"), "test")).isFalse()
        // Back on the first server, the table still holds the other server's ids.
        assertThat(repointsReusedLibraryIds(setOf("home", "test"), "home")).isFalse()
    }
}
