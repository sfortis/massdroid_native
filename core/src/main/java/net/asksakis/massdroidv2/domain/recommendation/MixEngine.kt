package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.random.Random

private const val TAG = "MixEngine"

// SmartMix artist-ordering constants
private const val TOP_ARTISTS_LIMIT = 16
// Broaden the genre net so secondary interests get a turn instead of always
// drawing from the same 3 to 4 favourites.
private const val TOP_GENRES_LIMIT = 6
// Deeper sampling per genre lets less-played but on-genre artists into the pool.
private const val ARTISTS_PER_GENRE = 20
// Comfort-anchor count and exploration-pool size are now derived from the
// user "discovery" knob (see DISCOVERY_* constants below).
private const val FAVORITE_ARTISTS_CANDIDATE_LIMIT = 24
private const val RECENT_ARTIST_WEIGHT = 0.65
private const val SM_ARTIST_RANK_JITTER = 0.22
// Per-track genre affinity boost weight (applied to the non-negative averaged
// genre affinity, see genreAffinity).
private const val GENRE_AFFINITY_WEIGHT = 0.6

// --- User tuning knobs (all in [0,1], 0.5 = neutral/legacy behaviour) ---
private const val DEFAULT_VARIETY = 0.5
private const val DEFAULT_DISCOVERY = 0.5
// Variety -> track-rank jitter multiplier range (0.5x .. 2.0x).
private const val VARIETY_JITTER_MIN = 0.5
private const val VARIETY_JITTER_SPAN = 1.5
// Variety -> per-artist candidate-pool factor (x2 .. x10 of maxPerArtist).
private const val VARIETY_POOL_MIN = 2.0
private const val VARIETY_POOL_SPAN = 8.0
// Per-artist track cap floor/ceiling (see dynamicMaxPerArtist). Floor keeps
// diversity when the pool is large; ceiling lets a small pool fill a bit more.
// Kept low (3) so a small genre can't turn into "the same artist x6" now that
// the cap is enforced per provider-agnostic artist (no cross-provider doubling).
private const val MAX_PER_ARTIST_FLOOR = 2
private const val MAX_PER_ARTIST_CEIL = 3
// Max tracks from one album, so a single record can't dominate the mix.
private const val MAX_PER_ALBUM = 2
// Discovery -> how many comfort anchors are guaranteed before exploration fills
// the rest. Low discovery keeps more familiar artists up front.
private const val DISCOVERY_COMFORT_MAX = 6
private const val DISCOVERY_COMFORT_MIN = 1
// Discovery -> exploration artist pool target (familiar-leaning .. wide).
private const val DISCOVERY_EXPLORE_MIN = 14
private const val DISCOVERY_EXPLORE_SPAN = 24

sealed class MixMode {
    data class SmartMix(
        val artistScores: List<ArtistScore>,
        val genreScores: List<GenreScore>,
        val genreArtists: Map<String, List<String>>,
        val recentArtistScoreMap: Map<String, Double>,
        val daypartAffinityByArtist: Map<String, Double>,
    ) : MixMode()

}

/** A pre-scored candidate for [MixEngine.buildFromCandidates] (seed-track path). */
data class CandidateTrack(
    val track: Track,
    val score: Double
)

@Singleton
class MixEngine @Inject constructor() {

