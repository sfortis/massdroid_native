package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
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
private const val VARIETY_FULL_ROTATION_THRESHOLD = 0.66
private const val SEED_LOOKBACK_DAYS = 30
private const val SEED_MIN_LISTENED_MS = 30_000L
private const val SEED_POOL_QUERY_LIMIT = 60
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
private const val SEED_RECENT_ARTIST_PENALTY = 0.2
private const val SEED_RECENT_TRACK_PENALTY = 0.5
private const val MIN_SEEDS = 2
// Strictness knob -> minimum tracks.score a seed must have. At 1.0 only "loved"
// tracks (score > 0.5) qualify; at 0.0 any non-disliked track (score >= 0).
private const val STRICTNESS_MAX_SCORE = 0.5
// If the strict score filter leaves too small a pool, relax to score >= 0 so a
// mix can still be built (cold-start / lightly-rated libraries).
private const val SEED_POOL_RELAX_MIN = 6
// Loved-track injection (comfort anchor). Discovery drives the share: at
// Discovery=0, up to MAX_FRACTION of the mix is the user's own loved tracks; at
// Discovery=1, none. Only tracks scored >= MIN_SCORE qualify; they enter with a
// top score so they survive the cap/interleave.
private const val OWN_INJECT_MAX_FRACTION = 0.4
// Always anchor a few of the user's own loved tracks, even at max Discovery, so
// a mix is never 100% strangers. Discovery scales additional injection above it.
private const val OWN_INJECT_FLOOR = 4
// "Liked" floor (0.1) rather than "loved" (0.5): once narrowed to the mix's
// genre and deduped by artist, a 0.5 floor leaves too small an in-genre pool to
// fill the quota. Score ordering still puts the most-loved in-genre tracks first.
private const val LOVED_INJECT_MIN_SCORE = 0.1
private const val LOVED_INJECT_POOL_LIMIT = 600
private const val OWN_INJECT_SCORE = 1.0

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
    private val settingsRepository: SettingsRepository,
    private val mixEngine: MixEngine
) {
    /**
     * Tuning knobs (0..1) from settings; Length is folded into [target].
     * Strictness gates which tracks may seed a mix: 0 = anything recently
     * listened (not disliked), 1 = only your most-loved tracks.
     */
    data class Tuning(val variety: Double, val discovery: Double, val strictness: Double)

    /** Cool-down context owned by the caller so back-to-back mixes diverge. */
    data class Recency(
        val excludedTrackUris: Set<String>,
        val recentArtistCounts: Map<String, Int>,
        val recentMixTrackUris: Set<String>
    )

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
    suspend fun buildSmartMix(tuning: Tuning, target: Int, recency: Recency): List<Track> {
        if (!hasLastFmKey()) return emptyList()
        val mixSeed = System.currentTimeMillis()
        val random = kotlin.random.Random(mixSeed)
        val selection = selectSeedTracks(tuning, random)
        val seeds = selection.seeds
        if (seeds.size < MIN_SEEDS) {
            Log.d(TAG, "only ${seeds.size} seeds, skipping")
            return emptyList()
        }
        Log.d(TAG, "${seeds.size} seeds -> ${seeds.joinToString { "${it.artistName} - ${it.trackName}" }}")
        return assembleSeedTrackMix(seeds, tuning, target, mixSeed, recency, selection.coherentGenres)
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
        return assembleSeedTrackMix(seeds, tuning, target, mixSeed, recency, setOf(normalizeGenre(genre)))
    }

    private suspend fun hasLastFmKey(): Boolean =
        try {
            settingsRepository.lastFmApiKey.first().isNotBlank()
        } catch (_: Exception) {
            false
        }

    // Shared core: gather a deduped track.getSimilar candidate pool, resolve to
    // playable tracks (cache-first + bounded search + background prefetch), apply
    // the recent-mix cool-down, and run the diversity/interleave.
    private suspend fun assembleSeedTrackMix(
        seeds: List<SeedTrack>,
        tuning: Tuning,
        target: Int,
        mixSeed: Long,
        recency: Recency,
        coherentGenres: Set<String>
    ): List<Track> {
        val similarsPerSeed = seedSimilarsPerSeed(tuning)
        val similarLists = coroutineScope {
            seeds.map { seed ->
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

        val resolved = resolveSeedCandidates(ordered)
        // Warm the cache for everything we could not resolve inline so the next
        // mix is fuller and instant.
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
        // Discovery -> zero injection (pure discovery). The injected tracks carry
        // a top score so they survive the cap/interleave (guaranteed presence),
        // and are sampled per run so favourites rotate.
        val injectCount = ((1.0 - tuning.discovery) * OWN_INJECT_MAX_FRACTION * target)
            .roundToInt()
            .coerceAtLeast(OWN_INJECT_FLOOR)
        val injected = if (injectCount > 0) {
            lovedInjection(coherentGenres, injectCount, mixSeed, recency)
        } else {
            emptyList()
        }
        val allCandidates = injected + candidates

        val mix = mixEngine.buildFromCandidates(allCandidates, target, mixSeed)
        Log.d(TAG, "built ${mix.size} tracks (target $target) from ${candidates.size} discovery + ${injected.size} loved-injected")
        return mix
    }

    // The user's OWN loved tracks (score >= LOVED_INJECT_MIN_SCORE) that are
    // genre-coherent with the mix, sampled and capped to [count]. These are real
    // played URIs, injected directly (not via similars) as comfort anchors with a
    // top score. Excludes recent-mix tracks so the anchors rotate.
    private suspend fun lovedInjection(
        coherentGenres: Set<String>,
        count: Int,
        mixSeed: Long,
        recency: Recency
    ): List<CandidateTrack> {
        val since = System.currentTimeMillis() - GENRE_SEED_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        val raw = querySeedTracks(since, LOVED_INJECT_MIN_SCORE, LOVED_INJECT_POOL_LIMIT)
        val notRecent = raw.filter { it.trackUri.isNotBlank() && it.trackUri !in recency.recentMixTrackUris }
        val inGenre = notRecent.filter { row ->
            coherentGenres.isEmpty() || row.genres.any { normalizeGenre(it) in coherentGenres }
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
    // families). Falls back to a plain sampled rotation when there is no genre
    // data to anchor a cluster.
    /** Chosen seeds plus the tight genre envelope (primary cluster genres) used to keep loved injection coherent. */
    private data class SeedSelection(val seeds: List<SeedTrack>, val coherentGenres: Set<String>)

    private suspend fun selectSeedTracks(tuning: Tuning, random: kotlin.random.Random): SeedSelection {
        val since = System.currentTimeMillis() - SEED_LOOKBACK_DAYS * 24L * 60 * 60 * 1000
        val pool = fetchSeedPool(since, SEED_POOL_QUERY_LIMIT, tuning) { it }
        val byArtist = dedupeByArtist(pool)
        if (byArtist.size <= SEED_COUNT) return SeedSelection(byArtist, emptySet())

        val tagged = byArtist.filter { it.genres.isNotEmpty() }
        if (tagged.isEmpty()) {
            val anchorCount = seedAnchorCount(tuning)
            val anchors = byArtist.take(anchorCount)
            val sampled = byArtist.drop(anchorCount).shuffled(random).take(SEED_COUNT - anchorCount)
            return SeedSelection(anchors + sampled, emptySet())
        }
        // Variety biases WHICH primary we anchor on: low variety restricts it to
        // the most-recent tagged seeds (steadier); high variety draws from the
        // whole tagged pool (full rotation).
        val primaryPool = if (tuning.variety >= VARIETY_FULL_ROTATION_THRESHOLD) {
            tagged
        } else {
            val window = (SEED_ANCHOR_MAX + tuning.variety * tagged.size).toInt().coerceIn(1, tagged.size)
            tagged.take(window)
        }
        val primary = primaryPool.shuffled(random).first()
        val primaryGenres = primary.genres.map { normalizeGenre(it) }.toSet()
        val cluster = byArtist.filter { seed ->
            seed.trackUri == primary.trackUri ||
                seed.genres.any { normalizeGenre(it) in primaryGenres }
        }
        val ordered = listOf(primary) +
            cluster.filter { it.trackUri != primary.trackUri }.shuffled(random)
        val result = ordered.take(SEED_COUNT)
        Log.d(TAG, "cluster around '${primary.artistName}' (${primaryGenres.joinToString("/")}): ${result.size} seeds")
        // Loved injection is gated on the PRIMARY genres (tight), not the broad
        // union of every seed's tags, so it never pulls in off-cluster favourites.
        return SeedSelection(result, primaryGenres)
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
    // candidate not resolved inline, so the next mix is fuller and instant.
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
                Log.d(TAG, "prefetch: warmed ${warmed.get()} resolutions")
            } catch (e: Exception) {
                Log.w(TAG, "prefetch failed: ${e.message}")
            }
        }
    }
}
