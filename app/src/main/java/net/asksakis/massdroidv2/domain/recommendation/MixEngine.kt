package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "MixEngine"

// SmartMix artist-ordering constants
private const val TOP_ARTISTS_LIMIT = 16
// Broaden the genre net so secondary interests get a turn instead of always
// drawing from the same 3 to 4 favourites.
private const val TOP_GENRES_LIMIT = 6
// Deeper sampling per genre lets less-played but on-genre artists into the pool.
private const val ARTISTS_PER_GENRE = 20
// Fewer guaranteed top-scorers so the mix isn't dominated by 4 comfort artists
// every single time.
private const val COMFORT_ANCHOR_LIMIT = 2
private const val EXPLORATION_ARTIST_TARGET = 28
private const val FAVORITE_ARTISTS_CANDIDATE_LIMIT = 24
private const val RECENT_ARTIST_WEIGHT = 0.65
private const val SM_ARTIST_RANK_JITTER = 0.22

sealed class MixMode {
    data class SmartMix(
        val artistScores: List<ArtistScore>,
        val genreScores: List<GenreScore>,
        val genreArtists: Map<String, List<String>>,
        val recentArtistScoreMap: Map<String, Double>,
        val daypartAffinityByArtist: Map<String, Double>,
    ) : MixMode()

}

@Singleton
class MixEngine @Inject constructor() {

    fun buildArtistOrder(
        mode: MixMode,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        favoriteArtistUris: Set<String>,
        excludedArtistUris: Set<String>,
        randomSeed: Long = System.currentTimeMillis()
    ): List<String> = buildSmartMixArtistOrder(
        mode as MixMode.SmartMix, bllArtistScoreMap, smartArtistScoreMap,
        favoriteArtistUris, excludedArtistUris, randomSeed
    )

