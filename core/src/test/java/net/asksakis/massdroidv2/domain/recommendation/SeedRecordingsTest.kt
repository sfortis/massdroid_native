package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.SeedTrack
import org.junit.Test

/**
 * Pins that a seed cannot come back into its own mix under another uri. The track
 * route excluded the seed by uri only, and on 2026-11-25 three of four mixes
 * played one of their own seeds again.
 */
class SeedRecordingsTest {

    private val seeds = SeedRecordings(
        listOf(
            SeedTrack(
                trackUri = "library://track/1",
                trackName = "My Silver Lining",
                artistName = "First Aid Kit",
                lastPlayedAt = 0L
            ),
            SeedTrack(
                trackUri = "library://track/2",
                trackName = "Epitaph for a Dying Star",
                artistName = "Nachtmystium",
                lastPlayedAt = 0L
            )
        )
    )

    private fun track(uri: String, artists: String, title: String, primary: String = "") = Track(
        itemId = uri.substringAfterLast("/"),
        provider = uri.substringBefore("://"),
        name = title,
        uri = uri,
        artistNames = artists,
        primaryArtistName = primary
    )

    @Test
    fun `the seed under its own uri is matched`() {
        assertThat(seeds.contains(track("library://track/1", "Anyone", "Anything"))).isTrue()
    }

    @Test
    fun `the seed under another provider's uri is matched`() {
        assertThat(seeds.contains(track("deezer://track/9", "First Aid Kit", "My Silver Lining"))).isTrue()
    }

    @Test
    fun `the seed credited with a guest is matched by its lead artist`() {
        val withGuest = track(
            "deezer://track/10",
            "Nachtmystium, Scott Judd",
            "Epitaph for a Dying Star",
            primary = "Nachtmystium"
        )
        assertThat(seeds.contains(withGuest)).isTrue()
    }

    @Test
    fun `another track by a seed artist is not matched`() {
        assertThat(seeds.contains(track("deezer://track/11", "First Aid Kit", "Emmylou"))).isFalse()
    }

    @Test
    fun `the same title by another artist is not matched`() {
        assertThat(seeds.contains(track("deezer://track/12", "Someone Else", "My Silver Lining"))).isFalse()
    }
}
