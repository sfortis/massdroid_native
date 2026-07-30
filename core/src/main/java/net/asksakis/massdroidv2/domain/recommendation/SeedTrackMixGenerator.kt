package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.CachedSimilarArtist
import net.asksakis.massdroidv2.domain.repository.SeedTrack
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

// Plays (all time) that make a track count as confirmed taste rather than a play
// the listener merely sat through. Two is deliberately low: on a real history it
// already yields ~428 distinct artists, so the pool stays wide enough to rotate.
private const val SEED_CONFIRMED_MIN_PLAYS = 2

// Largest share of the seed pool the confirmed-taste query may take, reached at
// Strictness 1.0 and scaled linearly by Strictness (so 0.0 keeps the old
// recency-only pool exactly). Measured on a real 30-day history over 2000
// simulated mixes, non-primary seeds:
//
//   recency only, plain shuffle (old) : 88% played-once-only, mean score 0.319
//   recency only, weighted            : 82%                 , mean score 0.368
//   50/50 split, weighted             : 14%                 , mean score 1.255
//   confirmed only, weighted          :  0%                 , mean score 1.136
//
// The split beats BOTH extremes on score because it keeps the high-scoring recent
// tracks as well. Rotation does not suffer, it IMPROVES: distinct primary seeds
// over 500 mixes went 205 (recency only, 240-artist pool) -> 239 (split, 353) ->
// 288 (confirmed only, 428), because the recency window wastes most of its 600
// rows on repeats of the engine's own output.
private const val SEED_POOL_CONFIRMED_MAX_SHARE = 0.5
private const val GENRE_SEED_LOOKBACK_DAYS = 365
private const val GENRE_SEED_POOL_LIMIT = 250
// MA-native discovery. `similar_artists` returns playable items, so there is no
// name-resolution stage at all and these numbers cost one round-trip each,
// rather than one round-trip PLUS a provider search per candidate.
private const val MA_SIMILAR_PER_SEED = 25
private const val MA_TOP_TRACKS_PER_ARTIST = 5
private const val MA_TRACKS_PER_ARTIST = 2
private const val MA_POOL_FACTOR = 1.6
private const val MA_RANK_DECAY = 0.001
// Extra artists fetched beyond the arithmetic minimum, to absorb the ones that
// return nothing playable.
private const val MA_ARTIST_FETCH_SLACK = 8
private const val MA_TRACK_FETCH_CONCURRENCY = 6
// Cache lifetimes. Similar-artist and top-track listings barely move, and without
// caching the MA route made ~42 live calls per mix (18s) against the Last.fm
// route's 6.6s, which is answered from Room.
private const val MA_SIMILAR_TTL_MS = 14L * 24 * 60 * 60 * 1000
private const val MA_TOP_TRACKS_TTL_MS = 14L * 24 * 60 * 60 * 1000

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
// Injected loved tracks enter with a MID-PACK relevance so they COMPETE with the
// discoveries instead of dominating the front of the mix. A top score (1.0) made
// every mix front-loaded with familiar songs and killed the discovery feel; at
// 0.5 they sit mid-distribution (candidate scores span ~0..1), surfacing some
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
 * What to call the finished mix, from the genres of ALL its seeds.
 *
 * Naming it after the primary seed alone is wrong: the primary is one artist of
 * eight, and the mix is built from the whole cluster. A primary tagged
 * "new wave" can anchor a cluster that is otherwise indie, and calling that a
 * "New wave mix" when indie plays is worse than saying nothing specific.
 *
 * So: the most common dominant genre among the seeds wins, but only if at least
 * two seeds agree on it. Otherwise the seeds genuinely disagree on the detail and
 * we fall back to the family they share ("rock"), which is vaguer but true.
 * Null when even that is unknown, and the caller then says nothing.
 */
@VisibleForTesting
internal fun mixLabel(seedGenres: List<List<String>>, family: String?): String? {
    val votes = seedGenres.mapNotNull { dominantGenre(it) }.groupingBy { it }.eachCount()
    // maxByOrNull on a map is order-dependent on ties; sort the tie away so the
    // same cluster always produces the same name.
    val winner = votes.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()
    return when {
        winner != null && winner.value >= MIX_LABEL_MIN_AGREEMENT -> winner.key
        else -> family
    }
}

private const val MIX_LABEL_MIN_AGREEMENT = 2

/**
 * Rows of the seed pool reserved for confirmed taste (replayed tracks), scaled
 * linearly by [strictness] up to [SEED_POOL_CONFIRMED_MAX_SHARE].
 *
 * Strictness 0.0 MUST return 0: that is the contract that keeps the bottom of the
 * slider on the old recency-only pool, where the listener has asked for "what I
 * played lately", not "what I keep coming back to".
 */