    fun buildTracks(
        mode: MixMode,
        artistOrder: List<String>,
        tracksByArtist: Map<String, List<Track>>,
        excludedArtistUris: Set<String>,
        excludedTrackUris: Set<String> = emptySet(),
        favoriteArtistUris: Set<String>,
        favoriteAlbumUris: Set<String>,
        artistBaseScore: (String) -> Double,
        target: Int,
        randomSeed: Long = System.currentTimeMillis(),
        adjacentGenres: Set<String> = emptySet()
    ): List<Track> {
        if (target <= 0 || artistOrder.isEmpty()) return emptyList()
        val random = Random(randomSeed)
        val maxPerArtist = maxTracksPerArtist(mode)
        val jitter = trackJitter(mode)
        val favArtist = favArtistBonus(mode)
        val favAlbum = favAlbumBonus(mode)
        val favTrack = favTrackBonus(mode)
        val modeTag = modeTag(mode)

        val smartMode = mode as MixMode.SmartMix
        val genreScoreMap = smartMode.genreScores.toScoreMap()

        val seenTrackKeys = mutableSetOf<String>()
        val byArtistCount = mutableMapOf<String, Int>()
        val scored = mutableListOf<ScoredTrack>()

        for (artistUri in artistOrder) {
            val tracks = tracksByArtist[artistUri].orEmpty()
            if (tracks.isEmpty()) continue

            val ranked = tracks
                .asSequence()
                .filter { it.uri.isNotBlank() && trackDedupeKey(it) !in seenTrackKeys }
                .filterNot { it.uri in excludedTrackUris }
                .filterNot { track ->
                    val trackArtistKeys = buildSet {
                        addAll(track.artistUris)
                        MediaIdentity.canonicalArtistKey(track.artistItemId, track.artistUri)
                            ?.let(::add)
                        add(MediaIdentity.artistKeyFromUri(artistUri) ?: artistUri)
                    }
                    trackArtistKeys.any { it in excludedArtistUris }
                }
                .mapNotNull { track ->
                    val genreScore =
                        track.genres.sumOf { g -> genreScoreMap[normalizeGenre(g)] ?: 0.0 } * 0.6

                    val trackAlbumKey = MediaIdentity.canonicalAlbumKey(track.albumItemId, track.albumUri)
                    val score = artistBaseScore(artistUri) +
                        genreScore +
                        (if (artistUri in favoriteArtistUris) favArtist else 0.0) +
                        (if (track.favorite) favTrack else 0.0) +
                        (if (!trackAlbumKey.isNullOrBlank() && trackAlbumKey in favoriteAlbumUris) favAlbum else 0.0) +
                        random.nextDouble(-jitter, jitter)

                    ScoredTrack(track = track, artistUri = artistUri, score = score)
                }
                .sortedByDescending { it.score }
                .toList()

            // Sample from the top-K candidates instead of strictly taking the
            // top maxPerArtist by score. Without this, favourite-album and
            // favourite-track bonuses (~+1.2 combined) so far outweigh the
            // ±0.7 jitter that the same two "hit" tracks emerge from a long
            // discography on every mix. Drawing maxPerArtist out of a wider
            // bucket keeps quality high (still scored top tier) while letting
            // less-played album cuts surface across mixes.
            val candidatePoolSize = (maxPerArtist * 5).coerceAtLeast(maxPerArtist)
            val sampled = ranked.take(candidatePoolSize).shuffled(random)

            for (candidate in sampled) {
                // Bucket by the iteration artist first; the per-track artistUri
                // can be a provider URI (e.g. spotify://artist/abc) while the
                // iteration URI is the canonical library:// one. If we trust
                // track-level URIs we end up with one logical artist split
                // across multiple buckets, and the per-artist cap silently
                // doubles. Fall back to track-level only if iteration URI is
                // missing for some reason.
                val bucketArtist = MediaIdentity.artistKeyFromUri(candidate.artistUri)
                    ?: MediaIdentity.canonicalArtistKey(
                        itemId = candidate.track.artistItemId,
                        uri = candidate.track.artistUri
                    )
                    ?: candidate.artistUri
                val count = byArtistCount[bucketArtist] ?: 0
                if (count >= maxPerArtist) continue
                scored += candidate
                seenTrackKeys += trackDedupeKey(candidate.track)
                byArtistCount[bucketArtist] = count + 1
                if (scored.size >= target * 2) break
            }
            if (scored.size >= target * 2) break
        }

        val ordered = scored
            .sortedByDescending { it.score }
            .distinctBy { trackDedupeKey(it.track) }

        Log.d(modeTag, "buildTracks: ${ordered.size} scored from ${tracksByArtist.size} artists, target=$target")

        val artistPriority = artistOrder.withIndex().associate { it.value to it.index }
        val interleaved = interleaveByArtist(
            scoredTracks = ordered,
            limit = target,
            random = random,
            artistPriority = artistPriority,
            firstPassUniqueArtists = firstPassUnique(mode),
            minArtistGap = artistGap(mode)
        )
        interleaved.forEachIndexed { i, track ->
            Log.d(modeTag, "  track #${i + 1}: ${track.artistNames} - ${track.name}")
        }
        return interleaved
    }

    fun buildTrackUris(
        mode: MixMode,
        artistOrder: List<String>,
        tracksByArtist: Map<String, List<Track>>,
        excludedArtistUris: Set<String>,
        excludedTrackUris: Set<String> = emptySet(),
        favoriteArtistUris: Set<String>,
        favoriteAlbumUris: Set<String>,
        artistBaseScore: (String) -> Double,
        target: Int,
        randomSeed: Long = System.currentTimeMillis(),
        adjacentGenres: Set<String> = emptySet()
    ): List<String> = buildTracks(
        mode = mode,
        artistOrder = artistOrder,
        tracksByArtist = tracksByArtist,
        excludedArtistUris = excludedArtistUris,
        excludedTrackUris = excludedTrackUris,
        favoriteArtistUris = favoriteArtistUris,
        favoriteAlbumUris = favoriteAlbumUris,
        artistBaseScore = artistBaseScore,
        target = target,
        randomSeed = randomSeed,
        adjacentGenres = adjacentGenres
    ).map { it.uri }

