package net.asksakis.massdroidv2.domain.recommendation

import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random

private const val TOP_ARTISTS_LIMIT = 12
private const val TOP_GENRES_LIMIT = 4
private const val ARTISTS_PER_GENRE = 6
private const val MAX_TRACKS_PER_ARTIST = 2
private const val MAX_TRACK_DECADE_GAP = 20
private const val ARTIST_RANK_JITTER = 0.22
private const val TRACK_RANK_JITTER = 0.28
private const val FAVORITE_ARTIST_BONUS = 0.70
private const val FAVORITE_ALBUM_BONUS = 0.40
private const val FAVORITE_ARTISTS_CANDIDATE_LIMIT = 18

@Singleton
class SmartMixEngine @Inject constructor() {

    fun buildArtistOrder(
        artistScores: List<ArtistScore>,
        genreScores: List<GenreScore>,
        genreArtists: Map<String, List<String>>,
        excludedArtistUris: Set<String>,
        favoriteArtistUris: Set<String>,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        daypartAffinityByArtist: Map<String, Double>,
        artistDominantDecades: Map<String, Int>,
        focusDecade: Int?,
        randomSeed: Long = System.currentTimeMillis()
    ): List<String> {
        val random = Random(randomSeed)
        val artistScoreMap = artistScores.associate { it.artistUri to it.score }
        val topGenres = genreScores.take(TOP_GENRES_LIMIT).map { it.genre }

        val candidates = linkedSetOf<String>()
        favoriteArtistUris
            .asSequence()
            .filterNot { it in excludedArtistUris }
            .take(FAVORITE_ARTISTS_CANDIDATE_LIMIT)
            .forEach { candidates += it }
        artistScores
            .asSequence()
            .map { it.artistUri }
            .filterNot { it in excludedArtistUris }
            .take(TOP_ARTISTS_LIMIT)
            .forEach { candidates += it }

        for (genre in topGenres) {
            genreArtists[genre]
                .orEmpty()
                .asSequence()
                .filterNot { it in excludedArtistUris }
                .distinct()
                .sortedByDescending { uri ->
                    artistCompositeScore(
                        uri = uri,
                        artistScoreMap = artistScoreMap,
                        bllArtistScoreMap = bllArtistScoreMap,
                        smartArtistScoreMap = smartArtistScoreMap,
                        favoriteArtistUris = favoriteArtistUris,
                        daypartAffinityByArtist = daypartAffinityByArtist,
                        artistDominantDecades = artistDominantDecades,
                        focusDecade = focusDecade
                    )
                }
                .take(ARTISTS_PER_GENRE)
                .forEach { candidates += it }
        }

        val jitter = candidates.associateWith { random.nextDouble(-ARTIST_RANK_JITTER, ARTIST_RANK_JITTER) }
        return candidates.sortedByDescending { uri ->
            artistCompositeScore(
                uri = uri,
                artistScoreMap = artistScoreMap,
                bllArtistScoreMap = bllArtistScoreMap,
                smartArtistScoreMap = smartArtistScoreMap,
                favoriteArtistUris = favoriteArtistUris,
                daypartAffinityByArtist = daypartAffinityByArtist,
                artistDominantDecades = artistDominantDecades,
                focusDecade = focusDecade
            ) + (jitter[uri] ?: 0.0)
        }
    }

    fun buildTrackUris(
        artistOrder: List<String>,
        tracksByArtist: Map<String, List<Track>>,
        genreScores: List<GenreScore>,
        excludedArtistUris: Set<String>,
        favoriteAlbumUris: Set<String>,
        artistBaseScore: (String) -> Double,
        focusDecade: Int?,
        target: Int,
        randomSeed: Long = System.currentTimeMillis()
    ): List<String> {
        if (target <= 0 || artistOrder.isEmpty()) return emptyList()
        val random = Random(randomSeed)
        val genreScoreMap = genreScores.associate { it.genre to it.score }

        val seenTrackUris = mutableSetOf<String>()
        val byArtistCount = mutableMapOf<String, Int>()
        val scored = mutableListOf<ScoredTrack>()

        for (artistUri in artistOrder) {
            val tracks = tracksByArtist[artistUri].orEmpty()
            if (tracks.isEmpty()) continue

            val ranked = tracks
                .asSequence()
                .filter { it.uri.isNotBlank() && it.uri !in seenTrackUris }
                .filterNot { track ->
                    val artistKey = MediaIdentity.canonicalArtistKey(track.artistItemId, track.artistUri)
                        ?: candidateArtistKey(artistUri)
                    artistKey in excludedArtistUris
                }
                .filter { trackPassesDecadeFilter(it.year, focusDecade) }
                .map { track ->
                    val genreAffinity = track.genres.sumOf { g -> genreScoreMap[g] ?: 0.0 } * 0.6
                    val decadeBonus = trackDecadeBonus(track.year, focusDecade)
                    val favoriteBonus = if (track.favorite) 0.25 else 0.0
                    val trackAlbumKey = MediaIdentity.canonicalAlbumKey(track.albumItemId, track.albumUri)
                    val favoriteAlbumBonus =
                        if (!trackAlbumKey.isNullOrBlank() && trackAlbumKey in favoriteAlbumUris) FAVORITE_ALBUM_BONUS
                        else 0.0
                    ScoredTrack(
                        track = track,
                        artistUri = artistUri,
                        score = artistBaseScore(artistUri) + genreAffinity + decadeBonus + favoriteBonus +
                            favoriteAlbumBonus +
                            random.nextDouble(-TRACK_RANK_JITTER, TRACK_RANK_JITTER)
                    )
                }
                .sortedByDescending { it.score }
                .toList()

            for (candidate in ranked) {
                val bucketArtist = MediaIdentity.canonicalArtistKey(
                    itemId = candidate.track.artistItemId,
                    uri = candidate.track.artistUri
                ) ?: candidateArtistKey(candidate.artistUri)
                val count = byArtistCount[bucketArtist] ?: 0
                if (count >= MAX_TRACKS_PER_ARTIST) continue
                scored += candidate
                seenTrackUris += candidate.track.uri
                byArtistCount[bucketArtist] = count + 1
                if (scored.size >= target * 2) break
            }
            if (scored.size >= target * 2) break
        }

        val ordered = scored
            .sortedByDescending { it.score }
            .distinctBy { it.track.uri }

        return interleaveByArtist(ordered, limit = target, random = random)
            .map { it.uri }
    }

