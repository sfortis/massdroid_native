package net.asksakis.massdroidv2.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.RecommendationItems
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
private const val MIN_SECTION_ITEMS = 3
private const val RECENT_FAVORITE_TRACKS_FOLDER_ID = "recent_favorite_tracks"
private const val RECENT_FAVORITE_ALBUMS_FOLDER_ID = "recent_favorite_albums"
private const val RECENTLY_ADDED_TRACKS_FOLDER_ID = "recently_added_tracks"
private const val RECENT_FAVORITE_ALBUMS_TITLE = "Recent Favorite Albums"
private const val RECENT_FAVORITE_TRACKS_TITLE = "Recent Favorite Tracks"
private const val RECENTLY_ADDED_TRACKS_TITLE = "Recently Added Tracks"
private const val RECENT_TRACKS_QUERY_FACTOR = 5

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
    private var mediaEventJob: Job? = null
    private var fullLoadJob: Job? = null
    private var loadGeneration = 0L

    init {
        autoConnect()
        viewModelScope.launch {
            loadFromCache()
            observeConnection()
        }
        viewModelScope.launch {
            observeMediaEvents()
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
        val historyGenreArtists = try {
            playHistoryRepository.getGenreArtistMap()
        } catch (_: Exception) {
            emptyMap()
        }
        val genreItems = buildGenreItemsFromArtists(cached.topArtists, historyGenreArtists)
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

    private suspend fun observeMediaEvents() {
        wsClient.events.collect { event ->
            when (event.event) {
                EventType.MEDIA_ITEM_UPDATED -> {
                    mediaEventJob?.cancel()
                    mediaEventJob = viewModelScope.launch {
                        delay(350)
                        if (wsClient.connectionState.value is ConnectionState.Connected) {
                            when (eventMediaType(event)) {
                                "track" -> refreshRecentFavoriteTracksSection()
                                "album" -> refreshFavoriteAlbumsAndRecentlyAddedTracksSections()
                                else -> {
                                    cacheStale = true
                                    loadFromServer()
                                }
                            }
                        }
                    }
                }
                EventType.MEDIA_ITEM_ADDED,
                EventType.MEDIA_ITEM_DELETED -> {
                    mediaEventJob?.cancel()
                    mediaEventJob = viewModelScope.launch {
                        delay(500)
                        if (wsClient.connectionState.value is ConnectionState.Connected) {
                            refreshFavoriteAlbumsAndRecentlyAddedTracksSections()
                        }
                    }
                }
            }
        }
    }

    private fun eventMediaType(event: net.asksakis.massdroidv2.data.websocket.ServerEvent): String? {
        val root = event.data?.jsonObject ?: return null
        val direct = root["media_type"]?.jsonPrimitive?.contentOrNull
        if (!direct.isNullOrBlank()) return direct.lowercase()

        val nested = root["media_item"]?.jsonObject?.get("media_type")?.jsonPrimitive?.contentOrNull
        if (!nested.isNullOrBlank()) return nested.lowercase()

        return null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshRecentFavoriteTracksSection() {
        val freshTracks = loadRecentFavoriteTracks()

        _sections.value = reorderHomeSections(
            upsertTrackSection(
                current = _sections.value,
                title = RECENT_FAVORITE_TRACKS_TITLE,
                tracks = freshTracks
            )
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshFavoriteAlbumsAndRecentlyAddedTracksSections() {
        val freshAlbums = loadRecentFavoriteAlbums()
        val freshTracks = loadRecentlyAddedTracks()

        val withAlbums = upsertAlbumSection(
            current = _sections.value,
            title = RECENT_FAVORITE_ALBUMS_TITLE,
            albums = freshAlbums
        )
        val withTracks = upsertTrackSection(
            current = withAlbums,
            title = RECENTLY_ADDED_TRACKS_TITLE,
            tracks = freshTracks
        )
        _sections.value = reorderHomeSections(withTracks)
    }

    private fun upsertTrackSection(
        current: List<DiscoverSection>,
        title: String,
        tracks: List<Track>
    ): List<DiscoverSection> {
        val currentIndex = current.indexOfFirst {
            it is DiscoverSection.TrackSection && it.title == title
        }
        val hasEnoughItems = tracks.size >= MIN_SECTION_ITEMS

        return when {
            currentIndex >= 0 && hasEnoughItems -> {
                current.toMutableList().apply {
                    this[currentIndex] = DiscoverSection.TrackSection(title = title, tracks = tracks)
                }
            }
            currentIndex >= 0 && !hasEnoughItems -> {
                current.toMutableList().apply { removeAt(currentIndex) }
            }
            currentIndex < 0 && hasEnoughItems -> {
                current.toMutableList().apply {
                    add(DiscoverSection.TrackSection(title = title, tracks = tracks))
                }
            }
            else -> current
        }
    }

    private fun upsertAlbumSection(
        current: List<DiscoverSection>,
        title: String,
        albums: List<Album>
    ): List<DiscoverSection> {
        val currentIndex = current.indexOfFirst {
            it is DiscoverSection.AlbumSection && it.title == title
        }
        val hasEnoughItems = albums.size >= MIN_SECTION_ITEMS

        return when {
            currentIndex >= 0 && hasEnoughItems -> {
                current.toMutableList().apply {
                    this[currentIndex] = DiscoverSection.AlbumSection(title = title, albums = albums)
                }
            }
            currentIndex >= 0 && !hasEnoughItems -> {
                current.toMutableList().apply { removeAt(currentIndex) }
            }
            currentIndex < 0 && hasEnoughItems -> {
                current.toMutableList().apply {
                    add(DiscoverSection.AlbumSection(title = title, albums = albums))
                }
            }
            else -> current
        }
    }

    private fun reorderHomeSections(sections: List<DiscoverSection>): List<DiscoverSection> {
        return sections
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<DiscoverSection>>(
                    { sectionPriority(it.value) },
                    { it.index }
                )
            )
            .map { it.value }
    }

    private fun sectionPriority(section: DiscoverSection): Int {
        return when (section) {
            is DiscoverSection.GenreRadioSection -> 0
            is DiscoverSection.AlbumSection ->
                when (section.title) {
                    "Albums You Might Like" -> 1
                    RECENT_FAVORITE_ALBUMS_TITLE -> 3
                    else -> 100
                }
            is DiscoverSection.ArtistSection ->
                if (section.title == "Artists You Might Like") 2 else 100
            is DiscoverSection.TrackSection ->
                when (section.title) {
                    RECENT_FAVORITE_TRACKS_TITLE -> 4
                    RECENTLY_ADDED_TRACKS_TITLE -> 5
                    else -> 100
                }
            is DiscoverSection.PlaylistSection -> 100
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
        val generation = ++loadGeneration
        fullLoadJob?.cancel()
        fullLoadJob = viewModelScope.launch {
            Log.d(TAG, "loadFromServer: starting (manual=$isManualRefresh)")
            if (isManualRefresh) _isRefreshing.value = true
            try {
                val artistsDef = async { loadLibraryArtists() }
                val randomArtistsDef = async { loadRandomArtists() }
                val favoriteAlbumsDef = async { loadRecentFavoriteAlbums() }
                val recsDef = async { loadRecommendations() }

                val artists = artistsDef.await()
                val randomArtists = randomArtistsDef.await()
                val recentFavoriteAlbums = favoriteAlbumsDef.await()
                val serverFolders = recsDef.await()
                val enrichedFolders = mergeFavoriteAlbumsFolder(serverFolders, recentFavoriteAlbums)

                val merged = buildList {
                    if (artists != null) addAll(artists)
                    if (randomArtists != null) addAll(randomArtists)
                }.distinctBy { it.uri }
                Log.d(TAG, "Merged ${merged.size} library artists, ${serverFolders.size} server folders")

                val historyGenreArtists = try {
                    playHistoryRepository.getGenreArtistMap()
                } catch (_: Exception) {
                    emptyMap()
                }
                val genreItems = buildGenreItemsFromArtists(merged, historyGenreArtists)

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
                if (generation != loadGeneration) return@launch

                _sections.value = sectionBuilder.buildSections(
                    serverFolders = enrichedFolders,
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
                        serverFolders = enrichedFolders
                    )
                )
            } finally {
                if (generation == loadGeneration) {
                    _isLoading.value = false
                    _isRefreshing.value = false
                }
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

    private fun buildGenreItemsFromArtists(
        artists: List<Artist>,
        historyGenreArtists: Map<String, List<String>> = emptyMap()
    ): List<GenreItem> {
        val genreMap = mutableMapOf<String, MutableList<Artist>>()
        for (artist in artists) {
            for (genre in artist.genres) {
                genreMap.getOrPut(genre) { mutableListOf() }.add(artist)
            }
        }
        // Merge server artists with Room DB artists (distinct URIs per genre)
        val serverUris = genreMap.mapValues { (_, artistList) ->
            artistList.map { it.uri }
        }
        genreArtists = buildMap {
            val allGenres = serverUris.keys + historyGenreArtists.keys
            for (genre in allGenres) {
                val merged = buildList {
                    addAll(serverUris[genre].orEmpty())
                    addAll(historyGenreArtists[genre].orEmpty())
                }.distinct()
                put(genre, merged)
            }
        }
        for ((genre, uris) in genreArtists.entries.sortedByDescending { it.value.size }.take(10)) {
            val serverCount = serverUris[genre]?.size ?: 0
            val historyCount = historyGenreArtists[genre]?.size ?: 0
            Log.d(TAG, "Genre '$genre': ${uris.size} artists (server=$serverCount, history=$historyCount)")
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
        musicRepository.getArtists(orderBy = "play_count_desc", limit = 500)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load library artists", e)
        null
    }

    private suspend fun loadRandomArtists(): List<Artist>? = try {
        musicRepository.getArtists(orderBy = "random", limit = 500)
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
    private suspend fun loadRecentFavoriteAlbums(limit: Int = 10): List<Album> {
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
            .distinctBy { it.uri }
            .take(limit)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadRecentFavoriteTracks(limit: Int = 10): List<Track> {
        val favoritesByAdded = try {
            musicRepository.getTracks(
                orderBy = "timestamp_added_desc",
                limit = limit * RECENT_TRACKS_QUERY_FACTOR,
                favoriteOnly = true
            )
        } catch (_: Exception) {
            emptyList()
        }

        return favoritesByAdded
            .filter { it.favorite }
            .distinctBy { it.uri }
            .take(limit)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadRecentlyAddedTracks(limit: Int = 10): List<Track> {
        val recentTracks = try {
            musicRepository.getTracks(
                orderBy = "timestamp_added_desc",
                limit = limit * 2
            )
        } catch (_: Exception) {
            emptyList()
        }

        return recentTracks
            .distinctBy { it.uri }
            .take(limit)
    }

    private fun mergeFavoriteAlbumsFolder(
        serverFolders: List<RecommendationFolder>,
        recentFavoriteAlbums: List<Album>
    ): List<RecommendationFolder> {
        val withoutFavoriteAlbums = serverFolders.filterNot {
            it.provider == "library" && it.itemId == RECENT_FAVORITE_ALBUMS_FOLDER_ID
        }
        if (recentFavoriteAlbums.size < MIN_SECTION_ITEMS) return withoutFavoriteAlbums

        return withoutFavoriteAlbums + RecommendationFolder(
            itemId = RECENT_FAVORITE_ALBUMS_FOLDER_ID,
            name = RECENT_FAVORITE_ALBUMS_TITLE,
            provider = "library",
            items = RecommendationItems(albums = recentFavoriteAlbums)
        )
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
        Log.d(TAG, "startGenreRadio: genre='$genre', ${uris.size} artist URIs: $uris")
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