    // --- SmartMix artist ordering ---

    private fun buildSmartMixArtistOrder(
        mode: MixMode.SmartMix,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        favoriteArtistUris: Set<String>,
        excludedArtistUris: Set<String>,
        randomSeed: Long
    ): List<String> {
        val random = Random(randomSeed)
        val artistScoreMap = mode.artistScores.toScoreMap()
        val topGenres = mode.genreScores.take(TOP_GENRES_LIMIT).map { normalizeGenre(it.genre) }
        val scoreByUri = mutableMapOf<String, Double>()
        val comfortCandidates = linkedSetOf<String>()
        val genreCandidates = linkedSetOf<String>()

        favoriteArtistUris
            .asSequence()
            .filterNot { it in excludedArtistUris }
            .take(FAVORITE_ARTISTS_CANDIDATE_LIMIT)
            .forEach { comfortCandidates += it }
        mode.artistScores
            .asSequence()
            .map { it.artistUri }
            .filterNot { it in excludedArtistUris }
            .take(TOP_ARTISTS_LIMIT)
            .forEach { comfortCandidates += it }

        for (genre in topGenres) {
            mode.genreArtists[genre]
                .orEmpty()
                .asSequence()
                .filterNot { it in excludedArtistUris }
                .distinct()
                .sortedByDescending { uri ->
                    compositeArtistScore(
                        uri, artistScoreMap, bllArtistScoreMap, smartArtistScoreMap,
                        mode.recentArtistScoreMap, favoriteArtistUris, mode.daypartAffinityByArtist
                    ).also { scoreByUri[uri] = it }
                }
                .take(ARTISTS_PER_GENRE)
                .forEach { genreCandidates += it }
        }

        val comfortAnchors = comfortCandidates
            .sortedByDescending { uri ->
                compositeArtistScore(
                    uri, artistScoreMap, bllArtistScoreMap, smartArtistScoreMap,
                    mode.recentArtistScoreMap, favoriteArtistUris, mode.daypartAffinityByArtist
                ).also { scoreByUri[uri] = it }
            }
            .take(COMFORT_ANCHOR_LIMIT)

        val explorationPool = (genreCandidates + comfortCandidates)
            .filterNot { it in comfortAnchors }
            .distinct()

        val explorationArtists = weightedSampleArtists(
            candidates = explorationPool,
            scoreByUri = scoreByUri,
            limit = EXPLORATION_ARTIST_TARGET,
            random = random
        )

        val candidates = (comfortAnchors + explorationArtists).distinct()
        Log.d(
            TAG,
            "SmartMix artistOrder: ${candidates.size} candidates " +
                "(comfort=${comfortAnchors.size}, explore=${explorationArtists.size}, " +
                "${favoriteArtistUris.size} favorites, ${mode.artistScores.size} BLL, " +
                "topGenres=${topGenres.joinToString()})"
        )
        val sorted = weightedOrderArtists(
            candidates = candidates,
            scoreByUri = candidates.associateWith { uri ->
                compositeArtistScore(
                    uri, artistScoreMap, bllArtistScoreMap, smartArtistScoreMap,
                    mode.recentArtistScoreMap, favoriteArtistUris, mode.daypartAffinityByArtist
                )
            },
            random = random
        )
        sorted.forEachIndexed { i, uri ->
            val score = scoreByUri[uri] ?: compositeArtistScore(
                uri, artistScoreMap, bllArtistScoreMap, smartArtistScoreMap,
                mode.recentArtistScoreMap, favoriteArtistUris, mode.daypartAffinityByArtist
            )
            val isFav = if (uri in favoriteArtistUris) " [FAV]" else ""
            Log.d(TAG, "  artist #${i + 1}: $uri score=${String.format("%.2f", score)}$isFav")
        }
        return sorted
    }

