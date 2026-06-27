package net.asksakis.massdroidv2.data.lastfm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure name-normalization tests for the seed-track similar resolver keys. */
class LastFmTrackSimilarResolverTest {

    @Test
    fun `normalizeName lowercases and collapses non-alphanumerics`() {
        assertThat(LastFmTrackSimilarResolver.normalizeName("The Beatles")).isEqualTo("the beatles")
        assertThat(LastFmTrackSimilarResolver.normalizeName("AC/DC")).isEqualTo("ac dc")
    }

    @Test
    fun `normalizeName strips parenthetical and bracketed suffixes`() {
        assertThat(LastFmTrackSimilarResolver.normalizeName("Song (Remix)")).isEqualTo("song")
        assertThat(LastFmTrackSimilarResolver.normalizeName("Track [Feat. X]")).isEqualTo("track")
    }

    @Test
    fun `normalizeName reduces a punctuation-only string to empty`() {
        assertThat(LastFmTrackSimilarResolver.normalizeName("---!!!***")).isEqualTo("")
        assertThat(LastFmTrackSimilarResolver.normalizeName("")).isEqualTo("")
    }

    @Test
    fun `sourceKey joins normalized artist and track with a pipe`() {
        assertThat(LastFmTrackSimilarResolver.sourceKey("Daft Punk", "One More Time"))
            .isEqualTo("daft punk|one more time")
        assertThat(LastFmTrackSimilarResolver.sourceKey("", "")).isEqualTo("|")
    }
}
