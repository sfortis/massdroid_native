package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.RecommendationEngine
import net.asksakis.massdroidv2.domain.recommendation.ScoredAlbum
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository

private const val ORCHESTRATOR_TAG = "DiscoverReco"

class DiscoverRecommendationOrchestrator(
    private val musicRepository: MusicRepository,
    private val playHistoryRepository: PlayHistoryRepository,
    private val recommendationEngine: RecommendationEngine
) {

    suspend fun buildSuggestedArtists(
        candidateArtists: List<Artist>,
        excludedArtistUris: Set<String>,
        artistSignalScores: Map<String, Double>,
        artistIdentity: (Artist) -> String
    ): List<Artist> {
        val genreScores = playHistoryRepository.getScoredGenres(days = 90, limit = 20)
        val artistScores = playHistoryRepository.getScoredArtists(days = 90, limit = 50)
        val genreAdjacency = playHistoryRepository.getGenreAdjacencyMap()

        Log.d(
            ORCHESTRATOR_TAG,
            "BLL: ${genreScores.size} genres, ${artistScores.size} artists, ${genreAdjacency.size} adjacency"
        )
        if (genreScores.isNotEmpty()) {
            Log.d(
                ORCHESTRATOR_TAG,
                "Top genres: ${genreScores.take(3).map { "${it.genre}=${String.format("%.2f", it.score)}" }}"
            )
        }

        if (genreScores.isEmpty()) return candidateArtists.shuffled().take(10)

        val recentlyAdded = try {
            musicRepository.getArtists(orderBy = "recently_added", limit = 20)
        } catch (_: Exception) {
            emptyList()
        }

        return recommendationEngine.buildSuggestedArtists(
            candidates = candidateArtists,
            genreScores = genreScores,
            artistScores = artistScores,
            genreAdjacency = genreAdjacency,
            recentlyAdded = recentlyAdded,
            excludedArtistUris = excludedArtistUris,
            artistSignalScores = artistSignalScores,
            artistIdentity = artistIdentity,
            count = 10
        )
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun loadDiscoverAlbums(
        candidateArtists: List<Artist>,
        recommendationAlbums: List<Album>,
        excludedArtistUris: Set<String>,
        artistSignalScores: Map<String, Double>,
        albumIdentity: (Album) -> String,
        artistIdentity: (Artist) -> String,
        loadArtistAlbumsForIdentity: suspend (String) -> List<Album>
    ): List<Album>? = try {
        val artistScores = playHistoryRepository.getScoredArtists(days = 90, limit = 10)
        val genreScores = playHistoryRepository.getScoredGenres(days = 90, limit = 10)

        if (artistScores.isEmpty()) {
            (recommendationAlbums + musicRepository.getAlbums(orderBy = "random", limit = 20))
                .distinctBy(albumIdentity)
                .take(10)
        } else {
            val recentAlbumUris = playHistoryRepository.getRecentAlbums(limit = 50)
                .map { MediaIdentity.canonicalAlbumKey(uri = it.albumUri) ?: it.albumUri }
                .toSet()
            val topArtistUris = artistScores
                .map { it.artistUri }
                .filterNot { it in excludedArtistUris }
                .toSet()

            val artistGenreMap = candidateArtists.mapNotNull { artist ->
                artistIdentity(artist).let { it to artist.genres.toSet() }
            }.toMap()
            val genreScoreMap = genreScores.associate { it.genre to it.score }

            val allCandidateAlbums = mutableListOf<ScoredAlbum>()

            coroutineScope {
                val artistDefs = artistScores
                    .filterNot { it.artistUri in excludedArtistUris }
                    .take(5)
                    .map { score ->
                        async {
                            try {
                                val albums = loadArtistAlbumsForIdentity(score.artistUri)
                                val genres = artistGenreMap[score.artistUri].orEmpty()
                                albums.filter { album ->
                                    val key = albumIdentity(album)
                                    key !in recentAlbumUris && album.albumType != "single"
                                }.map { album ->
                                    ScoredAlbum(
                                        album = album,
                                        genres = genres,
                                        relevance = genres.sumOf { g -> genreScoreMap[g] ?: 0.0 } +
                                            (artistSignalScores[score.artistUri] ?: 0.0) * 0.25
                                    )
                                }
                            } catch (_: Exception) {
                                emptyList<ScoredAlbum>()
                            }
                        }
                    }
                for (def in artistDefs) {
                    allCandidateAlbums.addAll(def.await())
                }

                val topGenreNames = genreScores.map { it.genre }.toSet()
                val discoveryArtists = candidateArtists
                    .filter { artist -> artistIdentity(artist) !in topArtistUris }
                    .filterNot { artist -> artistIdentity(artist) in excludedArtistUris }
                    .filter { it.genres.any { g -> g in topGenreNames } }
                    .shuffled()
                    .take(5)

                val genreDefs = discoveryArtists.map { artist ->
                    async {
                        try {
                            val albums = musicRepository.getArtistAlbums(artist.itemId, artist.provider)
                            val genres = artist.genres.toSet()
                            albums.filter { album ->
                                val key = albumIdentity(album)
                                key !in recentAlbumUris && album.albumType != "single"
                            }.map { album ->
                                ScoredAlbum(
                                    album = album,
                                    genres = genres,
                                    relevance = genres.sumOf { g -> genreScoreMap[g] ?: 0.0 } +
                                        (artistSignalScores[artistIdentity(artist)] ?: 0.0) * 0.25
                                )
                            }
                        } catch (_: Exception) {
                            emptyList<ScoredAlbum>()
                        }
                    }
                }
                for (def in genreDefs) {
                    allCandidateAlbums.addAll(def.await())
                }
            }

            val uniqueCandidates = allCandidateAlbums.distinctBy { albumIdentity(it.album) }
            val cappedCandidates = uniqueCandidates
                .groupBy {
                    MediaIdentity.canonicalArtistKey(
                        itemId = it.album.artists.firstOrNull()?.itemId,
                        uri = it.album.artists.firstOrNull()?.uri
                    ) ?: it.album.artistNames
                }
                .flatMap { (_, albums) -> albums.shuffled().take(1) }
            val ranked = recommendationEngine.rankAlbumsForDiscovery(cappedCandidates, count = 10)

            val usedUris = ranked.mapTo(mutableSetOf()) { albumIdentity(it) }
            usedUris.addAll(recentAlbumUris)
            val remaining = 10 - ranked.size
            val result = ranked.toMutableList()
            if (remaining > 0) {
                val randomAlbums = musicRepository.getAlbums(orderBy = "random", limit = 20)
                    .filter { album -> albumIdentity(album) !in usedUris }
                    .take(remaining)
                result.addAll(randomAlbums)
            }

            result
                .distinctBy(albumIdentity)
                .shuffled()
                .take(10)
                .ifEmpty {
                    recommendationAlbums
                        .distinctBy(albumIdentity)
                        .shuffled()
                        .take(10)
                        .ifEmpty { musicRepository.getAlbums(orderBy = "random", limit = 10) }
                }
        }
    } catch (e: Exception) {
        Log.e(ORCHESTRATOR_TAG, "Failed to load discover albums", e)
        null
    }
}
