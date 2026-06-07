package net.asksakis.massdroidv2.data.genre

import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.domain.repository.DecadeScore
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedArtist(
    val name: String,
    val uri: String,
    val itemId: String,
    val provider: String
)

@Singleton
class GenreRepository @Inject constructor(
    private val dao: PlayHistoryDao,
    private val playHistoryRepository: PlayHistoryRepository
) {
    // --- Browse/Display: library artists only ---

    suspend fun libraryGenres(): List<String> = dao.getLibraryGenres()

    suspend fun libraryArtistsForGenre(genre: String): List<ResolvedArtist> =
        dao.getLibraryArtistsByGenre(genre).map { it.toResolved() }

    // --- Algorithmic: all artists (library preferred) ---

    suspend fun allArtistsForGenre(genre: String): List<ResolvedArtist> =
        dao.getArtistsByGenre(genre).map { it.toResolved() }

    suspend fun genreArtistMap(): Map<String, List<String>> =
        playHistoryRepository.getGenreArtistMap()

    suspend fun scoredGenres(days: Int = 90, limit: Int = 20): List<GenreScore> =
        playHistoryRepository.getScoredGenres(days, limit)

    suspend fun topGenres(days: Int = 30, limit: Int = 10): List<GenreScore> =
        playHistoryRepository.getTopGenres(days, limit)

    suspend fun adjacencyMap(): Map<String, Set<String>> =
        playHistoryRepository.getGenreAdjacencyMap()

    suspend fun decadesForGenre(genre: String, days: Int = 365, limit: Int = 3): List<DecadeScore> =
        playHistoryRepository.getTopDecadesForGenre(genre, days, limit)

    suspend fun searchArtistUris(query: String): List<String> =
        playHistoryRepository.searchArtistUrisByGenre(query)

    private fun net.asksakis.massdroidv2.data.database.ArtistNameUri.toResolved(): ResolvedArtist {
        val parts = uri.split("://", limit = 2)
        val provider = parts.getOrElse(0) { "library" }
        val path = parts.getOrElse(1) { "" }
        val itemId = path.substringAfter("/", "")
        return ResolvedArtist(name = name, uri = uri, itemId = itemId, provider = provider)
    }
}
