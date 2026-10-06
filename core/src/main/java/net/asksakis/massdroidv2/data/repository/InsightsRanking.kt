package net.asksakis.massdroidv2.data.repository

import net.asksakis.massdroidv2.data.database.GenrePlayTimestamp
import net.asksakis.massdroidv2.data.database.InsightsPlayRow
import net.asksakis.massdroidv2.domain.repository.InsightsAlbum
import net.asksakis.massdroidv2.domain.repository.InsightsArtist
import net.asksakis.massdroidv2.domain.repository.InsightsGenre
import net.asksakis.massdroidv2.domain.repository.InsightsTrack
import java.util.Locale

/**
 * Builds the Recommendation insights lists from one row per play and credited artist.
 * Every list is ordered by play count in the window, a later last play first on a tie,
 * so the order matches the number shown next to each entry.
 *
 * The same artist, track or album is often stored under several uris: the library copy
 * and one per provider (`deezer--x://`). Music Assistant also spells an artist with
 * different letter cases across providers ("Kings of Leon", "Kings Of Leon"). Ranked by
 * uri or by exact name, each copy took its own place in a list and the plays were split
 * between them. Here the copies are merged by a case-insensitive key: the artist's name,
 * a track's title with its artists, and an album's title with its most played artist.
 * Each merged entry links to its library copy when there is one.
 *
 * Blocked artists are matched by uri only. A block is stored under every uri the server
 * knows for that artist, and two acts that share a name must not block each other.
 */
internal object InsightsRanking {

    /** One artist credit on a played track. */
    data class Credit(val uri: String, val name: String)

    /** One play, rebuilt from its rows, with every artist credited on the track. */
    data class Play(
        val playId: Long,
        val playedAt: Long,
        val listenedMs: Long?,
        val duration: Double?,
        val trackUri: String,
        val trackName: String,
        val albumUri: String?,
        val albumName: String?,
        val imageUrl: String?,
        val year: Int?,
        val credits: List<Credit>
    )

    /** Folds the per-artist rows back into plays, keeping the rows' order. */
    fun plays(rows: List<InsightsPlayRow>): List<Play> =
        rows.groupBy { it.playId }.map { (playId, playRows) ->
            val first = playRows.first()
            Play(
                playId = playId,
                playedAt = first.playedAt,
                listenedMs = first.listenedMs,
                duration = first.duration,
                trackUri = first.trackUri,
                trackName = first.trackName,
                albumUri = first.albumUri,
                albumName = first.albumName,
                imageUrl = first.imageUrl,
                year = first.year,
                credits = playRows.mapNotNull { row ->
                    val uri = row.artistUri ?: return@mapNotNull null
                    val name = row.artistName ?: return@mapNotNull null
                    Credit(uri, name)
                }.distinctBy { it.uri }
            )
        }

    /** Artists by play count. A play crediting two copies of the same artist counts once. */
    fun rankArtists(plays: List<Play>, blockedUris: Set<String>, limit: Int): List<InsightsArtist> {
        val groups = LinkedHashMap<String, MergedArtist>()
        for (play in plays) {
            for (credit in play.credits) {
                val group = groups.getOrPut(key(credit.name)) { MergedArtist() }
                group.nameByUri.putIfAbsent(credit.uri, credit.name)
                group.plays[play.playId] = play
            }
        }
        return groups.values
            .filterNot { group -> group.nameByUri.keys.any { it in blockedUris } }
            .sortedWith(
                compareByDescending<MergedArtist> { it.plays.size }
                    .thenByDescending { group -> group.plays.values.maxOf { it.playedAt } }
            )
            .take(limit)
            .map { group ->
                val uri = preferredUri(group.nameByUri.keys)
                InsightsArtist(uri, group.nameByUri.getValue(uri), group.plays.size)
            }
    }

    /**
     * Genres by play count. A play reaches a genre once through the track and again through
     * each of its artists, so the count is of distinct plays.
     */
    fun rankGenres(rows: List<GenrePlayTimestamp>, limit: Int): List<InsightsGenre> =
        rows.groupBy { it.genre }
            .map { (genre, genreRows) -> genre to genreRows.map { it.playedAt }.toSet() }
            .sortedWith(
                compareByDescending<Pair<String, Set<Long>>> { it.second.size }
                    .thenByDescending { it.second.max() }
            )
            .take(limit)
            .map { (genre, playTimes) -> InsightsGenre(genre, playTimes.size) }