@VisibleForTesting
internal fun confirmedPoolBudget(strictness: Double): Int {
    // NaN would reach roundToInt() and throw ("Cannot round NaN value"), taking
    // the whole mix build down; a broken slider value falls back to the safe,
    // pre-existing recency-only pool instead.
    val s = if (strictness.isNaN()) 0.0 else strictness.coerceIn(0.0, 1.0)
    return (RECENCY_POOL_LIMIT * s * SEED_POOL_CONFIRMED_MAX_SHARE).roundToInt()
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
/**
 * Artist identity for blocking and de-duplication: case, bracketed suffixes and
 * punctuation removed, so `Röyksopp (Live)` and `royksopp` are the same artist.
 */
@VisibleForTesting
internal fun normalizeArtistKey(raw: String): String =
    raw.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

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
 * tracks seed Music Assistant's own `similar_artists` + `top_tracks`, producing
 * a coherent pool of already-playable candidates that is run through
 * [MixEngine.buildFromCandidates] for diversity/interleave. Primary engine for
 * both Smart Mix and Genre Radio.
 *
 * Music Assistant is the single source of truth for BOTH similarity and genre.
 * It answers from the providers the user actually has, so every candidate is
 * playable as returned, and its genres describe the same catalogue the seeds
 * came from. Last.fm is deliberately not consulted here: it answered from its
 * own global catalogue, so it named tracks MA had to resolve by search (often
 * to the wrong version, and slowly), and its tags are blank for whole scenes,
 * which quietly turned the genre gate off exactly where it was needed.
 *
 * Pure of UI/VM concerns: the caller supplies the [Tuning] knobs, the track
 * target, and the [Recency] cool-down context; this returns an ordered track
 * list (empty when it cannot produce a solid mix, so the caller can fall back).
 */
@Singleton
class SeedTrackMixGenerator @Inject constructor(
    private val playHistoryRepository: PlayHistoryRepository,
    private val musicRepository: MusicRepository,
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

    /**
     * A built mix, the primary-cluster genres it anchored on (for the caller's
     * rotation cool-down), and a human-readable name for the cluster.
     *
     * [clusterLabel] exists because the seed-track engine used to hand the caller
     * a null genre, so the phone always said the generic "Smart mix ready" and
     * only named a genre on the rare runs that fell back to the genre engine.
     */
    data class SeedMixResult(
        val tracks: List<Track>,
        val clusterGenres: Set<String>,
        val clusterLabel: String? = null
    )

    /**
     * Smart Mix: seed from a genre-coherent cluster of recent tracks (rotated per
     * run for variety). Empty if there are too few seeds or candidates.
     */
    suspend fun buildSmartMix(tuning: Tuning, target: Int, recency: Recency): SeedMixResult {
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
            selection.coherentGenres, selection.coreFamilies
        )
        return SeedMixResult(tracks, selection.coherentGenres, selection.clusterLabel)
    }

    /**
     * Genre Radio: seed from the user's own tracks tagged with [genre]. Genre is
     * fixed, so the pool stays coherent with zero bleed. Empty if there are too
     * few in-genre seeds (caller falls back to the server radio).
     */
    suspend fun buildGenreRadio(genre: String, tuning: Tuning, target: Int, recency: Recency): List<Track> {
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
            seeds, tuning, target, mixSeed, recency, genreSet, genreFamilies(genreSet)
        )
    }

    /**
     * Shared core: gather candidates from Music Assistant, apply the recent-mix
     * cool-down, and run the diversity/interleave.
     *
     * Music Assistant is the only source of both similarity and genre. It
     * aggregates every configured provider, which is exactly the library the
     * user actually listens to, whereas Last.fm answered from its own global
     * catalogue: it named artists MA could not play (each one costing a
     * `music/search` to resolve, and often resolving to the wrong version), and
     * its tags were blank for whole scenes, which silently emptied the genre
     * gate. See [gatherFromMa].
     *
     * There is no fallback: when MA cannot supply a mix this returns empty and
     * the caller drops to the genre engine, which is a real mix rather than a
     * mix built on a second, disagreeing notion of what the music is.
     */
    @Suppress("LongParameterList")
    private suspend fun assembleSeedTrackMix(
        seeds: List<SeedTrack>,
        tuning: Tuning,
        target: Int,
        mixSeed: Long,
        recency: Recency,
        coherentGenres: Set<String>,
        coreFamilies: Set<String>
    ): List<Track> {
        // Blocked artists are excluded everywhere: as seeds, as similar
        // candidates, and from loved injection. Matched by normalized name.
        val blockedKeys = recency.blockedArtistNames
            .map { normalizeArtistKey(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val activeSeeds = seeds.filterNot { normalizeArtistKey(it.artistName) in blockedKeys }
        if (activeSeeds.isEmpty()) return emptyList()

        val candidates = gatherFromMa(activeSeeds, blockedKeys, coreFamilies, target)
        if (candidates.isEmpty()) {
            Log.d(TAG, "MA returned no candidates for this cluster")
            return emptyList()
        }
        // Recent-mix cool-down: tracks that appeared in the last few mixes, and
        // artists that recurred, are softly penalised (not excluded) so back-to-
        // back mixes diverge without the pool ever collapsing below the target.
        return finishMix(candidates, tuning, target, mixSeed, recency, coherentGenres, coreFamilies)
    }

    /**
     * Recent-mix cool-down, loved-track injection, then the diversity/interleave
     * build.
     */
    @Suppress("LongParameterList")
    private suspend fun finishMix(
        resolved: List<CandidateTrack>,
        tuning: Tuning,
        target: Int,
        mixSeed: Long,
        recency: Recency,
        coherentGenres: Set<String>,
        coreFamilies: Set<String>
    ): List<Track> {
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

    /**
     * Candidate pool straight from Music Assistant, no Last.fm involved.
     *
     * `music/artists/similar_artists` + `music/artists/top_tracks` return fully
     * formed, PLAYABLE items, so this path skips the entire name-resolution stage
     * the Last.fm route needs (up to [SEED_INLINE_SEARCH_BUDGET] `music/search`
     * calls, which is where most of a mix build's wall-clock went).
     *
     * Returns an empty list when MA cannot help - notably when no seed is a
     * `library` artist, since provider items report no similar artists - and the
     * caller then falls back to the Last.fm route.
     */
    private suspend fun gatherFromMa(
        activeSeeds: List<SeedTrack>,
        blockedKeys: Set<String>,
        coreFamilies: Set<String>,
        target: Int
    ): List<CandidateTrack> {
        val seedRefs = activeSeeds.mapNotNull { seed ->
            parseArtistRef(seed.artistUri)?.let { seed to it }
        }
        if (seedRefs.isEmpty()) return emptyList()

        val similarArtists = coroutineScope {
            seedRefs.map { (seed, ref) ->
                async {
                    playHistoryRepository
                        .getCachedMaSimilarArtists(seed.artistUri, MA_SIMILAR_TTL_MS)
                        ?.let { return@async it }
                    val fresh = try {
                        musicRepository.getSimilarArtists(ref.itemId, ref.provider, MA_SIMILAR_PER_SEED)
                    } catch (e: Exception) {
                        Log.w(TAG, "similar_artists failed for ${ref.itemId}: ${e.message}")
                        emptyList()
                    }.map { CachedSimilarArtist(it.uri, it.name, it.genres) }
                    if (fresh.isNotEmpty()) {
                        playHistoryRepository.cacheMaSimilarArtists(seed.artistUri, fresh)
                    }
                    fresh
                }
            }.awaitAll()
        }.flatten()

        // Dedupe by artist identity, drop blocked artists and the seeds themselves.
        val seedNames = activeSeeds.mapTo(mutableSetOf()) { it.artistName.trim().lowercase() }
        val pool = LinkedHashMap<String, CachedSimilarArtist>()
        for (art in similarArtists) {
            val key = art.name.trim().lowercase()
            if (key.isEmpty() || key in seedNames) continue
            if (normalizeArtistKey(art.name) in blockedKeys) continue
            pool.putIfAbsent(key, art)
        }
        if (pool.isEmpty()) return emptyList()

        // Gate on the cluster's families using the genres MA carries on the
        // similar artist itself, falling back to whatever the DB already holds
        // for that artist (MA provider genres written during play history). An
        // artist we cannot judge is KEPT: dropping the unknown is what makes
        // whole scenes invisible, and MA has no genres at all for ~17% of them.
        val dbGenres = playHistoryRepository.getArtistGenreMap(
            pool.values.filter { it.genres.isEmpty() }.map { it.uri }
        )
        val gated = pool.values.filter { art ->
            if (coreFamilies.isEmpty()) return@filter true
            // The DB set is a union with no weight order, so it is reordered by
            // family frequency before dominantFamily reads "the first tag".
            val genres = art.genres.ifEmpty { orderByFamilyFrequency(dbGenres[art.uri].orEmpty()) }
            val family = dominantFamily(genres)
            family == null || family in coreFamilies
        }
        Log.d(TAG, "MA pool: ${pool.size} similar artists, ${gated.size} passed the family gate")

        // Fetch in parallel: this used to be a sequential loop over every gated
        // artist, which made the MA route SLOWER than the Last.fm one it replaces
        // despite doing less work. Only fetch as many artists as the pool target
        // needs, since two tracks each is the cap anyway.
        val wanted = (target * MA_POOL_FACTOR).roundToInt().coerceAtLeast(target)
        val needArtists = (wanted / MA_TRACKS_PER_ARTIST) + MA_ARTIST_FETCH_SLACK
        // Throttled: MA aggregates providers in one asyncio.gather, so a burst
        // starves the server. 34 unbounded calls took 70s; the same work at
        // concurrency 6 is what the Last.fm path already used and why it kept up.
        val gate = Semaphore(MA_TRACK_FETCH_CONCURRENCY)
        val trackLists = coroutineScope {
            gated.shuffled().take(needArtists).map { art ->
                async {
                    gate.withPermit {
                    val ref = parseArtistRef(art.uri) ?: return@async art to emptyList()
                    // top_tracks ONLY. The unbounded artist_tracks listing was
                    // tried as a fallback and cost ~1.5s per artist (27+ tracks
                    // over the wire each), which alone made a build take 70s.
                    // We need two tracks per artist, and top_tracks supplies
                    // that: 20 artists yielded 33 usable tracks in practice.
                    val cached = playHistoryRepository.getCachedArtistTracks(art.uri, MA_TOP_TRACKS_TTL_MS)
                    val tracks = cached ?: try {
                        musicRepository.getArtistTopTracks(ref.itemId, ref.provider, MA_TOP_TRACKS_PER_ARTIST)
                            .also { if (it.isNotEmpty()) playHistoryRepository.cacheArtistTracks(art.uri, it) }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    art to tracks
                    }
                }
            }.awaitAll()
        }

        val out = mutableListOf<CandidateTrack>()
        val seenTitles = mutableSetOf<String>()
        for ((art, tracks) in trackLists) {
            if (out.size >= wanted) break
            var taken = 0
            for (t in tracks) {
                if (taken >= MA_TRACKS_PER_ARTIST) break
                // MA can return the same recording twice (album + single/remaster).
                val titleKey = "${art.name.lowercase()}|${normalizeGenre(t.name)}"
                if (t.uri.isBlank() || !seenTitles.add(titleKey)) continue
                out += CandidateTrack(track = t, score = 1.0 - out.size * MA_RANK_DECAY)
                taken++
            }
        }
        Log.d(TAG, "MA candidates: ${out.size} playable tracks from ${trackLists.size} artists (0 searches, 0 Last.fm)")
        return out
    }

    private data class ArtistRef(val itemId: String, val provider: String)

    /** `library://artist/59` -> (59, library). Null when the uri is unusable. */
    private fun parseArtistRef(uri: String?): ArtistRef? {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val provider = raw.substringBefore("://", "").trim()
        val itemId = raw.substringAfter("://", "").trim('/').substringAfterLast('/').trim()
        if (provider.isEmpty() || itemId.isEmpty()) return null
        return ArtistRef(itemId, provider)
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
            .map { normalizeArtistKey(it) }
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
                normalizeArtistKey(it.artistName) !in blockedKeys
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

    // Genres an injected loved track is judged by: the DB's artist genres (what
    // MA reported for the artist), falling back to the track's own genres. Both
    // are unordered sets, so they are reordered by family frequency before the
    // first mapped family decides.
    private fun injectionGenres(row: SeedTrack): List<String> =
        orderByFamilyFrequency(row.artistGenres.ifEmpty { row.genres })

    // Variety knob -> how many of the most-recent tracks stay as stable anchors.
    private fun seedAnchorCount(tuning: Tuning): Int =
        ((1.0 - tuning.variety) * SEED_ANCHOR_MAX).roundToInt().coerceIn(0, SEED_ANCHOR_MAX)

    // Pick a cluster of seeds that are the same kind of music as a per-run
    // rotated primary (see seedJoinsCluster). Variety comes from WHICH primary
    // is picked; coherence from the single family rule. Falls back to a plain
    // sampled rotation when there is no genre data to anchor a cluster.
    /**
     * Chosen seeds, the tight genre envelope ([coherentGenres]: the primary's
     * genres, which keep the loved injection coherent), and [coreFamilies]:
     * what the mix IS, which is the PRIMARY's dominant family.
     *
     * Deriving it from every seed's family is what let one seed's side-tag open
     * a foreign family (a lounge cluster where only `Klub Rider` carried a
     * secondary "electronic" tag admitted trance and IDM), and a multi-family
     * primary merge two worlds. Cluster and gate now answer to the same family.
     */
    private data class SeedSelection(
        val seeds: List<SeedTrack>,
        val coherentGenres: Set<String>,
        val coreFamilies: Set<String> = emptySet(),
        /**
         * What to call this mix for the user, e.g. "post punk". [coherentGenres]
         * cannot answer that: it is a Set, so it has lost the weight order that
         * makes the primary's first tag the meaningful one.
         */
        val clusterLabel: String? = null
    )

    /**
     * Cluster coherence genres per seed: the DB's artist genres, falling back to
     * the track's own tags.
     *
     * `artist_genres` is a UNION of everything MA ever reported for the artist,
     * unordered, so it is reordered by family frequency before anything reads
     * "the first tag" (see [orderByFamilyFrequency]) - the tag that decides the
     * cluster must be the one the artist's body of tags actually points at, not
     * whichever landed first alphabetically. The candidate gate reads the same
     * source, so seeds and candidates are judged on the same data.
     */
    private fun coherenceGenreMap(pool: List<SeedTrack>): Map<String, List<String>> {
        val map = HashMap<String, List<String>>(pool.size)
        for (seed in pool) {
            map[seed.trackUri] =
                orderByFamilyFrequency(seed.artistGenres.ifEmpty { seed.genres }.map { normalizeGenre(it) })
        }
        return map
    }

    private suspend fun selectSeedTracks(
        tuning: Tuning,
        random: kotlin.random.Random,
        recency: Recency
    ): SeedSelection {
        val since = System.currentTimeMillis() - SEED_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        val pool = strictnessRankedPool(querySeedPool(since, tuning.strictness), tuning.strictness)
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
        // family.
        return SeedSelection(
            result,
            primaryGenres.toSet(),
            setOfNotNull(primaryFamily),
            mixLabel(result.map { genresBySeed[it.trackUri].orEmpty() }, primaryFamily)
        )
    }

    /**
     * The seed candidate pool: a confirmed-taste slice (tracks replayed at least
     * [SEED_CONFIRMED_MIN_PLAYS] times) plus a recency slice, budgeted by
     * Strictness. See [SEED_POOL_CONFIRMED_MAX_SHARE] for the measurements.
     *
     * Recency always fills whatever the confirmed query could not supply, so a
     * new user with no replay history gets exactly the old recency-only pool
     * rather than an empty mix.
     */
    private suspend fun querySeedPool(sinceMs: Long, strictness: Double): List<SeedTrack> {
        val confirmedBudget = confirmedPoolBudget(strictness)
        val confirmed =
            if (confirmedBudget <= 0) emptyList()
            else queryConfirmedSeedTracks(sinceMs, confirmedBudget)
        val recent = queryRecentSeedTracks(sinceMs, RECENCY_POOL_LIMIT - confirmed.size)
        // A track can sit in both slices; the confirmed copy wins so the budget
        // is not silently spent twice on the same row.
        val taken = confirmed.mapTo(mutableSetOf()) { it.trackUri }
        val merged = confirmed + recent.filter { taken.add(it.trackUri) }
        Log.d(
            TAG,
            "seed pool: ${merged.size} (confirmed ${confirmed.size}/$confirmedBudget " +
                "+ recent ${merged.size - confirmed.size}, strictness $strictness)"
        )
        return merged
    }

    private suspend fun queryRecentSeedTracks(sinceMs: Long, limit: Int): List<SeedTrack> =
        if (limit <= 0) emptyList() else try {
            playHistoryRepository.getRecentSeedTracks(sinceMs, SEED_MIN_LISTENED_MS, limit)
        } catch (e: Exception) {
            Log.w(TAG, "getRecentSeedTracks failed: ${e.message}")
            emptyList()
        }

    private suspend fun queryConfirmedSeedTracks(sinceMs: Long, limit: Int): List<SeedTrack> =
        try {
            playHistoryRepository.getConfirmedSeedTracks(
                sinceMs,
                SEED_MIN_LISTENED_MS,
                SEED_CONFIRMED_MIN_PLAYS,
                limit
            )
        } catch (e: Exception) {
            Log.w(TAG, "getConfirmedSeedTracks failed: ${e.message}")
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
            val artistKey = normalizeArtistKey(row.artistName)
            if (artistKey.isBlank() || !seenArtists.add(artistKey)) continue
            byArtist += row
        }
        return byArtist
    }
}
