package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmSimilarResolver
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import net.asksakis.massdroidv2.domain.recommendation.RecommendationEngine
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import kotlin.math.ln
import kotlin.random.Random

private const val ORCHESTRATOR_TAG = "DiscoverReco"
private const val DISCOVERY_SEED_LIMIT = 8
private const val DISCOVERY_PER_SEED_SIMILARS = 4
private const val DISCOVERY_RESOLVE_BUDGET = 25
private const val DISCOVERY_VOTE_WEIGHT = 0.2
private const val DISCOVERY_TOP_GENRES_FOR_SEEDS = 8
private const val DISCOVERY_MMR_LAMBDA = 0.35
private const val DISCOVERY_PRIMARY_GENRE_CAP = 2
private const val DISCOVERY_ALBUM_GENRE_CAP = 2
private const val DISCOVERY_ALBUM_ARTIST_POOL_SIZE = 20
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
    @Suppress("unused") private val recommendationEngine: RecommendationEngine,
    private val lastFmSimilarResolver: LastFmSimilarResolver,
    private val lastFmGenreResolver: LastFmGenreResolver
) {

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

        val artistsLastFm = rankArtistsFromPool(pool)
        val artistsFallback = buildProviderArtistsFallback(
            serverFolders,
            libraryNames,
            excludedArtistUris,
            alreadyPickedNames = artistsLastFm.mapTo(mutableSetOf()) { it.name.lowercase() }
        )
        val artists = (artistsLastFm + artistsFallback)
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

        val albumsLastFm = buildLastFmAlbums(pool, recentAlbumKeys)
        val albumsFallback = buildProviderAlbumsFallback(
            serverFolders,
            libraryArtists,
            recentAlbumKeys,
            alreadyPickedAlbumKeys = albumsLastFm.mapNotNullTo(mutableSetOf()) {
                MediaIdentity.canonicalAlbumKey(uri = it.uri) ?: it.uri.ifBlank { null }
            }
        )
        val albums = (albumsLastFm + albumsFallback)
            .distinctBy { MediaIdentity.canonicalAlbumKey(uri = it.uri) ?: it.uri.ifBlank { it.name.lowercase() } }
            .take(albumCount)

        Log.d(
            ORCHESTRATOR_TAG,
            "Discovery: artists lastFm=${artistsLastFm.size} fallback=${artistsFallback.size} final=${artists.size}; " +
                "albums lastFm=${albumsLastFm.size} fallback=${albumsFallback.size} final=${albums.size}"
        )
        return DiscoveryResult(artists = artists, albums = albums)
    }

    private suspend fun buildEnrichedCandidatePool(
        libraryNames: Set<String>,
        excludedArtistUris: Set<String>
    ): List<Pair<DiscoveryCandidate, Artist>> = coroutineScope {
        val seeds = buildDiverseSeeds()
        if (seeds.isEmpty()) return@coroutineScope emptyList()

        Log.d(
            ORCHESTRATOR_TAG,
            "Discovery seeds (${seeds.size}): ${seeds.joinToString { it.artistName }}"
        )

        val similarsBySeed = seeds.map { seed ->
            async {
                try {
                    seed to lastFmSimilarResolver.resolve(seed.artistName, limit = DISCOVERY_PER_SEED_SIMILARS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(ORCHESTRATOR_TAG, "Last.fm similar fetch failed for ${seed.artistName}: ${e.message}")
                    seed to emptyList()
                }
            }
        }.awaitAll()

        val candidatesByName = mutableMapOf<String, DiscoveryCandidate>()
        for ((seed, similars) in similarsBySeed) {
            for (similar in similars) {
                val name = similar.name.lowercase()
                if (name in libraryNames) continue
                if (similar.matchScore <= 0.0) continue
                val candidate = candidatesByName.getOrPut(name) { DiscoveryCandidate(name) }
                candidate.bestMatchScore = maxOf(candidate.bestMatchScore, similar.matchScore)
                candidate.voters.add(seed.artistName)
                candidate.seedBllSum += seed.score
            }
        }

        if (candidatesByName.isEmpty()) return@coroutineScope emptyList()

        val toResolve = candidatesByName.values
            .sortedByDescending { it.compositeScore() }
            .take(DISCOVERY_RESOLVE_BUDGET)

        val resolved = toResolve.map { candidate ->
            async { candidate to resolveLastFmCandidate(candidate.name) }
        }.awaitAll()

        val filtered = resolved
            .mapNotNull { (candidate, artist) -> if (artist != null) candidate to artist else null }
            .filter { (_, artist) ->
                artist.imageUrl != null &&
                    !isExcluded(artist, excludedArtistUris) &&
                    artist.name.lowercase() !in libraryNames
            }
            .distinctBy { it.second.uri }

        val enriched = enrichGenresViaLastFm(filtered)
        Log.d(ORCHESTRATOR_TAG, "Enriched candidate pool size: ${enriched.size}")
        enriched
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
    private suspend fun buildLastFmAlbums(
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

        val albumPairs = coroutineScope {
            capped.map { (candidate, artist) ->
                async {
                    val album = pickBestAlbumForArtist(artist, recentAlbumKeys)
                    if (album != null) Triple(candidate, artist, album) else null
                }
            }.awaitAll().filterNotNull()
        }

        if (albumPairs.isEmpty()) return emptyList()

        return mmrRerankAlbumsByGenre(albumPairs, lambda = DISCOVERY_MMR_LAMBDA)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun pickBestAlbumForArtist(
        artist: Artist,
        recentAlbumKeys: Set<String>
    ): Album? {
        val albums = try {
            musicRepository.getArtistAlbums(artist.itemId, artist.provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(ORCHESTRATOR_TAG, "getArtistAlbums failed for ${artist.name}: ${e.message}")
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

    private suspend fun enrichGenresViaLastFm(
        candidates: List<Pair<DiscoveryCandidate, Artist>>
    ): List<Pair<DiscoveryCandidate, Artist>> = coroutineScope {
        candidates.map { (cand, artist) ->
            async {
                if (artist.genres.isNotEmpty()) {
                    cand to artist
                } else {
                    val tags = try {
                        lastFmGenreResolver.resolve(artist.name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(ORCHESTRATOR_TAG, "Genre enrich failed for ${artist.name}: ${e.message}")
                        emptyList()
                    }
                    cand to if (tags.isNotEmpty()) artist.copy(genres = tags) else artist
                }
            }
        }.awaitAll()
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
            if (pool.isEmpty()) null else pool[Random.nextInt(pool.size)]
        }.distinctBy { it.artistUri }

        val usedUris = perFamilySeeds.mapTo(mutableSetOf()) { it.artistUri }
        val padPool = artistScores
            .filter { it.artistUri !in usedUris }
            .take(DISCOVERY_PAD_RANDOMIZATION_POOL)
            .shuffled()
        val pad = padPool.take(DISCOVERY_SEED_LIMIT - perFamilySeeds.size)

        return (perFamilySeeds + pad).take(DISCOVERY_SEED_LIMIT)
    }

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val union = a.union(b).size
        if (union == 0) return 0.0
        return a.intersect(b).size.toDouble() / union.toDouble()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolveLastFmCandidate(similarName: String): Artist? = try {
        val results = musicRepository.search(similarName, listOf(MediaType.ARTIST), limit = 5)
        val match = results.artists.firstOrNull { it.name.equals(similarName, ignoreCase = true) }
            ?: return null
        try {
            musicRepository.getArtist(match.itemId, match.provider) ?: match
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            match
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(ORCHESTRATOR_TAG, "Resolve failed for $similarName: ${e.message}")
        null
    }

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
