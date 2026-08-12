package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.repository.SeedTrack
import org.junit.Test

/**
 * Pins that a mix seats the seeds sharing the primary's exact GENRE before the ones
 * that only share its family.
 *
 * A family is far too coarse to spend eight seats on. `rock` alone spans 33 genres,
 * from classic rock and rockabilly to shoegaze and post rock, so membership by
 * family let a post-rock anchor sit with seven indie seeds. Measured on a real build
 * anchored on God Is an Astronaut: the other seven seeds were Misun, One Sentence.
 * Supervisor, The Head And The Heart, The Irrepressibles, Broncho, The Shins and
 * Small Black, all indie, and of the 33 tracks that came out exactly 2 were
 * post-rock. The listener asked for post rock and got indie.
 *
 * The family stays the outer boundary (see [seedJoinsCluster]); this only decides
 * who gets seated inside it, so a thin scene still fills a mix from the wider
 * family instead of failing.
 */
class SeedClusterGenreOrderTest {

    private fun seed(name: String) = SeedTrack(
        trackUri = "library://track/$name",
        trackName = name,
        artistName = name,
        lastPlayedAt = 0L
    )

    private val genres = mapOf(
        "GodIsAnAstronaut" to listOf("post rock", "instrumental rock"),
        "Caspian" to listOf("post-rock", "instrumental"),
        "WeLostTheSea" to listOf("post rock"),
        "TheShins" to listOf("indie rock", "indie pop"),
        "Broncho" to listOf("indie", "garage rock"),
        "Elbow" to listOf("alternative", "indie rock")
    )

    private fun split(others: List<String>, primary: String) =
        partitionByExactGenre(others.map { seed(it) }, genres.getValue(primary)) {
            genres[it.artistName].orEmpty()
        }

    @Test
    fun `seeds of the primary's own genre are seated first`() {
        val (sameGenre, widerFamily) = split(
            listOf("TheShins", "Caspian", "Broncho", "WeLostTheSea", "Elbow"),
            primary = "GodIsAnAstronaut"
        )

        assertThat(sameGenre.map { it.artistName }).containsExactly("Caspian", "WeLostTheSea").inOrder()
        assertThat(widerFamily.map { it.artistName })
            .containsExactly("TheShins", "Broncho", "Elbow").inOrder()
    }

    @Test
    fun `the hyphenated and spaced spellings are the same genre`() {
        // The library really holds both: 65 artists tagged `post rock` and 33
        // tagged `post-rock`. Comparing them raw would split one scene in two.
        val (sameGenre, _) = split(listOf("Caspian"), primary = "GodIsAnAstronaut")
        assertThat(sameGenre.map { it.artistName }).containsExactly("Caspian")
    }

    @Test
    fun `an indie anchor seats indie seeds, not the post-rock ones`() {
        val (sameGenre, widerFamily) = split(
            listOf("GodIsAnAstronaut", "Elbow", "Caspian", "Broncho"),
            primary = "TheShins"
        )

        assertThat(sameGenre.map { it.artistName }).containsExactly("Elbow")
        assertThat(widerFamily.map { it.artistName })
            .containsExactly("GodIsAnAstronaut", "Caspian", "Broncho").inOrder()
    }

    @Test
    fun `ordering within each group is preserved, so scoring still decides`() {
        // Both groups must come back in the caller's order: the caller has already
        // applied strictness weighting and productive-first, and this must not
        // reshuffle either.
        val (sameGenre, widerFamily) = split(
            listOf("WeLostTheSea", "Elbow", "Caspian", "TheShins", "Broncho"),
            primary = "GodIsAnAstronaut"
        )

        assertThat(sameGenre.map { it.artistName }).isEqualTo(listOf("WeLostTheSea", "Caspian"))
        assertThat(widerFamily.map { it.artistName }).isEqualTo(listOf("Elbow", "TheShins", "Broncho"))
    }

    @Test
    fun `a primary with no genres leaves the cluster order untouched`() {
        val others = listOf("Caspian", "TheShins").map { seed(it) }
        val (sameGenre, widerFamily) = partitionByExactGenre(others, emptyList()) {
            genres[it.artistName].orEmpty()
        }

        assertThat(sameGenre).isEmpty()
        assertThat(widerFamily).isEqualTo(others)
    }

    @Test
    fun `a seed with no genres of its own falls to the wider family`() {
        val others = listOf(seed("Unknown"), seed("Caspian"))
        val (sameGenre, widerFamily) = partitionByExactGenre(
            others,
            genres.getValue("GodIsAnAstronaut")
        ) { genres[it.artistName].orEmpty() }

        assertThat(sameGenre.map { it.artistName }).containsExactly("Caspian")
        assertThat(widerFamily.map { it.artistName }).containsExactly("Unknown")
    }
}
