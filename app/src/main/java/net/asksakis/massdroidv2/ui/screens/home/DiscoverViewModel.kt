package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSectionBuilder
import net.asksakis.massdroidv2.domain.recommendation.RecommendationEngine
import net.asksakis.massdroidv2.domain.recommendation.ScoredAlbum
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject

private const val TAG = "DiscoverVM"

data class GenreItem(
    val name: String,
    val count: Int,
    val imageUrl: String?
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val playHistoryRepository: PlayHistoryRepository,
    private val wsClient: MaWebSocketClient,
    private val discoverCache: DiscoverCache,
    private val recommendationEngine: RecommendationEngine,
    private val sectionBuilder: DiscoverSectionBuilder
) : ViewModel() {

    private val _sections = MutableStateFlow<List<DiscoverSection>>(emptyList())
    val sections: StateFlow<List<DiscoverSection>> = _sections.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _radioOverlayGenre = MutableStateFlow<String?>(null)
    val radioOverlayGenre: StateFlow<String?> = _radioOverlayGenre.asStateFlow()

    private var genreArtists = mapOf<String, List<String>>()
    private var cacheStale = true

    init {
        autoConnect()
        viewModelScope.launch {
            loadFromCache()
            observeConnection()
        }
    }

    private fun autoConnect() {
        viewModelScope.launch {
            if (wsClient.connectionState.value is ConnectionState.Disconnected) {
                val url = settingsRepository.serverUrl.first()
                val token = settingsRepository.authToken.first()
                if (url.isNotBlank() && token.isNotBlank()) {
                    wsClient.connect(url, token)
                }
            }
        }
    }

    private suspend fun loadFromCache() {
        val cached = discoverCache.load() ?: return
        val genreItems = buildGenreItemsFromArtists(cached.topArtists)
        val bllScores = try {
            playHistoryRepository.getScoredGenres(days = 90, limit = 20)
        } catch (_: Exception) {
            emptyList()
        }
        _sections.value = sectionBuilder.buildSections(
            serverFolders = cached.serverFolders,
            suggestedArtists = cached.suggestedArtists,
            suggestedAlbums = cached.discoverAlbums,
            genreItems = genreItems,
            bllGenreScores = bllScores
        )
        _isLoading.value = false
        cacheStale = discoverCache.isStale(cached)
    }

    private suspend fun observeConnection() {
        wsClient.connectionState.collect { state ->
            if (state is ConnectionState.Connected) {
                loadFromServer()
            }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        val connState = wsClient.connectionState.value
        Log.d(TAG, "refresh() called, connection=$connState")
        viewModelScope.launch {
            if (connState is ConnectionState.Connected) {
                loadFromServer(isManualRefresh = true)
            } else {
                Log.d(TAG, "refresh() skipped: not connected")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadFromServer(isManualRefresh: Boolean = false) {
        if (!isManualRefresh && !cacheStale && _sections.value.isNotEmpty()) {
            Log.d(TAG, "loadFromServer: skipped (cache fresh)")
            return
        }
        viewModelScope.launch {
            Log.d(TAG, "loadFromServer: starting (manual=$isManualRefresh)")
            if (isManualRefresh) _isRefreshing.value = true
            try {
                val artistsDef = async { loadLibraryArtists() }
                val randomArtistsDef = async { loadRandomArtists() }
                val recsDef = async { loadRecommendations() }

                val artists = artistsDef.await()
                val randomArtists = randomArtistsDef.await()
                val serverFolders = recsDef.await()

                val merged = buildList {
                    if (artists != null) addAll(artists)
                    if (randomArtists != null) addAll(randomArtists)
                }.distinctBy { it.uri }
                Log.d(TAG, "Merged ${merged.size} library artists, ${serverFolders.size} server folders")

                val genreItems = buildGenreItemsFromArtists(merged)

                val suggested = try {
                    buildSuggestedArtists(merged)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to build suggested artists", e)
                    merged.shuffled().take(10)
                }
                Log.d(TAG, "Suggested artists: ${suggested.size}")

                val discover = loadDiscoverAlbums(merged)

                val bllGenreScores = try {
                    playHistoryRepository.getScoredGenres(days = 90, limit = 20)
                } catch (_: Exception) {
                    emptyList()
                }

                _sections.value = sectionBuilder.buildSections(
                    serverFolders = serverFolders,
                    suggestedArtists = suggested,
                    suggestedAlbums = discover ?: emptyList(),
                    genreItems = genreItems,
                    bllGenreScores = bllGenreScores
                )
                Log.d(TAG, "Built ${_sections.value.size} sections")

                cacheStale = false
                discoverCache.save(
                    DiscoverCache.CacheData(
                        suggestedArtists = suggested,
                        discoverAlbums = discover ?: emptyList(),
                        topArtists = artists ?: emptyList(),
                        serverFolders = serverFolders
                    )
                )
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun buildSuggestedArtists(libraryArtists: List<Artist>): List<Artist> {
        val genreScores = playHistoryRepository.getScoredGenres(days = 90, limit = 20)
        val artistScores = playHistoryRepository.getScoredArtists(days = 90, limit = 50)
        val genreAdjacency = playHistoryRepository.getGenreAdjacencyMap()

        Log.d(TAG, "BLL: ${genreScores.size} genres, ${artistScores.size} artists, ${genreAdjacency.size} adjacency")
        if (genreScores.isNotEmpty()) {
            Log.d(TAG, "Top genres: ${genreScores.take(3).map { "${it.genre}=${String.format("%.2f", it.score)}" }}")
        }

        if (genreScores.isEmpty()) return libraryArtists.shuffled().take(10)

        val recentlyAdded = try {
            musicRepository.getArtists(orderBy = "recently_added", limit = 20)
        } catch (_: Exception) {
            emptyList()
        }

        return recommendationEngine.buildSuggestedArtists(
            candidates = libraryArtists,
            genreScores = genreScores,
            artistScores = artistScores,
            genreAdjacency = genreAdjacency,
            recentlyAdded = recentlyAdded,
            count = 10
        )
    }

    private fun buildGenreItemsFromArtists(artists: List<Artist>): List<GenreItem> {
        val genreMap = mutableMapOf<String, MutableList<Artist>>()
        for (artist in artists) {
            for (genre in artist.genres) {
                genreMap.getOrPut(genre) { mutableListOf() }.add(artist)
            }
        }
        genreArtists = genreMap.mapValues { (_, artistList) ->
            artistList.map { it.uri }
        }
        return genreMap
            .map { (name, artistList) ->
                GenreItem(
                    name = name,
                    count = artistList.size,
                    imageUrl = artistList.firstOrNull()?.imageUrl
                )
            }
            .sortedByDescending { it.count }
            .take(10)
    }

    private suspend fun loadLibraryArtists(): List<Artist>? = try {
        musicRepository.getArtists(orderBy = "play_count_desc", limit = 200)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load library artists", e)
        null
    }

    private suspend fun loadRandomArtists(): List<Artist>? = try {
        musicRepository.getArtists(orderBy = "random", limit = 200)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load random artists", e)
        null
    }

    private suspend fun loadRecommendations(): List<RecommendationFolder> = try {
        musicRepository.getRecommendations()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load recommendations", e)
        emptyList()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadDiscoverAlbums(libraryArtists: List<Artist>): List<Album>? = try {
        val artistScores = playHistoryRepository.getScoredArtists(days = 90, limit = 10)
        val genreScores = playHistoryRepository.getScoredGenres(days = 90, limit = 10)

        if (artistScores.isEmpty()) {
            musicRepository.getAlbums(orderBy = "random", limit = 10)
        } else {
            val recentAlbumUris = playHistoryRepository.getRecentAlbums(limit = 50)
                .map { it.albumUri }.toSet()
            val topArtistUris = artistScores.map { it.artistUri }.toSet()

            val artistGenreMap = libraryArtists.associate { it.uri to it.genres.toSet() }
            val genreScoreMap = genreScores.associate { it.genre to it.score }

            val allCandidateAlbums = mutableListOf<ScoredAlbum>()

            coroutineScope {
                val artistDefs = artistScores.take(5).mapNotNull { score ->
                    parseMediaUri(score.artistUri)?.let { (provider, itemId) ->
                        async {
                            try {
                                val albums = musicRepository.getArtistAlbums(itemId, provider)
                                val genres = artistGenreMap[score.artistUri].orEmpty()
                                albums.filter { it.uri !in recentAlbumUris && it.albumType != "single" }.map { album ->
                                    ScoredAlbum(
                                        album = album,
                                        genres = genres,
                                        relevance = genres.sumOf { g -> genreScoreMap[g] ?: 0.0 }
                                    )
                                }
                            } catch (_: Exception) {
                                emptyList<ScoredAlbum>()
                            }
                        }
                    }
                }
                for (def in artistDefs) {
                    allCandidateAlbums.addAll(def.await())
                }

                val topGenreNames = genreScores.map { it.genre }.toSet()
                val discoveryArtists = libraryArtists
                    .filter { it.uri !in topArtistUris }
                    .filter { it.genres.any { g -> g in topGenreNames } }
                    .shuffled()
                    .take(5)

                val genreDefs = discoveryArtists.map { artist ->
                    async {
                        try {
                            val albums = musicRepository.getArtistAlbums(artist.itemId, artist.provider)
                            val genres = artist.genres.toSet()
                            albums.filter { it.uri !in recentAlbumUris && it.albumType != "single" }.map { album ->
                                ScoredAlbum(
                                    album = album,
                                    genres = genres,
                                    relevance = genres.sumOf { g -> genreScoreMap[g] ?: 0.0 }
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

            // Deduplicate and cap per artist to ensure diversity
            val uniqueCandidates = allCandidateAlbums.distinctBy { it.album.uri }
            val cappedCandidates = uniqueCandidates
                .groupBy { it.album.artistNames }
                .flatMap { (_, albums) -> albums.shuffled().take(1) }
            val ranked = recommendationEngine.rankAlbumsForDiscovery(cappedCandidates, count = 10)

            val usedUris = ranked.mapTo(mutableSetOf()) { it.uri }
            usedUris.addAll(recentAlbumUris)
            val remaining = 10 - ranked.size
            val result = ranked.toMutableList()
            if (remaining > 0) {
                val randomAlbums = musicRepository.getAlbums(orderBy = "random", limit = 20)
                    .filter { it.uri !in usedUris }
                    .take(remaining)
                result.addAll(randomAlbums)
            }

            result.distinctBy { it.uri }.shuffled().take(10).ifEmpty {
                musicRepository.getAlbums(orderBy = "random", limit = 10)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load discover albums", e)
        null
    }

    fun startGenreRadio(genre: String) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        val uris = genreArtists[genre]?.shuffled() ?: return
        if (uris.isEmpty()) return
        _radioOverlayGenre.value = genre
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uris, radioMode = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start genre radio", e)
            }
            delay(2500)
            _radioOverlayGenre.value = null
        }
    }

    fun playTrack(track: Track) {
        val queueId = playerRepository.selectedPlayer.value?.playerId ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, track.uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play track", e)
            }
        }
    }

    private fun parseMediaUri(uri: String): Pair<String, String>? {
        val sep = uri.indexOf("://")
        if (sep < 0) return null
        val provider = uri.substring(0, sep)
        val itemId = uri.substringAfterLast("/")
        return if (provider.isNotBlank() && itemId.isNotBlank()) provider to itemId else null
    }
}
