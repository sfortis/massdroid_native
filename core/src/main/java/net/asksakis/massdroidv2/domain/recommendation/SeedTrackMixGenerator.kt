package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
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

// Only library items answer `similar_artists`; see [productiveFirst].
private const val LIBRARY_URI_PREFIX = "library://"

private const val SEED_COUNT = 8
private const val SEED_ANCHOR_MAX = 3
private const val SEED_LOOKBACK_DAYS = 30
/**
 * Score a track must reach before it may SEED a mix.
 *
 * A track played once and never returned to settles at 0.28 (2695 of them on a
 * real library, 74% of everything played in a month), so with no floor the
 * engine's own output became its input: eight Guts tracks, each played exactly
 * once because a previous mix served them, made him a primary seed and produced
 * a 33-track hip hop mix for a listener whose history is 6125 electronic plays
 * and 8 abstract-hip-hop ones.
 *
 * Set just above that 0.28 so a single passive play cannot seed, while a replay,
 * a like, or a full listen still can. Measured effect on the pool: 2192 eligible
 * artists -> 688, which is still ~86x the eight seeds a mix needs.
 */
private const val SEED_MIN_SCORE = 0.30

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
// Ceiling for the per-seed depth when a cluster has only one or two library
// seeds to expand from. Above this the server cost stops being worth it (100
// takes 4.9s against 0.3s for 25, measured).
private const val MA_SIMILAR_MAX_PER_SEED = 100
private const val MA_TOP_TRACKS_PER_ARTIST = 5
private const val MA_TRACKS_PER_ARTIST = 2
private const val MA_POOL_FACTOR = 1.6
private const val MA_RANK_DECAY = 0.001
// Discovery's reach. Depth: how far down each seed's similar list we fetch
// (1x the base at Discovery 0, 3x at 1). Window: how much wider than the
// artists we need the draw pool is, so high Discovery can surface the tail
// instead of the same closest artists every run.
private const val DISCOVERY_DEPTH_SPAN = 2.0
private const val DISCOVERY_WINDOW_SPAN = 2.0
// Extra artists fetched beyond the arithmetic minimum, to absorb the ones that
// return nothing playable.
private const val MA_ARTIST_FETCH_SLACK = 8
private const val MA_TRACK_FETCH_CONCURRENCY = 6

/**
 * Wall-clock budget for a whole Smart Mix build, measured from the moment it
 * starts. Builds ran 3s to 28s on a real library and the tail is spent waiting for
 * the slowest provider to answer top_tracks; past this point the mix is assembled
 * from whatever arrived. Chosen over "wait for everything" because a mix that is a
 * few candidates lighter is indistinguishable to the listener, while half a minute
 * of spinner is not.
 */
private const val BUILD_BUDGET_MS = 15_000L

/**
 * Floor for the fetch stage, in case seed selection alone ate the budget. Without
 * it a slow start would leave zero time for track fetching and yield an empty mix
 * rather than a short one.
 */
private const val MIN_FETCH_BUDGET_MS = 2_000L
// `similar_tracks` route. Every seed is asked, because this is the route most
// setups actually have: a provider that does not implement it costs one
// round-trip per seed to find that out, and one that does answers for library and
// provider items alike (measured: 8 of 8 seeds answered 25 tracks each).
private const val SIMILAR_TRACK_SEED_LIMIT = SEED_COUNT
private const val SIMILAR_TRACKS_PER_SEED = 20
// Track-level similarity is good evidence but less structured than "this artist
// is similar, here are their top tracks", so it ranks just under the artist
// route rather than competing with its best.
private const val SIMILAR_TRACK_SCALE = 0.8

/**
 * What a candidate keeps when we know its family and it is NOT one the mix is
 * anchored on (`FamilyMatch.OFF`).
 *
 * Low enough that an off-family candidate never outranks an on-family one worth
 * having: at this scale the closest possible off-family candidate scores below an
 * on-family candidate sitting halfway down its seed's similar list. So they fill
 * the tail of a mix that would otherwise be short, and nothing more. That is the
 * point, because the pool starving is exactly how unrelated tracks got in: when
 * the family gate dropped every describable disagreement, a cluster left with 8
 * usable candidates for 33 slots filled the rest from other seeds' neighbourhoods.
 */
private const val OFF_FAMILY_SCALE = 0.35
// Cache lifetimes. Similar-artist and top-track listings barely move, and without
// caching the MA route made ~42 live calls per mix (18s) against the Last.fm
// route's 6.6s, which is answered from Room.
private const val MA_SIMILAR_TTL_MS = 14L * 24 * 60 * 60 * 1000
private const val MA_TOP_TRACKS_TTL_MS = 14L * 24 * 60 * 60 * 1000

/**
 * Lifetime of a seed's cached `similar_tracks`. Same 14 days as the artist route's
 * caches, for the same reason: a track's neighbourhood barely moves, while asking
 * for it fresh on every build cost 8 to 14 seconds of a 15-second budget.
 *
 * Shorter than the others would defeat the purpose, since the seeds themselves
 * rotate: a seed asked today is unlikely to be asked again tomorrow, so the cache
 * pays off across weeks rather than within a session.
 */
private const val MA_SIMILAR_TRACKS_TTL_MS = 14L * 24 * 60 * 60 * 1000

