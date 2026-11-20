package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * Pins what a delivered mix is allowed to consume.
 *
 * These cooldowns used to be written during the build, before anyone knew what Music
 * Assistant would take. A delivery that failed therefore still suppressed all 33
 * tracks and their artists from later mixes, and that is not a rare edge: the server
 * fails the whole `play_media` call when any item carries a zero year, measured 21
 * times in 24 hours on a real server. The rule now is that only what the queue
 * accepted costs anything.
 */
class MixCooldownTest {

    private data class Result(
        override val acceptedUris: List<String>,
        override val rejectedUris: List<String> = emptyList()
    ) : QueueLoadResultView

    private fun track(id: String, artist: String, artistUri: String? = null) = Track(
        itemId = id,
        provider = "deezer",
        name = "Track $id",
        uri = "deezer://track/$id",
        artistNames = artist,
        artistUri = artistUri
    )

    private val mix = listOf(
        track("1", "Caspian", "library://artist/1"),
        track("2", "Hammock", "library://artist/2"),
        track("3", "Alcest", "library://artist/3")
    )

    @Test
    fun `a fully accepted mix consumes all of itself`() {
        val consumed = cooldownFor(mix, Result(mix.map { it.uri }))

        assertThat(consumed.trackUris).hasSize(3)
        // Each artist is recorded under its URI key and its name.
        assertThat(consumed.artistKeys).hasSize(6)
    }

    @Test
    fun `a rejected track costs nothing, and neither does its artist`() {
        val consumed = cooldownFor(
            mix,
            Result(
                acceptedUris = listOf("deezer://track/1"),
                rejectedUris = listOf("deezer://track/2", "deezer://track/3")
            )
        )

        assertThat(consumed.trackUris).containsExactly("deezer://track/1")
        // Hammock and Alcest never reached the queue, so they stay eligible.
        assertThat(consumed.artistKeys).containsExactly("library://artist/1", "caspian")
    }

    @Test
    fun `a delivery that accepted nothing consumes nothing`() {
        val consumed = cooldownFor(mix, Result(emptyList(), mix.map { it.uri }))

        assertThat(consumed.trackUris).isEmpty()
        assertThat(consumed.artistKeys).isEmpty()
    }

    @Test
    fun `an artist survives the cooldown when any one of their tracks was accepted`() {
        val twoBySameArtist = listOf(
            track("10", "Caspian", "library://artist/1"),
            track("11", "Caspian", "library://artist/1")
        )
        val consumed = cooldownFor(
            twoBySameArtist,
            Result(listOf("deezer://track/10"), listOf("deezer://track/11"))
        )

        assertThat(consumed.trackUris).containsExactly("deezer://track/10")
        assertThat(consumed.artistKeys).containsExactly("library://artist/1", "caspian")
    }

    @Test
    fun `an artist with no uri falls back to the credited name`() {
        val nameOnly = listOf(track("20", "Some Band, Featured Guest"))
        val consumed = cooldownFor(nameOnly, Result(listOf("deezer://track/20")))

        assertThat(consumed.artistKeys).containsExactly("some band")
    }

    @Test
    fun `uris the mix never contained are ignored`() {
        // Defensive: the accepted list comes back from the server, so it must not be
        // able to inject a cooldown for something that was never in this mix.
        val consumed = cooldownFor(mix, Result(listOf("deezer://track/1", "deezer://track/999")))

        assertThat(consumed.trackUris).containsExactly("deezer://track/1")
    }

    @Test
    fun `a consumed artist is penalized in the next seed-track mix`() {
        // The window used to hold only URI keys while the seed-track engine looked
        // artists up by name, so this penalty was always zero.
        val consumed = cooldownFor(mix, Result(mix.map { it.uri }))
        val recency = SeedTrackMixGenerator.Recency(
            excludedTrackUris = emptySet(),
            recentArtistCounts = listOf(consumed.artistKeys).flatten().groupingBy { it }.eachCount(),
            recentMixTrackUris = emptySet()
        )
        fun penaltyOf(candidate: Track) = recentArtistPenalty(
            recentArtistCount(trackArtistCooldownKeys(candidate), recency.recentArtistCounts),
            perMix = 1.0
        )

        // The same artist URI.
        assertThat(penaltyOf(track("30", "Caspian", "library://artist/1"))).isEqualTo(1.0)
        // The same name under another provider's URI.
        assertThat(penaltyOf(track("31", "Caspian", "deezer://artist/777"))).isEqualTo(1.0)
        // An artist who was not in the mix.
        assertThat(penaltyOf(track("32", "Mogwai", "library://artist/9"))).isEqualTo(0.0)
    }

    @Test
    fun `an artist counts once per mix even though it has two keys`() {
        val first = cooldownFor(mix, Result(mix.map { it.uri }))
        val second = cooldownFor(
            listOf(track("40", "Caspian", "deezer://artist/777")),
            Result(listOf("deezer://track/40"))
        )
        val counts = listOf(first.artistKeys, second.artistKeys).flatten().groupingBy { it }.eachCount()

        val keys = trackArtistCooldownKeys(track("41", "Caspian", "library://artist/1"))
        assertThat(recentArtistCount(keys, counts)).isEqualTo(2)
    }
}
