package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.SeedTrack
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

    private fun track(
        id: String,
        artist: String,
        artistUri: String? = null,
        primaryArtistName: String = ""
    ) = Track(
        itemId = id,
        provider = "deezer",
        name = "Track $id",
        uri = "deezer://track/$id",
        artistNames = artist,
        artistUri = artistUri,
        primaryArtistName = primaryArtistName
    )

    /** The seed-track engine's penalty for [candidate], with a penalty of 1.0 per mix. */
    private fun penaltyOf(candidate: Track, windows: List<Set<String>>) = recentArtistPenalty(
        recentArtistCount(trackArtistCooldownLookupKeys(candidate), windows),
        perMix = 1.0
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
        assertThat(consumed.artistKeys).containsExactly("library://artist/1", "library-name:caspian")
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
        assertThat(consumed.artistKeys).containsExactly("library://artist/1", "library-name:caspian")
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
            recentArtistWindows = listOf(consumed.artistKeys),
            recentMixTrackUris = emptySet()
        )
        val windows = recency.recentArtistWindows

        // The same artist URI.
        assertThat(penaltyOf(track("30", "Caspian", "library://artist/1"), windows)).isEqualTo(1.0)
        // The same name under another provider's URI.
        assertThat(penaltyOf(track("31", "Caspian", "deezer://artist/777"), windows)).isEqualTo(1.0)
        // An artist who was not in the mix.
        assertThat(penaltyOf(track("32", "Mogwai", "library://artist/9"), windows)).isEqualTo(0.0)
    }

    @Test
    fun `an artist counts once per mix even though it has two keys`() {
        val first = cooldownFor(mix, Result(mix.map { it.uri }))
        val second = cooldownFor(
            listOf(track("40", "Caspian", "deezer://artist/777")),
            Result(listOf("deezer://track/40"))
        )
        val windows = listOf(first.artistKeys, second.artistKeys)

        val keys = trackArtistCooldownLookupKeys(track("41", "Caspian", "library://artist/1"))
        assertThat(recentArtistCount(keys, windows)).isEqualTo(2)
    }

    @Test
    fun `two library artists with the same name do not share a cooldown`() {
        // Music Assistant merges all providers of one artist into one library item,
        // so two library ids are two different artists.
        val nirvana = listOf(track("50", "Nirvana", "library://artist/1"))
        val windows = listOf(cooldownFor(nirvana, Result(listOf("deezer://track/50"))).artistKeys)

        assertThat(penaltyOf(track("51", "Nirvana", "library://artist/2"), windows)).isEqualTo(0.0)
        // A provider artist of the same name is still taken for the library one.
        assertThat(penaltyOf(track("52", "Nirvana", "deezer://artist/555"), windows)).isEqualTo(1.0)
    }

    @Test
    fun `an injected loved track keeps same-name library artists apart`() {
        val injected = lovedInjectionTrack(
            SeedTrack(
                trackUri = "library://track/90",
                trackName = "Lithium",
                artistName = "Nirvana",
                artistUri = "library://artist/1",
                lastPlayedAt = 0L
            )
        )
        val windows = listOf(cooldownFor(listOf(injected), Result(listOf(injected.uri))).artistKeys)

        assertThat(penaltyOf(track("91", "Nirvana", "library://artist/1"), windows)).isEqualTo(1.0)
        assertThat(penaltyOf(track("92", "Nirvana", "library://artist/2"), windows)).isEqualTo(0.0)
    }

    @Test
    fun `a library artist finds a provider artist of the same name`() {
        val deezerNirvana = listOf(track("60", "Nirvana", "deezer://artist/555"))
        val windows = listOf(cooldownFor(deezerNirvana, Result(listOf("deezer://track/60"))).artistKeys)

        assertThat(penaltyOf(track("61", "Nirvana", "library://artist/1"), windows)).isEqualTo(1.0)
    }

    @Test
    fun `windows written by older versions still match`() {
        // Before the library prefix every name was stored plain, and before that the
        // window held URI keys only. Both still count, without a migration.
        val withPlainName = setOf("library://artist/1", "nirvana")
        val uriOnly = setOf("library://artist/1")

        assertThat(penaltyOf(track("70", "Nirvana", "library://artist/1"), listOf(withPlainName, uriOnly)))
            .isEqualTo(2.0)
        assertThat(penaltyOf(track("71", "Nirvana", "deezer://artist/555"), listOf(withPlainName))).isEqualTo(1.0)
    }

    @Test
    fun `the name key is the first artist's own name`() {
        val credited = track(
            "80",
            "Tyler, The Creator, Frank Ocean",
            "deezer://artist/888",
            primaryArtistName = "Tyler, The Creator"
        )
        assertThat(trackArtistCooldownKeys(credited)).containsExactly("deezer://artist/888", "tyler, the creator")

        // Without the artist list, the joined credits are split at the first comma.
        val joinedOnly = track("81", "Some Band, Featured Guest", "deezer://artist/889")
        assertThat(trackArtistCooldownKeys(joinedOnly)).containsExactly("deezer://artist/889", "some band")
    }
}