// Recent-mix cool-down penalties (subtracted from a candidate's score when the
// track / its artist appeared in recent mixes). Raised from 0.2/0.5 after
// offline tuning on the real library: with weighted-sampling selection the
// stronger penalties meaningfully cut cross-mix repetition (a 0.5 track penalty
// was too small to dislodge a ~0.9-match recent track). Paired with the deeper
// SMART_MIX_HISTORY_DEPTH in MixPlaybackOrchestrator.
private const val SEED_RECENT_ARTIST_PENALTY = 0.4
private const val SEED_RECENT_TRACK_PENALTY = 1.5
// One seed is enough now that a lone library seed is asked for up to
// MA_SIMILAR_MAX_PER_SEED similars: a single-seed cluster produced a full mix in
// testing, while refusing it dropped the user to the genre engine and 8 tracks.
// Precise MusicBrainz genres make clusters tighter, so single-seed ones (the
// only punk artist in a library, say) are now common rather than pathological.
private const val MIN_SEEDS = 1
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
    // The PRIMARY seed is what the cluster was built around, so it names the
    // mix. A majority vote across all seeds sounds fairer but is not: umbrella
    // tags like "alternative" and "indie" appear as the lead genre of a great
    // many artists, so they win the vote in almost any rock-ish cluster. A
    // progressive-rock mix around Gazpacho (progressive rock / art rock /
    // crossover prog) came out named "alternative", as did a post-rock one
    // around Pg.lost, which tells the listener nothing about either.
    val primary = seedGenres.firstOrNull()?.let { dominantGenre(it) }
    if (primary != null && primary !in UMBRELLA_LABELS) return primary

    // The primary itself is an umbrella (or has no mapped genre): fall back to
    // what the rest of the cluster agrees on, then to the family.
    val votes = seedGenres.drop(1).mapNotNull { dominantGenre(it) }
        .filterNot { it in UMBRELLA_LABELS }
        .groupingBy { it }
        .eachCount()
    // maxByOrNull on a map is order-dependent on ties; sort the tie away so the
    // same cluster always produces the same name.
    val winner = votes.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()
    return when {
        winner != null && winner.value >= MIX_LABEL_MIN_AGREEMENT -> winner.key
        else -> primary ?: family
    }
}

private const val MIX_LABEL_MIN_AGREEMENT = 2

/**
 * Genres too broad to name a mix after. They are real genres and stay perfectly
 * usable for gating; they just say nothing when shown to a listener, and they
 * are common enough as a lead tag to win any vote they take part in.
 */
private val UMBRELLA_LABELS = setOf(
    "alternative", "alternative rock", "indie", "rock", "pop", "electronic",
    "electronica", "dance", "experimental", "instrumental", "world"
)

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
 * The two discovery routes into one pool, keeping the better score when both found
 * the same track.
 *
 * Same-track overlap is real but small (10 of 190 on a measured build), so this is
 * about not letting the weaker evidence overwrite the stronger one rather than
 * about volume. The artist route's entries come first because [MixEngine] re-sorts
 * by score anyway and that route is the better-structured of the two.
 */
@VisibleForTesting
internal fun mergeRoutes(
    fromArtists: List<CandidateTrack>,
    fromTracks: List<CandidateTrack>
): List<CandidateTrack> {
    if (fromTracks.isEmpty()) return fromArtists
    if (fromArtists.isEmpty()) return fromTracks
    val byUri = LinkedHashMap<String, CandidateTrack>(fromArtists.size + fromTracks.size)
    for (c in fromArtists + fromTracks) {
        val existing = byUri[c.track.uri]
        if (existing == null || c.score > existing.score) byUri[c.track.uri] = c
    }
    return byUri.values.toList()
}

/**
 * Comparable genre keys. Lowercased and de-hyphenated, because the two spellings
 * genuinely both occur: this library holds 65 artists tagged `post rock` and 33
 * tagged `post-rock`, and comparing them raw would treat the same scene as two.
 * [dominantFamily] already de-hyphenates for its own lookup; exact-genre
 * comparison needs the same treatment.
 */
private fun genreKeys(genres: Iterable<String>): Set<String> =
    genres.mapTo(mutableSetOf()) { normalizeGenre(it).replace('-', ' ') }

/**
 * Splits the cluster into the seeds that share an exact genre with the primary and
 * the rest, which only share its family.
 *
 * A family is far too coarse to build a mix on. `rock` alone spans 33 genres, from
 * classic rock and rockabilly to shoegaze and post rock, so cluster membership by
 * family means a post-rock anchor and seven indie seeds form one "cluster" and the
 * indie similars then outnumber everything. Measured on a real build anchored on
 * God Is an Astronaut: 7 of 8 seeds were indie and only 2 of the resulting 33
 * tracks were post-rock.
 *
 * Family stays the OUTER boundary (that is what [seedJoinsCluster] decides) and
 * this only reorders inside it, so a scene with too few seeds of its own still
 * fills a mix from the wider family rather than failing. Stable on both sides:
 * the score-weighted and productive-first ordering the caller applies is preserved
 * within each group.
 */
