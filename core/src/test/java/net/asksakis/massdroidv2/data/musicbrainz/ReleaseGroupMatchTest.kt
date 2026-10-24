package net.asksakis.massdroidv2.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Pins which MusicBrainz release group an album is matched to. The cases come from the
 * owner's library on 2026-10-23, where the top-scored hit for the album "Warpaint" by
 * Warpaint was a different release.
 */
class ReleaseGroupMatchTest {

    private fun candidate(
        id: String,
        title: String,
        type: String?,
        artist: String = "Warpaint",
        year: Int? = null,
        score: Int = 100,
    ) = ReleaseGroupCandidate(id, title, type, listOf(artist), year, score)

    /** The search answer for "Warpaint" by Warpaint, reduced to its first three hits. */
    private val warpaintSearch = listOf(
        candidate("on", "Warpaint on Warpaint", "other", score = 100),
        candidate("album", "Warpaint", "album", year = 2014, score = 99),
        candidate("other-album", "Warpaint", "album", year = 2015, score = 99),
    )

    @Test
    fun `a higher scored release of another title is not taken`() {
        val picked = ReleaseGroupMatch.pick(warpaintSearch, "Warpaint", "Warpaint", "album", 2014)

        assertThat(picked?.id).isEqualTo("album")
    }

    @Test
    fun `the year chooses between releases of the same title`() {
        val picked = ReleaseGroupMatch.pick(warpaintSearch, "Warpaint", "Warpaint", "album", 2015)

        assertThat(picked?.id).isEqualTo("other-album")
    }

    @Test
    fun `a year that matches nothing still accepts the best match`() {
        // A reissue carries its own year; the release group carries the first one.
        val picked = ReleaseGroupMatch.pick(warpaintSearch, "Warpaint", "Warpaint", "album", 2022)

        assertThat(picked?.id).isAnyOf("album", "other-album")
    }

    @Test
    fun `another artist's release of the same title is refused`() {
        val search = listOf(candidate("x", "Warpaint", "album", artist = "Someone Else", score = 100))

        assertThat(ReleaseGroupMatch.pick(search, "Warpaint", "Warpaint", "album", null)).isNull()
    }

    @Test
    fun `a single is not matched to an album of the same name`() {
        val search = listOf(
            candidate("album", "Love Is to Die", "album", score = 100),
            candidate("single", "Love Is to Die", "single", score = 95),
        )

        assertThat(ReleaseGroupMatch.pick(search, "Warpaint", "Love Is to Die", "single", null)?.id)
            .isEqualTo("single")
    }

    @Test
    fun `an unknown type accepts album single and ep but not other`() {
        val search = listOf(
            candidate("other", "Love Is to Die", "other", score = 100),
            candidate("single", "Love Is to Die", "single", score = 92),
        )

        assertThat(ReleaseGroupMatch.pick(search, "Warpaint", "Love Is to Die", "unknown", null)?.id)
            .isEqualTo("single")
    }

    @Test
    fun `a score below the bar is refused`() {
        val search = listOf(candidate("low", "Warpaint", "album", score = ReleaseGroupMatch.MIN_SCORE - 1))

        assertThat(ReleaseGroupMatch.pick(search, "Warpaint", "Warpaint", "album", null)).isNull()
    }

    @Test
    fun `provider suffixes and punctuation do not stop a match`() {
        val search = listOf(candidate("deluxe", "Radiate Like This", "album", score = 100))

        assertThat(
            ReleaseGroupMatch.pick(search, "warpaint", "Radiate Like This (Deluxe Edition)", "album", null)?.id
        ).isEqualTo("deluxe")
        assertThat(ReleaseGroupMatch.withoutProviderSuffix("Love Is to Die - Single")).isEqualTo("Love Is to Die")
    }

    @Test
    fun `the search answer is read into candidates`() {
        val body = """
            {"release-groups":[{"id":"8f2c","score":99,"title":"Warpaint","primary-type":"Album",
              "first-release-date":"2014-01-17",
              "artist-credit":[{"name":"Warpaint","artist":{"id":"a1","name":"Warpaint"}}]},
             {"id":"no-date","score":"x","title":"Exquisite Corpse"}]}
        """.trimIndent()

        val parsed = ReleaseGroupMatch.parse(Json { ignoreUnknownKeys = true }, body)

        assertThat(parsed).containsExactly(
            ReleaseGroupCandidate("8f2c", "Warpaint", "album", listOf("Warpaint"), 2014, 99),
            ReleaseGroupCandidate("no-date", "Exquisite Corpse", null, emptyList(), null, 0),
        ).inOrder()
    }

    @Test
    fun `an answer that is not json gives no candidates`() {
        assertThat(ReleaseGroupMatch.parse(Json, "<html>busy</html>")).isEmpty()
    }
}
