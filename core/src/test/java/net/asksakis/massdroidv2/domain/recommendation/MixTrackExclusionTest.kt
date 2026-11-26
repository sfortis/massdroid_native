package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how a mix candidate is matched against the excluded tracks: by its uri, and
 * by the recording (lead artist and title), because a skip can be stored under
 * another provider's uri for the same song.
 */
class MixTrackExclusionTest {

    private val recency = SeedTrackMixGenerator.Recency(
        excludedTrackUris = setOf("library://track/1"),
        excludedTrackKeys = setOf(trackIdentityKey("Skunk Anansie", "Weak")),
        recentArtistWindows = emptyList(),
        recentMixTrackUris = emptySet()
    )

    @Test
    fun `the excluded uri is matched`() {
        assertThat(recency.excludesTrack("library://track/1", "Anyone", "Anything")).isTrue()
    }

    @Test
    fun `the uri is compared in its canonical form`() {
        assertThat(recency.excludesTrack("library://track/1?version=2", "Anyone", "Anything")).isTrue()
    }

    @Test
    fun `the same recording under another provider's uri is matched`() {
        assertThat(recency.excludesTrack("deezer://track/77", "Skunk Anansie", "Weak")).isTrue()
    }

    @Test
    fun `another track is not matched`() {
        assertThat(recency.excludesTrack("deezer://track/78", "Skunk Anansie", "Hedonism")).isFalse()
    }
}