@VisibleForTesting
internal fun partitionByExactGenre(
    seeds: List<SeedTrack>,
    primaryGenres: List<String>,
    genresOf: (SeedTrack) -> List<String>
): Pair<List<SeedTrack>, List<SeedTrack>> {
    val primaryKeys = genreKeys(primaryGenres)
    if (primaryKeys.isEmpty()) return emptyList<SeedTrack>() to seeds
    return seeds.partition { seed -> genreKeys(genresOf(seed)).any { it in primaryKeys } }
}

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
 * Only a `library://` artist can seed discovery: Music Assistant answers
 * `similar_artists` for library items (24-25 each, measured) and returns
 * **nothing at all** for provider items, because the provider path needs
 * `ProviderFeature.SIMILAR_ARTISTS` and Deezer does not implement it.
 *
 * That matters because the library is a small slice of what gets listened to:
 * of the artists eligible to seed a mix here, 524 are library and 1526 are
 * provider-only, so a cluster picked purely on genre averages about two
 * productive seeds out of eight. One unlucky cluster (all provider but a single
 * artist) yielded 25 similar artists and an 8-track "mix".
 *
 * So within the cluster the genre rules already chose, the seeds that can
 * actually produce are moved to the front. This changes NOTHING about which
 * cluster a mix is about, only which of its members are asked.
 */
