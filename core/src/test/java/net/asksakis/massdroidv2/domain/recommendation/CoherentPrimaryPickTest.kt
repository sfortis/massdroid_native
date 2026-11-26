package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins which primary a mix is built around. A primary whose coherent cluster held
 * only 2 seeds (The Cinematic Orchestra) produced a short mix that was filled with
 * loved injections and off-family tracks.
 */
class CoherentPrimaryPickTest {

    private val coherentSeeds = mapOf(
        "The Cinematic Orchestra" to 2,
        "Toundra" to 1,
        "Temples" to 8,
        "Actors" to 6
    )

    @Test
    fun `a primary with 2 coherent seeds is passed over for one with 8`() {
        val pick = pickCoherentPrimary(listOf("The Cinematic Orchestra", "Temples"), MIN_COHERENT_SEEDS, { it }) {
            coherentSeeds.getValue(it)
        }

        assertThat(pick.primary).isEqualTo("Temples")
        assertThat(pick.rejected).containsExactly("The Cinematic Orchestra" to 2)
    }

    @Test
    fun `the first primary that reaches the floor is taken in the drawn order`() {
        val pick = pickCoherentPrimary(listOf("Toundra", "Actors", "Temples"), MIN_COHERENT_SEEDS, { it }) {
            coherentSeeds.getValue(it)
        }

        assertThat(pick.primary).isEqualTo("Actors")
        assertThat(pick.rejected).containsExactly("Toundra" to 1)
    }

    @Test
    fun `the first primary is not re-checked when it already reaches the floor`() {
        val asked = mutableListOf<String>()
        val pick = pickCoherentPrimary(listOf("Temples", "Toundra"), MIN_COHERENT_SEEDS, { it }) {
            asked += it
            coherentSeeds.getValue(it)
        }

        assertThat(pick.primary).isEqualTo("Temples")
        assertThat(pick.rejected).isEmpty()
        assertThat(asked).containsExactly("Temples")
    }

    @Test
    fun `when no primary reaches the floor the one with the most coherent seeds is taken`() {
        val pick = pickCoherentPrimary(listOf("Toundra", "The Cinematic Orchestra"), MIN_COHERENT_SEEDS, { it }) {
            coherentSeeds.getValue(it)
        }

        assertThat(pick.primary).isEqualTo("The Cinematic Orchestra")
        assertThat(pick.cluster).isEqualTo("The Cinematic Orchestra")
        assertThat(pick.rejected).containsExactly("Toundra" to 1)
    }

    @Test
    fun `a tie below the floor keeps the earlier primary`() {
        val pick = pickCoherentPrimary(listOf("A", "B"), MIN_COHERENT_SEEDS, { it }) { 2 }

        assertThat(pick.primary).isEqualTo("A")
        assertThat(pick.rejected).containsExactly("B" to 2)
    }
}
