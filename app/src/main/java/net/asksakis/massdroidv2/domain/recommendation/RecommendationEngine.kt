package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "RecommendEngine"

data class ScoredArtist(
    val artist: Artist,
    val relevance: Double
)

data class ScoredAlbum(
    val album: Album,
    val genres: Set<String>,
    val relevance: Double
)

@Singleton
class RecommendationEngine @Inject constructor() {

    companion object {
        private const val ARTIST_LAMBDA = 0.7
        private const val ALBUM_LAMBDA = 0.5
        private const val EXPLOIT_RATIO = 0.7
        private const val EXPLORE_RATIO = 0.2
        private const val JITTER_AMOUNT = 0.15
    }

    fun buildSuggestedArtists(
        candidates: List<Artist>,
        genreScores: List<GenreScore>,
        artistScores: List<ArtistScore>,
        genreAdjacency: Map<String, Set<String>>,
        recentlyAdded: List<Artist>,
        excludedArtistUris: Set<String> = emptySet(),
        artistSignalScores: Map<String, Double> = emptyMap(),
        artistIdentity: (Artist) -> String = { it.uri },
        similarArtistScores: Map<String, Double> = emptyMap(),
        similarArtistUris: Set<String> = emptySet(),
        count: Int = 10
    ): List<Artist> {
        if (genreScores.isEmpty()) return candidates.shuffled().take(count)

        val topArtistUris = artistScores.map { it.artistUri }.toSet()
        val eligible = candidates.filter {
            val artistKey = artistIdentity(it)
            it.imageUrl != null &&
                artistKey !in topArtistUris &&
                artistKey !in excludedArtistUris
        }
        if (eligible.isEmpty()) return candidates.shuffled().take(count)

        val genreScoreMap = genreScores.toScoreMap()
        val topGenreNames = genreScoreMap.keys

        // Score each candidate by sum of their genre affinities + signal + similar bonus
        val scored = eligible.map { artist ->
            val key = artistIdentity(artist)
            val affinityScore = artist.genres.sumOf { g -> genreScoreMap[g.lowercase()] ?: 0.0 }
            val signalBoost = artistSignalScores[key] ?: 0.0
            val similarBonus = (similarArtistScores[key] ?: 0.0) * 0.6
            ScoredArtist(artist, affinityScore + signalBoost * 0.5 + similarBonus)
        }.filter { it.relevance > 0.0 }

        val exploitCount = (count * EXPLOIT_RATIO).toInt().coerceAtLeast(1)
        val exploreCount = (count * EXPLORE_RATIO).toInt().coerceAtLeast(1)
        val wildcardCount = count - exploitCount - exploreCount

        // Exploit: MMR re-ranked top scored (with jitter for variety)
        val exploitPicks = mmrRerank(scored, ARTIST_LAMBDA, exploitCount) { it.artist.genres.map { g -> g.lowercase() }.toSet() }
        val usedUris = exploitPicks.mapTo(mutableSetOf()) { artistIdentity(it.artist) }

        // Explore: similar artists first, then adjacent genre artists
        val similarCandidates = if (similarArtistUris.isNotEmpty()) {
            eligible.filter { artistIdentity(it) in similarArtistUris && artistIdentity(it) !in usedUris }
        } else {
            emptyList()
        }
        val adjacentGenres = topGenreNames.flatMapTo(mutableSetOf()) { g ->
            genreAdjacency[g].orEmpty()
        } - topGenreNames
        val adjacentCandidates = if (adjacentGenres.isNotEmpty()) {
            eligible.filter { artistIdentity(it) !in usedUris && it.genres.any { g -> g.lowercase() in adjacentGenres } }
        } else {
            eligible.filter { artistIdentity(it) !in usedUris && it.genres.none { g -> g.lowercase() in topGenreNames } }
        }
        val exploreCandidates = (similarCandidates + adjacentCandidates).distinctBy { artistIdentity(it) }
        val explorePicks = exploreCandidates.shuffled().take(exploreCount)
        usedUris.addAll(explorePicks.map { artistIdentity(it) })

        // Wildcard: random from recently added or remaining eligible
        val remainingSlots = count - exploitPicks.size - explorePicks.size
        val wildcardPool = recentlyAdded.filter { artist ->
            val key = artistIdentity(artist)
            artist.imageUrl != null && key !in usedUris && key !in topArtistUris
        }
        val wildcardPicks = if (wildcardPool.isNotEmpty()) {
            wildcardPool.shuffled().take(remainingSlots)
        } else {
            eligible.filter { artistIdentity(it) !in usedUris }.shuffled().take(remainingSlots)
        }

        val result = exploitPicks.map { it.artist } + explorePicks + wildcardPicks
        Log.d(TAG, "Artists: ${exploitPicks.size} exploit, ${explorePicks.size} explore, ${wildcardPicks.size} wildcard")
        return result.distinctBy { artistIdentity(it) }.ifEmpty { candidates.shuffled().take(count) }
    }

    fun rankAlbumsForDiscovery(
        candidates: List<ScoredAlbum>,
        count: Int = 10
    ): List<Album> {
        if (candidates.isEmpty()) return emptyList()
        val reranked = mmrRerank(candidates, ALBUM_LAMBDA, count) { it.genres }
        Log.d(TAG, "Albums MMR: ${reranked.size} from ${candidates.size} candidates")
        return reranked.map { it.album }
    }

    private fun <T> mmrRerank(
        items: List<T>,
        lambda: Double,
        count: Int,
        genreExtractor: (T) -> Set<String>
    ): List<T> where T : Any {
        if (items.isEmpty()) return emptyList()

        val relevanceMap = when {
            items.first() is ScoredArtist -> items.associate {
                it to (it as ScoredArtist).relevance
            }
            items.first() is ScoredAlbum -> items.associate {
                it to (it as ScoredAlbum).relevance
            }
            else -> return items.take(count)
        }

        // Normalize relevance to [0, 1] and add jitter for variety across refreshes
        val maxRelevance = relevanceMap.values.maxOrNull() ?: return items.take(count)
        val minRelevance = relevanceMap.values.minOrNull() ?: 0.0
        val range = maxRelevance - minRelevance
        val normalized = if (range > 0.0) {
            relevanceMap.mapValues { (_, v) ->
                val base = (v - minRelevance) / range
                (base + Random.nextDouble(-JITTER_AMOUNT, JITTER_AMOUNT)).coerceIn(0.0, 1.0)
            }
        } else {
            relevanceMap.mapValues { Random.nextDouble(0.5, 1.0) }
        }

        val selected = mutableListOf<T>()
        val remaining = items.toMutableList()
        val selectedGenres = mutableListOf<Set<String>>()

        repeat(count.coerceAtMost(items.size)) {
            var bestItem: T? = null
            var bestMmr = Double.MIN_VALUE

            for (candidate in remaining) {
                val rel = normalized[candidate] ?: 0.0
                val candidateGenres = genreExtractor(candidate)
                val maxSim = if (selectedGenres.isEmpty()) {
                    0.0
                } else {
                    selectedGenres.maxOf { jaccardSimilarity(candidateGenres, it) }
                }
                val mmr = lambda * rel - (1.0 - lambda) * maxSim
                if (mmr > bestMmr) {
                    bestMmr = mmr
                    bestItem = candidate
                }
            }

            bestItem?.let { item ->
                selected.add(item)
                remaining.remove(item)
                selectedGenres.add(genreExtractor(item))
            }
        }

        return selected
    }

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union.toDouble()
    }
}
