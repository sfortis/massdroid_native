package net.asksakis.massdroidv2.domain.discover

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import org.junit.Test

/**
 * The home screen shows the rows the MA user chose in the MA frontend, read from the same
 * `discover.rows` preference, so a row hidden in the web UI is hidden in the app too.
 */
class HomeRowLayoutTest {

    private fun row(id: String, byDefault: Boolean = true) = RecommendationFolder(
        itemId = id,
        name = id,
        provider = "recommendations",
        items = RecommendationItems(),
        uri = "library://folder/$id",
        enabledByDefault = byDefault
    )

    private val played = row("recently_played")
    private val added = row("recently_added_albums")
    private val random = row("random_albums", byDefault = false)

    @Test
    fun `with no choice the server defaults apply`() {
        assertThat(HomeRowLayout().visibleRows(listOf(played, random, added))).containsExactly(played, added).inOrder()
    }

    @Test
    fun `hidden and shown override the defaults`() {
        val layout = HomeRowLayout(hidden = setOf(played.uri!!), shown = setOf(random.uri!!))
        assertThat(layout.visibleRows(listOf(played, random, added))).containsExactly(random, added).inOrder()
    }

    @Test
    fun `the user's order wins, and rows it does not name follow in server order`() {
        val other = row("recent_artists")
        val layout = HomeRowLayout(order = listOf(added.uri!!, played.uri!!))
        assertThat(layout.visibleRows(listOf(played, other, added))).containsExactly(added, played, other).inOrder()
    }

    @Test
    fun `showing a default row only unhides it, showing an opt-in row records it`() {
        val hiddenPlayed = HomeRowLayout(hidden = setOf(played.uri!!))
        assertThat(hiddenPlayed.withVisibility(played, true)).isEqualTo(HomeRowLayout())
        assertThat(HomeRowLayout().withVisibility(random, true).shown).containsExactly(random.uri)
        assertThat(HomeRowLayout(shown = setOf(random.uri!!)).withVisibility(random, false))
            .isEqualTo(HomeRowLayout(hidden = setOf(random.uri!!)))
    }

    @Test
    fun `reordering moves the rows and keeps the frontend's own entries in place`() {
        val layout = HomeRowLayout(order = listOf("players", played.uri!!, "top_picks", added.uri!!))
        val moved = layout.withOrder(listOf(played, added), listOf(added.uri!!, played.uri!!))
        assertThat(moved.order).containsExactly("players", added.uri, "top_picks", played.uri).inOrder()
        assertThat(moved.visibleRows(listOf(played, added))).containsExactly(added, played).inOrder()
    }

    @Test
    fun `reordering with no stored order starts from the server order`() {
        val moved = HomeRowLayout().withOrder(listOf(played, random, added), listOf(added.uri!!, played.uri!!, random.uri!!))
        assertThat(moved.order).containsExactly(added.uri, played.uri, random.uri).inOrder()
    }

    @Test
    fun `a row the order does not name sits after its server predecessor`() {
        val other = row("recent_artists")
        val layout = HomeRowLayout(order = listOf(added.uri!!, played.uri!!))
        assertThat(layout.orderedRows(listOf(played, other, added))).containsExactly(added, played, other).inOrder()
    }

    @Test
    fun `a row without a uri follows its default and cannot be changed`() {
        val bare = played.copy(uri = null)
        assertThat(HomeRowLayout(hidden = setOf("x")).isVisible(bare)).isTrue()
        assertThat(HomeRowLayout().withVisibility(bare, false)).isEqualTo(HomeRowLayout())
    }
}
