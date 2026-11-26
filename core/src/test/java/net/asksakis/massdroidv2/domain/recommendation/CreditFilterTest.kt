package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * Pins which tracks the Smart Mix routes accept by their credits.
 *
 * Deezer lists a track among an artist's top tracks when the artist is only one
 * of its credits. "All You Need Is Love" from Pavarotti and Friends credits ten
 * artists, and it came back as Skunk Anansie's second top track, so it opened
 * rock mixes again and again.
 */
class CreditFilterTest {

    private fun track(
        id: String,
        artists: List<Pair<String, String?>>,
        withUris: Boolean = true
    ) = Track(
        itemId = id,
        provider = "deezer",
        name = "Track $id",
        uri = "deezer://track/$id",
        artistNames = artists.joinToString(", ") { it.first },
        artistUri = if (withUris) artists.firstOrNull()?.second else null,
        artistUris = if (withUris) artists.mapNotNull { it.second } else emptyList(),
        primaryArtistName = artists.firstOrNull()?.first.orEmpty()
    )

    private val skunkAnansie = "deezer://artist/1" to "Skunk Anansie"

    // Ten credits, Pavarotti first and Skunk Anansie fifth.
    private val charity = track(
        "2305674",
        listOf("Luciano Pavarotti" to "deezer://artist/100") +
            (2..4).map { "Artist $it" to "deezer://artist/${100 + it}" } +
            listOf(skunkAnansie.second to skunkAnansie.first) +
            (6..10).map { "Artist $it" to "deezer://artist/${100 + it}" }
    )

    @Test
    fun `a guest credit is not the expanded artist's own track`() {
        assertThat(isPrimaryArtistOf(charity, skunkAnansie.first, skunkAnansie.second)).isFalse()
    }

    @Test
    fun `the artist's own track is accepted by uri`() {
        val own = track("1", listOf("Skunk Anansie" to "deezer://artist/1"))
        assertThat(isPrimaryArtistOf(own, skunkAnansie.first, skunkAnansie.second)).isTrue()
    }

    @Test
    fun `the same artist under another uri is accepted by name`() {
        // A library artist's top tracks can carry the provider URI of the same artist.
        val own = track("2", listOf("Skunk Anansie" to "deezer://artist/1"))
        assertThat(isPrimaryArtistOf(own, "library://artist/59", "Skunk Anansie")).isTrue()
    }

    @Test
    fun `a cached track without artist refs is compared by its first name`() {
        val own = track("3", listOf("Skunk Anansie" to null, "Guest" to null), withUris = false)
        val other = track("4", listOf("Luciano Pavarotti" to null, "Skunk Anansie" to null), withUris = false)

        assertThat(isPrimaryArtistOf(own, skunkAnansie.first, skunkAnansie.second)).isTrue()
        assertThat(isPrimaryArtistOf(other, skunkAnansie.first, skunkAnansie.second)).isFalse()
    }

    @Test
    fun `without a primary artist name the lead is the first of the joined names`() {
        val own = track("13", listOf("Skunk Anansie" to null, "Guest" to null), withUris = false)
            .copy(primaryArtistName = "")
        val guest = track("14", listOf("Guest" to null, "Skunk Anansie" to null), withUris = false)
            .copy(primaryArtistName = "")

        assertThat(isPrimaryArtistOf(own, skunkAnansie.first, skunkAnansie.second)).isTrue()
        assertThat(isPrimaryArtistOf(guest, skunkAnansie.first, skunkAnansie.second)).isFalse()
    }

    @Test
    fun `a Greek name matches across case and accents and not a name that only adds a number`() {
        val own = track("15", listOf("ΤΡΥΠΕΣ" to "deezer://artist/30"))
        val other = track("16", listOf("Τρύπες 2" to "deezer://artist/31"))

        assertThat(isPrimaryArtistOf(own, "library://artist/30", "Τρύπες")).isTrue()
        assertThat(isPrimaryArtistOf(other, "library://artist/30", "Τρύπες")).isFalse()
    }

    @Test
    fun `a track that credits more than four artists is a compilation`() {
        assertThat(isCompilationCredit(charity)).isTrue()

        val five = track("5", (1..5).map { "A$it" to "deezer://artist/$it" })
        val four = track("6", (1..4).map { "A$it" to "deezer://artist/$it" })
        assertThat(isCompilationCredit(five)).isTrue()
        assertThat(isCompilationCredit(four)).isFalse()
    }

    @Test
    fun `a duet passes both rules`() {
        val duet = track(
            "7",
            listOf("Thylacine" to "deezer://artist/7", "Catching Flies" to "deezer://artist/8")
        )
        assertThat(isCompilationCredit(duet)).isFalse()
        assertThat(isPrimaryArtistOf(duet, "deezer://artist/7", "Thylacine")).isTrue()
    }

    @Test
    fun `without refs the credits are counted from the joined names`() {
        val cachedCharity = track("8", (1..10).map { "Artist $it" to null }, withUris = false)
        val cachedFeature = track("9", listOf("Tyler" to null, "The Creator" to null, "Frank Ocean" to null), false)

        assertThat(isCompilationCredit(cachedCharity)).isTrue()
        assertThat(isCompilationCredit(cachedFeature)).isFalse()
    }

    @Test
    fun `rejected tracks do not use the artist's track budget`() {
        val own1 = track("10", listOf("Skunk Anansie" to "deezer://artist/1"))
        val own2 = track("11", listOf("Skunk Anansie" to "deezer://artist/1", "Guest" to "deezer://artist/9"))
        val ownCompilation = track(
            "12",
            listOf("Skunk Anansie" to "deezer://artist/1") + (2..6).map { "A$it" to "deezer://artist/$it" }
        )
        val counts = CreditFilterCounts()

        val kept = ownTopTracks(
            listOf(own1, charity, ownCompilation, own2),
            skunkAnansie.first,
            skunkAnansie.second,
            counts
        )

        assertThat(kept).containsExactly(own1, own2).inOrder()
        assertThat(counts.notPrimary).isEqualTo(1)
        assertThat(counts.compilation).isEqualTo(1)
    }
}