@VisibleForTesting
internal fun productiveFirst(seeds: List<SeedTrack>): List<SeedTrack> {
    val (productive, rest) = seeds.partition { it.artistUri.startsWith(LIBRARY_URI_PREFIX) }
    return productive + rest
}

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
 * [candidateGenres] must be weight-ordered, which is what the genre resolvers
 * return (MusicBrainz reports genres by count and the resolver preserves it). The previous
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
 * Is this candidate primary what the mix is looking for?
 *
 * Only ever asked with [hop] true, i.e. "give me a different family than the
 * recent mixes". The `hop = false` arm exists for the tests that pin it, but
 * the caller no longer uses it: preferring the RECENT family was what pinned
 * half of all mixes to rock, since that family holds prog, shoegaze, post-punk,
 * garage and dream pop and staying in it is not staying in a sound. Rotation
 * inside a family is handled per genre instead, which is fine-grained enough to
 * drift without whiplash.
 *
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
    private val musicBrainzGenreResolver: MusicBrainzGenreResolver,
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
            selection.coherentGenres, selection.coreFamilies,
            deadlineAt = mixSeed + BUILD_BUDGET_MS
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
            seeds, tuning, target, mixSeed, recency, genreSet, genreFamilies(genreSet),
            deadlineAt = mixSeed + BUILD_BUDGET_MS
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
        coreFamilies: Set<String>,
        deadlineAt: Long
    ): List<Track> {
        // Blocked artists are excluded everywhere: as seeds, as similar
        // candidates, and from loved injection. Matched by normalized name.
        val blockedKeys = recency.blockedArtistNames
            .map { normalizeArtistKey(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val activeSeeds = seeds.filterNot { normalizeArtistKey(it.artistName) in blockedKeys }
        if (activeSeeds.isEmpty()) return emptyList()

        // Two independent routes, run together rather than one as the other's
        // fallback.
        //
        // The artist route (`similar_artists` + `top_tracks`) is better structured
        // but needs `ProviderFeature.SIMILAR_ARTISTS`, which only a handful of
        // providers declare - Deezer does not, and on a typical setup it is served
        // by the OPTIONAL lastfm_recommendations metadata provider. A user without
        // it had no route at all here.
        //
        // The track route (`similar_tracks`) is the common denominator: Deezer,
        // Jellyfin, Plex, Emby, OpenSubsonic and Apple Music all declare it, it
        // answers for library AND provider items alike, and it returns playable
        // tracks directly, with no top_tracks fan-out behind it. Measured on a real
        // build, the eight seeds each answered 25 tracks: 190 distinct tracks by 147
        // distinct artists, half of them artists the listener had never played. On
        // the cluster where the artist route admitted NOTHING (an `industrial` seed
        // whose 70 similars shared no family), the track route returned the right
        // neighbourhood outright: Second Still, Drab Majesty, Ash Code, Selofan.
        //
        // It is also the door to Music Assistant's `sonic_similarity` plugin, which
        // serves CLAP audio-embedding similarity through this very feature.
        // ONE server gate shared by both routes. Music Assistant aggregates every
        // provider in a single asyncio loop, so what matters is the total number of
        // requests in flight, not how many each route makes. Running the routes in
        // parallel with a gate each starved the cheaper one outright: the artist
        // route's 34 top_tracks calls held the server for 14.7s and all 8
        // similar_tracks calls timed out together, leaving a 28-track mix. With a
        // shared gate the two routes queue fairly and neither can crowd the other
        // out.
        val serverGate = Semaphore(MA_TRACK_FETCH_CONCURRENCY)
        val candidates = coroutineScope {
            val artistRoute = async {
                gatherFromMa(
                    activeSeeds, blockedKeys, coreFamilies, target,
                    tuning.discovery, mixSeed, deadlineAt, serverGate
                )
            }
            val trackRoute = async {
                gatherSimilarTracks(
                    activeSeeds, blockedKeys, coreFamilies,
                    SIMILAR_TRACKS_PER_SEED, deadlineAt, serverGate
                )
            }
            mergeRoutes(artistRoute.await(), trackRoute.await())
        }
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
                CandidateTrack(
                    track = c.track,
                    score = c.score - artistPenalty - trackPenalty,
                    verified = c.verified
                )
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
     * The ARTIST route: `music/artists/similar_artists` + `music/artists/top_tracks`.
     *
     * Both return fully formed, PLAYABLE items, so this path has no
     * name-resolution stage at all. It only works from `library://` seeds,
     * because it needs `ProviderFeature.SIMILAR_ARTISTS` and a provider seed
     * reliably answers zero (verified: Tosca 0, Anderholm 0, The Raveonettes 24,
     * Juno Francis 25). Only 26% of the artists eligible to seed a mix here are
     * library ones, so this route alone leaves most of a mix unbuilt; the track
     * route in [gatherSimilarTracks] runs alongside it and carries the rest.
     */
    @Suppress("LongParameterList")
    private suspend fun gatherFromMa(
        activeSeeds: List<SeedTrack>,
        blockedKeys: Set<String>,
        coreFamilies: Set<String>,
        target: Int,
        discovery: Double,
        mixSeed: Long,
        deadlineAt: Long,
        serverGate: Semaphore
    ): List<CandidateTrack> {
        val seedRefs = activeSeeds
            .filter { it.artistUri.startsWith(LIBRARY_URI_PREFIX) }
            .mapNotNull { seed -> parseArtistRef(seed.artistUri)?.let { seed to it } }
        if (seedRefs.isEmpty()) {
            Log.d(TAG, "no library seed in this cluster, the track route carries it alone")
            return emptyList()
        }
        // Ask each productive seed deeper when there are few of them, so a
        // cluster carried by one or two library artists still fills a mix.
        //
        // The depth is per seed rather than a flat maximum because the server
        // cost is superlinear, measured on this library: limit 25 answers in
        // 0.3s, 40 in 1.5s, 100 in 4.9s. Asking 100 from all eight seeds put 31s
        // into a single mix (MA aggregates providers in one asyncio loop, so the
        // calls do not really run in parallel); asking 25 each costs ~2s for the
        // same eight, and the one-seed cluster that actually needs the depth
        // pays the 4.9s alone.
        //
        // Discovery widens the same list: at 0 we take only the closest matches
        // (comfort), at 1 we reach a long way down each seed's similars. This is
        // the knob's main job and it went missing when the Last.fm route, which
        // owned it, was removed - leaving Discovery in charge of little more
        // than how many of your own loved tracks get mixed in.
        val depth = (MA_SIMILAR_PER_SEED * (1.0 + discovery * DISCOVERY_DEPTH_SPAN)).roundToInt()
        val perSeed = (depth * SEED_COUNT / seedRefs.size)
            .coerceIn(MA_SIMILAR_PER_SEED, MA_SIMILAR_MAX_PER_SEED)
        Log.d(
            TAG,
            "${seedRefs.size}/${activeSeeds.size} seeds are library artists, " +
                "asking $perSeed similar each (discovery $discovery)"
        )

        val similarArtists = coroutineScope {
            seedRefs.map { (seed, ref) ->
                async {
                    // A cached entry is used as-is even if this mix would have
                    // asked deeper: re-fetching to top it up costs seconds and
                    // buys candidates the pool rarely needs. Depth is therefore
                    // NOT part of the cache key.
                    playHistoryRepository
                        .getCachedMaSimilarArtists(seed.artistUri, MA_SIMILAR_TTL_MS)
                        ?.let { return@async it }
                    val fresh = try {
                        musicRepository.getSimilarArtists(ref.itemId, ref.provider, perSeed)
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
        }

        // Dedupe by artist identity, drop blocked artists and the seeds
        // themselves, and remember each artist's BEST rank across the seeds.
        //
        // The rank is the server's own similarity ordering, and it is the only
        // measure of "how close is this to the seed" this route has. It used to
        // be thrown away (the lists were flattened and later shuffled), so the
        // score attached to a candidate reflected nothing but the order it
        // happened to be processed in - which is why the first track of a mix
        // could be anything at all.
        val seedNames = activeSeeds.mapTo(mutableSetOf()) { it.artistName.trim().lowercase() }
        val pool = LinkedHashMap<String, CachedSimilarArtist>()
        val rankByArtist = HashMap<String, Int>()
        for (list in similarArtists) {
            list.forEachIndexed { rank, art ->
                val key = art.name.trim().lowercase()
                if (key.isEmpty() || key in seedNames) return@forEachIndexed
                if (normalizeArtistKey(art.name) in blockedKeys) return@forEachIndexed
                pool.putIfAbsent(key, art)
                rankByArtist[art.uri] = minOf(rankByArtist[art.uri] ?: Int.MAX_VALUE, rank)
            }
        }
        if (pool.isEmpty()) return emptyList()

        val dbGenres = playHistoryRepository.getArtistGenreMap(
            pool.values.filter { it.genres.isEmpty() }.map { it.uri }
        )
        val genresByArtist = HashMap<String, List<String>>(pool.size)
        for (art in pool.values) genresByArtist[art.uri] = genresFor(art, dbGenres)
        // Family is a RANKING signal, not a veto - except for the undescribable,
        // which stay out. See [FamilyMatch] for the evidence behind each case.
        //
        // This used to drop every candidate whose family disagreed, and that is
        // what let unrelated tracks in rather than keeping them out. Measured over
        // 16 clusters, half had fewer than 10 same-family candidates for 33 slots
        // and one had none at all, because the label disagrees with the server's
        // own neighbourhood: a `jazz` seed's 99 similars resolved 45 to chill and
        // 8 to jazz. A starved cluster then filled from the other seeds'
        // neighbourhoods, which share nothing but the coarse label.
        //
        // Keeping the disagreements at OFF_FAMILY_SCALE puts them behind every
        // on-family candidate, so a healthy cluster is unaffected and a starved
        // one draws on its own neighbourhood instead of a stranger's.
        val familyMatch = HashMap<String, FamilyMatch>(pool.size)
        for (art in pool.values) {
            familyMatch[art.uri] = classifyFamily(genresByArtist[art.uri].orEmpty(), coreFamilies)
        }
        val gated = pool.values.filter { familyMatch[it.uri] != FamilyMatch.UNKNOWN }
        val offFamily = gated.count { familyMatch[it.uri] == FamilyMatch.OFF }
        Log.d(
            TAG,
            "MA pool: ${pool.size} similar artists, ${gated.size} usable " +
                "($offFamily off-family, kept at $OFF_FAMILY_SCALE; " +
                "${pool.size - gated.size} dropped as undescribable)"
        )
        // Whatever we still cannot describe is left to LibraryGenreEnricher's
        // discovery phase, which walks the same similar-artist cache outside any
        // build. Warming it from here was worse than useless: it was capped at
        // twenty per run, it shared the one-per-second MusicBrainz budget with
        // the enricher, and the seed half of it resolved 0 of 20 on thirteen
        // consecutive builds because nothing filtered out the artists already
        // answered "nothing" for.

        // Fetch in parallel: this used to be a sequential loop over every gated
        // artist, which made the MA route SLOWER than the Last.fm one it replaces
        // despite doing less work. Only fetch as many artists as the pool target
        // needs, since two tracks each is the cap anyway.
        val wanted = (target * MA_POOL_FACTOR).roundToInt().coerceAtLeast(target)
        val needArtists = (wanted / MA_TRACKS_PER_ARTIST) + MA_ARTIST_FETCH_SLACK
        // Throttled through the SHARED gate: MA aggregates providers in one
        // asyncio.gather, so a burst starves the server. 34 unbounded calls took
        // 70s; the same work at concurrency 6 is what the Last.fm path already used
        // and why it kept up. The gate is shared with the track route because the
        // server counts total requests, not requests per route.
        val gate = serverGate
        // Closest first, then a Discovery-sized window to draw from. Shuffling
        // the whole gated pool (what this used to do) made the mix open on a
        // random artist; taking a flat top-N instead would pin every mix to the
        // same closest artists and leave Discovery no room past the fetch depth.
        // So: order by the server's similarity, widen the window with Discovery,
        // and sample within it. The candidate SCORE stays the similarity rank,
        // so whoever is drawn, the closest of them still opens the mix.
        //
        // On-family artists are ordered ahead of off-family ones regardless of
        // closeness, because this order decides who we spend a top_tracks call on.
        // Sorting on closeness alone would let a very close disagreement take the
        // budget from an on-family artist further down the list, which is the
        // opposite of what OFF_FAMILY_SCALE means downstream.
        val byCloseness = gated.sortedWith(
            compareBy(
                { familyMatch[it.uri] != FamilyMatch.ON },
                { rankByArtist[it.uri] ?: Int.MAX_VALUE }
            )
        )
        val window = (needArtists * (1.0 + discovery * DISCOVERY_WINDOW_SPAN)).roundToInt()
        val chosen = if (byCloseness.size <= needArtists) {
            byCloseness
        } else {
            byCloseness.take(window).shuffled(kotlin.random.Random(mixSeed)).take(needArtists)
        }
        // Bounded by wall clock, not by completeness. Measured on a real library a
        // build ran 3s to 28s, almost all of it here: dozens of top_tracks calls,
        // any one of which can stall on a slow provider. Waiting for the last
        // straggler made the user stare at a spinner for half a minute.
        //
        // Results are collected AS THEY LAND rather than with awaitAll, so when the
        // budget runs out we keep every artist that answered and drop only the ones
        // still in flight. A slightly smaller pool is a mix; a 28-second wait is not.
        val collected = java.util.concurrent.ConcurrentLinkedQueue<Pair<CachedSimilarArtist, List<Track>>>()
        val remaining = (deadlineAt - System.currentTimeMillis()).coerceAtLeast(MIN_FETCH_BUDGET_MS)
        val finishedInTime = withTimeoutOrNull(remaining) {
            coroutineScope {
                chosen.forEach { art ->
                    launch {
                        gate.withPermit {
                    val ref = parseArtistRef(art.uri) ?: return@launch
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
                    collected += art to tracks
                    }
                    }
                }
            }
        } != null
        // Back into on-family-then-closeness order, the same order the fetch used.
        // `collected` is in ORDER OF ARRIVAL, and the loop below stops at `wanted`,
        // so leaving it as-is would let whichever provider answered fastest decide
        // which candidates make the cut instead of how well they fit.
        val trackLists = collected.sortedWith(
            compareBy(
                { (art, _) -> familyMatch[art.uri] != FamilyMatch.ON },
                { (art, _) -> rankByArtist[art.uri] ?: Int.MAX_VALUE }
            )
        )
        if (!finishedInTime) {
            Log.d(TAG, "top_tracks budget spent: kept ${trackLists.size}/${chosen.size} artists")
        }

        val out = mutableListOf<CandidateTrack>()
        val seenTitles = mutableSetOf<String>()
        for ((art, tracks) in trackLists) {
            if (out.size >= wanted) break
            // What a candidate is worth: how similar the server said the artist is
            // to a seed, scaled down when we know their family and it is not one
            // the mix is anchored on.
            val rank = rankByArtist[art.uri] ?: MA_SIMILAR_MAX_PER_SEED
            val closeness = 1.0 - (rank.toDouble() / MA_SIMILAR_MAX_PER_SEED).coerceIn(0.0, 1.0)
            val onFamily = familyMatch[art.uri] == FamilyMatch.ON
            // `verified` gates who may OPEN a mix, so it means "fits this mix",
            // not merely "we can describe them". An off-family candidate is worth
            // keeping in the tail but must never be the first thing the user hears.
            val verified = onFamily
            val artistScore = if (onFamily) closeness else closeness * OFF_FAMILY_SCALE
            var taken = 0
            for (t in tracks) {
                if (taken >= MA_TRACKS_PER_ARTIST) break
                // MA can return the same recording twice (album + single/remaster).
                val titleKey = "${art.name.lowercase()}|${normalizeGenre(t.name)}"
                if (t.uri.isBlank() || !seenTitles.add(titleKey)) continue
                // Second track of an artist ranks just below the first.
                out += CandidateTrack(
                    track = t,
                    score = artistScore - taken * MA_RANK_DECAY,
                    verified = verified
                )
                taken++
            }
        }
        val onFamilyOut = out.count { it.verified }
        Log.d(
            TAG,
            "artist route: ${out.size} playable tracks from ${trackLists.size} artists " +
                "(on-family=$onFamilyOut, off-family=${out.size - onFamilyOut})"
        )
        return out
    }

    /**
     * The TRACK route: `similar_tracks` on every seed, library and provider alike.
     *
     * This is the route most Music Assistant setups actually have. Deezer,
     * Jellyfin, Plex, Emby, OpenSubsonic and Apple Music all declare
     * `ProviderFeature.SIMILAR_TRACKS`, against the handful that declare
     * SIMILAR_ARTISTS, and it is also how the `sonic_similarity` plugin exposes
     * CLAP audio-embedding similarity.
     *
     * Scored below the artist route's best but overlapping it: a track the provider
     * calls similar to something you played is strong evidence, just less
     * structured than "this artist is similar and here are their top tracks".
     * Returns nothing for providers that do not implement it, which costs one
     * round-trip per seed to discover.
     */
    @Suppress("LongParameterList")
    private suspend fun gatherSimilarTracks(
        seeds: List<SeedTrack>,
        blockedKeys: Set<String>,
        coreFamilies: Set<String>,
        perSeed: Int,
        deadlineAt: Long,
        serverGate: Semaphore
    ): List<CandidateTrack> {
        val refs = seeds.take(SIMILAR_TRACK_SEED_LIMIT)
            .mapNotNull { seed -> parseArtistRef(seed.trackUri)?.let { seed to it } }
        if (refs.isEmpty()) return emptyList()
        val seedTrackUris = seeds.mapTo(mutableSetOf()) { it.trackUri }
        // Bounded by the same build budget as the artist route, and collected AS
        // RESULTS LAND rather than with awaitAll. This route runs on every build
        // now instead of only as a fallback, so one provider that never answers
        // would otherwise hold the whole mix open with no timeout to free it.
        val landed = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, List<Track>>>()
        val remaining = (deadlineAt - System.currentTimeMillis()).coerceAtLeast(MIN_FETCH_BUDGET_MS)
        val finishedInTime = withTimeoutOrNull(remaining) {
            coroutineScope {
                refs.forEachIndexed { index, (seed, ref) ->
                    launch {
                        // Cached as-is even when this build would have asked for more:
                        // the same reasoning as the similar-artist cache, since depth
                        // is not part of the key and re-asking costs seconds. Read
                        // BEFORE taking a permit, so a cache hit never queues behind
                        // the artist route's calls.
                        val cached = playHistoryRepository
                            .getCachedSimilarTracks(seed.trackUri, MA_SIMILAR_TRACKS_TTL_MS)
                        if (cached != null) {
                            if (cached.isNotEmpty()) landed += index to cached
                            return@launch
                        }
                        val tracks = serverGate.withPermit {
                            try {
                                musicRepository.getSimilarTracks(ref.itemId, ref.provider, perSeed)
                            } catch (e: Exception) {
                                Log.w(TAG, "similar_tracks failed for ${seed.trackName}: ${e.message}")
                                emptyList()
                            }
                        }
                        if (tracks.isEmpty()) return@launch
                        playHistoryRepository.cacheSimilarTracks(seed.trackUri, tracks)
                        landed += index to tracks
                    }
                }
            }
        } != null
        // Back into seed order: `landed` is in order of arrival, and a candidate's
        // score comes from its RANK within its seed's list, so the ordering below
        // must not depend on which provider answered first.
        val lists = landed.sortedBy { (index, _) -> index }.map { (_, tracks) -> tracks }
        if (!finishedInTime) {
            Log.d(TAG, "track route budget spent: kept ${lists.size}/${refs.size} seeds")
        }
        val kept = mutableListOf<CandidateTrack>()
        val seen = mutableSetOf<String>()
        var dropped = 0
        var offFamily = 0
        // The same three-way family judgement the artist route uses, and this route
        // needs it at least as much: track-similarity follows one song's neighbours
        // rather than an artist's identity, so it drifts further. It is the route
        // that pulled Grateful Dead, CCR and the Allman Brothers into a folk mix
        // through a Bob Dylan seed, and a Malian world-music project into an
        // indie-rock one.
        //
        // What changed is that a describable disagreement is now demoted instead of
        // dropped. Dropping it was throwing away the good part of a coarse label:
        // 44% of what this route returns has no resolvable family at all, and those
        // still stay out.
        val dbGenres = playHistoryRepository.getArtistGenreMap(
            lists.flatten().mapNotNull { it.artistUri }.distinct()
        )
        for (list in lists) {
            list.forEachIndexed { rank, track ->
                // The seed track itself always comes back first.
                if (track.uri.isBlank() || track.uri in seedTrackUris) return@forEachIndexed
                if (normalizeArtistKey(track.artistNames) in blockedKeys) return@forEachIndexed
                if (!seen.add(track.uri)) return@forEachIndexed
                val genres = trackCandidateGenres(track, dbGenres)
                val match = classifyFamily(genres, coreFamilies)
                if (match == FamilyMatch.UNKNOWN) {
                    dropped++
                    return@forEachIndexed
                }
                if (match == FamilyMatch.OFF) offFamily++
                val closeness = 1.0 - (rank.toDouble() / perSeed).coerceIn(0.0, 1.0)
                val onFamily = match == FamilyMatch.ON
                val familyScale = if (onFamily) 1.0 else OFF_FAMILY_SCALE
                kept += CandidateTrack(
                    track = track,
                    score = closeness * SIMILAR_TRACK_SCALE * familyScale,
                    // Only an on-family candidate may open a mix; see the artist route.
                    verified = onFamily
                )
            }
        }
        if (kept.isNotEmpty() || dropped > 0) {
            Log.d(
                TAG,
                "track route: ${kept.size} candidates from ${refs.size} seeds " +
                    "(on-family=${kept.size - offFamily}, off-family=$offFamily, " +
                    "$dropped dropped as undescribable)"
            )
        }
        return kept
    }

    /**
     * What a similar-track candidate is, best source first: the genres MA put on
     * the track, then the DB's genres for its artist, then MusicBrainz. Null
     * family means unjudgeable, and those are kept but scored down.
     */
    private suspend fun trackCandidateGenres(
        track: Track,
        dbGenres: Map<String, List<String>>
    ): List<String> {
        track.genres.takeIf { it.isNotEmpty() }?.let { return it }
        track.artistUri?.let { uri ->
            dbGenres[uri]?.takeIf { it.isNotEmpty() }?.let { return orderByFamilyFrequency(it) }
        }
        val name = track.artistNames.split(",").firstOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return emptyList()
        return try {
            musicBrainzGenreResolver.cachedGenres(name).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * What we know a candidate artist is, best source first: the genres MA
     * attached to the similar-artist item, then whatever the DB holds for that
     * uri, then MusicBrainz.
     *
     * MusicBrainz is read from cache ONLY. Its 1 req/s ceiling makes it
     * unusable inline (a cold pool of 60 unknowns would add a minute to the
     * mix), so misses are filled by LibraryGenreEnricher's discovery phase,
     * outside any build, and pay off from the next mix onward.
     *
     * The DB set is a union with no weight order, so it is reordered by family
     * frequency before dominantFamily reads "the first tag"; MA and MusicBrainz
     * both come weight-ordered already.
     */
    private suspend fun genresFor(
        art: CachedSimilarArtist,
        dbGenres: Map<String, List<String>>
    ): List<String> {
        art.genres.takeIf { it.isNotEmpty() }?.let { return it }
        dbGenres[art.uri]?.takeIf { it.isNotEmpty() }?.let { return orderByFamilyFrequency(it) }
        return try {
            musicBrainzGenreResolver.cachedGenres(art.name).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
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
        // weight-ordered artist tags. Judging on the DB's track genres let an
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
    private suspend fun injectionGenres(row: SeedTrack): List<String> =
        musicBrainzGenres(listOf(row))[normalizeArtistKey(row.artistName)]
            ?: orderByFamilyFrequency(row.artistGenres.ifEmpty { row.genres })

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
     * Cluster coherence genres per seed: MusicBrainz first, then the DB's artist
     * genres, then the track's own tags.
     *
     * MusicBrainz leads because `artist_genres` is written by NAME across every
     * uri an artist has, so two artists sharing a name MERGE there: the rock Jack
     * White carries techno, the pop Annie carries black metal, and a seed picked
     * on that lands in the wrong cluster. MusicBrainz is keyed per entity, so it
     * keeps them apart - provided the entity was identified correctly in the
     * first place, which is why the resolver disambiguates by recording rather
     * than by name alone.
     *
     * `artist_genres` is a UNION of everything MA ever reported for the artist,
     * unordered, so it is reordered by family frequency before anything reads
     * "the first tag" (see [orderByFamilyFrequency]) - the tag that decides the
     * cluster must be the one the artist's body of tags actually points at, not
     * whichever landed first alphabetically. The candidate gate reads the same
     * source, so seeds and candidates are judged on the same data.
     */
    private suspend fun coherenceGenreMap(pool: List<SeedTrack>): Map<String, List<String>> {
        val mb = musicBrainzGenres(pool)
        val map = HashMap<String, List<String>>(pool.size)
        for (seed in pool) {
            map[seed.trackUri] = mb[normalizeArtistKey(seed.artistName)]
                ?: orderByFamilyFrequency(seed.artistGenres.ifEmpty { seed.genres }.map { normalizeGenre(it) })
        }
        return map
    }

    /**
     * MusicBrainz genres for these artists, from cache only, keyed by
     * [normalizeArtistKey]. Weight-ordered as MusicBrainz reports them, so
     * unlike the DB fallback they need no family-frequency reordering.
     */
    private suspend fun musicBrainzGenres(seeds: List<SeedTrack>): Map<String, List<String>> =
        try {
            val refs = seeds.map { MusicBrainzGenreResolver.ArtistRef(it.artistName, it.artistMbid) }
            val byKey = musicBrainzGenreResolver.cachedGenresFor(refs)
            // Re-key to the artist so callers do not need to know whether the
            // entry was stored under an id or a name.
            seeds.mapNotNull { seed ->
                val key = seed.artistMbid?.takeIf { it.isNotBlank() }
                    ?: seed.artistName.trim().lowercase()
                byKey[key]?.let { normalizeArtistKey(seed.artistName) to it }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }

    private suspend fun selectSeedTracks(
        tuning: Tuning,
        random: kotlin.random.Random,
        recency: Recency
    ): SeedSelection {
        val since = System.currentTimeMillis() - SEED_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        val ranked = strictnessRankedPool(querySeedPool(since, tuning.strictness), tuning.strictness)
        // Blocked artists are dropped HERE, before a primary is chosen, not later
        // when the seed list is assembled. Filtering afterwards removed the blocked
        // artist but kept the cluster built around them: their genres still became
        // the envelope the whole mix was gated on, so blocking an artist did not
        // stop mixes that sound like them.
        val blocked = recency.blockedArtistNames
            .map { normalizeArtistKey(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val pool = if (blocked.isEmpty()) ranked
        else ranked.filterNot { normalizeArtistKey(it.artistName) in blocked }
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
        // Rotation is per GENRE: a primary whose genres all appeared in recent
        // mixes is skipped, so consecutive mixes move even inside one family
        // (deep house -> techno -> nu disco).
        //
        // Families are far too coarse to rotate on. "rock" holds prog, shoegaze,
        // post-punk, garage and dream pop, so staying in it is not staying in a
        // sound - yet the old rule actively PREFERRED the recent family whenever
        // the variety dice said "no hop", which was half the time. Measured over
        // 23 mixes: 11 landed in rock and the listener saw "alternative" again
        // and again. Variety now only ever pushes AWAY, never back.
        val recentFamilies = genreFamilies(recency.recentClusterGenres)
        val hop = shouldHopFamily(tuning.variety, random)
        val exactFresh = primaryPool.filter { seed ->
            coherenceGenres(seed).none { it in recency.recentClusterGenres }
        }
        val preferred = if (hop) {
            exactFresh.filter { seed ->
                prefersCandidateFamily(genreFamilies(coherenceGenres(seed)), recentFamilies, hop = true)
            }
        } else {
            exactFresh
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
        // at all, so they are drawn score-weighted instead of plain-shuffled,
        // then ordered so the ones that can actually produce candidates come
        // first (see productiveFirst).
        //
        // Seeds sharing the primary's exact GENRE are seated before the ones that
        // only share its family, because only eight seeds survive `take` and the
        // family is far too broad to spend them on: an anchor on post rock filled
        // seven of its eight seats with indie and produced a mix with two
        // post-rock tracks in it. Both groups keep the score-weighted,
        // productive-first ordering, so this changes WHICH seeds are seated, not
        // how each group is ranked.
        val others = cluster.filter { it.trackUri != primary.trackUri }
        val (sameGenre, widerFamily) = partitionByExactGenre(others, primaryGenres) {
            genresBySeed[it.trackUri].orEmpty()
        }
        fun seated(list: List<SeedTrack>) =
            productiveFirst(strictnessWeightedOrder(list, tuning.strictness, random))
        val ordered = listOf(primary) + seated(sameGenre) + seated(widerFamily)
        val result = ordered.take(SEED_COUNT)
        Log.d(
            TAG,
            "cluster around '${primary.artistName}' (${primaryGenres.joinToString("/")}" +
                "${primaryFamily?.let { " -> $it" }.orEmpty()}): ${result.size} seeds " +
                "(${sameGenre.size} share its genre, ${widerFamily.size} only its family; " +
                "hop=$hop preferred=${preferred.size} exactFresh=${exactFresh.size} " +
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
            playHistoryRepository.getRecentSeedTracks(
                sinceMs, SEED_MIN_LISTENED_MS, SEED_MIN_SCORE, limit
            )
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
