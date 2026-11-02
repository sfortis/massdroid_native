package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * The MA user's home rows join the app's own sections. Before this the app fetched items for
 * three rows only, so on MA 2.10 the home screen showed almost nothing of what the official
 * app shows (r/homeassistant, 2026-11-01).
 */
class DiscoverHomeRowsTest {

    private val builder = DiscoverSectionBuilder()

    private fun albums(prefix: String) =
        (1..3).map { Album(itemId = "$prefix$it", provider = "p", name = "$prefix $it", uri = "$prefix$it") }

    private fun tracks(prefix: String) =
        (1..3).map { Track(itemId = "$prefix$it", provider = "p", name = "$prefix $it", uri = "$prefix$it") }

    private fun row(id: String, name: String, provider: String = "recommendations", items: RecommendationItems) =
        RecommendationFolder(itemId = id, name = name, provider = provider, items = items, uri = "library://folder/$id")

    private fun titles(sections: List<DiscoverSection>) = sections.map {
        when (it) {
            is DiscoverSection.AlbumSection -> it.title
            is DiscoverSection.TrackSection -> it.title
            is DiscoverSection.ArtistSection -> it.title
            is DiscoverSection.PlaylistSection -> it.title
            is DiscoverSection.GenreRadioSection -> it.title
        }
    }

    private fun build(homeRows: List<RecommendationFolder>, serverFolders: List<RecommendationFolder> = emptyList()) =
        builder.buildSections(
            serverFolders = serverFolders,
            suggestedArtists = emptyList(),
            suggestedAlbums = emptyList(),
            genreItems = emptyList(),
            bllGenreScores = emptyList(),
            homeRows = homeRows
        )

    @Test
    fun `home rows are drawn in the user's order`() {
        val sections = build(
            listOf(
                row("recently_added_albums", "Recently added albums", items = RecommendationItems(albums = albums("a"))),
                row("recently_played", "Recently played", items = RecommendationItems(tracks = tracks("t")))
            )
        )
        assertThat(titles(sections)).containsExactly("Recently added albums", "Recently played").inOrder()
    }

    @Test
    fun `two rows with one name stay apart by provider`() {
        val sections = build(
            listOf(
                row("recently_played", "Recently played", items = RecommendationItems(tracks = tracks("a"))),
                row("rp", "Recently played", provider = "spotify--x1", items = RecommendationItems(tracks = tracks("b")))
            )
        )
        assertThat(titles(sections)).containsExactly("Recently played", "Recently played · Spotify").inOrder()
    }

    @Test
    fun `three rows with one name from one provider still get distinct titles`() {
        val sections = build(
            listOf(
                row("recently_played", "Recently played", items = RecommendationItems(tracks = tracks("a"))),
                row("rp1", "Recently played", provider = "spotify--x1", items = RecommendationItems(tracks = tracks("b"))),
                row("rp2", "Recently played", provider = "spotify--x2", items = RecommendationItems(tracks = tracks("c")))
            )
        )
        assertThat(titles(sections))
            .containsExactly("Recently played", "Recently played · Spotify", "Recently played · Spotify 2").inOrder()
    }

    @Test
    fun `the live favorites row is not drawn twice, and an empty row is left out`() {
        val sections = build(
            listOf(
                row("recent_favorite_tracks", "Recently favorited tracks", items = RecommendationItems(tracks = tracks("f"))),
                row("in_progress", "In progress", items = RecommendationItems())
            )
        )
        assertThat(titles(sections)).doesNotContain("Recently favorited tracks")
        assertThat(titles(sections)).doesNotContain("In progress")
    }

    @Test
    fun `the explore fallback stays away when the user shows the random row`() {
        val random = row("random_albums", "Random albums", items = RecommendationItems(albums = albums("r")))
        val withRow = build(homeRows = listOf(random), serverFolders = listOf(random))
        val withoutRow = build(homeRows = emptyList(), serverFolders = listOf(random))
        assertThat(titles(withRow)).containsExactly("Random albums")
        assertThat(titles(withoutRow)).containsExactly("Explore Albums")
    }
}
