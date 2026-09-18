package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the rule that keeps the search history readable.
 *
 * The search screen queries the server while the listener is still typing, so
 * one search for "pink floyd" can also store "pink" on the way to it. The
 * screen tells this layer which entry that was, and only that entry is
 * replaced. A shorter search made at some earlier point is a search of its own
 * and survives, which is the difference between "pink" typed two seconds ago
 * and "adele" searched yesterday before today's "adele 30".
 */
class RecentSearchHistoryTest {

    @Test
    fun `a longer query replaces the entry the same typing run stored`() {
        val history = mergeRecentSearch(listOf("pink", "dua lipa"), "pink floyd", replacing = "pink")

        assertThat(history).containsExactly("pink floyd", "dua lipa").inOrder()
    }

    @Test
    fun `an earlier search is kept even when the new query grows out of it`() {
        val history = mergeRecentSearch(listOf("adele"), "adele 30", replacing = null)

        assertThat(history).containsExactly("adele 30", "adele").inOrder()
    }

    @Test
    fun `the newest search comes first and older ones are kept`() {
        var history = mergeRecentSearch(emptyList(), "miles davis")
        history = mergeRecentSearch(history, "dua lipa")

        assertThat(history).containsExactly("dua lipa", "miles davis").inOrder()
    }

    @Test
    fun `searching the same thing again moves it to the top instead of duplicating it`() {
        var history = mergeRecentSearch(emptyList(), "radiohead")
        history = mergeRecentSearch(history, "dua lipa")
        history = mergeRecentSearch(history, "RADIOHEAD")

        assertThat(history).containsExactly("RADIOHEAD", "dua lipa").inOrder()
    }

    @Test
    fun `the history stops at ten entries and drops the oldest`() {
        var history = emptyList<String>()
        for (letter in 'a'..'l') history = mergeRecentSearch(history, "query $letter")

        assertThat(history).hasSize(MAX_RECENT_SEARCHES)
        assertThat(history.first()).isEqualTo("query l")
        assertThat(history.last()).isEqualTo("query c")
    }

    @Test
    fun `a stored entry never carries a newline, which is the record separator`() {
        val history = mergeRecentSearch(emptyList(), "  pink \n floyd  ")

        assertThat(history).containsExactly("pink floyd")
    }

    @Test
    fun `a blank query is not remembered`() {
        val history = mergeRecentSearch(listOf("dua lipa"), "   ")

        assertThat(history).containsExactly("dua lipa")
    }

    @Test
    fun `a blank replacement removes nothing`() {
        val history = mergeRecentSearch(listOf("dua lipa"), "adele", replacing = "  ")

        assertThat(history).containsExactly("adele", "dua lipa").inOrder()
    }
}
