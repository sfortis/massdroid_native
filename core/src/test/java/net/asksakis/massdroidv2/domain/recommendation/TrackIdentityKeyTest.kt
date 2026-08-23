package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how a recording is recognised across the several uris that serve it.
 *
 * A dislike used to be matched on uri alone, so the same song under a second uri
 * came straight back. Measured on a real library: of 22 tracks the listener had
 * explicitly disliked, 5 also existed under another uri, once as `deezer://` and
 * once as `library://` or as two releases of the same recording. Music Assistant
 * also resolves a requested track to a different version of its own accord.
 */
class TrackIdentityKeyTest {

    @Test
    fun `the same recording under different uris shares one key`() {
        val fromDeezer = trackIdentityKey("Caspian", "Sad Heart of Mine")
        val fromLibrary = trackIdentityKey("Caspian", "Sad Heart of Mine")

        assertThat(fromDeezer).isEqualTo(fromLibrary)
        assertThat(fromDeezer).isNotEmpty()
    }

    @Test
    fun `punctuation, case and spacing do not create a second identity`() {
        val plain = trackIdentityKey("Sigur Ros", "Hoppipolla")
        assertThat(trackIdentityKey("sigur  ros", "Hoppipolla")).isEqualTo(plain)
        assertThat(trackIdentityKey("Sigur-Ros", "hoppipolla")).isEqualTo(plain)
        assertThat(trackIdentityKey("SIGUR ROS", " Hoppipolla ")).isEqualTo(plain)
    }

    @Test
    fun `a remaster reads as a different recording`() {
        // Deliberate: the suffix is part of the title we were given, and treating
        // every parenthetical as noise would collapse genuinely different versions
        // (a remix is not the original). The uri match still covers the exact copy.
        assertThat(trackIdentityKey("Caspian", "Sad Heart of Mine (Remastered)"))
            .isNotEqualTo(trackIdentityKey("Caspian", "Sad Heart of Mine"))
    }

    @Test
    fun `different songs by one artist stay separate`() {
        assertThat(trackIdentityKey("Hammock", "Longest Year"))
            .isNotEqualTo(trackIdentityKey("Hammock", "Breathturn"))
    }

    @Test
    fun `the same title by different artists stays separate`() {
        assertThat(trackIdentityKey("Alcest", "Autre Temps"))
            .isNotEqualTo(trackIdentityKey("Lantlos", "Autre Temps"))
    }

    @Test
    fun `a missing half yields no identity, so callers fall back to the uri`() {
        assertThat(trackIdentityKey(null, "Some Song")).isEmpty()
        assertThat(trackIdentityKey("Some Artist", null)).isEmpty()
        assertThat(trackIdentityKey("", "")).isEmpty()
        // Punctuation only flattens to nothing, which must not match everything.
        assertThat(trackIdentityKey("...", "???")).isEmpty()
    }
}
