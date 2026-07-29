package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmSimilarResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmTrackSimilarResolver
import net.asksakis.massdroidv2.data.lastfm.SimilarArtist
import net.asksakis.massdroidv2.data.lastfm.SimilarTrack
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SeedTrack
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.roundToInt

private const val TAG = "SeedTrackMix"

private const val SEED_COUNT = 8
private const val SEED_ANCHOR_MAX = 3
private const val SEED_LOOKBACK_DAYS = 30
private const val SEED_MIN_LISTENED_MS = 30_000L
// Recency-ordered candidate pool that Strictness re-ranks toward score. Larger
// than the old top-by-score limit so low Strictness has genuinely-recent (not
// just top-scored) tracks to draw from.
//
// This is the ROTATION budget: the primary seed (and therefore the genre world
// of the whole mix) is drawn from here. At 150 rows a real 30-day history
// collapsed to ~85 artists and only ~19 distinct anchor genres, so consecutive
// mixes kept landing on the same handful of clusters. At 600 the same history
// yields ~226 artists across ~37 anchors. The query cost is flat (~21 ms either
// way; the 30-day GROUP BY dominates), so the width is essentially free.
private const val RECENCY_POOL_LIMIT = 600

// How much Strictness may tilt the pick of the NON-primary seeds toward score.
// Weight spans 1.0 (lowest-scored in the cluster) to 1 + this (highest), so at
// Strictness 1.0 a top-scored seed is ~10x likelier than a bottom-scored one and
// at Strictness 0.0 the draw is uniform (the old plain shuffle).
//
// Why this exists: the pool is dominated by tracks played exactly ONCE (measured
// on a real 30-day history: 211 of 240 artists after dedupe, 88%, mean score
// 0.267 vs 0.703 for repeat-played ones). Those are overwhelmingly tracks the
// engine ITSELF queued and the user simply did not skip (78% of all plays are
// >=90% completions), so seeding from them uniformly makes each mix a mutation
// of the previous one. strictnessRankedPool already answers to Strictness, but
// it only decides the PRIMARY via varietyWindow; the other seeds used to be a
// plain shuffle over the whole pool, which is where the drift came from.
private const val SEED_WEIGHT_STRENGTH = 9.0
private const val GENRE_SEED_LOOKBACK_DAYS = 365
private const val GENRE_SEED_POOL_LIMIT = 250
private const val SEED_SIMILARS_MIN = 15
private const val SEED_SIMILARS_SPAN = 25
// Provider searches we are willing to run while the user waits. Raised from 24
// once the rotation started visiting genuinely new clusters more often: on a
// cold cluster every candidate is a cache miss, and 24 searches yielded only 17
// playable tracks, so a 33-track mix came out at 24. Concurrency stays at 6 on
// purpose (MA aggregates every provider in one asyncio.gather, so a burst is
// what starves it, see BulkRpcThrottle).
private const val SEED_INLINE_SEARCH_BUDGET = 32
private const val SEED_SEARCH_CONCURRENCY = 6
private const val SEED_TRACK_SEARCH_LIMIT = 5
private const val SEED_SEARCH_TIMEOUT_MS = 4000L
private const val SEED_PREFETCH_CONCURRENCY = 2
private const val RESOLVED_TRACK_TTL_MS = 30L * 24 * 60 * 60 * 1000
// Recent-mix cool-down penalties (subtracted from a candidate's score when the
// track / its artist appeared in recent mixes). Raised from 0.2/0.5 after
// offline tuning on the real library: with weighted-sampling selection the
// stronger penalties meaningfully cut cross-mix repetition (a 0.5 track penalty
// was too small to dislodge a ~0.9-match recent track). Paired with the deeper
// SMART_MIX_HISTORY_DEPTH in MixPlaybackOrchestrator.
private const val SEED_RECENT_ARTIST_PENALTY = 0.4
private const val SEED_RECENT_TRACK_PENALTY = 1.5
private const val MIN_SEEDS = 2
// Variety knob -> genre movement between consecutive mixes. Below this the next
// mix STAYS in the recent genre family (drifting the sub-genre via exactFresh:
// deep house -> techno -> nu-disco), avoiding whiplash to unrelated genres.
// At/above it the mix is allowed to HOP to a different family (exploration).
// This replaces the old always-on family-hop that jumped e.g. deep house ->
// hard rock every run.
// A rotation preference is dropped only at a genuine dead end. The failure it
// guards against was a SINGLE surviving candidate, which handed back the same
// cluster twice in a row; two or more is already a real choice. Set higher (5)
// at first, it threw away valid hops: a mix asking for a different family had
// four candidates, lost them to the net, and landed on the recent family again.
private const val PRIMARY_PREFERENCE_MIN = 2
// Strictness knob -> minimum tracks.score a seed must have. At 1.0 only "loved"
// tracks (score > 0.5) qualify; at 0.0 any non-disliked track (score >= 0).
private const val STRICTNESS_MAX_SCORE = 0.5
// If the strict score filter leaves too small a pool, relax to score >= 0 so a
// mix can still be built (cold-start / lightly-rated libraries).
private const val SEED_POOL_RELAX_MIN = 6
// Loved-track injection (comfort anchor). Discovery drives the share, with NO
// ceiling: at Discovery=0 up to MAX_FRACTION of the mix may be the user's own
// loved tracks, and at high Discovery it tapers to zero (pure discovery). The
// score floor for what qualifies is derived from the Strictness slider (see
// lovedInjection), so a low Discovery no longer fills the mix with weakly-liked
// filler.
private const val OWN_INJECT_MAX_FRACTION = 0.30
private const val LOVED_INJECT_POOL_LIMIT = 600
// Artist tags the genre gate reads are warmed INLINE for the top candidates
// before gating. Without this the gate was near-inert on real runs: ~60% of
// candidates had no cached tags at gate time and "unknown passes" let them all
// through (four days of logs show zero gate drops). The limit sits just above
// SEED_INLINE_SEARCH_BUDGET so everything that can be resolved inline is judged.
private const val GATE_WARM_LIMIT = 30
private const val GATE_WARM_CONCURRENCY = 4
// Pool wideners. Obscure seeds (cover/lounge projects, compilation acts) are
// unknown to `track.getSimilar`: real runs produced 21-42 unique candidates for
// a 33-track target, so mixes came up short and the loved injection grew to a
// quarter of the mix. When the pool is below target * this factor we widen,
// cheapest and most personal source first.
private const val WIDENER_POOL_FACTOR = 2.5
// The tight gate legitimately drops a large share of a widened pool, so
// relaxation must be a rescue for genuinely starved libraries, not a routine
// step: at target * this factor there is still enough to build a real mix once
// the loved injection is added, and staying tight is worth more than the last
// few tracks. Relaxing at "< target" would have undone the gate on every run.
private const val GATE_RELAX_FACTOR = 0.5
// Candidates whose artist we have no tags for are admitted only while the
// judged pool holds fewer than target * this. Above it there is enough known-
// good material that guessing is pure risk.
private const val GATE_UNKNOWN_DROP_FACTOR = 1.5
private const val WIDENER_SIMILAR_PER_SEED = 6
private const val WIDENER_ARTIST_LIMIT = 12
private const val WIDENER_TRACKS_PER_ARTIST = 5
// Widened candidates are weaker evidence than a direct track similar, so their
// scores are scaled below them: similar-artist top tracks first, genre top
// tracks (least personal) last.
private const val WIDENER_ARTIST_SCALE = 0.6
private const val WIDENER_TAG_SCALE = 0.35
private const val WIDENER_TAG_GENRE_LIMIT = 2
private const val WIDENER_TAG_TRACKS = 30
// Multiplier for a candidate that is one of its artist's top tracks.
private const val TOP_TRACK_BONUS = 1.25
// `tag.getTopTracks` on a broad tag returns the global chart, not the cluster:
// asking for "dance" put two Filipino budots novelty tracks in a deep-house
// mix. The widener therefore prefers the cluster's SPECIFIC tags and only falls
// back to a broad one when the cluster has nothing else (Genre Radio on a broad
// genre is exactly that case, and there it is the user's explicit choice).
private val BROAD_GENRE_TAGS = setOf(
    "alternative", "club", "dance", "electronic", "electronica", "indie",
    "jazz", "metal", "pop", "rock", "world"
)
// Injected loved tracks enter with a MID-PACK relevance so they COMPETE with the
// seed-similars instead of dominating the front of the mix. A top score (1.0) made
// every mix front-loaded with familiar songs and killed the discovery feel; at
// 0.5 they sit mid-distribution (Last.fm match scores span ~0..1), surfacing some
// comfort without burying the discoveries.
private const val OWN_INJECT_SCORE = 0.5
// Rediscovery bias applied to the loved-injection pool (see rediscoveryOrder).
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
private const val REDISCOVERY_JITTER_MIN = 0.5
private const val REDISCOVERY_JITTER_SPAN = 1.0

