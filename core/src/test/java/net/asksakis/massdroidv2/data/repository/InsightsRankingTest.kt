package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.data.database.GenrePlayTimestamp
import net.asksakis.massdroidv2.data.database.InsightsPlayRow
import org.junit.Test

/**
 * Pins how the Recommendation insights lists merge copies of one item. The cases come from
 * the S25's database on 2026-10-05: "Kings of Leon" and "Kings Of Leon" ranked as two
 * artists, "I Want Everything" was stored under six uris, and every blocked artist was
 * stored under a Deezer uri while the library copy carried the plays.
 */
class InsightsRankingTest {

    private val lib = "library://artist/57"
    private val dz = "deezer--x://artist/385"

    @Test
    fun `letter-case variants of an artist merge into one entry linked to the library copy`() {
        val plays = InsightsRanking.plays(
            row(1, track = "deezer--x://track/1", artist = dz, artistName = "Kings Of Leon") +
                row(2, track = "library://track/9", artist = lib, artistName = "Kings of Leon") +
                row(3, track = "library://track/9", artist = lib, artistName = "Kings of Leon")
        )

        val artists = InsightsRanking.rankArtists(plays, emptySet(), limit = 10)

        assertThat(artists).hasSize(1)
        assertThat(artists.single().uri).isEqualTo(lib)
        assertThat(artists.single().name).isEqualTo("Kings of Leon")
        assertThat(artists.single().plays).isEqualTo(3)
    }

    @Test
    fun `a play crediting two copies of one artist counts once`() {
        val plays = InsightsRanking.plays(
            row(1, artist = lib, artistName = "Kings of Leon") +
                row(1, artist = dz, artistName = "Kings Of Leon")
        )

        val artists = InsightsRanking.rankArtists(plays, emptySet(), limit = 10)

        assertThat(artists.single().plays).isEqualTo(1)
    }

    @Test
    fun `artists are ordered by play count, a later last play first on a tie`() {
        val plays = InsightsRanking.plays(
            row(1, artist = "library://artist/1", artistName = "Often", playedAt = 10) +
                row(2, artist = "library://artist/1", artistName = "Often", playedAt = 11) +
                row(3, artist = "library://artist/2", artistName = "Once, long ago", playedAt = 5) +
                row(4, artist = "library://artist/3", artistName = "Once, just now", playedAt = 1_000)
        )

        val artists = InsightsRanking.rankArtists(plays, emptySet(), limit = 10)

        assertThat(artists.map { it.name }).containsExactly("Often", "Once, just now", "Once, long ago").inOrder()
    }

    @Test
    fun `an artist blocked under any of its uris is left out with its tracks and albums`() {
        val plays = InsightsRanking.plays(
            row(1, track = "library://track/9", artist = lib, artistName = "Kings of Leon", album = "library://album/3") +
                row(2, track = "deezer--x://track/1", artist = dz, artistName = "Kings Of Leon", album = "library://album/3")
        )
        val blocked = setOf(dz)

        assertThat(InsightsRanking.rankArtists(plays, blocked, limit = 10)).isEmpty()
        assertThat(InsightsRanking.rankTracks(plays, blocked, limit = 10).map { it.uri })
            .containsExactly("library://track/9")
        assertThat(InsightsRanking.rankAlbums(plays, blocked, limit = 10)).isEmpty()
    }

    @Test
    fun `a same-named act is not blocked by name alone`() {
        val plays = InsightsRanking.plays(row(1, artist = lib, artistName = "K.O"))

        val artists = InsightsRanking.rankArtists(plays, setOf("deezer--x://artist/557447"), limit = 10)

        assertThat(artists).hasSize(1)
    }

    @Test
    fun `provider copies of a track merge by title and artist and link to the library copy`() {
        val plays = InsightsRanking.plays(
            row(1, track = "deezer--x://track/102753000", trackName = "I Want Everything", artistName = "Chocolate Avenue") +
                row(2, track = "deezer--x://track/102753002", trackName = "I want everything", artistName = "Chocolate Avenue") +
                row(3, track = "library://track/678", trackName = "I Want Everything", artistName = "Chocolate Avenue") +
                row(4, track = "library://track/1", trackName = "Other", artistName = "Someone")
        )

        val tracks = InsightsRanking.rankTracks(plays, emptySet(), limit = 10)

        assertThat(tracks.first().uri).isEqualTo("library://track/678")
        assertThat(tracks.first().plays).isEqualTo(3)
        assertThat(tracks.first().artistName).isEqualTo("Chocolate Avenue")
        assertThat(tracks).hasSize(2)
    }