    // --- Shared scoring helpers ---

    private fun compositeArtistScore(
        uri: String,
        artistScoreMap: Map<String, Double>,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        recentArtistScoreMap: Map<String, Double>,
        favoriteArtistUris: Set<String>,
        daypartAffinityByArtist: Map<String, Double>
    ): Double {
        val artistScore = compressPreferenceScore(artistScoreMap[uri] ?: bllArtistScoreMap[uri] ?: 0.0)
        val smart = (smartArtistScoreMap[uri] ?: 0.0) * 0.45
        val recent = (recentArtistScoreMap[uri] ?: 0.0) * RECENT_ARTIST_WEIGHT
        val favoriteArtistBonus = if (uri in favoriteArtistUris) SM_FAV_ARTIST_BONUS else 0.0
        val daypart = daypartBonus(daypartAffinityByArtist[uri])
        return artistScore + smart + recent + favoriteArtistBonus + daypart
    }

    private fun compressPreferenceScore(raw: Double): Double =
        if (raw == 0.0) 0.0 else kotlin.math.sign(raw) * kotlin.math.sqrt(kotlin.math.abs(raw))

    private fun daypartBonus(affinity: Double?): Double {
        if (affinity == null) return 0.0
        return ((affinity - 0.35) * 1.8).coerceIn(-0.45, 0.95)
    }

    // --- Weighted sampling (SmartMix) ---

