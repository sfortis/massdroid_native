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
private const val TOP_GENRES_LIMIT = 4
private const val ARTISTS_PER_GENRE = 12
private const val COMFORT_ANCHOR_LIMIT = 4
private const val EXPLORATION_ARTIST_TARGET = 22
private const val FAVORITE_ARTISTS_CANDIDATE_LIMIT = 24
private const val RECENT_ARTIST_WEIGHT = 0.90
private const val SM_ARTIST_RANK_JITTER = 0.22

sealed class MixMode {
    data class SmartMix(
        val artistScores: List<ArtistScore>,
        val genreScores: List<GenreScore>,
        val genreArtists: Map<String, List<String>>,
        val recentArtistScoreMap: Map<String, Double>,
        val daypartAffinityByArtist: Map<String, Double>,
    ) : MixMode()

    data class GenreMix(
        val genre: String,
        val artistUris: List<String>,
        val recentArtistScoreMap: Map<String, Double> = emptyMap(),
        val daypartAffinityByArtist: Map<String, Double> = emptyMap(),
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
    ): List<String> = when (mode) {
        is MixMode.SmartMix -> buildSmartMixArtistOrder(
            mode, bllArtistScoreMap, smartArtistScoreMap,
            favoriteArtistUris, excludedArtistUris, randomSeed
        )
        is MixMode.GenreMix -> buildGenreMixArtistOrder(
            mode, bllArtistScoreMap, smartArtistScoreMap,
            favoriteArtistUris, randomSeed
        )
    }

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

        val genreScoreMap = when (mode) {
            is MixMode.SmartMix -> mode.genreScores.toScoreMap()
            is MixMode.GenreMix -> emptyMap()
        }
        val targetGenre = (mode as? MixMode.GenreMix)?.let { fuzzyNormalizeGenre(it.genre) }
        val noGenreConf = if (mode is MixMode.GenreMix) 0.0 else 0.58

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
                    val genreScore = if (targetGenre != null) {
                        val confidence = genreConfidence(targetGenre, track, noGenreConf, adjacentGenres)
                        if (confidence <= 0.0) return@mapNotNull null
                        confidence * 1.25
                    } else {
                        track.genres.sumOf { g -> genreScoreMap[normalizeGenre(g)] ?: 0.0 } * 0.6
                    }

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

            for (candidate in ranked) {
                val bucketArtist = MediaIdentity.canonicalArtistKey(
                    itemId = candidate.track.artistItemId,
                    uri = candidate.track.artistUri
                ) ?: MediaIdentity.artistKeyFromUri(candidate.artistUri) ?: candidate.artistUri
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

    // --- GenreMix artist ordering ---

    private fun buildGenreMixArtistOrder(
        mode: MixMode.GenreMix,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        favoriteArtistUris: Set<String>,
        randomSeed: Long
    ): List<String> {
        val random = Random(randomSeed)
        val sorted = mode.artistUris
            .distinct()
            .sortedByDescending { artistUri ->
                val bll = compressPreferenceScore(bllArtistScoreMap[artistUri] ?: 0.0)
                val smart = (smartArtistScoreMap[artistUri] ?: 0.0) * 0.45
                val recent = (mode.recentArtistScoreMap[artistUri] ?: 0.0) * RECENT_ARTIST_WEIGHT
                val favBonus = if (artistUri in favoriteArtistUris) GM_FAV_ARTIST_BONUS else 0.0
                val daypart = daypartBonus(mode.daypartAffinityByArtist[artistUri])
                bll + smart + recent + favBonus + daypart + random.nextDouble(-0.10, 0.10)
            }
        Log.d(TAG, "GenreMix artistOrder: ${sorted.size} artists for genre='${mode.genre}'")
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

    // --- Genre matching (GenreMix) ---

    private fun genreConfidence(
        targetGenre: String,
        track: Track,
        noGenreConfidence: Double = 0.58,
        adjacentGenres: Set<String> = emptySet()
    ): Double {
        val trackGenres = track.genres.map(::fuzzyNormalizeGenre).filter { it.isNotBlank() }
        return when {
            trackGenres.any { genreMatches(targetGenre, it) } -> 1.0
            trackGenres.any { it in adjacentGenres } -> 0.6
            trackGenres.isEmpty() -> noGenreConfidence
            else -> 0.0
        }
    }

    private fun genreMatches(targetGenre: String, candidateGenre: String): Boolean {
        if (candidateGenre == targetGenre) return true
        val targetTokens = tokenizeGenre(targetGenre)
        val candidateTokens = tokenizeGenre(candidateGenre)
        return targetTokens.isNotEmpty() && targetTokens.all { it in candidateTokens }
    }

    private fun tokenizeGenre(value: String): Set<String> =
        value.split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun fuzzyNormalizeGenre(value: String): String =
        normalizeGenre(value)
            .replace("&", "and")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")

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

    // --- Mode-dependent constants ---

    private fun maxTracksPerArtist(mode: MixMode): Int = when (mode) {
        is MixMode.SmartMix -> 2
        is MixMode.GenreMix -> 2
    }

    private fun firstPassUnique(mode: MixMode): Int = when (mode) {
        is MixMode.SmartMix -> 20
        is MixMode.GenreMix -> 20
    }

    private fun artistGap(mode: MixMode): Int = when (mode) {
        is MixMode.SmartMix -> 12
        is MixMode.GenreMix -> 8
    }

    private fun trackJitter(mode: MixMode): Double = when (mode) {
        is MixMode.SmartMix -> 0.28
        is MixMode.GenreMix -> 0.12
    }

    private fun favArtistBonus(mode: MixMode): Double = when (mode) {
        is MixMode.SmartMix -> 0.35
        is MixMode.GenreMix -> 0.45
    }

    private fun favAlbumBonus(mode: MixMode): Double = when (mode) {
        is MixMode.SmartMix -> 0.40
        is MixMode.GenreMix -> 0.25
    }

    private fun favTrackBonus(mode: MixMode): Double = when (mode) {
        is MixMode.SmartMix -> 0.25
        is MixMode.GenreMix -> 0.35
    }

    private fun modeTag(mode: MixMode): String = when (mode) {
        is MixMode.SmartMix -> "SmartMix"
        is MixMode.GenreMix -> "GenreMix"
    }

    private data class ScoredTrack(
        val track: Track,
        val artistUri: String,
        val score: Double
    )

    companion object {
        private const val SM_FAV_ARTIST_BONUS = 0.35
        private const val GM_FAV_ARTIST_BONUS = 0.45
    }
}
