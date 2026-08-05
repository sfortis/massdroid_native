package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.util.mapMaBounded
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import kotlin.math.ln
import kotlin.random.Random

private const val ORCHESTRATOR_TAG = "DiscoverReco"
private const val DISCOVERY_SEED_LIMIT = 8
private const val LIBRARY_PROVIDER = "library"
private const val LIBRARY_URI_PREFIX = "library://"
private const val DISCOVERY_PER_SEED_SIMILARS = 4
// Track neighbours fetched per provider seed; a handful is enough to yield the
// four artists the pool wants after dedupe.
private const val DISCOVERY_SIMILAR_TRACKS_PER_SEED = 15
// How many seeds may fall back to the track route in one build.
private const val DISCOVERY_TRACK_ROUTE_SEEDS = 4
// Artists warmed per feed build. At 1 req/s this is ~30s of background work;
// the rest are picked up by later builds.
private const val GENRE_WARM_LIMIT = 20
private const val DISCOVERY_RESOLVE_BUDGET = 25
private const val DISCOVERY_VOTE_WEIGHT = 0.2
private const val DISCOVERY_TOP_GENRES_FOR_SEEDS = 8
private const val DISCOVERY_MMR_LAMBDA = 0.35
private const val DISCOVERY_PRIMARY_GENRE_CAP = 2
private const val DISCOVERY_ALBUM_GENRE_CAP = 2
private const val DISCOVERY_ALBUM_ARTIST_POOL_SIZE = 20
private const val CANDIDATE_RESOLVE_TIMEOUT_MS = 7_000L
private const val MIN_DISCOVERY_FOR_RANKING = 10
private const val DISCOVERY_SEED_RANDOMIZATION_POOL = 3
private const val DISCOVERY_PAD_RANDOMIZATION_POOL = 20
private const val DISCOVERY_SCORE_JITTER = 0.20
private const val DISCOVERY_MMR_JITTER = 0.20
private const val PROVIDER_RECOMMENDED_ARTISTS_FOLDER = "recommended_artists"
private const val PROVIDER_RECOMMENDED_ALBUMS_FOLDER = "recommended_albums"

data class DiscoveryResult(
    val artists: List<Artist>,
    val albums: List<Album>
)

