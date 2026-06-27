package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/** folderToSection: section type selection + the >=MIN_SECTION_ITEMS invariant. */
class DiscoverSectionBuilderTest {

    private val builder = DiscoverSectionBuilder()

    private fun album(i: Int) = Album(itemId = "al$i", provider = "p", name = "Album $i", uri = "al$i")
    private fun artist(i: Int) = Artist(itemId = "ar$i", provider = "p", name = "Artist $i", uri = "ar$i")
    private fun track(i: Int) = Track(itemId = "tr$i", provider = "p", name = "Track $i", uri = "tr$i")
    private fun folder(items: RecommendationItems) =
        RecommendationFolder(itemId = "f", name = "Folder", provider = "p", items = items)

    @Test
    fun `a folder with three albums becomes an AlbumSection`() {
        val s = builder.folderToSection(folder(RecommendationItems(albums = (1..3).map(::album))))
        assertThat(s).isInstanceOf(DiscoverSection.AlbumSection::class.java)
        assertThat((s as DiscoverSection.AlbumSection).albums).hasSize(3)
    }

    @Test
    fun `a folder with three tracks becomes a TrackSection`() {
        val s = builder.folderToSection(folder(RecommendationItems(tracks = (1..3).map(::track))))
        assertThat(s).isInstanceOf(DiscoverSection.TrackSection::class.java)
    }

    @Test
    fun `a type that reaches the minimum wins over a smaller type`() {
        val s = builder.folderToSection(
            folder(RecommendationItems(albums = listOf(album(1)), tracks = (1..5).map(::track)))
        )
        // albums (1) < MIN, tracks (5) >= MIN -> TrackSection
        assertThat(s).isInstanceOf(DiscoverSection.TrackSection::class.java)
    }

    @Test
    fun `a mixed folder under the minimum per type yields no section`() {
        // 2 albums + 2 artists: total 4 >= MIN but no single type >= MIN.
        // Must NOT emit a sub-minimum single-type section (the invariant bug).
        val s = builder.folderToSection(
            folder(RecommendationItems(albums = (1..2).map(::album), artists = (1..2).map(::artist)))
        )
        assertThat(s).isNull()
    }

    @Test
    fun `an empty folder yields no section`() {
        assertThat(builder.folderToSection(folder(RecommendationItems()))).isNull()
    }
}