/**
 * Loved-track injection quota: Discovery tapers the share of the mix reserved
 * for the user's own loved tracks. At Discovery=0 up to [OWN_INJECT_MAX_FRACTION]
 * of [target]; at Discovery>=1 it is 0 (pure discovery). NO floor (the old
 * always-4 floor made high-Discovery mixes never fully fresh). Never negative.
 */
@VisibleForTesting
internal fun seedTrackInjectCount(discovery: Double, target: Int): Int =
    ((1.0 - discovery) * OWN_INJECT_MAX_FRACTION * target).roundToInt().coerceAtLeast(0)

/**
 * Strictness re-ranks a RECENCY-ordered seed pool between recency (low = anything
 * recently played) and score (high = only most-loved):
 *   priority = strictness*scoreNorm + (1 - strictness)*recencyNorm
 * Replaces the old score-FLOOR, which did nothing on a well-rated library:
 * `ORDER BY score DESC LIMIT n` returned the same top-scored seeds at every
 * floor, so low and high Strictness produced identical mixes. [pool] must be
 * recency-descending (index 0 = most recent).
 */
@VisibleForTesting
internal fun strictnessRankedPool(pool: List<SeedTrack>, strictness: Double): List<SeedTrack> {
    if (pool.size <= 1) return pool
    val maxScore = pool.maxOf { it.score }
    val minScore = pool.minOf { it.score }
    val range = (maxScore - minScore).takeIf { it > 0.0 } ?: 1.0
    val lastIndex = (pool.size - 1).toDouble()
    val s = strictness.coerceIn(0.0, 1.0)
    return pool.withIndex().sortedByDescending { (index, seed) ->
        val scoreNorm = (seed.score - minScore) / range
        val recencyNorm = 1.0 - index / lastIndex
        s * scoreNorm + (1.0 - s) * recencyNorm
    }.map { it.value }
}

/**
 * Randomised order over [seeds] that leans on `score` as [strictness] rises.
 *
 * Weighted sampling WITHOUT replacement (Efraimidis-Spirakis): draw one uniform
 * per item and sort by `u^(1/w)`, which yields an order whose first element is
 * chosen with probability `w_i / sum(w)`, and so on down the list. At strictness
 * 0 every weight is 1.0, so this degrades exactly to a uniform shuffle.
 *
 * NOTE this only re-weights WITHIN the cluster it is handed. It cannot conjure
 * confirmed-taste seeds that the pool query never returned, and measured on a
 * real history that is the binding constraint: `getRecentSeedTracks` orders by
 * recency alone, so of ~739 artists with 3+ plays in the last 30 days only ~10
 * survive into the 600-row window. This helper moved once-only seeds 88% -> 79%;
 * closing the rest needs the POOL to carry confirmed taste, not a better draw.
 *
 * It stays a DRAW rather than a ranking on purpose: consecutive mixes must still
 * differ (that is what [strictnessRankedPool] plus rotation buys us), we only
 * want the passively-played tail to stop being as likely as a track the listener
 * actually returned to. See [SEED_WEIGHT_STRENGTH] for the measurement.
 */
@VisibleForTesting
internal fun strictnessWeightedOrder(
    seeds: List<SeedTrack>,
    strictness: Double,
    random: kotlin.random.Random
): List<SeedTrack> {
    if (seeds.size <= 1) return seeds
    val s = strictness.coerceIn(0.0, 1.0)
    if (s <= 0.0) return seeds.shuffled(random)
    // RANK, not min-max: `tracks.score` is long-tailed (a real cluster ran
    // -0.32..1.96 with the mass under 0.4), so a single high outlier flattened
    // min-max normalisation and left the top and bottom of the pool within 1.4x
    // of each other instead of the intended 10x. Ranking is outlier-proof.
    // Ties share the lowest rank of the tied run, so equal scores keep equal
    // weight and a flat cluster stays a uniform shuffle.
    val rankByScore = seeds.map { it.score }.distinct().sorted()
        .withIndex().associate { (i, score) -> score to i }
    val lastRank = (rankByScore.size - 1).takeIf { it > 0 } ?: return seeds.shuffled(random)
    // The key is drawn ONCE per seed and the materialised pairs are sorted:
    // sorting on a lambda that calls random() would re-roll the key on every
    // comparison, an inconsistent comparator that makes TimSort throw.
    return seeds
        .map { seed ->
            val scoreNorm = (rankByScore.getValue(seed.score)).toDouble() / lastRank
            val weight = 1.0 + s * SEED_WEIGHT_STRENGTH * scoreNorm
            seed to random.nextDouble().pow(1.0 / weight)
        }
        .sortedByDescending { it.second }
        .map { it.first }
}

/**
 * Variety window (count of top-ranked seeds the primary is drawn from) with NO
 * plateau: spans the whole tagged pool across 0..1 so the upper half of the
 * slider keeps changing the result (the old 0.66 full-rotation threshold made
 * every value >= 0.66 identical). variety 0 -> 1 (steadiest), 1 -> n (widest).
 */
@VisibleForTesting
internal fun varietyWindow(variety: Double, n: Int): Int =
    (1.0 + variety.coerceIn(0.0, 1.0) * (n - 1)).roundToInt().coerceIn(1, n)

/**
 * Cluster membership. ONE rule: a seed belongs to the mix when it IS the same
 * kind of music as the primary, i.e. their dominant families match.
 *
 * This replaced a two-part rule (at least one shared exact tag, vetoed on
 * disjoint families) that ran in parallel with a seed-union envelope on the
 * candidate side. Two half-rules let a multi-family primary bridge two worlds:
 * anchored on `Ronan [house, chillout, lounge]`, the cluster admitted both
 * house acts and bossa-cover acts, and the mix came out as french house next to
 * lounge standards. Measured on the real library, 90% of primaries still find
 * 8+ same-family seeds; the rest get a smaller cluster, which the pool wideners
 * now cover.
 *
 * When the primary carries no mapped family at all there is nothing to compare,
 * so membership falls back to a plain shared exact tag.
 */
@VisibleForTesting
internal fun seedJoinsCluster(
    seedGenres: List<String>,
    primaryGenres: List<String>,
    primaryFamily: String?
): Boolean {
    if (primaryFamily == null) return seedGenres.any { it in primaryGenres }
    return dominantFamily(seedGenres) == primaryFamily
}

/**
 * Word-level genre tokens for the LOOSE candidate gate ("indie rock" -> indie,
 * rock). Short tokens ("of", "nu") and connectors ("and": "drum and bass" must
 * not match "rhythm and blues") are noise and dropped.
 */