    private fun weightedSampleArtists(
        candidates: List<String>,
        scoreByUri: Map<String, Double>,
        limit: Int,
        random: Random
    ): List<String> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val pool = candidates.toMutableList()
        val result = mutableListOf<String>()
        while (pool.isNotEmpty() && result.size < limit) {
            val minScore = pool.minOf { scoreByUri[it] ?: 0.0 }
            val weights = pool.map { artist ->
                val normalized = (scoreByUri[artist] ?: 0.0) - minScore + 0.35
                normalized.coerceAtLeast(0.08)
            }
            val total = weights.sum()
            var pick = random.nextDouble() * total
            var pickedIndex = pool.lastIndex
            for (index in pool.indices) {
                pick -= weights[index]
                if (pick <= 0.0) {
                    pickedIndex = index
                    break
                }
            }
            result += pool.removeAt(pickedIndex)
        }
        return result
    }

    private fun weightedOrderArtists(
        candidates: List<String>,
        scoreByUri: Map<String, Double>,
        random: Random
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val pool = candidates.toMutableList()
        val result = mutableListOf<String>()
        while (pool.isNotEmpty()) {
            val minScore = pool.minOf { scoreByUri[it] ?: 0.0 }
            val weights = pool.map { artist ->
                val base = (scoreByUri[artist] ?: 0.0) - minScore + 0.30
                kotlin.math.sqrt(base.coerceAtLeast(0.06)) + random.nextDouble(0.0, SM_ARTIST_RANK_JITTER)
            }
            val total = weights.sum()
            var pick = random.nextDouble() * total
            var pickedIndex = pool.lastIndex
            for (index in pool.indices) {
                pick -= weights[index]
                if (pick <= 0.0) {
                    pickedIndex = index
                    break
                }
            }
            result += pool.removeAt(pickedIndex)
        }
        return result
    }

    // --- Interleaving ---

    private fun interleaveByArtist(
        scoredTracks: List<ScoredTrack>,
        limit: Int,
        random: Random,
        artistPriority: Map<String, Int>,
        firstPassUniqueArtists: Int,
        minArtistGap: Int
    ): List<Track> {
        if (scoredTracks.isEmpty()) return emptyList()
        val buckets = scoredTracks
            .groupBy { it.artistUri }
            .mapValues { (_, list) -> ArrayDeque(list) }
            .toMutableMap()
        val result = mutableListOf<Track>()
        val emittedTrackKeys = mutableSetOf<String>()
        val emittedArtists = mutableListOf<String>()
        val emittedArtistCounts = mutableMapOf<String, Int>()

        while (buckets.isNotEmpty() && result.size < limit) {
            val candidateKeys = buckets.keys
                .shuffled(random)
                .sortedWith(
                    compareBy<String> { emittedArtistCounts[it] ?: 0 }
                        .thenBy { artistPriority[it] ?: Int.MAX_VALUE }
                        .thenByDescending { buckets[it]?.firstOrNull()?.score ?: Double.NEGATIVE_INFINITY }
                )

            val preferredKey = candidateKeys.firstOrNull { artistUri ->
                canEmitArtist(artistUri, emittedArtists, emittedArtistCounts, firstPassUniqueArtists, minArtistGap)
            } ?: candidateKeys.firstOrNull()
            val queue = preferredKey?.let { buckets[it] } ?: break
            val next = queue.removeFirstOrNull()
            if (next != null) {
                if (emittedTrackKeys.add(trackDedupeKey(next.track))) {
                    result += next.track
                    if (preferredKey != null) {
                        emittedArtists += preferredKey
                        emittedArtistCounts[preferredKey] = (emittedArtistCounts[preferredKey] ?: 0) + 1
                    }
                }
            }
            if (queue.isEmpty()) {
                buckets.remove(preferredKey)
            }
        }
        return result
    }

    private fun canEmitArtist(
        artistUri: String,
        emittedArtists: List<String>,
        emittedArtistCounts: Map<String, Int>,
        firstPassUniqueArtists: Int,
        minArtistGap: Int
    ): Boolean {
        val emittedCount = emittedArtistCounts[artistUri] ?: 0
        if (emittedCount == 0) return true
        if (emittedArtists.size < firstPassUniqueArtists) return false
        val recentArtists = emittedArtists.takeLast(minArtistGap)
        return artistUri !in recentArtists
    }

    // --- Identity helpers ---

    private fun trackDedupeKey(track: Track): String {
        val artist = normalizeTrackText(track.artistNames.substringBefore(","))
        val name = normalizeTrackText(track.name)
        if (artist.isNotBlank() && name.isNotBlank()) return "$artist|$name"
        return track.uri.ifBlank { "$artist|$name" }
    }

    private fun normalizeTrackText(value: String): String =
        value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // --- Constants ---

    // Cap at 2 so a 40-track mix needs at least ~20 distinct artists, which
    // forces the interleaver to pull deeper into the candidate pool instead of
    // bunching three tracks each from the top half-dozen scorers.
    @Suppress("UnusedParameter")
    private fun maxTracksPerArtist(mode: MixMode): Int = 2
    @Suppress("UnusedParameter")
    private fun firstPassUnique(mode: MixMode): Int = 20
    @Suppress("UnusedParameter")
    private fun artistGap(mode: MixMode): Int = 12
    @Suppress("UnusedParameter")
    private fun trackJitter(mode: MixMode): Double = 0.7
    @Suppress("UnusedParameter")
    private fun favArtistBonus(mode: MixMode): Double = 0.8
    // Lowered so favourite-album/track bonuses no longer overwhelm the ±jitter
    // that's meant to rotate which album cuts surface across mixes. With the
    // old values the same 1-2 "hit" tracks of a favourited artist won every
    // single time and the user heard them on repeat.
    @Suppress("UnusedParameter")
    private fun favAlbumBonus(mode: MixMode): Double = 0.3
    @Suppress("UnusedParameter")
    private fun favTrackBonus(mode: MixMode): Double = 0.2
    @Suppress("UnusedParameter")
    private fun modeTag(mode: MixMode): String = "SmartMix"

    private data class ScoredTrack(
        val track: Track,
        val artistUri: String,
        val score: Double
    )

    companion object {
        private const val SM_FAV_ARTIST_BONUS = 0.8
    }
}