    fun buildArtistOrder(
        mode: MixMode,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        favoriteArtistUris: Set<String>,
        excludedArtistUris: Set<String>,
        randomSeed: Long = System.currentTimeMillis(),
        // User "discovery" knob in [0,1]. Low keeps more familiar (comfort)
        // artists up front and a narrower exploration pool; high pushes more
        // exploration/adjacent artists in.
        discovery: Double = DEFAULT_DISCOVERY
    ): List<String> = buildSmartMixArtistOrder(
        mode as MixMode.SmartMix, bllArtistScoreMap, smartArtistScoreMap,
        favoriteArtistUris, excludedArtistUris, randomSeed, discovery
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
        adjacentGenres: Set<String> = emptySet(),
        // User "variety" knob in [0,1]. It widens BOTH the per-artist candidate
        // pool (how many of an artist's tracks are eligible to rotate in) and the
        // track-rank jitter. Low = tight, the strongest few tracks every time;
        // high = repeated mixes diverge more. The pool was the dominant rotation
        // source, so a jitter-only knob barely moved the result.
        variety: Double = DEFAULT_VARIETY
    ): List<Track> {
        if (target <= 0 || artistOrder.isEmpty()) return emptyList()
        val random = Random(randomSeed)
        val varietyClamped = variety.coerceIn(0.0, 1.0)
        // Per-artist cap scales gently with how many artists actually have tracks
        // (floor 2, ceiling 3): a small genre may allow 3/artist to fill out, a
        // large pool stays at 2 for maximum diversity. The cap is now enforced per
        // provider-agnostic artist name, so it can't be doubled by the same artist
        // appearing under library:// and a provider URI.
        val usableArtists = artistOrder.count { !tracksByArtist[it].isNullOrEmpty() }
        val maxPerArtist = dynamicMaxPerArtist(target, usableArtists)
        val jitter = trackJitter(mode) * (VARIETY_JITTER_MIN + varietyClamped * VARIETY_JITTER_SPAN)
        val favArtist = favArtistBonus(mode)
        val favAlbum = favAlbumBonus(mode)
        val favTrack = favTrackBonus(mode)
        val modeTag = modeTag(mode)

        val smartMode = mode as MixMode.SmartMix
        val genreScoreMap = smartMode.genreScores.toScoreMap()

        val seenTrackKeys = mutableSetOf<String>()
        val byArtistCount = mutableMapOf<String, Int>()
        val byAlbumCount = mutableMapOf<String, Int>()
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
                    // Non-negative, tag-count-fair affinity: a loved-but-binged
                    // genre has a negative log-domain BLL score, which must NOT
                    // penalise the track (that's "familiar", not "disliked").
                    // Real dislikes are excluded upstream via suppressed/blocked.
                    val genreScore = genreAffinity(track.genres, genreScoreMap) * GENRE_AFFINITY_WEIGHT

                    val trackAlbumKey = MediaIdentity.canonicalAlbumKey(track.albumItemId, track.albumUri)
                    val score = artistBaseScore(artistUri) +
                        genreScore +
                        (if (artistUri in favoriteArtistUris) favArtist else 0.0) +
                        (if (track.favorite) favTrack else 0.0) +
                        (if (!trackAlbumKey.isNullOrBlank() && trackAlbumKey in favoriteAlbumUris) favAlbum else 0.0) +
                        random.nextDouble(-jitter, jitter)

                    val nameKey = artistNameKey(track)
                        .ifBlank { MediaIdentity.artistKeyFromUri(artistUri) ?: artistUri }
                    ScoredTrack(track = track, artistUri = artistUri, artistKey = nameKey, score = score)
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
            val poolFactor = VARIETY_POOL_MIN + varietyClamped * VARIETY_POOL_SPAN
            val candidatePoolSize = (maxPerArtist * poolFactor).toInt().coerceAtLeast(maxPerArtist)
            val sampled = ranked.take(candidatePoolSize).shuffled(random)

            for (candidate in sampled) {
                // Bucket by provider-agnostic artist name so the same artist under
                // library:// and deezer:// can't double the per-artist cap. Also
                // cap tracks per album so one record can't dominate the mix.
                val bucketArtist = candidate.artistKey
                if ((byArtistCount[bucketArtist] ?: 0) >= maxPerArtist) continue
                val albumKey = MediaIdentity.canonicalAlbumKey(
                    candidate.track.albumItemId, candidate.track.albumUri
                )
                if (!albumKey.isNullOrBlank() && (byAlbumCount[albumKey] ?: 0) >= MAX_PER_ALBUM) continue
                scored += candidate
                seenTrackKeys += trackDedupeKey(candidate.track)
                byArtistCount[bucketArtist] = (byArtistCount[bucketArtist] ?: 0) + 1
                if (!albumKey.isNullOrBlank()) {
                    byAlbumCount[albumKey] = (byAlbumCount[albumKey] ?: 0) + 1
                }
                if (scored.size >= target * 2) break
            }
            if (scored.size >= target * 2) break
        }

        val ordered = scored
            .sortedByDescending { it.score }
            .distinctBy { trackDedupeKey(it.track) }

        Log.d(modeTag, "buildTracks: ${ordered.size} scored from ${tracksByArtist.size} artists, target=$target")

