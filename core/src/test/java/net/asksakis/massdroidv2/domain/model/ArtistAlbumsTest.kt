package net.asksakis.massdroidv2.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The artist page's Discography section never repeats a uri the page already shows (#87). */
class ArtistAlbumsTest {

    private fun album(uri: String) = Album(itemId = uri.substringAfterLast('/'), provider = "x", name = uri, uri = uri)

    @Test
    fun `a pre-2_9 server's merged answer leaves only what the library section does not show`() {
        val library = listOf(album("library://album/4"), album("library://album/7"))
        val merged = listOf(album("library://album/4"), album("library://album/7"), album("jellyfin://album/abc"))

        assertThat(discographyBeyondLibrary(library, merged).map { it.uri })
            .containsExactly("jellyfin://album/abc")
    }

    @Test
    fun `a uri repeated inside the discography is shown once`() {
        val discography = listOf(album("library://album/4"), album("jellyfin://album/abc"), album("library://album/4"))

        assertThat(discographyBeyondLibrary(emptyList(), discography).map { it.uri })
            .containsExactly("library://album/4", "jellyfin://album/abc").inOrder()
    }

    @Test
    fun `a discography that does not overlap the library is kept as it is`() {
        val library = listOf(album("library://album/4"))
        val discography = listOf(album("spotify://album/1"), album("spotify://album/2"))

        assertThat(discographyBeyondLibrary(library, discography)).isEqualTo(discography)
    }
}
