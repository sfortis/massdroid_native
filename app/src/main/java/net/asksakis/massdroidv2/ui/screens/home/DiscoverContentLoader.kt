package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.canonicalKey
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository

private const val LOADER_TAG = "DiscoverLoader"
private const val MIN_DISCOVER_SECTION_ITEMS = 3
private const val RECENT_TRACKS_QUERY_FACTOR = 5
private const val RECENT_FAVORITE_ALBUMS_FOLDER_ID_LOCAL = "recent_favorite_albums"
private const val RECENT_FAVORITE_ALBUMS_TITLE_LOCAL = "Recent Favorite Albums"

data class DiscoverContentBundle(
    val mergedArtists: List<Artist>,
    val enrichedFolders: List<RecommendationFolder>,
    val recommendationAlbums: List<Album>,
    val genreItems: List<GenreItem>,
    val genreArtists: Map<String, List<String>>,
    val strictGenreArtists: Map<String, List<String>>
)

class DiscoverContentLoader(
    private val musicRepository: MusicRepository,
    private val playHistoryRepository: PlayHistoryRepository
) {

    @Suppress("TooGenericExceptionCaught")
    suspend fun load(excludedArtistUris: Set<String>): DiscoverContentBundle = coroutineScope {
        val artistsDef = async { loadLibraryArtists() }
        val randomArtistsDef = async { loadRandomArtists() }
        val favoriteAlbumsDef = async { loadRecentFavoriteAlbums() }
        val recsDef = async { loadRecommendations() }

        val artists = artistsDef.await()
        val randomArtists = randomArtistsDef.await()
        val recentFavoriteAlbums = favoriteAlbumsDef.await()
        val serverFolders = recsDef.await()
        val enrichedFolders = mergeFavoriteAlbumsFolder(serverFolders, recentFavoriteAlbums)
        val recommendationArtists = extractRecommendationArtists(enrichedFolders)
        val recommendationAlbums = extractRecommendationAlbums(enrichedFolders)

        val mergedArtists = buildList {
            if (artists != null) addAll(artists)
            if (randomArtists != null) addAll(randomArtists)
            addAll(recommendationArtists)
        }.distinctBy { it.canonicalKey() ?: it.uri }

        val artistByUri = mergedArtists.mapNotNull { artist ->
            artist.canonicalKey()?.let { it to artist }
        }.toMap()

        val filteredArtists = if (excludedArtistUris.isEmpty()) {
            mergedArtists
        } else {
            mergedArtists.filterNot { artist ->
                val key = artist.canonicalKey() ?: return@filterNot false
                key in excludedArtistUris
            }
        }

        val historyGenreArtists = try {
            playHistoryRepository.getGenreArtistMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val (genreItems, genreArtists, strictGenreArtists) = buildGenreData(filteredArtists, historyGenreArtists, artistByUri)

        Log.d(LOADER_TAG, "Merged ${mergedArtists.size} artists, ${serverFolders.size} server folders")

        DiscoverContentBundle(
            mergedArtists = mergedArtists,
            enrichedFolders = enrichedFolders,
            recommendationAlbums = recommendationAlbums,
            genreItems = genreItems,
            genreArtists = genreArtists,
            strictGenreArtists = strictGenreArtists
        )
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun loadRecentFavoriteAlbums(limit: Int = 10): List<Album> {
        val favoritesByAdded = try {
            musicRepository.getAlbums(
                orderBy = "timestamp_added_desc",
                limit = limit * RECENT_TRACKS_QUERY_FACTOR,
                favoriteOnly = true
            )
        } catch (_: Exception) {
            emptyList()
        }

        return favoritesByAdded
            .filter { it.favorite }
            .distinctBy { it.canonicalKey() ?: it.uri }
            .take(limit)
    }

    private suspend fun loadLibraryArtists(): List<Artist>? = try {
        musicRepository.getArtists(orderBy = "play_count_desc", limit = 500)
    } catch (e: Exception) {
        Log.e(LOADER_TAG, "Failed to load library artists", e)
        null
    }

    private suspend fun loadRandomArtists(): List<Artist>? = try {
        musicRepository.getArtists(orderBy = "random", limit = 500)
    } catch (e: Exception) {
        Log.e(LOADER_TAG, "Failed to load random artists", e)
        null
    }

    private suspend fun loadRecommendations(): List<RecommendationFolder> = try {
        musicRepository.getRecommendations()
    } catch (e: Exception) {
        Log.e(LOADER_TAG, "Failed to load recommendations", e)
        emptyList()
    }

    private fun extractRecommendationArtists(folders: List<RecommendationFolder>): List<Artist> =
        folders.asSequence()
            .flatMap { it.items.artists.asSequence() }
            .distinctBy { it.canonicalKey() ?: it.uri }
            .toList()

    private fun extractRecommendationAlbums(folders: List<RecommendationFolder>): List<Album> =
        folders.asSequence()
            .flatMap { it.items.albums.asSequence() }
            .distinctBy { it.canonicalKey() ?: it.uri }
            .toList()

    private fun mergeFavoriteAlbumsFolder(
        serverFolders: List<RecommendationFolder>,
        recentFavoriteAlbums: List<Album>
    ): List<RecommendationFolder> {
        val withoutFavoriteAlbums = serverFolders.filterNot {
            it.provider == "library" && it.itemId == RECENT_FAVORITE_ALBUMS_FOLDER_ID_LOCAL
        }
        if (recentFavoriteAlbums.size < MIN_DISCOVER_SECTION_ITEMS) return withoutFavoriteAlbums

        return withoutFavoriteAlbums + RecommendationFolder(
            itemId = RECENT_FAVORITE_ALBUMS_FOLDER_ID_LOCAL,
            name = RECENT_FAVORITE_ALBUMS_TITLE_LOCAL,
            provider = "library",
            items = RecommendationItems(albums = recentFavoriteAlbums)
        )
    }

    fun buildGenreData(
        artists: List<Artist>,
        historyGenreArtists: Map<String, List<String>>,
        artistByUri: Map<String, Artist>
    ): Triple<List<GenreItem>, Map<String, List<String>>, Map<String, List<String>>> {
        val genreMap = mutableMapOf<String, MutableList<Artist>>()
        for (artist in artists) {
            for (genre in artist.genres) {
                genreMap.getOrPut(genre.lowercase()) { mutableListOf() }.add(artist)
            }
        }

        val strictGenreArtists = genreMap.mapValues { (_, artistList) ->
            artistList.map { it.uri }
        }
        val genreArtists = buildMap {
            val allGenres = strictGenreArtists.keys + historyGenreArtists.keys
            for (genre in allGenres) {
                val merged = buildList {
                    addAll(strictGenreArtists[genre].orEmpty())
                    addAll(
                        historyGenreArtists[genre]
                            .orEmpty()
                            .mapNotNull { key -> artistByUri[key]?.uri }
                    )
                }.distinctBy { MediaIdentity.artistKeyFromUri(it) ?: it }
                put(genre, merged)
            }
        }

        val genreItems = genreArtists
            .filter { (_, uris) -> uris.isNotEmpty() }
            .map { (name, uris) ->
                GenreItem(
                    name = name,
                    count = uris.size,
                    imageUrl = genreMap[name]?.firstOrNull()?.imageUrl
                        ?: uris.firstNotNullOfOrNull { artistByUri[it]?.imageUrl }
                )
            }
            .sortedByDescending { it.count }

        return Triple(genreItems, genreArtists, strictGenreArtists)
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun loadFavoriteArtistKeys(limit: Int = 500): Set<String> = try {
        musicRepository.getArtists(
            orderBy = "play_count_desc",
            limit = limit,
            favoriteOnly = true
        )
            .asSequence()
            .filter { it.favorite }
            .mapNotNull { it.canonicalKey() }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun loadFavoriteAlbumKeys(limit: Int = 500): Set<String> = try {
        musicRepository.getAlbums(
            orderBy = "play_count_desc",
            limit = limit,
            favoriteOnly = true
        )
            .asSequence()
            .filter { it.favorite }
            .mapNotNull { it.canonicalKey() }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }
}