        // Priority is keyed by the same provider-agnostic artistKey the buckets
        // use: each artist's best (highest-scored) position in the ordered list.
        val artistPriority = ordered.withIndex()
            .groupBy { it.value.artistKey }
            .mapValues { (_, items) -> items.minOf { it.index } }
        val interleaved = interleaveByArtist(
            scoredTracks = ordered,
            limit = target,
            random = random,
            artistPriority = artistPriority,
            // Clamp to the real pool size: with few artists a fixed 20-unique /
            // 12-gap rule blocks every second track (it can never reach 20 unique)
            // and leaves the mix short. Scale both to what's actually available.
            firstPassUniqueArtists = minOf(firstPassUnique(mode), usableArtists),
            minArtistGap = minOf(artistGap(mode), (usableArtists - 1).coerceAtLeast(0))
        )
        interleaved.forEachIndexed { i, track ->
            Log.d(modeTag, "  track #${i + 1}: ${track.artistNames} - ${track.name}")
        }
        return interleaved
    }

    /**
     * Build a final mix from a flat list of pre-scored candidate tracks: the
     * seed-track generator path (Last.fm track.getSimilar). Scores are supplied
     * per candidate (match x BLL x recency), so this skips the artist-first
     * scoring of [buildTracks] but reuses the exact same diversity guarantees:
     * cross-provider per-artist cap, per-album cap, and the anti-clustering
     * interleave. This is what keeps a coherent seed-track pool from collapsing
     * into the same artist back-to-back.
     */
    fun buildFromCandidates(
        candidates: List<CandidateTrack>,
        target: Int,
        randomSeed: Long = System.currentTimeMillis()
    ): List<Track> {
        if (target <= 0 || candidates.isEmpty()) return emptyList()
        val random = Random(randomSeed)
        val scored = candidates
            .filter { it.track.uri.isNotBlank() }
            .map { c ->
                val key = artistNameKey(c.track)
                    .ifBlank { MediaIdentity.artistKeyFromUri(c.track.artistUri.orEmpty()) ?: c.track.uri }
                ScoredTrack(
                    track = c.track,
                    artistUri = c.track.artistUri ?: c.track.uri,
                    artistKey = key,
                    score = c.score
                )
            }
            .sortedByDescending { it.score }
            .distinctBy { trackDedupeKey(it.track) }
        if (scored.isEmpty()) return emptyList()

        val usableArtists = scored.map { it.artistKey }.distinct().size
        val maxPerArtist = dynamicMaxPerArtist(target, usableArtists)
        val byArtistCount = mutableMapOf<String, Int>()
        val byAlbumCount = mutableMapOf<String, Int>()
        val capped = mutableListOf<ScoredTrack>()
        for (c in scored) {
            if ((byArtistCount[c.artistKey] ?: 0) >= maxPerArtist) continue
            val albumKey = MediaIdentity.canonicalAlbumKey(c.track.albumItemId, c.track.albumUri)
            if (!albumKey.isNullOrBlank() && (byAlbumCount[albumKey] ?: 0) >= MAX_PER_ALBUM) continue
            capped += c
            byArtistCount[c.artistKey] = (byArtistCount[c.artistKey] ?: 0) + 1
            if (!albumKey.isNullOrBlank()) {
                byAlbumCount[albumKey] = (byAlbumCount[albumKey] ?: 0) + 1
            }
            if (capped.size >= target * 2) break
        }

        val artistPriority = capped.withIndex()
            .groupBy { it.value.artistKey }
            .mapValues { (_, items) -> items.minOf { it.index } }
        val interleaved = interleaveByArtist(
            scoredTracks = capped,
            limit = target,
            random = random,
            artistPriority = artistPriority,
            firstPassUniqueArtists = minOf(20, usableArtists),
            minArtistGap = minOf(12, (usableArtists - 1).coerceAtLeast(0))
        )
        Log.d(TAG, "buildFromCandidates: ${interleaved.size}/${target} from ${candidates.size} candidates, $usableArtists artists, cap=$maxPerArtist")
        return interleaved
    }

    // --- SmartMix artist ordering ---

    private fun buildSmartMixArtistOrder(
        mode: MixMode.SmartMix,
        bllArtistScoreMap: Map<String, Double>,
        smartArtistScoreMap: Map<String, Double>,
        favoriteArtistUris: Set<String>,
        excludedArtistUris: Set<String>,
        randomSeed: Long,
        discovery: Double
    ): List<String> {
        val random = Random(randomSeed)
        val discoveryClamped = discovery.coerceIn(0.0, 1.0)
        val comfortLimit = (
            DISCOVERY_COMFORT_MAX - discoveryClamped * (DISCOVERY_COMFORT_MAX - DISCOVERY_COMFORT_MIN)
            ).toInt().coerceAtLeast(DISCOVERY_COMFORT_MIN)
        val exploreTarget = (DISCOVERY_EXPLORE_MIN + discoveryClamped * DISCOVERY_EXPLORE_SPAN).toInt()
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
            .take(comfortLimit)

        val explorationPool = (genreCandidates + comfortCandidates)
            .filterNot { it in comfortAnchors }
            .distinct()

        val explorationArtists = weightedSampleArtists(
            candidates = explorationPool,
            scoreByUri = scoreByUri,
            limit = exploreTarget,
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
            .groupBy { it.artistKey }
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

    // Per-artist track cap, scaled to the pool size. With many artists it stays
    // at the floor (2) for maximum diversity; with a small genre pool it rises
    // (up to the ceiling) so a few deep-catalogue artists can still fill the
    // target instead of leaving the mix at a handful of tracks.
    private fun dynamicMaxPerArtist(target: Int, usableArtists: Int): Int {
        if (usableArtists <= 0) return MAX_PER_ARTIST_FLOOR
        val needed = ceil(target.toDouble() / usableArtists).toInt()
        return needed.coerceIn(MAX_PER_ARTIST_FLOOR, MAX_PER_ARTIST_CEIL)
    }
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
        // Provider-agnostic artist identity (normalized name): the SAME artist
        // under library:// and deezer:// must share one bucket so the per-artist
        // cap and interleave gap can't be doubled by a cross-provider duplicate.
        val artistKey: String,
        val score: Double
    )

    /** Normalized first-artist name, the cross-provider artist bucket key. */
    private fun artistNameKey(track: Track): String =
        normalizeTrackText(track.artistNames.substringBefore(","))

    companion object {
        private const val SM_FAV_ARTIST_BONUS = 0.8
    }
}
