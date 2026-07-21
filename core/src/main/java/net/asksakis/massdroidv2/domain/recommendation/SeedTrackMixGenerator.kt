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
import net.asksakis.massdroidv2.data.lastfm.LastFmTrackSimilarResolver
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SeedTrack
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "SeedTrackMix"

private const val SEED_COUNT = 8
private const val SEED_ANCHOR_MAX = 3
private const val SEED_LOOKBACK_DAYS = 30
private const val SEED_MIN_LISTENED_MS = 30_000L
// Recency-ordered candidate pool that Strictness re-ranks toward score. Larger
// than the old top-by-score limit so low Strictness has genuinely-recent (not
// just top-scored) tracks to draw from.
private const val RECENCY_POOL_LIMIT = 150
private const val GENRE_SEED_LOOKBACK_DAYS = 365
private const val GENRE_SEED_POOL_LIMIT = 250
private const val SEED_SIMILARS_MIN = 15
private const val SEED_SIMILARS_SPAN = 25
private const val SEED_INLINE_SEARCH_BUDGET = 24
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
private const val FAMILY_HOP_VARIETY_THRESHOLD = 0.66
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
// seed-similars instead of dominating the front of the mix. A top score (1.0) made
// every mix front-loaded with familiar songs and killed the discovery feel; at
// 0.5 they sit mid-distribution (Last.fm match scores span ~0..1), surfacing some
// comfort without burying the discoveries.
private const val OWN_INJECT_SCORE = 0.5

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
 * Variety window (count of top-ranked seeds the primary is drawn from) with NO
 * plateau: spans the whole tagged pool across 0..1 so the upper half of the
 * slider keeps changing the result (the old 0.66 full-rotation threshold made
 * every value >= 0.66 identical). variety 0 -> 1 (steadiest), 1 -> n (widest).
 */
@VisibleForTesting
internal fun varietyWindow(variety: Double, n: Int): Int =
    (1.0 + variety.coerceIn(0.0, 1.0) * (n - 1)).roundToInt().coerceIn(1, n)

/**
 * Cluster membership: a seed joins the primary's cluster with at least one
 * shared (normalized) ARTIST genre, vetoed when the two sides' mapped genre
 * FAMILIES are known and disjoint. Replayed against the real library: requiring
 * 2 shared tags on the clean 3-tag artist genres collapsed a valid cluster to
 * the primary alone, while 1 shared tag on the old noisy TRACK tags let a
 * techno seed bridge into an indie cluster (the family veto + artist-level tags
 * close that hole; the seed had 0 shared artist tags).
 */