    /**
     * Tracks ordered by play count, a later last play first on a tie. Each track names the
     * album it opens: the library copy of any album its copies were played from.
     */
    fun rankTracks(plays: List<Play>, blockedUris: Set<String>, limit: Int): List<InsightsTrack> {
        val groups = LinkedHashMap<String, MutableList<Play>>()
        for (play in plays) {
            if (play.credits.any { it.uri in blockedUris }) continue
            val artistsKey = play.credits.map { key(it.name) }.sorted().joinToString("|")
            groups.getOrPut(key(play.trackName) + "\u0000" + artistsKey) { mutableListOf() }.add(play)
        }
        return groups.values
            .sortedWith(compareByDescending<List<Play>> { it.size }.thenByDescending { group -> group.maxOf { it.playedAt } })
            .take(limit)
            .map { group ->
                val uri = preferredUri(group.map { it.trackUri })
                val shown = group.first { it.trackUri == uri }
                val albums = group.filter { it.albumUri != null && !it.albumName.isNullOrBlank() }
                val albumUri = albums.mapNotNull { it.albumUri }.takeIf { it.isNotEmpty() }?.let(::preferredUri)
                InsightsTrack(
                    uri = uri,
                    name = shown.trackName,
                    artistName = shown.credits.joinToString(", ") { it.name },
                    albumUri = albumUri,
                    albumName = albums.firstOrNull { it.albumUri == albumUri }?.albumName,
                    plays = group.size
                )
            }
    }

    /**
     * Albums ordered by play count, a later last play first on a tie. An album's artist is
     * the one credited most often on its played tracks, so a compilation keeps one row.
     * An album is left out when that artist is blocked.
     */
    fun rankAlbums(plays: List<Play>, blockedUris: Set<String>, limit: Int): List<InsightsAlbum> {
        val byUri = LinkedHashMap<String, MutableList<Play>>()
        for (play in plays) {
            val uri = play.albumUri ?: continue
            if (play.albumName.isNullOrBlank()) continue
            byUri.getOrPut(uri) { mutableListOf() }.add(play)
        }
        val groups = LinkedHashMap<String, MutableList<AlbumCopy>>()
        for ((uri, albumPlays) in byUri) {
            val credits = albumPlays.flatMap { it.credits }
            val artistKey = credits.groupingBy { key(it.name) }.eachCount().maxByOrNull { it.value }?.key
            val artistCredits = credits.filter { key(it.name) == artistKey }
            if (artistCredits.any { it.uri in blockedUris }) continue
            val name = albumPlays.first().albumName.orEmpty()
            val copy = AlbumCopy(uri, name, albumPlays, artistCredits.firstOrNull()?.name.orEmpty())
            groups.getOrPut(key(name) + "\u0000" + artistKey.orEmpty()) { mutableListOf() }.add(copy)
        }
        return groups.values
            .map { copies -> copies to copies.flatMap { it.plays } }
            .sortedWith(
                compareByDescending<Pair<List<AlbumCopy>, List<Play>>> { it.second.size }
                    .thenByDescending { (_, groupPlays) -> groupPlays.maxOf { it.playedAt } }
            )
            .take(limit)
            .map { (copies, groupPlays) ->
                val uri = preferredUri(copies.map { it.uri })
                val shown = copies.first { it.uri == uri }
                val first = shown.plays.first()
                InsightsAlbum(
                    uri = uri,
                    name = shown.name,
                    artistName = shown.artistName,
                    imageUrl = first.imageUrl ?: groupPlays.firstNotNullOfOrNull { it.imageUrl },
                    year = first.year ?: groupPlays.firstNotNullOfOrNull { it.year },
                    plays = groupPlays.size
                )
            }
    }

    /** The library copy when there is one, otherwise the same provider copy every time. */
    fun preferredUri(uris: Collection<String>): String =
        uris.filter { it.startsWith(LIBRARY_PREFIX) }.minOrNull() ?: uris.min()

    /** The merge key: letter case and runs of white space do not tell two copies apart. */
    fun key(text: String): String = text.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    private class MergedArtist {
        val nameByUri = LinkedHashMap<String, String>()
        val plays = LinkedHashMap<Long, Play>()
    }

    private class AlbumCopy(val uri: String, val name: String, val plays: List<Play>, val artistName: String)

    private const val LIBRARY_PREFIX = "library://"
    private val WHITESPACE = Regex("\\s+")
}