    @Test
    fun `a track opens the library copy of its album`() {
        val plays = InsightsRanking.plays(
            row(1, track = "deezer--x://track/1", trackName = "Song", album = "deezer--x://album/5", albumName = "Record") +
                row(2, track = "library://track/9", trackName = "Song", album = "library://album/3", albumName = "Record")
        )

        val track = InsightsRanking.rankTracks(plays, emptySet(), limit = 10).single()

        assertThat(track.albumUri).isEqualTo("library://album/3")
        assertThat(track.albumName).isEqualTo("Record")
    }

    @Test
    fun `a track with no album has nothing to open`() {
        val plays = InsightsRanking.plays(row(1, trackName = "Single"))

        assertThat(InsightsRanking.rankTracks(plays, emptySet(), limit = 10).single().albumUri).isNull()
    }

    @Test
    fun `tracks with the same title by different artists stay apart`() {
        val plays = InsightsRanking.plays(
            row(1, track = "library://track/1", trackName = "Intro", artist = "library://artist/1", artistName = "A") +
                row(2, track = "library://track/2", trackName = "Intro", artist = "library://artist/2", artistName = "B")
        )

        assertThat(InsightsRanking.rankTracks(plays, emptySet(), limit = 10)).hasSize(2)
    }

    @Test
    fun `albums with the same title by different artists stay apart`() {
        val plays = InsightsRanking.plays(
            row(1, artist = "library://artist/1", artistName = "A", album = "library://album/1", albumName = "Greatest Hits") +
                row(2, artist = "library://artist/2", artistName = "B", album = "library://album/2", albumName = "Greatest Hits")
        )

        val albums = InsightsRanking.rankAlbums(plays, emptySet(), limit = 10)

        assertThat(albums.map { it.artistName }).containsExactly("A", "B")
    }

    @Test
    fun `a compilation keeps one row under its most credited artist`() {
        val plays = InsightsRanking.plays(
            row(1, artist = "library://artist/1", artistName = "Various", album = "library://album/7", albumName = "Mix") +
                row(2, artist = "library://artist/1", artistName = "Various", album = "library://album/7", albumName = "Mix") +
                row(3, artist = "library://artist/2", artistName = "Guest", album = "library://album/7", albumName = "Mix")
        )

        val albums = InsightsRanking.rankAlbums(plays, emptySet(), limit = 10)

        assertThat(albums).hasSize(1)
        assertThat(albums.single().artistName).isEqualTo("Various")
        assertThat(albums.single().plays).isEqualTo(3)
    }

    @Test
    fun `genre plays count each play once even when it reaches the genre twice`() {
        val rows = listOf(
            GenrePlayTimestamp("rock", playedAt = 1, listenedMs = null, duration = null),
            GenrePlayTimestamp("rock", playedAt = 1, listenedMs = null, duration = null),
            GenrePlayTimestamp("rock", playedAt = 2, listenedMs = null, duration = null)
        )

        val genres = InsightsRanking.rankGenres(rows, limit = 10)

        assertThat(genres.single().plays).isEqualTo(2)
    }

    @Test
    fun `preferred uri falls back to the same provider copy every time`() {
        assertThat(InsightsRanking.preferredUri(listOf("deezer--x://track/2", "deezer--x://track/1")))
            .isEqualTo("deezer--x://track/1")
    }

    private fun row(
        playId: Long,
        track: String = "library://track/$playId",
        trackName: String = "Track $playId",
        artist: String? = lib,
        artistName: String? = "Kings of Leon",
        album: String? = null,
        albumName: String? = album?.let { "Album" },
        playedAt: Long = playId
    ) = listOf(
        InsightsPlayRow(
            playId = playId,
            playedAt = playedAt,
            listenedMs = null,
            duration = null,
            trackUri = track,
            trackName = trackName,
            albumUri = album,
            albumName = albumName,
            imageUrl = null,
            year = null,
            artistUri = artist,
            artistName = artistName
        )
    )
}