class DiscoverRecommendationOrchestrator(
    private val musicRepository: MusicRepository,
    private val playHistoryRepository: PlayHistoryRepository,
    private val genreRepository: net.asksakis.massdroidv2.data.genre.GenreRepository,
    private val musicBrainzGenreResolver: MusicBrainzGenreResolver,
    private val providerHealthReporter: net.asksakis.massdroidv2.data.util.ProviderHealthReporter
) {

    // Seeds still allowed to take the expensive track route this build.
    // Background genre warming outlives one feed build on purpose.
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trackRouteBudget = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun buildDiscovery(
        libraryArtists: List<Artist>,
        serverFolders: List<RecommendationFolder>,
        excludedArtistUris: Set<String>,
        artistCount: Int = 10,
        albumCount: Int = 10
    ): DiscoveryResult {
        val libraryNames = libraryArtists
            .asSequence()
            .filter { it.uri.startsWith("library://") }
            .map { it.name.lowercase() }
            .toSet()

        val pool = buildEnrichedCandidatePool(libraryNames, excludedArtistUris)

        val artistsSimilar = rankArtistsFromPool(pool)
        val artistsFallback = buildProviderArtistsFallback(
            serverFolders,
            libraryNames,
            excludedArtistUris,
            alreadyPickedNames = artistsSimilar.mapTo(mutableSetOf()) { it.name.lowercase() }
        )
        val artists = (artistsSimilar + artistsFallback)
            .distinctBy { it.uri.ifBlank { it.name.lowercase() } }
            .take(artistCount)

        val recentAlbumKeys = try {
            playHistoryRepository.getRecentAlbums(limit = 50)
                .map { MediaIdentity.canonicalAlbumKey(uri = it.albumUri) ?: it.albumUri }
                .toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }

        val albumsSimilar = buildAlbumsFromPool(pool, recentAlbumKeys)
        val albumsFallback = buildProviderAlbumsFallback(
            serverFolders,
            libraryArtists,
            recentAlbumKeys,
            alreadyPickedAlbumKeys = albumsSimilar.mapNotNullTo(mutableSetOf()) {
                MediaIdentity.canonicalAlbumKey(uri = it.uri) ?: it.uri.ifBlank { null }
            }
        )
        val albums = (albumsSimilar + albumsFallback)
            .distinctBy { MediaIdentity.canonicalAlbumKey(uri = it.uri) ?: it.uri.ifBlank { it.name.lowercase() } }
            .take(albumCount)

        Log.d(
            ORCHESTRATOR_TAG,
            "Discovery: artists similar=${artistsSimilar.size} fallback=${artistsFallback.size} final=${artists.size}; " +
                "albums similar=${albumsSimilar.size} fallback=${albumsFallback.size} final=${albums.size}"
        )
        return DiscoveryResult(artists = artists, albums = albums)
    }

    /**
     * Candidates from Music Assistant's own `similar_artists`.
     *
     * This used to ask Last.fm for NAMES and then search every provider for
     * each one, which needed an API key the listener had to go and create, and
     * still lost candidates whose name did not match exactly. Music Assistant
     * answers with fully formed, playable items, so the whole name-resolution
     * stage is gone: no key, no search per candidate, no near-miss.
     *
     * Its answers are Last.fm's, fetched with Music Assistant's own built-in
     * key, so the quality is unchanged for someone who never configures
     * anything.
     */
    private suspend fun buildEnrichedCandidatePool(
        libraryNames: Set<String>,
        excludedArtistUris: Set<String>
    ): List<Pair<DiscoveryCandidate, Artist>> = coroutineScope {
        trackRouteBudget.set(DISCOVERY_TRACK_ROUTE_SEEDS)
        val seeds = buildDiverseSeeds()
        if (seeds.isEmpty()) return@coroutineScope emptyList()

        Log.d(
            ORCHESTRATOR_TAG,
            "Discovery seeds (${seeds.size}): ${seeds.joinToString { it.artistName }}"
        )

        val similarsBySeed = seeds.map { seed ->
            async { seed to similarArtistsForSeed(seed) }
        }.awaitAll()
        // Only a library item answers `similar_artists`. A seed that could not be
        // resolved to one contributes nothing, and that is indistinguishable from
        // "this artist genuinely has no similars" unless it is counted: measured
        // on a real library, 14 of 20 seeds fell in this hole and the server-folder
        // fallback was quietly carrying the feature.
        val silent = similarsBySeed.count { it.second.isEmpty() }
        if (silent > 0) {
            Log.d(ORCHESTRATOR_TAG, "Discovery seeds with no similars: $silent/${seeds.size}")
        }

        val candidatesByUri = LinkedHashMap<String, DiscoveryCandidate>()
        val artistByUri = HashMap<String, Artist>()
        for ((seed, similars) in similarsBySeed) {
            similars.forEachIndexed { rank, artist ->
                if (artist.uri.isBlank()) return@forEachIndexed
                if (artist.name.lowercase() in libraryNames) return@forEachIndexed
                if (isExcluded(artist, excludedArtistUris)) return@forEachIndexed
                // The server's ordering is the only similarity measure this
                // route has; position stands in for the match score Last.fm
                // used to hand over.
                val closeness = 1.0 - (rank.toDouble() / DISCOVERY_PER_SEED_SIMILARS).coerceIn(0.0, 1.0)
                artistByUri.putIfAbsent(artist.uri, artist)
                val candidate = candidatesByUri.getOrPut(artist.uri) { DiscoveryCandidate(artist.name) }
                candidate.bestMatchScore = maxOf(candidate.bestMatchScore, closeness)
                candidate.voters.add(seed.artistName)
                candidate.seedBllSum += seed.score
            }
        }
        if (candidatesByUri.isEmpty()) return@coroutineScope emptyList()

        val picked = candidatesByUri.entries
            .sortedByDescending { it.value.compositeScore() }
            .take(DISCOVERY_RESOLVE_BUDGET)
            .mapNotNull { (uri, cand) -> artistByUri[uri]?.let { cand to it } }
            .filter { (_, artist) -> artist.imageUrl != null }

        val enriched = enrichGenres(picked)
        Log.d(ORCHESTRATOR_TAG, "Enriched candidate pool size: ${enriched.size}")
        enriched
    }

    /**
     * One seed's similar artists, cached the same way the Smart Mix engine
     * caches them.
     *
     * Only a library item answers `similar_artists`; a provider item returns
     * nothing (measured: 25 results for the library uri of an artist, 0 for the
     * same artist's Deezer uri). Music Assistant resolves a provider item to its
     * library counterpart on `get_artist`, so asking it first is what makes a
     * provider-uri seed usable at all.
     */
    private suspend fun similarArtistsForSeed(
        seed: net.asksakis.massdroidv2.domain.repository.ArtistScore
    ): List<Artist> {
        val ref = maItemRef(seed.artistUri) ?: return emptyList()
        return try {
            val resolved = if (ref.second == LIBRARY_PROVIDER) {
                ref
            } else {
                val artist = musicRepository.getArtist(ref.first, ref.second) ?: return emptyList()
                maItemRef(artist.uri) ?: (artist.itemId to artist.provider)
            }
            val direct = musicRepository
                .getSimilarArtists(resolved.first, resolved.second, DISCOVERY_PER_SEED_SIMILARS)
            // A provider artist with no library counterpart answers nothing here,
            // and on a real library that was most of the seeds. Track similarity
            // is the route those providers DO implement, so the seed contributes
            // through its neighbours' artists instead of contributing nothing.
            // Same two-route split the Smart Mix engine already uses.
            direct.ifEmpty {
                // Bounded: each fallback seed costs a top_tracks, a similar_tracks
                // and a lookup per candidate, so letting every seed take it would
                // trade a thin feed for a slow one.
                if (trackRouteBudget.decrementAndGet() >= 0) {
                    artistsViaSimilarTracks(resolved)
                } else {
                    emptyList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ORCHESTRATOR_TAG, "similar_artists failed for ${seed.artistName}: ${e.message}")
            emptyList()
        }
    }

    /**
     * Candidate artists reached through `similar_tracks`.
     *
     * Track similarity needs a track, so one of the seed artist's own TOP tracks
     * stands in for them. `top_tracks` and not `artist_tracks`: the unbounded
     * listing sends every track an artist has over the wire, measured at ~1.5 s
     * each by the Smart Mix engine, and asking for it here timed out on a real
     * server and left the Discover refresh spinning.
     *
     * The artists are built FROM the tracks, with the track's own artwork as the
     * card image, and nothing is looked up. Resolving each artist by id for a
     * proper artist photo cost 17 requests on a real build - and those requests
     * were followed by eight full reloads of the favourite-tracks section, which
     * together were 24 of the 31 seconds a refresh took. A card showing the
     * artwork of the song that led there is a fair trade for that.
     */
    private suspend fun artistsViaSimilarTracks(seedRef: Pair<String, String>): List<Artist> {
        val seedTrack = withTimeoutOrNull(CANDIDATE_RESOLVE_TIMEOUT_MS) {
            musicRepository.getArtistTopTracks(seedRef.first, seedRef.second, limit = 1)
        }?.firstOrNull { it.uri.isNotBlank() } ?: return emptyList()
        val trackRef = maItemRef(seedTrack.uri) ?: return emptyList()
        val similar = withTimeoutOrNull(CANDIDATE_RESOLVE_TIMEOUT_MS) {
            musicRepository.getSimilarTracks(trackRef.first, trackRef.second, DISCOVERY_SIMILAR_TRACKS_PER_SEED)
        } ?: return emptyList()
        val seen = mutableSetOf<String>()
        return similar.mapNotNull { track ->
            val uri = track.artistUri?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = track.artistNames.split(",").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val image = track.imageUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ref = maItemRef(uri) ?: return@mapNotNull null
            if (!seen.add(uri)) return@mapNotNull null
            Artist(
                itemId = ref.first,
                provider = ref.second,
                name = name,
                uri = uri,
                imageUrl = image,
            )
        }.take(DISCOVERY_PER_SEED_SIMILARS)
    }

    /** `provider://artist/id` -> (id, provider). Provider-agnostic. */
    private fun maItemRef(uri: String): Pair<String, String>? {
        val provider = uri.substringBefore("://", "").trim()
        val itemId = uri.substringAfter("://", "").trim('/').substringAfterLast('/').trim()
        return if (provider.isEmpty() || itemId.isEmpty()) null else itemId to provider
    }

    private fun rankArtistsFromPool(
        pool: List<Pair<DiscoveryCandidate, Artist>>
    ): List<Artist> {
        if (pool.isEmpty()) return emptyList()
        val capped = capPerGenreFamily(pool, capPerFamily = DISCOVERY_PRIMARY_GENRE_CAP)
        Log.d(ORCHESTRATOR_TAG, "Artists after per-family cap=$DISCOVERY_PRIMARY_GENRE_CAP: ${capped.size}")
        return mmrRerankArtistsByGenre(capped, lambda = DISCOVERY_MMR_LAMBDA)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun buildAlbumsFromPool(
        pool: List<Pair<DiscoveryCandidate, Artist>>,
        recentAlbumKeys: Set<String>
    ): List<Album> {
        if (pool.isEmpty()) return emptyList()
        val capped = capPerGenreFamily(pool, capPerFamily = DISCOVERY_ALBUM_GENRE_CAP)
            .take(DISCOVERY_ALBUM_ARTIST_POOL_SIZE)
        if (capped.isEmpty()) return emptyList()

        Log.d(
            ORCHESTRATOR_TAG,
            "Albums artist pool: ${capped.size} (cap=$DISCOVERY_ALBUM_GENRE_CAP, max=$DISCOVERY_ALBUM_ARTIST_POOL_SIZE)"
        )

        val albumPairs = capped.mapMaBounded { (candidate, artist) ->
            val album = pickBestAlbumForArtist(artist, recentAlbumKeys)
            if (album != null) Triple(candidate, artist, album) else null
        }.filterNotNull()

        if (albumPairs.isEmpty()) return emptyList()

        return mmrRerankAlbumsByGenre(albumPairs, lambda = DISCOVERY_MMR_LAMBDA)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pickBestAlbumForArtist(
        artist: Artist,
        recentAlbumKeys: Set<String>
    ): Album? {
        val albums = try {
            // Discography (provider catalogue), not getArtistAlbums: a library artist's
            // artist_albums(library) is ~empty on MA 2.9+, which would starve album suggestions.
            musicRepository.getArtistDiscography(artist.itemId, artist.provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ORCHESTRATOR_TAG, "getArtistDiscography failed for ${artist.name}: ${e.message}")
            return null
        }

        return albums.asSequence()
            .filter { it.imageUrl != null }
            .filter { it.albumType != "single" && it.albumType != "compilation" }
            .filter { album ->
                val key = MediaIdentity.canonicalAlbumKey(uri = album.uri) ?: album.uri
                key !in recentAlbumKeys
            }
            .firstOrNull()
            ?: albums.firstOrNull { it.imageUrl != null && it.albumType != "single" }
    }

    private fun mmrRerankArtistsByGenre(
        candidates: List<Pair<DiscoveryCandidate, Artist>>,
        lambda: Double
    ): List<Artist> {
        if (candidates.isEmpty()) return emptyList()

        val rawScores = candidates.associateWith { it.first.compositeScore() }
        val maxScore = rawScores.values.maxOrNull() ?: return candidates.map { it.second }
        val minScore = rawScores.values.minOrNull() ?: 0.0
        val range = maxScore - minScore
        val normalized = rawScores.mapValues { (_, v) ->
            val base = if (range > 0.0) (v - minScore) / range else 1.0
            (base + Random.nextDouble(-DISCOVERY_MMR_JITTER, DISCOVERY_MMR_JITTER)).coerceIn(0.0, 1.0)
        }
        val genresByPair = candidates.associateWith { (_, artist) ->
            artist.genres.map { normalizeGenre(it) }.toSet()
        }
        return greedyMmrSelect(candidates, normalized, genresByPair, lambda).map { it.second }
    }

    private fun mmrRerankAlbumsByGenre(
        candidates: List<Triple<DiscoveryCandidate, Artist, Album>>,
        lambda: Double
    ): List<Album> {
        if (candidates.isEmpty()) return emptyList()

        val rawScores = candidates.associateWith { it.first.compositeScore() }
        val maxScore = rawScores.values.maxOrNull() ?: return candidates.map { it.third }
        val minScore = rawScores.values.minOrNull() ?: 0.0
        val range = maxScore - minScore
        val normalized = rawScores.mapValues { (_, v) ->
            val base = if (range > 0.0) (v - minScore) / range else 1.0
            (base + Random.nextDouble(-DISCOVERY_MMR_JITTER, DISCOVERY_MMR_JITTER)).coerceIn(0.0, 1.0)
        }
        // Album genres prefer album.genres, fall back to artist genres which were enriched via Last.fm
        val genresByTriple = candidates.associateWith { (_, artist, album) ->
            val albumGenres = album.genres.map { normalizeGenre(it) }.toSet()
            if (albumGenres.isNotEmpty()) albumGenres
            else artist.genres.map { normalizeGenre(it) }.toSet()
        }
        return greedyMmrSelect(candidates, normalized, genresByTriple, lambda).map { it.third }
    }

    private fun <T> greedyMmrSelect(
        candidates: List<T>,
        normalized: Map<T, Double>,
        genresMap: Map<T, Set<String>>,
        lambda: Double
    ): List<T> {
        val selected = mutableListOf<T>()
        val remaining = candidates.toMutableList()
        val selectedGenres = mutableListOf<Set<String>>()

        while (remaining.isNotEmpty()) {
            var bestPair: T? = null
            var bestMmr = Double.NEGATIVE_INFINITY
            for (item in remaining) {
                val rel = normalized[item] ?: 0.0
                val candGenres = genresMap[item] ?: emptySet()
                val maxSim = if (selectedGenres.isEmpty()) {
                    0.0
                } else {
                    selectedGenres.maxOf { jaccardSimilarity(candGenres, it) }
                }
                val mmr = lambda * rel - (1.0 - lambda) * maxSim
                if (mmr > bestMmr) {
                    bestMmr = mmr
                    bestPair = item
                }
            }
            bestPair?.let {
                selected.add(it)
                remaining.remove(it)
                selectedGenres.add(genresMap[it] ?: emptySet())
            }
        }
        return selected
    }

    private fun capPerGenreFamily(
        candidates: List<Pair<DiscoveryCandidate, Artist>>,
        capPerFamily: Int
    ): List<Pair<DiscoveryCandidate, Artist>> {
        if (candidates.isEmpty() || capPerFamily <= 0) return candidates
        val perFamilyCount = mutableMapOf<String, Int>()
        val kept = mutableListOf<Pair<DiscoveryCandidate, Artist>>()
        val overflow = mutableListOf<Pair<DiscoveryCandidate, Artist>>()
        for (pair in candidates.sortedByDescending { it.first.compositeScore() }) {
            val (_, artist) = pair
            val primary = artist.genres.firstOrNull()
            val family = primary?.let { genreFamily(it) }
            if (family.isNullOrBlank()) {
                kept.add(pair)
                continue
            }
            val current = perFamilyCount.getOrDefault(family, 0)
            if (current < capPerFamily) {
                perFamilyCount[family] = current + 1
                kept.add(pair)
            } else {
                overflow.add(pair)
            }
        }
        val target = MIN_DISCOVERY_FOR_RANKING
        return if (kept.size >= target) kept else kept + overflow.take(target - kept.size)
    }

    private fun genreFamily(genre: String): String {
        val n = normalizeGenre(genre)
        return when {
            n.isBlank() -> ""
            n == "metal" || n.endsWith(" metal") -> "metal"
            n == "rock" || n.endsWith(" rock") -> "rock"
            n == "pop" || n.endsWith(" pop") -> "pop"
            n in ELECTRONIC_GENRES -> "electronic"
            n in SOUL_BLUES_GENRES -> "soul-blues"
            n in JAZZ_GENRES -> "jazz"
            n in FOLK_GENRES -> "folk"
            n in HIP_HOP_GENRES -> "hip-hop"
            n in CLASSICAL_GENRES -> "classical"
            n in REGGAE_GENRES -> "reggae"
            n == "indie" -> "indie"
            else -> n
        }
    }

    private companion object {
        private val ELECTRONIC_GENRES = setOf(
            "electronic", "ambient", "new age", "synthwave", "trance", "house",
            "techno", "edm", "downtempo", "dance", "electronica", "idm", "drum and bass"
        )
        private val SOUL_BLUES_GENRES = setOf(
            "blues", "soul", "rhythm and blues", "r&b", "rnb", "funk", "motown", "neo soul"
        )
        private val JAZZ_GENRES = setOf(
            "jazz", "fusion", "smooth jazz", "swing", "bebop", "free jazz", "jazz fusion"
        )
        private val FOLK_GENRES = setOf(
            "folk", "singer songwriter", "country", "americana", "bluegrass", "celtic"
        )
        private val HIP_HOP_GENRES = setOf("hip hop", "rap", "trap", "hip-hop")
        private val CLASSICAL_GENRES = setOf(
            "classical", "symphony", "orchestral", "opera", "baroque", "romantic"
        )
        private val REGGAE_GENRES = setOf("reggae", "ska", "dub", "dancehall")
    }

    /**
     * Fill in genres Music Assistant did not carry, from the MusicBrainz CACHE
     * only, and warm the misses in the background.
     *
     * MusicBrainz allows one request per second, so looking up the misses inline
     * put the whole feed behind them: measured on a real refresh, this phase alone
     * was 17.6 of the 28 seconds a manual refresh took, for about a dozen artists.
     * A feed that arrives in seconds with some genres missing is worth more than a
     * complete one nobody waits for, and the misses are on disk by the next
     * refresh - which is the same trade the Smart Mix engine already makes.
     */
    private suspend fun enrichGenres(
        candidates: List<Pair<DiscoveryCandidate, Artist>>
    ): List<Pair<DiscoveryCandidate, Artist>> {
        val missing = candidates.filter { (_, artist) -> artist.genres.isEmpty() }
        if (missing.isEmpty()) return candidates
        val cached = try {
            musicBrainzGenreResolver.cachedGenresFor(
                missing.map { (_, artist) ->
                    MusicBrainzGenreResolver.ArtistRef(artist.name, artist.mbid)
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        var filled = 0
        val result = candidates.map { (candidate, artist) ->
            if (artist.genres.isNotEmpty()) return@map candidate to artist
            val key = artist.mbid?.trim()?.takeIf { it.isNotEmpty() }
                ?: artist.name.trim().lowercase()
            val tags = cached[key]
            if (tags.isNullOrEmpty()) candidate to artist
            else {
                filled++
                candidate to artist.copy(genres = tags)
            }
        }
        val stillMissing = result.filter { (_, artist) -> artist.genres.isEmpty() }
        Log.d(
            ORCHESTRATOR_TAG,
            "Genres: $filled filled from cache, ${stillMissing.size} queued for MusicBrainz"
        )
        warmGenresInBackground(
            stillMissing.map { (_, artist) -> MusicBrainzGenreResolver.ArtistRef(artist.name, artist.mbid) }
        )
        return result
    }

    /**
     * Look the misses up off the critical path, so the NEXT feed has them.
     *
     * Deliberately not awaited: the caller is building a screen and one request
     * per second is not something a screen can wait for.
     */
    private fun warmGenresInBackground(artists: List<MusicBrainzGenreResolver.ArtistRef>) {
        val targets = artists.filter { it.name.isNotBlank() }.distinctBy { it.mbid ?: it.name }
        if (targets.isEmpty()) return
        warmScope.launch {
            for (target in targets.take(GENRE_WARM_LIMIT)) {
                runCatching { musicBrainzGenreResolver.resolve(target.name, target.mbid) }
            }
        }
    }

    private suspend fun buildDiverseSeeds(): List<net.asksakis.massdroidv2.domain.repository.ArtistScore> {
        val artistScores = playHistoryRepository.getScoredArtists(days = 90, limit = 50)
        if (artistScores.isEmpty()) return emptyList()

        val topGenres = try {
            genreRepository.scoredGenres(days = 90, limit = DISCOVERY_TOP_GENRES_FOR_SEEDS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (topGenres.isEmpty()) return artistScores.take(DISCOVERY_SEED_LIMIT)

        val genreArtistMap = try {
            genreRepository.genreArtistMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }

        val artistScoreByUri = artistScores.associateBy { it.artistUri }

        // Group user's top genres by family, then pick 1 seed per family from the
        // pooled top-BLL artists across all genres in that family. This prevents
        // 5 rock-flavored genres from contributing 5 rock seeds.
        val genresByFamily = topGenres.groupBy { genreFamily(it.genre) }
            .filter { it.key.isNotBlank() }

        val perFamilySeeds = genresByFamily.entries.mapNotNull { (_, genres) ->
            val pool = genres
                .flatMap { gs ->
                    genreArtistMap[normalizeGenre(gs.genre)].orEmpty()
                        .mapNotNull { uri -> artistScoreByUri[uri] }
                }
                .distinctBy { it.artistUri }
                .sortedByDescending { it.score }
                .take(DISCOVERY_SEED_RANDOMIZATION_POOL)
            // Prefer a library artist within the family, because only a library
            // item answers `similar_artists`. The genre map is dominated by
            // provider uris, so an unbiased pick here wasted most of the seeds.
            // Randomisation is kept, just inside the half that can answer.
            val answerable = pool.filter { it.artistUri.startsWith(LIBRARY_URI_PREFIX) }
            val draw = answerable.ifEmpty { pool }
            if (draw.isEmpty()) null else draw[Random.nextInt(draw.size)]
        }.distinctBy { it.artistUri }

        val usedUris = perFamilySeeds.mapTo(mutableSetOf()) { it.artistUri }
        // Library artists first here too, for the same reason.
        val padPool = artistScores
            .filter { it.artistUri !in usedUris }
            .sortedByDescending { it.artistUri.startsWith(LIBRARY_URI_PREFIX) }
            .take(DISCOVERY_PAD_RANDOMIZATION_POOL)
            .shuffled()
            .sortedByDescending { it.artistUri.startsWith(LIBRARY_URI_PREFIX) }
        val pad = padPool.take(DISCOVERY_SEED_LIMIT - perFamilySeeds.size)

        return preferLibraryUris((perFamilySeeds + pad).take(DISCOVERY_SEED_LIMIT))
    }

    /**
     * Point each seed at the artist's library uri when one exists.
     *
     * Only a library item answers `similar_artists`; a provider item returns
     * nothing. The genre map this picks from is dominated by provider uris (a
     * real library held 4917 provider artist rows against 678 library ones), so
     * the seeds skewed the wrong way and most of them contributed nothing:
     * measured, 6 of 20 calls went to a library item while 37 of the 50
     * candidate artists actually had one.
     *
     * Swapping the uri costs nothing - no extra request, same artist, same BLL
     * score - and it is strictly better than asking the server about an item it
     * cannot answer for.
     */
    private suspend fun preferLibraryUris(
        seeds: List<net.asksakis.massdroidv2.domain.repository.ArtistScore>
    ): List<net.asksakis.massdroidv2.domain.repository.ArtistScore> {
        if (seeds.none { !it.artistUri.startsWith(LIBRARY_URI_PREFIX) }) return seeds
        val libraryByName = try {
            playHistoryRepository.getLibraryArtistUriMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return seeds
        }
        var swapped = 0
        val mapped = seeds.map { seed ->
            if (seed.artistUri.startsWith(LIBRARY_URI_PREFIX)) return@map seed
            val libraryUri = libraryByName[seed.artistName] ?: return@map seed
            swapped++
            seed.copy(artistUri = libraryUri)
        }
        if (swapped > 0) Log.d(ORCHESTRATOR_TAG, "Seeds repointed to a library uri: $swapped")
        return mapped
    }

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val union = a.union(b).size
        if (union == 0) return 0.0
        return a.intersect(b).size.toDouble() / union.toDouble()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buildProviderArtistsFallback(
        serverFolders: List<RecommendationFolder>,
        libraryNames: Set<String>,
        excludedArtistUris: Set<String>,
        alreadyPickedNames: Set<String>
    ): List<Artist> {
        return serverFolders
            .asSequence()
            .filter { it.itemId == PROVIDER_RECOMMENDED_ARTISTS_FOLDER && it.provider != "library" }
            .flatMap { it.items.artists.asSequence() }
            .filter { it.imageUrl != null }
            .filter { it.name.lowercase() !in libraryNames }
            .filter { it.name.lowercase() !in alreadyPickedNames }
            .filter { !isExcluded(it, excludedArtistUris) }
            .distinctBy { it.uri.ifBlank { it.name.lowercase() } }
            .toList()
    }

    private fun buildProviderAlbumsFallback(
        serverFolders: List<RecommendationFolder>,
        libraryArtists: List<Artist>,
        recentAlbumKeys: Set<String>,
        alreadyPickedAlbumKeys: Set<String>
    ): List<Album> {
        val libraryArtistNames = libraryArtists
            .asSequence()
            .filter { it.uri.startsWith("library://") }
            .map { it.name.lowercase() }
            .toSet()
        return serverFolders
            .asSequence()
            .filter { it.itemId == PROVIDER_RECOMMENDED_ALBUMS_FOLDER && it.provider != "library" }
            .flatMap { it.items.albums.asSequence() }
            .filter { it.imageUrl != null }
            .filter { it.albumType != "single" && it.albumType != "compilation" }
            .filter { album ->
                val key = MediaIdentity.canonicalAlbumKey(uri = album.uri) ?: album.uri
                key !in recentAlbumKeys && key !in alreadyPickedAlbumKeys
            }
            .filter { album ->
                // Exclude albums whose primary artist is in user's library (= "known")
                val primaryArtistName = album.artists.firstOrNull()?.name?.lowercase()
                primaryArtistName == null || primaryArtistName !in libraryArtistNames
            }
            .distinctBy { MediaIdentity.canonicalAlbumKey(uri = it.uri) ?: it.uri.ifBlank { it.name.lowercase() } }
            .toList()
    }

    private fun isExcluded(artist: Artist, excludedArtistUris: Set<String>): Boolean {
        if (artist.uri in excludedArtistUris) return true
        val canonical = MediaIdentity.canonicalArtistKey(itemId = artist.itemId, uri = artist.uri)
        return canonical != null && canonical in excludedArtistUris
    }

    private class DiscoveryCandidate(
        val name: String,
        var bestMatchScore: Double = 0.0,
        val voters: MutableSet<String> = mutableSetOf(),
        var seedBllSum: Double = 0.0
    ) {
        private val jitter: Double =
            1.0 + Random.nextDouble(-DISCOVERY_SCORE_JITTER, DISCOVERY_SCORE_JITTER)

        fun compositeScore(): Double {
            val voteBoost = 1.0 + DISCOVERY_VOTE_WEIGHT * voters.size
            val seedWeight = 1.0 + ln(1.0 + seedBllSum.coerceAtLeast(0.0))
            return bestMatchScore * voteBoost * seedWeight * jitter
        }
    }
}