@VisibleForTesting
internal fun clusterOverlapSatisfied(seedGenres: Set<String>, primaryGenres: Set<String>): Boolean {
    if (seedGenres.none { it in primaryGenres }) return false
    val seedFamilies = genreFamilies(seedGenres)
    val primaryFamilies = genreFamilies(primaryGenres)
    if (seedFamilies.isEmpty() || primaryFamilies.isEmpty()) return true
    return seedFamilies.any { it in primaryFamilies }
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
 * Candidate genre gate decision: deterministic family comparison (the static
 * [genreFamilies] map) whenever both the candidate and the envelope carry
 * mapped tags; the loose token overlap only covers unmapped tags. A candidate
 * whose known families are all foreign to the cluster is dropped.
 */
@VisibleForTesting
internal fun genreGatePasses(
    candidateGenres: List<String>,
    envelopeFamilies: Set<String>,
    envelopeTokens: Set<String>
): Boolean {
    val candidateFamilies = genreFamilies(candidateGenres)
    if (candidateFamilies.isNotEmpty() && envelopeFamilies.isNotEmpty()) {
        return candidateFamilies.any { it in envelopeFamilies }
    }
    return genresOverlapLoose(candidateGenres, envelopeTokens)
}

/**
 * Variety-gated genre movement between consecutive mixes. Given a candidate
 * primary seed's genre families and the families of the recent mixes, decides
 * whether the candidate is PREFERRED for the next cluster:
 *  - below [FAMILY_HOP_VARIETY_THRESHOLD]: prefer the SAME family as recent
 *    (coherent adjacency drift, no whiplash to unrelated genres),
 *  - at/above it: prefer a DIFFERENT family (exploration hop).
 * With no recent families there is no preference (returns false), so the caller
 * falls back to the plain fresh pool. This keeps Smart Mix in the current genre
 * neighbourhood at mid/low Variety instead of jumping deep house -> hard rock
 * every run (the whiplash the old always-on family-hop caused).
 */
@VisibleForTesting
internal fun prefersCandidateFamily(
    candidateFamilies: Set<String>,
    recentFamilies: Set<String>,
    variety: Double,
): Boolean {
    if (recentFamilies.isEmpty()) return false
    val sharesRecentFamily = candidateFamilies.any { it in recentFamilies }
    return if (variety >= FAMILY_HOP_VARIETY_THRESHOLD) !sharesRecentFamily else sharesRecentFamily
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
            seeds, tuning, target, mixSeed, recency, selection.coherentGenres, selection.envelope
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
        // Tight coherence: inject only loved tracks tagged with the chosen genre.
        val genreSet = setOf(normalizeGenre(genre))
        return assembleSeedTrackMix(seeds, tuning, target, mixSeed, recency, genreSet, genreSet)
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
        genreEnvelope: Set<String>
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
        for (list in similarLists) {
            for (sim in list) {
                if (sim.artist.isBlank() || sim.track.isBlank()) continue
                if (LastFmTrackSimilarResolver.normalizeName(sim.artist) in blockedKeys) continue
                val nameKey = LastFmTrackSimilarResolver.sourceKey(sim.artist, sim.track)
                val existing = bestByKey[nameKey]
                if (existing == null || sim.matchScore > existing.matchScore) {
                    bestByKey[nameKey] = SeedCandidate(sim.artist, sim.track, sim.matchScore, nameKey)
                }
            }
        }
        if (bestByKey.isEmpty()) {
            Log.d(TAG, "Last.fm returned no similar tracks")
            return emptyList()
        }

        val ordered = bestByKey.values.sortedByDescending { it.matchScore }
        Log.d(TAG, "${ordered.size} unique candidates from track.getSimilar")

        // Candidate genre gate (cache-only): a candidate whose CACHED artist
        // tags share no token with the cluster envelope is a foreign family
        // (similars of a rogue seed, or genuine similar-drift) and is dropped.
        // Unknown artists pass: the prefetch warms their tags for future runs,
        // so the gate tightens as the cache fills without starving cold mixes.
        val gated = gateCandidatesByGenre(ordered, genreEnvelope)

        val resolved = resolveSeedCandidates(gated)
        // Warm the caches (track URI resolution + artist tags) for everything we
        // could not use inline so the next mix is fuller and better gated.
        scheduleSeedPrefetch(ordered)

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
            lovedInjection(coherentGenres, injectCount, mixSeed, recency, tuning.strictness)
        } else {
            emptyList()
        }
        val allCandidates = injected + candidates

        val mix = mixEngine.buildFromCandidates(allCandidates, target, mixSeed, tuning.discovery)
        Log.d(TAG, "built ${mix.size} tracks (target $target) from ${candidates.size} discovery + ${injected.size} loved-injected")
        return mix
    }

    // The user's OWN loved tracks (score >= LOVED_INJECT_MIN_SCORE) that are
    // genre-coherent with the mix, sampled and capped to [count]. These are real
    // played URIs, injected directly (not via similars) as comfort anchors with a
    // mid-pack score so they compete with (not dominate) the similars. Excludes
    // recent-mix tracks so the anchors rotate.
    private suspend fun lovedInjection(
        coherentGenres: Set<String>,
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
        // Family-aware in-genre gate (same as the discovery-candidate gate) so an
        // injected loved track cannot reintroduce a foreign family that merely
        // shares a noisy token with the cluster envelope.
        val envelopeTokens = genreTokens(coherentGenres)
        val envelopeFamilies = genreFamilies(coherentGenres)
        val inGenre = notRecent.filter { row ->
            coherentGenres.isEmpty() || genreGatePasses(row.genres, envelopeFamilies, envelopeTokens)
        }
        val deduped = dedupeByArtist(inGenre).shuffled(kotlin.random.Random(mixSeed)).take(count)
        Log.d(TAG, "loved-inject: want=$count inGenrePool=${inGenre.size} -> injected ${deduped.size}")
        return deduped.map { seed ->
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


    // Discovery knob -> how deep into each seed's similar list we pull. Low
    // discovery keeps the safest top matches; high discovery reaches further
    // down (more obscure, lower-match candidates).
    private fun seedSimilarsPerSeed(tuning: Tuning): Int =
        (SEED_SIMILARS_MIN + tuning.discovery * SEED_SIMILARS_SPAN).toInt().coerceAtLeast(SEED_SIMILARS_MIN)

    // Variety knob -> how many of the most-recent tracks stay as stable anchors.
    private fun seedAnchorCount(tuning: Tuning): Int =
        ((1.0 - tuning.variety) * SEED_ANCHOR_MAX).roundToInt().coerceIn(0, SEED_ANCHOR_MAX)

    // Pick a genre-COHERENT cluster of seeds, rotated per run. Variety comes from
    // which cluster is chosen (a different random primary seed); consistency
    // within a mix is preserved by only adding seeds whose genres overlap the
    // primary's EXACT genres (no adjacency widening, which bridged distant
    // families). Coherence uses ARTIST-level genres (track tags are crowd-noisy
    // and once bridged a techno artist into an indie cluster). Falls back to a
    // plain sampled rotation when there is no genre data to anchor a cluster.
    /**
     * Chosen seeds plus the tight genre envelope (primary cluster genres, keeps
     * loved injection coherent) and the wider seed-union [envelope] the
     * candidate genre gate checks against.
     */
    private data class SeedSelection(
        val seeds: List<SeedTrack>,
        val coherentGenres: Set<String>,
        val envelope: Set<String> = emptySet()
    )

    // Cluster coherence genres: clean artist tags first, track tags only as a
    // fallback for artists the enricher has not covered.
    private fun coherenceGenres(seed: SeedTrack): Set<String> =
        seed.artistGenres.ifEmpty { seed.genres }.map { normalizeGenre(it) }.toSet()

    private suspend fun selectSeedTracks(
        tuning: Tuning,
        random: kotlin.random.Random,
        recency: Recency
    ): SeedSelection {
        val since = System.currentTimeMillis() - SEED_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        val pool = strictnessRankedPool(queryRecentSeedTracks(since, RECENCY_POOL_LIMIT), tuning.strictness)
        val byArtist = dedupeByArtist(pool)
        if (byArtist.size <= SEED_COUNT) return SeedSelection(byArtist, emptySet())

        val tagged = byArtist.filter { coherenceGenres(it).isNotEmpty() }
        if (tagged.isEmpty()) {
            val anchorCount = seedAnchorCount(tuning)
            val anchors = byArtist.take(anchorCount)
            val sampled = byArtist.drop(anchorCount).shuffled(random).take(SEED_COUNT - anchorCount)
            return SeedSelection(anchors + sampled, emptySet())
        }
        // Variety biases WHICH primary we anchor on: low variety restricts it to
        // the top-ranked seeds (steadier), high variety draws from the whole
        // tagged pool. varietyWindow spans the full 0..1 range (no plateau).
        val primaryPool = tagged.take(varietyWindow(tuning.variety, tagged.size))
        // Genre movement (Variety-gated). exactFresh always rotates the sub-genre
        // (avoid the exact genres of recent mixes) so we never lock onto one thing.
        // The FAMILY behaviour then depends on Variety:
        //   - below FAMILY_HOP_VARIETY_THRESHOLD: prefer a primary in the SAME
        //     family as recent mixes (coherent adjacency drift, no whiplash),
        //   - at/above it: prefer a DIFFERENT family (exploration hop).
        // Either preference falls back to exactFresh, then the full window, so the
        // pool never collapses. Track/artist cool-down (below) keeps songs fresh.
        val recentFamilies = genreFamilies(recency.recentClusterGenres)
        val exactFresh = primaryPool.filter { seed ->
            coherenceGenres(seed).none { it in recency.recentClusterGenres }
        }
        val preferred = exactFresh.filter { seed ->
            prefersCandidateFamily(genreFamilies(coherenceGenres(seed)), recentFamilies, tuning.variety)
        }
        val freshPool = preferred.ifEmpty { exactFresh }
        val primary = freshPool.ifEmpty { primaryPool }.shuffled(random).first()
        val primaryGenres = coherenceGenres(primary)
        val cluster = byArtist.filter { seed ->
            seed.trackUri == primary.trackUri ||
                clusterOverlapSatisfied(coherenceGenres(seed), primaryGenres)
        }
        val ordered = listOf(primary) +
            cluster.filter { it.trackUri != primary.trackUri }.shuffled(random)
        val result = ordered.take(SEED_COUNT)
        Log.d(
            TAG,
            "cluster around '${primary.artistName}' (${primaryGenres.joinToString("/")}): " +
                "${result.size} seeds (freshPool=${freshPool.size}/${primaryPool.size})"
        )
        // Loved injection is gated on the PRIMARY genres (tight), not the broad
        // union of every seed's tags, so it never pulls in off-cluster favourites.
        // The candidate gate uses the seed-union envelope (wider, fewer false drops).
        val envelope = result.flatMap { coherenceGenres(it) }.toSet()
        return SeedSelection(result, primaryGenres, envelope)
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
            pool.filter { row -> row.genres.any { normalizeGenre(it) == target } }
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

    // Cache-only genre gate: drop candidates whose cached artist tags share no
    // token with the cluster envelope. Artists with no fresh cache entry (or a
    // known-empty tag list) pass; the prefetch warms them for the next run.
    private suspend fun gateCandidatesByGenre(
        ordered: List<SeedCandidate>,
        envelope: Set<String>
    ): List<SeedCandidate> {
        if (envelope.isEmpty()) return ordered
        val envelopeTokens = genreTokens(envelope)
        if (envelopeTokens.isEmpty()) return ordered
        val envelopeFamilies = genreFamilies(envelope)
        val tagsByArtist = HashMap<String, List<String>?>()
        val kept = ordered.filter { cand ->
            val tags = tagsByArtist.getOrPut(cand.artist) {
                try {
                    lastFmGenreResolver.cachedGenres(cand.artist)
                } catch (_: Exception) {
                    null
                }
            }
            tags.isNullOrEmpty() || genreGatePasses(tags, envelopeFamilies, envelopeTokens)
        }
        if (kept.size != ordered.size) {
            Log.d(
                TAG,
                "genre gate: dropped ${ordered.size - kept.size}/${ordered.size} off-cluster " +
                    "candidates (envelope=${envelope.joinToString("/")})"
            )
        }
        return kept
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
    private fun scheduleSeedPrefetch(ordered: List<SeedCandidate>) {
        if (prefetchJob?.isActive == true) return
        prefetchJob = prefetchScope.launch {
            try {
                val gate = Semaphore(SEED_PREFETCH_CONCURRENCY)
                val warmed = AtomicInteger(0)
                coroutineScope {
                    ordered.map { cand ->
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
                val warmedTags = warmCandidateArtistTags(ordered)
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