@VisibleForTesting
internal fun genreTokens(genres: Iterable<String>): Set<String> =
    genres.flatMap { normalizeGenre(it).split(' ', '-') }
        .filter { it.length > 2 && it != "and" && it != "the" }
        .toSet()

/**
 * Loose token overlap for gating candidates against the seed cluster's genre
 * envelope: any shared or containing token passes ("tech house" vs "house",
 * "electro" vs "electronic"). Deliberately permissive: this is a safety net
 * against a whole foreign family (techno in an indie mix), not a strict filter.
 */
@VisibleForTesting
internal fun genresOverlapLoose(candidateGenres: Iterable<String>, envelopeTokens: Set<String>): Boolean {
    if (envelopeTokens.isEmpty()) return true
    return genreTokens(candidateGenres).any { c ->
        envelopeTokens.any { e -> c == e || c.contains(e) || e.contains(c) }
    }
}

/**
 * Candidate genre gate decision: the candidate's DOMINANT family (see
 * [dominantFamily]) must belong to the cluster envelope. The loose token
 * overlap only covers candidates whose tags are all unmapped.
 *
 * [candidateGenres] must be weight-ordered (Last.fm `artist.getTopTags` order),
 * which is what `LastFmGenreResolver.cachedGenres` returns. The previous
 * any-family rule admitted an artist on their weakest tag, and on real mixes
 * that was the dominant source of bleed: `trance, electronic, ambient` passed
 * a lounge envelope, `darkwave, electronic, synthpop` passed a jazz/pop one.
 */
@VisibleForTesting
internal fun genreGatePasses(
    candidateGenres: List<String>,
    envelopeFamilies: Set<String>,
    envelopeTokens: Set<String>
): Boolean {
    val dominant = dominantFamily(candidateGenres)
    if (dominant != null && envelopeFamilies.isNotEmpty()) return dominant in envelopeFamilies
    return genresOverlapLoose(candidateGenres, envelopeTokens)
}

/**
 * Does this track qualify as a seed for a Genre Radio on [target]?
 *
 * The ARTIST's genres decide whenever we have them; a track's own genres are
 * consulted only for artists we know nothing about. Track genres are raw
 * provider metadata and routinely wrong at the item level: one Ist Ist track
 * carries a stray "disco" tag, and seeding a disco radio on a post-punk band
 * put its darkwave similars at the front of the queue. An artist tagged `post
 * punk, new wave` is not a disco act, whatever one of their tracks claims.
 */
@VisibleForTesting
internal fun seedMatchesGenre(artistGenres: List<String>, trackGenres: List<String>, target: String): Boolean {
    val source = artistGenres.ifEmpty { trackGenres }
    return source.any { normalizeGenre(it) == target }
}

/**
 * Whether THIS mix moves to a different genre family than the recent ones.
 * Variety is the PROBABILITY of hopping, drawn once per mix.
 *
 * It used to be a threshold (hop only at Variety >= 0.66), which turned the
 * knob into a cliff: at 0.52 every mix was pinned to the recent family, and
 * combined with the exact-genre freshness rule ("same family, but a sub-genre
 * unused for six mixes") the candidate primaries collapsed from 97 to 1. Two
 * consecutive mixes then anchored on the same cluster and shared a third of
 * their artists. As a probability the knob is smooth, and the extremes still
 * mean exactly what they did: 0 never hops, 1 always does.
 */
@VisibleForTesting
internal fun shouldHopFamily(variety: Double, random: kotlin.random.Random): Boolean =
    random.nextDouble() < variety.coerceIn(0.0, 1.0)

/**
 * Is this candidate primary what the mix is looking for? With [hop] it wants a
 * DIFFERENT family than the recent mixes (exploration), without it the SAME one
 * (drift inside the current neighbourhood, no whiplash to unrelated genres).
 * With no recent families there is no preference (returns false), so the caller
 * falls back to the plain fresh pool.
 */
@VisibleForTesting
internal fun prefersCandidateFamily(
    candidateFamilies: Set<String>,
    recentFamilies: Set<String>,
    hop: Boolean,
): Boolean {
    if (recentFamilies.isEmpty()) return false
    val sharesRecentFamily = candidateFamilies.any { it in recentFamilies }
    return if (hop) !sharesRecentFamily else sharesRecentFamily
}

/**
 * Track-level recommendation generator: recent (or in-genre) well-listened
 * tracks seed Last.fm `track.getSimilar`, producing a coherent candidate pool
 * that is resolved to playable provider URIs (cache-first + bounded search +
 * background prefetch) and run through [MixEngine.buildFromCandidates] for
 * diversity/interleave. Primary engine for both Smart Mix and Genre Radio.
 *
 * Pure of UI/VM concerns: the caller supplies the [Tuning] knobs, the track
 * target, and the [Recency] cool-down context; this returns an ordered track
 * list (empty when it cannot produce a solid mix, so the caller can fall back).
 */