    private fun artistCompositeScore(
        uri: String,
        artistScoreMap: Map<String, Double>,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        favoriteArtistUris: Set<String>,
        daypartAffinityByArtist: Map<String, Double>,
        artistDominantDecades: Map<String, Int>,
        focusDecade: Int?
    ): Double {
        val artistScore = artistScoreMap[uri] ?: bllArtistScoreMap[uri] ?: 0.0
        val smart = (smartArtistScoreMap[uri] ?: 0.0) * 0.5
        val favoriteArtistBonus = if (uri in favoriteArtistUris) FAVORITE_ARTIST_BONUS else 0.0
        val daypart = daypartBonus(daypartAffinityByArtist[uri])
        val decade = decadeAdjustment(artistDominantDecades[uri], focusDecade)
        return artistScore + smart + favoriteArtistBonus + daypart + decade
    }

    private fun daypartBonus(affinity: Double?): Double {
        if (affinity == null) return 0.0
        return ((affinity - 0.45) * 0.9).coerceIn(-0.30, 0.45)
    }

    private fun decadeAdjustment(decade: Int?, focusDecade: Int?): Double {
        if (decade == null || focusDecade == null) return 0.0
        val gap = abs(decade - focusDecade)
        return when {
            gap == 0 -> 1.0
            gap <= 10 -> 0.35
            gap >= 20 -> -1.0
            else -> -0.25
        }
    }

    private fun trackPassesDecadeFilter(year: Int?, focusDecade: Int?): Boolean {
        if (year == null || focusDecade == null) return true
        val decade = (year / 10) * 10
        return abs(decade - focusDecade) <= MAX_TRACK_DECADE_GAP
    }

    private fun candidateArtistKey(artistUri: String): String =
        MediaIdentity.canonicalArtistKey(uri = artistUri) ?: artistUri

    private fun trackDecadeBonus(year: Int?, focusDecade: Int?): Double {
        if (year == null || focusDecade == null) return 0.0
        val decade = (year / 10) * 10
        val gap = abs(decade - focusDecade)
        return when {
            gap == 0 -> 0.35
            gap <= 10 -> 0.15
            gap <= MAX_TRACK_DECADE_GAP -> -0.10
            else -> -0.45
        }
    }

    private fun interleaveByArtist(
        scoredTracks: List<ScoredTrack>,
        limit: Int,
        random: Random
    ): List<Track> {
        if (scoredTracks.isEmpty()) return emptyList()
        val buckets = scoredTracks
            .groupBy { it.artistUri }
            .mapValues { (_, list) -> ArrayDeque(list) }
            .toMutableMap()
        val keys = buckets.keys.shuffled(random).toMutableList()
        val result = mutableListOf<Track>()

        while (keys.isNotEmpty() && result.size < limit) {
            if (keys.size > 1) {
                val rotate = random.nextInt(keys.size)
                repeat(rotate) {
                    val first = keys.removeFirst()
                    keys.add(first)
                }
            }
            val iterator = keys.iterator()
            while (iterator.hasNext() && result.size < limit) {
                val key = iterator.next()
                val queue = buckets[key]
                if (queue == null) {
                    iterator.remove()
                    continue
                }
                val next = queue.removeFirstOrNull()
                if (next != null) result += next.track
                if (queue.isEmpty()) {
                    iterator.remove()
                    buckets.remove(key)
                }
            }
        }
        return result
    }

    private data class ScoredTrack(
        val track: Track,
        val artistUri: String,
        val score: Double
    )
}
