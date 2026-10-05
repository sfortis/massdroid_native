package net.asksakis.massdroidv2.data.database

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.database.GenreSpellingMigration.GenreUsage
import net.asksakis.massdroidv2.domain.recommendation.genreKey
import org.junit.Test

/**
 * Pins the planning half of the schema 20 to 21 genre merge: which stored names
 * fold into which.
 *
 * The SQL half is not covered. This project has no instrumented or Robolectric
 * tests, so nothing here opens a database; the planner was extracted as a pure
 * function so that the decisions, which are the part that can be wrong in a
 * subtle way, are tested on the counts measured on 2026-10-05.
 */
class GenreSpellingMigrationTest {

    /** Track and artist row counts from the owner's database, 2026-10-05. */
    private val realUsage = listOf(
        GenreUsage("synthpop", trackRows = 926, artistRows = 183),
        GenreUsage("synth pop", trackRows = 33, artistRows = 9),
        GenreUsage("synth-pop", trackRows = 193, artistRows = 123),
        GenreUsage("post punk", trackRows = 300, artistRows = 40),
        GenreUsage("post-punk", trackRows = 120, artistRows = 60),
        GenreUsage("house", trackRows = 800, artistRows = 90),
        GenreUsage("techno", trackRows = 500, artistRows = 70),
    )

    @Test
    fun `every spelling folds into the most used one`() {
        val plan = GenreSpellingMigration.planMerges(realUsage)

        assertThat(plan).containsExactly(
            "synth pop", "synthpop",
            "synth-pop", "synthpop",
            "post-punk", "post punk",
        )
    }

    @Test
    fun `genres with a single spelling are not touched`() {
        val plan = GenreSpellingMigration.planMerges(realUsage)

        assertThat(plan.keys).containsNoneOf("synthpop", "post punk", "house", "techno")
        assertThat(plan.values).containsNoneOf("house", "techno")
    }

    @Test
    fun `track and artist rows are counted together`() {
        // "lo fi" leads on track rows alone, "lo-fi" on the sum. The sum decides.
        val plan = GenreSpellingMigration.planMerges(
            listOf(GenreUsage("lo fi", 10, 0), GenreUsage("lo-fi", 6, 5))
        )

        assertThat(plan).containsExactly("lo fi", "lo-fi")
    }

    @Test
    fun `a tie goes to the lexically smallest name whatever the row order`() {
        val rows = listOf(GenreUsage("post-rock", 5, 5), GenreUsage("post rock", 8, 2))

        assertThat(GenreSpellingMigration.planMerges(rows)).containsExactly("post-rock", "post rock")
        assertThat(GenreSpellingMigration.planMerges(rows.reversed())).containsExactly("post-rock", "post rock")
    }

    @Test
    fun `accents and case merge, different genres do not`() {
        val plan = GenreSpellingMigration.planMerges(
            listOf(
                GenreUsage("electro", 40, 4),
                GenreUsage("électro", 2, 1),
                GenreUsage("Electro", 1, 0),
                GenreUsage("electronic", 400, 50),
                GenreUsage("r&b", 3, 1),
                GenreUsage("rnb", 9, 2),
            )
        )

        assertThat(plan).containsExactly("électro", "electro", "Electro", "electro")
    }

    @Test
    fun `names with no ASCII letters keep their own identity`() {
        // Both would have the empty key without the fallback, and one would be
        // folded into the other.
        val plan = GenreSpellingMigration.planMerges(
            listOf(GenreUsage("ραμπέτικο", 3, 1), GenreUsage("λαϊκό", 2, 1), GenreUsage(" ", 1, 0))
        )

        assertThat(plan).isEmpty()
    }

    @Test
    fun `the frozen key agrees with the live one at schema 21`() {
        // If the live rule changes later this test is expected to be updated by
        // removing the live side, never by changing the frozen copy.
        val names = listOf(
            "synth-pop", "Synth Pop", "post-punk", "lo fi", "avant-garde", "électro",
            "r&b", "drum & bass", "!!!", "ραμπέτικο", "  House  ",
        )
        for (name in names) {
            assertThat(GenreSpellingMigration.frozenGenreKey(name)).isEqualTo(genreKey(name))
        }
    }
}
