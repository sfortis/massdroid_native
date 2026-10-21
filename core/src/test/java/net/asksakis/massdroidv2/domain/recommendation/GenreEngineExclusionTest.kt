package net.asksakis.massdroidv2.domain.recommendation

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import org.junit.Test

/**
 * A disliked recording stays out of the genre engine under every uri it has.
 *
 * The seed-track engine already matched rejections by recording identity, but the genre engine
 * (the Smart Mix fallback and the Genre Radio genre path) took only uris, so a second copy of a
 * "Not for me" track, under another provider or library id, came back.
 */
class GenreEngineExclusionTest {

    private val artist = "library://artist/1"

    private fun track(id: String, name: String, uri: String = "deezer://track/$id") = Track(
        itemId = id,
        provider = "deezer",
        name = name,
        uri = uri,
        artistNames = "Zed, Alma",
        artistUri = artist,
    )

    private fun build(excludedUris: Set<String>, excludedKeys: Set<String>): List<Track> = MixEngine().buildTracks(
        mode = MixMode.SmartMix(
            artistScores = emptyList(),
            genreScores = emptyList(),
            genreArtists = emptyMap(),
            recentArtistScoreMap = emptyMap(),
            daypartAffinityByArtist = emptyMap(),
        ),
        artistOrder = listOf(artist),
        tracksByArtist = mapOf(
            artist to listOf(
                track("1", "Disliked"),
                track("2", "Disliked", uri = "library://track/2"),
                track("3", "Kept"),
            )
        ),
        excludedArtistUris = emptySet(),
        excludedTrackUris = excludedUris,
        excludedTrackKeys = excludedKeys,
        favoriteArtistUris = emptySet(),
        favoriteAlbumUris = emptySet(),
        artistBaseScore = { 1.0 },
        target = 3,
        randomSeed = 1L,
    )

    @Test
    fun `the copy of a disliked track under another uri is left out`() {
        val mix = build(
            excludedUris = setOf("deezer://track/1"),
            excludedKeys = setOf(trackIdentityKey("Zed", "Disliked")),
        )

        assertThat(mix.map { it.name }).containsExactly("Kept")
    }

    @Test
    fun `without the identity only the excluded uri is left out`() {
        // The control: the uri set alone is what let the other copy in.
        val mix = build(excludedUris = setOf("deezer://track/1"), excludedKeys = emptySet())

        assertThat(mix.map { it.uri }).doesNotContain("deezer://track/1")
    }
}