@Singleton
class SeedTrackMixGenerator @Inject constructor(
    private val playHistoryRepository: PlayHistoryRepository,
    private val musicRepository: MusicRepository,
    private val lastFmTrackSimilarResolver: LastFmTrackSimilarResolver,
    private val lastFmSimilarResolver: LastFmSimilarResolver,
    private val lastFmGenreResolver: LastFmGenreResolver,
    private val settingsRepository: SettingsRepository,
    private val mixEngine: MixEngine
) {
    /**
     * Tuning knobs (0..1) from settings; Length is folded into [target].
     * Strictness gates which tracks may seed a mix: 0 = anything recently
     * listened (not disliked), 1 = only your most-loved tracks.
     */
    data class Tuning(val variety: Double, val discovery: Double, val strictness: Double)

    /** Cool-down + exclusion context owned by the caller so back-to-back mixes diverge. */
    data class Recency(
        val excludedTrackUris: Set<String>,
        val recentArtistCounts: Map<String, Int>,
        val recentMixTrackUris: Set<String>,
        /** Raw names of blocked artists; the generator normalizes and excludes them from seeds, candidates and injection. */
        val blockedArtistNames: Set<String> = emptySet(),
        /**
         * Normalized cluster genres of the last few smart mixes. The next
         * primary seed prefers a genre family OUTSIDE this set, so consecutive
         * mixes rotate across everything the user listens to instead of
         * re-anchoring on the dominant genre every time.
         */
        val recentClusterGenres: Set<String> = emptySet()
    )

    /** A built mix plus the primary-cluster genres it anchored on (for the caller's rotation cool-down). */
    data class SeedMixResult(val tracks: List<Track>, val clusterGenres: Set<String>)

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var prefetchJob: Job? = null

    private data class SeedCandidate(
        val artist: String,
        val track: String,
        val matchScore: Double,
        val nameKey: String
    )

    /**
     * Smart Mix: seed from a genre-coherent cluster of recent tracks (rotated per
     * run for variety). Empty if there are too few seeds or candidates.
     */
    suspend fun buildSmartMix(tuning: Tuning, target: Int, recency: Recency): SeedMixResult {
        if (!hasLastFmKey()) return SeedMixResult(emptyList(), emptySet())
        val mixSeed = System.currentTimeMillis()
        val random = kotlin.random.Random(mixSeed)
        val selection = selectSeedTracks(tuning, random, recency)
        val seeds = selection.seeds
        if (seeds.size < MIN_SEEDS) {
            Log.d(TAG, "only ${seeds.size} seeds, skipping")
            return SeedMixResult(emptyList(), emptySet())
        }
        Log.d(TAG, "${seeds.size} seeds -> ${seeds.joinToString { "${it.artistName} - ${it.trackName}" }}")
        val tracks = assembleSeedTrackMix(
            seeds, tuning, target, mixSeed, recency,
            selection.coherentGenres, selection.envelope, selection.coreFamilies
        )
        return SeedMixResult(tracks, selection.coherentGenres)
    }

    /**
     * Genre Radio: seed from the user's own tracks tagged with [genre]. Genre is
     * fixed, so the pool stays coherent with zero bleed. Empty if there are too
     * few in-genre seeds (caller falls back to the server radio).
     */
    suspend fun buildGenreRadio(genre: String, tuning: Tuning, target: Int, recency: Recency): List<Track> {
        if (!hasLastFmKey()) return emptyList()
        val mixSeed = System.currentTimeMillis()
        val random = kotlin.random.Random(mixSeed)
        val seeds = selectGenreSeedTracks(genre, tuning, random)
        if (seeds.size < MIN_SEEDS) {
            Log.d(TAG, "genre '$genre': only ${seeds.size} in-genre seeds, deferring to server radio")
            return emptyList()
        }
        Log.d(TAG, "genre '$genre': ${seeds.size} seeds -> ${seeds.joinToString { it.artistName }}")
        // Tight coherence: inject only loved tracks tagged with the chosen genre,
        // and gate candidates on that genre's own family.
        val genreSet = setOf(normalizeGenre(genre))
        return assembleSeedTrackMix(
            seeds, tuning, target, mixSeed, recency, genreSet, genreSet, genreFamilies(genreSet)
        )
    }

    private suspend fun hasLastFmKey(): Boolean =
        try {
            settingsRepository.lastFmApiKey.first().isNotBlank()
        } catch (_: Exception) {
            false
        }

    // Shared core: gather a deduped track.getSimilar candidate pool, gate it
    // against the cluster's genre envelope, resolve to playable tracks
    // (cache-first + bounded search + background prefetch), apply the
    // recent-mix cool-down, and run the diversity/interleave.
    @Suppress("LongParameterList")
    private suspend fun assembleSeedTrackMix(
        seeds: List<SeedTrack>,
        tuning: Tuning,
        target: Int,
        mixSeed: Long,
        recency: Recency,
        coherentGenres: Set<String>,
        genreEnvelope: Set<String>,
        coreFamilies: Set<String>
    ): List<Track> {
        // Blocked artists are excluded everywhere: as seeds, as similar
        // candidates, and from loved injection. Matched by normalized name.
        val blockedKeys = recency.blockedArtistNames
            .map { LastFmTrackSimilarResolver.normalizeName(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val activeSeeds = seeds.filterNot {
            LastFmTrackSimilarResolver.normalizeName(it.artistName) in blockedKeys
        }
        if (activeSeeds.isEmpty()) return emptyList()

        val similarsPerSeed = seedSimilarsPerSeed(tuning)
        val similarLists = coroutineScope {
            activeSeeds.map { seed ->
                async {
                    try {
                        lastFmTrackSimilarResolver.resolve(seed.artistName, seed.trackName, similarsPerSeed)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        val bestByKey = LinkedHashMap<String, SeedCandidate>()
        mergeCandidates(bestByKey, similarLists.flatten(), 1.0, blockedKeys)
        val directCount = bestByKey.size

        // Pool wideners: a thin candidate pool cannot fill the target, and what
        // it does fill leans on the loved injection. Both wideners feed the same
        // gate below, so widening never loosens coherence.
        val poolTarget = (target * WIDENER_POOL_FACTOR).roundToInt()
        if (bestByKey.size < poolTarget) {
            widenFromSimilarArtists(activeSeeds, blockedKeys, bestByKey)
        }
        if (bestByKey.size < poolTarget) {
            widenFromGenreTopTracks(coherentGenres, blockedKeys, bestByKey)
        }
        if (bestByKey.isEmpty()) {
            Log.d(TAG, "Last.fm returned no candidates (direct or widened)")
            return emptyList()
        }

        applyTopTrackBonus(bestByKey)
        val ordered = bestByKey.values.sortedByDescending { it.matchScore }
        Log.d(TAG, "${ordered.size} unique candidates ($directCount from track.getSimilar)")

        // Candidate genre gate: a candidate whose DOMINANT artist family is
        // foreign to the cluster envelope (similars of a rogue seed, or genuine
        // similar-drift) is dropped. Tags for the top candidates are warmed
        // first so the gate actually has data to judge on; artists Last.fm has
        // no tags for still pass.
        warmTagsForGate(ordered, genreEnvelope)
        val gated = gateCandidates(ordered, genreEnvelope, coreFamilies, target)

        val resolved = resolveSeedCandidates(gated)
        // Warm the caches for everything we could not use inline so the next mix
        // is fuller and better gated: URI resolution only for candidates that
        // SURVIVED the gate (resolving rejected ones is wasted MA searches, and
        // the widened pool made that waste much larger), artist tags for the
        // whole pool so the gate can judge the tail on the next run.
        scheduleSeedPrefetch(gated, ordered)

        // Recent-mix cool-down: tracks that appeared in the last few mixes, and
        // artists that recurred, are softly penalised (not excluded) so back-to-
        // back mixes diverge without the pool ever collapsing below the target.
        val candidates = resolved
            .filterNot { it.track.uri in recency.excludedTrackUris }
            .map { c ->
                val artistKey = c.track.artistNames.split(",").firstOrNull()?.trim()?.lowercase().orEmpty()
                val artistPenalty = (recency.recentArtistCounts[artistKey] ?: 0) * SEED_RECENT_ARTIST_PENALTY
                val trackPenalty = if (c.track.uri in recency.recentMixTrackUris) SEED_RECENT_TRACK_PENALTY else 0.0
                CandidateTrack(track = c.track, score = c.score - artistPenalty - trackPenalty)
            }
        if (candidates.isEmpty()) return emptyList()

        // Loved-track injection (comfort anchor): low Discovery reserves a slice
        // of the mix for the user's OWN loved tracks (not similars), genre-
        // coherent with the seeds so it never reintroduces off-genre bleed. High
        // Discovery tapers it to ZERO (pure discovery) — there is no floor. The
        // injected tracks carry a mid-pack score so they COMPETE with the
        // similars (no longer guaranteed/front-loaded), sampled per run so
        // favourites rotate.
        val injectCount = seedTrackInjectCount(tuning.discovery, target)
        val injected = if (injectCount > 0) {
            lovedInjection(coherentGenres, coreFamilies, injectCount, mixSeed, recency, tuning.strictness)
        } else {
            emptyList()
        }
        val allCandidates = injected + candidates

        val mix = mixEngine.buildFromCandidates(allCandidates, target, mixSeed, tuning.discovery)
        Log.d(TAG, "built ${mix.size} tracks (target $target) from ${candidates.size} discovery + ${injected.size} loved-injected")
        return mix
    }

    // Merge a Last.fm track list into the candidate pool, keeping the best score
    // per track identity. [scale] weights the SOURCE (a direct track similar is
    // stronger evidence than a widener hit).
    private fun mergeCandidates(
        bestByKey: MutableMap<String, SeedCandidate>,
        similars: List<SimilarTrack>,
        scale: Double,
        blockedKeys: Set<String>
    ) {
        for (sim in similars) {
            if (sim.artist.isBlank() || sim.track.isBlank()) continue
            if (LastFmTrackSimilarResolver.normalizeName(sim.artist) in blockedKeys) continue
            val nameKey = LastFmTrackSimilarResolver.sourceKey(sim.artist, sim.track)
            val score = sim.matchScore * scale
            val existing = bestByKey[nameKey]
            if (existing == null || score > existing.matchScore) {
                bestByKey[nameKey] = SeedCandidate(sim.artist, sim.track, score, nameKey)
            }
        }
    }

    /**
     * Nudge candidates that are one of their artist's well-known tracks ahead of
     * their deep cuts. `track.getSimilar` answers "sounds like", which is not
     * the same as "worth hearing first": meeting an artist through an obscure
     * album track is a weak introduction. Cache-only, so it costs nothing and
     * simply improves as the widener fills the top-track cache.
     */
    private suspend fun applyTopTrackBonus(pool: MutableMap<String, SeedCandidate>) {
        var boosted = 0
        for ((_, candidates) in pool.values.groupBy { LastFmTrackSimilarResolver.normalizeName(it.artist) }) {
            val top = try {
                lastFmTrackSimilarResolver.cachedArtistTopTracks(candidates.first().artist)
            } catch (_: Exception) {
                null
            }
            if (top.isNullOrEmpty()) continue
            val topKeys = top.map { LastFmTrackSimilarResolver.sourceKey(it.artist, it.track) }.toSet()
            for (cand in candidates) {
                if (cand.nameKey !in topKeys) continue
                pool[cand.nameKey] = cand.copy(matchScore = cand.matchScore * TOP_TRACK_BONUS)
                boosted++
            }
        }
        if (boosted > 0) Log.d(TAG, "top-track bonus applied to $boosted candidates")
    }

    // Widener 1 (personal): the seeds' similar ARTISTS (artist.getSimilar, 30-day
    // cached) and then those artists' top tracks. Keeps the "close to what you
    // play" character when the exact seed tracks are unknown to Last.fm.
    private suspend fun widenFromSimilarArtists(
        seeds: List<SeedTrack>,
        blockedKeys: Set<String>,
        bestByKey: MutableMap<String, SeedCandidate>
    ) {
        val seedKeys = seeds.map { LastFmTrackSimilarResolver.normalizeName(it.artistName) }.toSet()
        val similarArtists = coroutineScope {
            seeds.map { seed ->
                async {
                    try {
                        lastFmSimilarResolver.resolve(seed.artistName, WIDENER_SIMILAR_PER_SEED)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }.flatten()
        val bestByArtist = LinkedHashMap<String, SimilarArtist>()
        for (candidate in similarArtists) {
            val key = LastFmTrackSimilarResolver.normalizeName(candidate.name)
            if (key.isBlank() || key in seedKeys || key in blockedKeys) continue
            val existing = bestByArtist[key]
            if (existing == null || candidate.matchScore > existing.matchScore) {
                bestByArtist[key] = candidate
            }
        }
        val picked = bestByArtist.values
            .sortedByDescending { it.matchScore }
            .take(WIDENER_ARTIST_LIMIT)
        if (picked.isEmpty()) return
        val topTracks = coroutineScope {
            picked.map { artist ->
                async {
                    val tracks = try {
                        lastFmTrackSimilarResolver
                            .resolveArtistTopTracks(artist.name, WIDENER_TRACKS_PER_ARTIST)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    artist to tracks
                }
            }.awaitAll()
        }
        for ((artist, tracks) in topTracks) {
            mergeCandidates(bestByKey, tracks, WIDENER_ARTIST_SCALE * artist.matchScore, blockedKeys)
        }
        Log.d(TAG, "widen(similar artists): ${picked.size} artists -> pool ${bestByKey.size}")
    }

    // Widener 2 (last resort): the cluster genre's own top tracks. Less personal
    // than the similar-artist path but in-genre by construction and it always
    // yields something, so a mix with unknown seeds still reaches its target.
    private suspend fun widenFromGenreTopTracks(
        genres: Set<String>,
        blockedKeys: Set<String>,
        bestByKey: MutableMap<String, SeedCandidate>
    ) {
        val specific = genres.filterNot { normalizeGenre(it) in BROAD_GENRE_TAGS }
        val picked = specific.ifEmpty { genres.toList() }.take(WIDENER_TAG_GENRE_LIMIT)
        if (picked.isEmpty()) return
        val lists = coroutineScope {
            picked.map { genre ->
                async {
                    try {
                        lastFmTrackSimilarResolver.resolveTagTopTracks(genre, WIDENER_TAG_TRACKS)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        lists.forEach { mergeCandidates(bestByKey, it, WIDENER_TAG_SCALE, blockedKeys) }
        Log.d(TAG, "widen(genre top tracks): '${picked.joinToString("/")}' -> pool ${bestByKey.size}")
    }

    // Fetch the artist tags the gate reads for the top candidates, so the gate
    // judges on data instead of waving through every uncached artist. Bounded
    // and concurrent; the shared Last.fm rate limiter paces the actual calls.
    private suspend fun warmTagsForGate(ordered: List<SeedCandidate>, envelope: Set<String>) {
        // A cluster with no genre identity (new user, nothing tagged yet) gates
        // nothing, so fetching tags for it would be ~30 wasted API calls and
        // several seconds added to every mix.
        if (envelope.isEmpty()) return
        val names = ordered.take(GATE_WARM_LIMIT).map { it.artist }.distinct()
        val gate = Semaphore(GATE_WARM_CONCURRENCY)
        val warmed = AtomicInteger(0)
        coroutineScope {
            names.map { name ->
                async {
                    try {
                        if (lastFmGenreResolver.cachedGenres(name) == null) {
                            gate.withPermit { lastFmGenreResolver.resolve(name) }
                            warmed.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        // best-effort; an unresolved artist stays "unknown" and passes
                    }
                }
            }.awaitAll()
        }
        if (warmed.get() > 0) Log.d(TAG, "gate warm: fetched ${warmed.get()} artist tags")
    }

    // The user's OWN loved tracks (score >= LOVED_INJECT_MIN_SCORE) that are
    // genre-coherent with the mix, sampled and capped to [count]. These are real
    // played URIs, injected directly (not via similars) as comfort anchors with a
    // mid-pack score so they compete with (not dominate) the similars. Excludes
    // recent-mix tracks so the anchors rotate.
    @Suppress("LongParameterList")
    private suspend fun lovedInjection(
        coherentGenres: Set<String>,
        coreFamilies: Set<String>,
        count: Int,
        mixSeed: Long,
        recency: Recency,
        strictness: Double
    ): List<CandidateTrack> {
        val since = System.currentTimeMillis() - GENRE_SEED_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        val blockedKeys = recency.blockedArtistNames
            .map { LastFmTrackSimilarResolver.normalizeName(it) }
            .filter { it.isNotBlank() }
            .toSet()
        // Floor derives from the Strictness slider, matching the seed threshold
        // (strictness * STRICTNESS_MAX_SCORE). A low Discovery therefore reserves
        // a slice for genuinely loved tracks, not weakly-liked (score ~0.1) filler.
        val minScore = strictness * STRICTNESS_MAX_SCORE
        val raw = querySeedTracks(since, minScore, LOVED_INJECT_POOL_LIMIT)
        val notRecent = raw.filter {
            it.trackUri.isNotBlank() &&
                it.trackUri !in recency.recentMixTrackUris &&
                LastFmTrackSimilarResolver.normalizeName(it.artistName) !in blockedKeys
        }
        // Same gate as the discovery candidates, on the same data: the artist's
        // weight-ordered Last.fm tags. Judging on the DB's track genres let an
        // indie-rock favourite into an electronic mix on a stray "dance" track
        // tag. Dedupe first (the gate is per artist, so it is decided once) and
        // stop as soon as the quota is filled instead of scanning the whole pool.
        // The SAME family the candidate gate uses (the primary's dominant one),
        // not every family present in the primary's tags: the primary `Mentol
        // [house, blues, deep house]` has a stray "blues", and taking families
        // from all of its tags opened blues and, through adjacency, soul and
        // jazz, which put a soul ballad in a french-electro mix.
        val envelopeTokens = genreTokens(coherentGenres)
        val envelopeFamilies = withAdjacentFamilies(coreFamilies)
        val shuffled = rediscoveryOrder(dedupeByArtist(notRecent), kotlin.random.Random(mixSeed))
        val picked = mutableListOf<SeedTrack>()
        var scanned = 0
        for (row in shuffled) {
            if (picked.size >= count) break
            scanned++
            val genres = injectionGenres(row)
            if (coherentGenres.isEmpty() || genreGatePasses(genres, envelopeFamilies, envelopeTokens)) {
                picked += row
            }
        }
        Log.d(TAG, "loved-inject: want=$count scanned=$scanned/${shuffled.size} -> injected ${picked.size}")
        return picked.map { seed ->
            val uri = seed.trackUri
            val sep = uri.indexOf("://")
            CandidateTrack(
                track = Track(
                    itemId = uri.substringAfterLast("/").ifBlank { uri },
                    provider = if (sep > 0) uri.substring(0, sep) else "",
                    name = seed.trackName,
                    uri = uri,
                    artistNames = seed.artistName
                ),
                score = OWN_INJECT_SCORE
            )
        }
    }


    /**
     * Rediscovery bias for the comfort slice: among tracks you like, favour the
     * ones you have NOT heard in a while, so the injected anchors are forgotten
     * favourites rather than the same handful on repeat (the library holds ~579
     * loved tracks untouched for 60+ days). The age weight is logarithmic and
     * multiplied by a per-run random factor, so a much older track usually wins
     * but consecutive mixes still differ.
     */
    private fun rediscoveryOrder(pool: List<SeedTrack>, random: kotlin.random.Random): List<SeedTrack> {
        val now = System.currentTimeMillis()
        // The weight is drawn ONCE per track and sorted on the materialised
        // pairs: `sortedByDescending { random... }` would re-roll the key on
        // every comparison, which is an inconsistent comparator (TimSort throws
        // on a list this size).
        return pool
            .map { seed ->
                val ageDays = ((now - seed.lastPlayedAt).coerceAtLeast(0L)) / MILLIS_PER_DAY.toDouble()
                val jitter = REDISCOVERY_JITTER_MIN + random.nextDouble() * REDISCOVERY_JITTER_SPAN
                seed to kotlin.math.ln(1.0 + ageDays) * jitter
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // Genres an injected loved track is judged by, best source first: the
    // artist's cached Last.fm tags (weight-ordered, what the gate expects), then
    // the DB's artist genres, then the track's own genres as a cold-start
    // fallback. The last two are unordered, so their first mapped family decides.
    private suspend fun injectionGenres(row: SeedTrack): List<String> {
        val cached = try {
            lastFmGenreResolver.cachedGenres(row.artistName)
        } catch (_: Exception) {
            null
        }
        return cached?.takeIf { it.isNotEmpty() }
            ?: orderByFamilyFrequency(row.artistGenres.ifEmpty { row.genres })
    }

    // Discovery knob -> how deep into each seed's similar list we pull. Low
    // discovery keeps the safest top matches; high discovery reaches further
    // down (more obscure, lower-match candidates).
    private fun seedSimilarsPerSeed(tuning: Tuning): Int =
        (SEED_SIMILARS_MIN + tuning.discovery * SEED_SIMILARS_SPAN).toInt().coerceAtLeast(SEED_SIMILARS_MIN)

    // Variety knob -> how many of the most-recent tracks stay as stable anchors.
    private fun seedAnchorCount(tuning: Tuning): Int =
        ((1.0 - tuning.variety) * SEED_ANCHOR_MAX).roundToInt().coerceIn(0, SEED_ANCHOR_MAX)

    // Pick a cluster of seeds that are the same kind of music as a per-run
    // rotated primary (see seedJoinsCluster). Variety comes from WHICH primary
    // is picked; coherence from the single family rule. Falls back to a plain
    // sampled rotation when there is no genre data to anchor a cluster.
    /**
     * Chosen seeds, the tight genre envelope (primary genres, keeps loved
     * injection coherent), the seed-union [envelope] used for token fallbacks
     * and gate relaxation, and [coreFamilies]: what the mix IS, which is the
     * PRIMARY's dominant family.
     *
     * Deriving it from every seed's family is what let one seed's side-tag open
     * a foreign family (a lounge cluster where only `Klub Rider` carried a
     * secondary "electronic" tag admitted trance and IDM), and a multi-family
     * primary merge two worlds. Cluster and gate now answer to the same family.
     */
    private data class SeedSelection(
        val seeds: List<SeedTrack>,
        val coherentGenres: Set<String>,
        val envelope: Set<String> = emptySet(),
        val coreFamilies: Set<String> = emptySet()
    )

    /**
     * Cluster coherence genres, weight-ordered, best source first: the artist's
     * cached Last.fm top tags, then the DB's artist genres, then the track tags.
     *
     * The DB's `artist_genres` is a UNION of everything ever written for the
     * artist (MA provider genres plus Last.fm enrichment), unordered and often
     * wider than Last.fm's top 3. That extra width silently broke clusters: the
     * primary Olga Kouklaki carried a DB-only "chillout", and that one side-tag
     * pulled a jazz act, a reggae act and two chill acts into an electronic
     * cluster. Last.fm's ordered top tags are also exactly what the candidate
     * gate reads, so seeds and candidates are now judged on the same data.
     */
    private suspend fun coherenceGenreMap(pool: List<SeedTrack>): Map<String, List<String>> {
        val map = HashMap<String, List<String>>(pool.size)
        for (seed in pool) {
            val cached = try {
                lastFmGenreResolver.cachedGenres(seed.artistName)
            } catch (_: Exception) {
                null
            }
            // Last.fm tags are weight-ordered; the DB fallbacks are alphabetical
            // sets, so they are reordered by family frequency before anything
            // reads "the first tag" (see orderByFamilyFrequency).
            val normalized = cached?.takeIf { it.isNotEmpty() }?.map { normalizeGenre(it) }
                ?: orderByFamilyFrequency(seed.artistGenres.ifEmpty { seed.genres }.map { normalizeGenre(it) })
            map[seed.trackUri] = normalized
        }
        return map
    }

    private suspend fun selectSeedTracks(
        tuning: Tuning,
        random: kotlin.random.Random,
        recency: Recency
    ): SeedSelection {
        val since = System.currentTimeMillis() - SEED_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        val pool = strictnessRankedPool(queryRecentSeedTracks(since, RECENCY_POOL_LIMIT), tuning.strictness)
        val byArtist = dedupeByArtist(pool)
        if (byArtist.size <= SEED_COUNT) return SeedSelection(byArtist, emptySet())

        val genresBySeed = coherenceGenreMap(byArtist)
        fun coherenceGenres(seed: SeedTrack): Set<String> = genresBySeed[seed.trackUri].orEmpty().toSet()

        val tagged = byArtist.filter { coherenceGenres(it).isNotEmpty() }
        if (tagged.isEmpty()) {
            val anchorCount = seedAnchorCount(tuning)
            val anchors = byArtist.take(anchorCount)
            val sampled = byArtist.drop(anchorCount).shuffled(random).take(SEED_COUNT - anchorCount)
            return SeedSelection(anchors + sampled, emptySet())
        }
        // The primary must have a family of its own: it is what the cluster and
        // the gate are both anchored on, so anchoring on an artist tagged only
        // "instrumental / soundtrack / lo fi" (mood and format tags, deliberately
        // unmapped) would leave the gate with nothing to filter by and produce
        // the one genuinely ungated mix. 99.5% of tagged artists qualify; if a
        // library has none at all we keep the old behaviour rather than refuse.
        val anchorable = tagged.filter { dominantFamily(genresBySeed[it.trackUri].orEmpty()) != null }
            .ifEmpty { tagged }
        // Variety biases WHICH primary we anchor on: low variety restricts it to
        // the top-ranked seeds (steadier), high variety draws from the whole
        // tagged pool. varietyWindow spans the full 0..1 range (no plateau).
        val primaryPool = anchorable.take(varietyWindow(tuning.variety, anchorable.size))
        // Genre movement. exactFresh always rotates the sub-genre (avoid the
        // exact genres of recent mixes) so we never lock onto one thing; on top
        // of that, Variety is the probability that this mix leaves the recent
        // FAMILY altogether. The preference is honoured only while it still
        // leaves a real choice: with fewer than PRIMARY_PREFERENCE_MIN options
        // the two rules have narrowed the field to a near-deterministic pick, so
        // we drop the family preference (then exact freshness, then the whole
        // window) rather than hand back the same cluster as last time.
        val recentFamilies = genreFamilies(recency.recentClusterGenres)
        val hop = shouldHopFamily(tuning.variety, random)
        val exactFresh = primaryPool.filter { seed ->
            coherenceGenres(seed).none { it in recency.recentClusterGenres }
        }
        val preferred = exactFresh.filter { seed ->
            prefersCandidateFamily(genreFamilies(coherenceGenres(seed)), recentFamilies, hop)
        }
        val freshPool = when {
            preferred.size >= PRIMARY_PREFERENCE_MIN -> preferred
            exactFresh.size >= PRIMARY_PREFERENCE_MIN -> exactFresh
            else -> primaryPool
        }
        val primary = freshPool.ifEmpty { primaryPool }.shuffled(random).first()
        val primaryGenres = genresBySeed[primary.trackUri].orEmpty()
        val primaryFamily = dominantFamily(primaryGenres)
        val cluster = byArtist.filter { seed ->
            seed.trackUri == primary.trackUri ||
                seedJoinsCluster(genresBySeed[seed.trackUri].orEmpty(), primaryGenres, primaryFamily)
        }
        // The primary keeps its uniform draw from the (already score-ranked and
        // variety-windowed) freshPool, because rotating the anchor is the whole
        // point of Variety. The remaining seeds are where Strictness had no say
        // at all, so they are drawn score-weighted instead of plain-shuffled.
        val ordered = listOf(primary) +
            strictnessWeightedOrder(
                cluster.filter { it.trackUri != primary.trackUri },
                tuning.strictness,
                random
            )
        val result = ordered.take(SEED_COUNT)
        Log.d(
            TAG,
            "cluster around '${primary.artistName}' (${primaryGenres.joinToString("/")}" +
                "${primaryFamily?.let { " -> $it" }.orEmpty()}): ${result.size} seeds " +
                "(hop=$hop preferred=${preferred.size} exactFresh=${exactFresh.size} " +
                "pool=${primaryPool.size} -> picked from ${freshPool.size})"
        )
        // Both the loved injection and the candidate gate answer to the primary:
        // the injection to its exact genres (tight), the gate to its dominant
        // family. The seed-union envelope survives only as token fallback and as
        // the relaxation target when the tight gate cannot fill the mix.
        val envelope = result.flatMap { coherenceGenres(it) }.toSet()
        return SeedSelection(result, primaryGenres.toSet(), envelope, setOfNotNull(primaryFamily))
    }

    private suspend fun queryRecentSeedTracks(sinceMs: Long, limit: Int): List<SeedTrack> =
        try {
            playHistoryRepository.getRecentSeedTracks(sinceMs, SEED_MIN_LISTENED_MS, limit)
        } catch (e: Exception) {
            Log.w(TAG, "getRecentSeedTracks failed: ${e.message}")
            emptyList()
        }

    // Seeds for Genre Radio: the user's own well-listened tracks tagged with the
    // chosen genre (longer lookback, since the user may not have played it
    // recently). Genre is fixed, so every seed is in-genre.
    private suspend fun selectGenreSeedTracks(
        genre: String,
        tuning: Tuning,
        random: kotlin.random.Random
    ): List<SeedTrack> {
        val target = normalizeGenre(genre)
        val since = System.currentTimeMillis() - GENRE_SEED_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        // Relax on the IN-GENRE count (a strict score may leave plenty overall
        // but few in this genre).
        val inGenre = fetchSeedPool(since, GENRE_SEED_POOL_LIMIT, tuning) { pool ->
            pool.filter { row -> seedMatchesGenre(row.artistGenres, row.genres, target) }
        }
        return dedupeByArtist(inGenre).shuffled(random).take(SEED_COUNT)
    }

    // Fetch the seed pool at the Strictness-derived score floor, then apply
    // [shape] (identity for Smart Mix, genre filter for Genre Radio). If the
    // shaped pool is too small and the floor was non-zero, retry at score >= 0
    // so a mix can still be built for lightly-rated libraries.
    private suspend fun fetchSeedPool(
        sinceMs: Long,
        limit: Int,
        tuning: Tuning,
        shape: (List<SeedTrack>) -> List<SeedTrack>
    ): List<SeedTrack> {
        val minScore = tuning.strictness * STRICTNESS_MAX_SCORE
        val strict = shape(querySeedTracks(sinceMs, minScore, limit))
        if (strict.size >= SEED_POOL_RELAX_MIN || minScore <= 0.0) return strict
        Log.d(TAG, "strict pool ${strict.size} (minScore=$minScore), relaxing to score>=0")
        return shape(querySeedTracks(sinceMs, 0.0, limit))
    }

    private suspend fun querySeedTracks(sinceMs: Long, minScore: Double, limit: Int): List<SeedTrack> =
        try {
            playHistoryRepository.getSeedTracks(sinceMs, SEED_MIN_LISTENED_MS, minScore, limit)
        } catch (e: Exception) {
            Log.w(TAG, "getSeedTracks failed: ${e.message}")
            emptyList()
        }

    // One track per primary artist, preserving the pool's (recency) order.
    private fun dedupeByArtist(pool: List<SeedTrack>): List<SeedTrack> {
        val seenArtists = mutableSetOf<String>()
        val byArtist = mutableListOf<SeedTrack>()
        for (row in pool) {
            val artistKey = LastFmTrackSimilarResolver.normalizeName(row.artistName)
            if (artistKey.isBlank() || !seenArtists.add(artistKey)) continue
            byArtist += row
        }
        return byArtist
    }

    /**
     * Gate the candidate pool against the cluster, tight first: a candidate's
     * DOMINANT family must be one of the cluster's [coreFamilies] (or a
     * neighbour of one). If that leaves too little to build a [target]-track
     * mix, retry against every family present in the seed [envelope] (the old,
     * looser rule) rather than ship a stub mix. This keeps a rich library tight
     * while a new or thin library, where a handful of seeds cannot describe a
     * genre world, still gets a full mix.
     */
    private suspend fun gateCandidates(
        ordered: List<SeedCandidate>,
        envelope: Set<String>,
        coreFamilies: Set<String>,
        target: Int
    ): List<SeedCandidate> {
        if (envelope.isEmpty()) return ordered
        val envelopeTokens = genreTokens(envelope)
        if (envelopeTokens.isEmpty()) return ordered
        val tagsByArtist = HashMap<String, List<String>?>()
        val tight = withAdjacentFamilies(coreFamilies)
        val kept = if (tight.isEmpty()) {
            ordered
        } else {
            gateAgainstFamilies(ordered, tight, envelopeTokens, tagsByArtist, target)
        }
        if (kept.size >= target * GATE_RELAX_FACTOR || tight.isEmpty()) {
            logGateResult(ordered.size, kept.size, tight, relaxed = false)
            return kept
        }
        // Too thin to fill the mix: fall back to the seed-union families.
        val wide = withAdjacentFamilies(genreFamilies(envelope))
        if (wide.size <= tight.size) {
            logGateResult(ordered.size, kept.size, tight, relaxed = false)
            return kept
        }
        val relaxed = gateAgainstFamilies(ordered, wide, envelopeTokens, tagsByArtist, target)
        logGateResult(ordered.size, relaxed.size, wide, relaxed = true)
        return relaxed
    }

    /**
     * Split the pool by what the gate can actually tell about each candidate,
     * then admit the unjudgeable ones ONLY if the judged ones cannot fill the
     * mix. warmTagsForGate covers the top of the pool, so what stays untagged is
     * the tail: artists Last.fm has nothing on, or that we never got to. Letting
     * them all through is how `Palehorse/Palerider [shoegaze, post rock, doom
     * metal]` became track #1 of a house mix (its tags arrived 44 s later, with
     * the prefetch). Dropping them unconditionally would instead break a new
     * user, whose pool is almost entirely unjudgeable.
     */
    private suspend fun gateAgainstFamilies(
        ordered: List<SeedCandidate>,
        families: Set<String>,
        envelopeTokens: Set<String>,
        tagsByArtist: MutableMap<String, List<String>?>,
        target: Int
    ): List<SeedCandidate> {
        val judged = mutableListOf<SeedCandidate>()
        val unjudgeable = mutableListOf<SeedCandidate>()
        for (cand in ordered) {
            val tags = tagsByArtist.getOrPut(cand.artist) {
                try {
                    lastFmGenreResolver.cachedGenres(cand.artist)
                } catch (_: Exception) {
                    null
                }
            }
            when {
                tags.isNullOrEmpty() -> unjudgeable += cand
                genreGatePasses(tags, families, envelopeTokens) -> judged += cand
            }
        }
        if (judged.size >= target * GATE_UNKNOWN_DROP_FACTOR) {
            if (unjudgeable.isNotEmpty()) {
                Log.d(TAG, "genre gate: dropped ${unjudgeable.size} untagged candidates (pool is rich)")
            }
            return judged
        }
        // Both lists were built in the pool's (score-descending) order, so a
        // merge by score restores it without an O(n^2) membership scan.
        return (judged + unjudgeable).sortedByDescending { it.matchScore }
    }

    private fun logGateResult(total: Int, kept: Int, families: Set<String>, relaxed: Boolean) {
        if (kept == total && !relaxed) return
        Log.d(
            TAG,
            "genre gate${if (relaxed) " (relaxed)" else ""}: kept $kept/$total " +
                "candidates (families=${families.sorted().joinToString("/")})"
        )
    }

    // Resolve candidate names to playable tracks: cache-first (instant), then a
    // bounded number of live MA provider searches. Cache misses beyond the inline
    // budget are left to the background prefetch.
    private suspend fun resolveSeedCandidates(ordered: List<SeedCandidate>): List<CandidateTrack> =
        coroutineScope {
            val searchBudget = AtomicInteger(SEED_INLINE_SEARCH_BUDGET)
            val gate = Semaphore(SEED_SEARCH_CONCURRENCY)
            ordered.map { cand ->
                async {
                    val cachedUri = playHistoryRepository
                        .getCachedResolvedTrackUri(cand.nameKey, RESOLVED_TRACK_TTL_MS)
                    if (cachedUri != null) {
                        return@async CandidateTrack(buildSyntheticTrack(cand, cachedUri), cand.matchScore)
                    }
                    if (searchBudget.getAndDecrement() <= 0) return@async null
                    gate.withPermit {
                        searchAndCacheTrack(cand)?.let { CandidateTrack(it, cand.matchScore) }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun searchAndCacheTrack(cand: SeedCandidate): Track? {
        return try {
            val result = withTimeoutOrNull(SEED_SEARCH_TIMEOUT_MS) {
                musicRepository.search(
                    query = "${cand.artist} ${cand.track}",
                    mediaTypes = listOf(MediaType.TRACK),
                    limit = SEED_TRACK_SEARCH_LIMIT
                )
            } ?: return null
            val match = result.tracks.firstOrNull { trackMatchesCandidate(it, cand) }
            val uri = match?.uri?.takeIf { it.isNotBlank() } ?: return null
            playHistoryRepository.cacheResolvedTrackUri(cand.nameKey, uri)
            match
        } catch (_: Exception) {
            null
        }
    }

    // A search hit is accepted only if the title matches and at least one
    // significant artist-name token overlaps: better to skip than inject the
    // wrong track (e.g. a same-titled cover by an unrelated artist).
    private fun trackMatchesCandidate(track: Track, cand: SeedCandidate): Boolean {
        val tName = LastFmTrackSimilarResolver.normalizeName(track.name)
        val cName = LastFmTrackSimilarResolver.normalizeName(cand.track)
        if (tName.isBlank() || cName.isBlank()) return false
        val nameMatch = tName == cName || tName.contains(cName) || cName.contains(tName)
        if (!nameMatch) return false
        val artistTokens = LastFmTrackSimilarResolver.normalizeName(cand.artist)
            .split(" ").filter { it.length > 2 }
        if (artistTokens.isEmpty()) return true
        val trackArtists = LastFmTrackSimilarResolver.normalizeName(track.artistNames)
        return artistTokens.any { trackArtists.contains(it) }
    }

    // URI-only synthetic track: enough to play (the server re-resolves full
    // metadata into the queue on replace) and to bucket by artist name. The
    // provider/itemId are best-effort.
    private fun buildSyntheticTrack(cand: SeedCandidate, uri: String): Track {
        val sep = uri.indexOf("://")
        val provider = if (sep > 0) uri.substring(0, sep) else ""
        val itemId = uri.substringAfterLast("/").ifBlank { uri }
        return Track(
            itemId = itemId,
            provider = provider,
            name = cand.track,
            uri = uri,
            artistNames = cand.artist
        )
    }

    // Single-flight background job that warms the resolution cache for every
    // candidate not resolved inline, plus the artist-tag cache the genre gate
    // reads, so the next mix is fuller and better gated.
    private fun scheduleSeedPrefetch(
        resolutionTargets: List<SeedCandidate>,
        tagTargets: List<SeedCandidate>
    ) {
        if (prefetchJob?.isActive == true) return
        prefetchJob = prefetchScope.launch {
            try {
                val gate = Semaphore(SEED_PREFETCH_CONCURRENCY)
                val warmed = AtomicInteger(0)
                coroutineScope {
                    resolutionTargets.map { cand ->
                        async {
                            val cached = playHistoryRepository
                                .getCachedResolvedTrackUri(cand.nameKey, RESOLVED_TRACK_TTL_MS)
                            if (cached != null) return@async
                            gate.withPermit {
                                if (searchAndCacheTrack(cand) != null) warmed.incrementAndGet()
                            }
                        }
                    }.awaitAll()
                }
                val warmedTags = warmCandidateArtistTags(tagTargets)
                Log.d(TAG, "prefetch: warmed ${warmed.get()} resolutions, $warmedTags artist tags")
            } catch (e: Exception) {
                Log.w(TAG, "prefetch failed: ${e.message}")
            }
        }
    }

    // Sequentially resolve missing artist tags (the global Last.fm rate limiter
    // paces the calls); resolve() writes to the same cache cachedGenres() reads.
    private suspend fun warmCandidateArtistTags(ordered: List<SeedCandidate>): Int {
        var warmedTags = 0
        for (name in ordered.map { it.artist }.distinct()) {
            try {
                if (lastFmGenreResolver.cachedGenres(name) == null) {
                    lastFmGenreResolver.resolve(name)
                    warmedTags++
                }
            } catch (_: Exception) {
                // best-effort warming; the gate treats misses as unknown
            }
        }
        return warmedTags
    }
}
