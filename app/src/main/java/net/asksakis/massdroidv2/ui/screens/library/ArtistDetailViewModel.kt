package net.asksakis.massdroidv2.ui.screens.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.lastfm.LastFmArtistInfoResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmSimilarResolver
import net.asksakis.massdroidv2.data.util.ProviderHealthReporter
import net.asksakis.massdroidv2.data.util.mapMaBounded
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.recommendation.MediaIdentity
import net.asksakis.massdroidv2.domain.recommendation.normalizeGenre
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject

private const val TAG = "LibraryVM"

/**
 * Per-call timeout for the MA RPCs that resolve a Last.fm similar-artist name to a playable
 * MA artist. `music/search` gathers every provider server-side with no per-provider timeout, so
 * a single slow/throttled provider can hang the call for minutes. We cap it short and degrade.
 */
private const val SIMILAR_RESOLVE_TIMEOUT_MS = 7_000L
// "Top Tracks" is a highlights section, not the full catalogue: cap it (artist_tracks can return
// hundreds). The artist's albums/discography cover the rest. Play-all uses this same capped set.
private const val ARTIST_TOP_TRACKS_LIMIT = 20

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val lastFmSimilarResolver: LastFmSimilarResolver,
    private val lastFmArtistInfoResolver: LastFmArtistInfoResolver,
    private val lastFmGenreResolver: LastFmGenreResolver,
    private val dao: PlayHistoryDao,
    private val providerHealthReporter: ProviderHealthReporter
) : ViewModel() {

    val itemId: String = savedStateHandle["itemId"] ?: ""
    val provider: String = savedStateHandle["provider"] ?: ""

    private val _artist = MutableStateFlow<Artist?>(null)
    val artist: StateFlow<Artist?> = _artist.asStateFlow()

    private val _artistInLibrary = MutableStateFlow(false)
    val artistInLibrary: StateFlow<Boolean> = _artistInLibrary.asStateFlow()

    // Albums actually in the user's library for this artist (library:// items). Often empty on
    // MA 2.9+ for an artist added via favourites/plays without whole albums saved.
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    // The artist's full discography from the default provider (the MA web UI's "All albums").
    // Shown as a separate section so the user can tell library albums apart from the catalogue.
    private val _discographyAlbums = MutableStateFlow<List<Album>>(emptyList())
    val discographyAlbums: StateFlow<List<Album>> = _discographyAlbums.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _artistName = MutableStateFlow(savedStateHandle.get<String>("name") ?: "Artist")
    val artistName: StateFlow<String> = _artistName.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _blockedArtistUris = MutableStateFlow<Set<String>>(emptySet())
    val blockedArtistUris: StateFlow<Set<String>> = _blockedArtistUris.asStateFlow()

    private val _similarArtists = MutableStateFlow<List<Artist>>(emptyList())
    val similarArtists: StateFlow<List<Artist>> = _similarArtists.asStateFlow()

    val players = playerRepository.players

    init {
        viewModelScope.launch { loadData(lazy = true) }
        viewModelScope.launch {
            smartListeningRepository.blockedArtistUris.collect { _blockedArtistUris.value = it }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                dao.clearSimilarArtistResolved(_artistName.value.lowercase())
                _artist.value?.uri?.let { musicRepository.refreshItemByUri(it) }
                    ?: musicRepository.requestLibrarySync(force = true)
                loadData(lazy = false)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun loadData(lazy: Boolean) {
        try {
            var artist = musicRepository.getArtist(itemId, provider, lazy = lazy)
            // Immediately replace genres with cached Last.fm tags if available
            if (artist != null) {
                val cached = dao.getLastFmTags(artist.name)
                if (cached != null) {
                    val tags = cached.tags.split(",").filter { it.isNotBlank() }
                    if (tags.isNotEmpty()) artist = artist.copy(genres = tags)
                }
            }
            _artist.value = artist
            _albums.value = musicRepository.getArtistAlbums(itemId, provider)
            _tracks.value = musicRepository.getArtistTracks(itemId, provider).take(ARTIST_TOP_TRACKS_LIMIT)
            // For a library artist the list above is only the in-library albums (often empty on
            // MA 2.9+); load the full provider discography as a separate "Discography" section.
            // A provider artist's list above is already its full catalogue, so skip the dup.
            if (provider.equals("library", ignoreCase = true)) {
                _discographyAlbums.value = musicRepository.getArtistDiscography(itemId, provider)
            }

            _artist.value?.let { a ->
                if (a.name.isNotBlank()) _artistName.value = a.name
            } ?: run {
                if (_artistName.value == "Artist") {
                    _tracks.value.firstOrNull()?.artistNames?.let { _artistName.value = it }
                }
            }

            // Auto-refresh if artist has no real image (only imageproxy fallback)
            val hasRealImage = _artist.value?.imageUrl?.let {
                !it.contains("imageproxy") || it.contains("path=http")
            } ?: false
            if (lazy && !hasRealImage) {
                kotlinx.coroutines.delay(500)
                val refreshed = musicRepository.getArtist(itemId, provider, lazy = false)
                if (refreshed != null) {
                    _artist.value = refreshed
                    if (refreshed.name.isNotBlank()) _artistName.value = refreshed.name
                }
            }
            _artistInLibrary.value = _artist.value.isInLibrary()
        } catch (e: Exception) {
            Log.w(TAG, "Load artist detail failed: ${e.message}")
        }

        val name = _artistName.value
        if (name.isNotBlank()) {
            viewModelScope.launch { loadSimilarArtists(name) }
            if (_artist.value?.description.isNullOrBlank()) {
                viewModelScope.launch { loadLastFmBio(name) }
            }
            viewModelScope.launch { enrichArtistGenresFromLastFm(name) }
        }
    }

    private suspend fun loadSimilarArtists(artistName: String) {
        try {
            val similar = lastFmSimilarResolver.resolve(artistName, limit = 8)
            if (similar.isEmpty()) return
            val key = artistName.lowercase()
            val cached = dao.getSimilarArtists(key)
            val now = System.currentTimeMillis()
            val resolveTtl = 7 * 86_400_000L
            val sourceGenres = lastFmGenreResolver.resolve(artistName).toSet()

            // Resolve candidates in parallel but globally concurrency-capped (mapMaBounded),
            // so the artist + Discover bulk resolvers can't burst the shared WS pipeline. Each MA
            // call has a short timeout: a slow/throttled provider makes `music/search` hang (the
            // server gathers ALL providers), so without this the row hangs then comes back empty.
            // On timeout we degrade (skip that candidate, don't poison the cache) and flag the row
            // as unavailable so the UI can explain why instead of silently showing nothing.
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val resolved = similar.mapMaBounded { sim ->
                try {
                    val cachedRow = cached.firstOrNull { it.similarArtist == sim.name }
                    val resolvedAt = cachedRow?.resolvedAt
                    if (resolvedAt != null && now - resolvedAt < resolveTtl) {
                        cachedRow.resolvedUri?.let { uri ->
                            Artist(
                                itemId = cachedRow.resolvedItemId.orEmpty(),
                                provider = cachedRow.resolvedProvider.orEmpty(),
                                name = cachedRow.resolvedName.orEmpty(),
                                uri = uri,
                                imageUrl = cachedRow.resolvedImageUrl
                            )
                        }
                    } else {
                        val searchResult = withTimeoutOrNull(SIMILAR_RESOLVE_TIMEOUT_MS) {
                            musicRepository.search(sim.name, listOf(MediaType.ARTIST), limit = 5)
                        }
                        if (searchResult == null) {
                            timedOut.set(true)
                            null
                        } else {
                            val candidates = searchResult.artists.filter { it.name.equals(sim.name, ignoreCase = true) }
                            val matched = if (candidates.isEmpty()) {
                                null
                            } else if (sourceGenres.isEmpty()) {
                                candidates.firstOrNull()
                            } else {
                                candidates.firstNotNullOfOrNull { c ->
                                    val detail = withTimeoutOrNull(SIMILAR_RESOLVE_TIMEOUT_MS) {
                                        musicRepository.getArtist(c.itemId, c.provider)
                                    } ?: run { timedOut.set(true); return@firstNotNullOfOrNull null }
                                    val cGenres = detail.genres.map { normalizeGenre(it) }.toSet()
                                    when {
                                        cGenres.isEmpty() -> detail
                                        cGenres.any { it in sourceGenres } -> detail
                                        else -> null
                                    }
                                }
                            }
                            dao.updateSimilarArtistResolved(
                                sourceArtist = key, similarArtist = sim.name,
                                itemId = matched?.itemId, provider = matched?.provider,
                                name = matched?.name, imageUrl = matched?.imageUrl, uri = matched?.uri,
                                resolvedAt = now
                            )
                            matched
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Resolve similar '${sim.name}' failed: ${e.message}")
                    null
                }
            }.filterNotNull()
            _similarArtists.value = resolved
            if (resolved.isEmpty() && timedOut.get()) providerHealthReporter.reportSearchTimeout()
        } catch (e: Exception) {
            Log.w(TAG, "Load similar artists failed: ${e.message}")
        }
    }

    private suspend fun enrichArtistGenresFromLastFm(artistName: String) {
        try {
            val lastFmGenres = lastFmGenreResolver.resolve(artistName)
            if (lastFmGenres.isNotEmpty()) {
                _artist.update { current ->
                    current?.copy(genres = lastFmGenres)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Enrich artist genres failed: ${e.message}")
        }
    }

    private suspend fun loadLastFmBio(artistName: String) {
        try {
            val bio = lastFmArtistInfoResolver.resolve(artistName) ?: return
            _artist.update { it?.copy(description = bio) }
        } catch (e: Exception) {
            Log.w(TAG, "Load Last.fm bio failed: ${e.message}")
        }
    }

    fun playUri(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uri)
            } catch (e: Exception) {
                Log.w(TAG, "play failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun playOnPlayer(uri: String, playerId: String) {
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(playerId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(playerId, uri)
            } catch (e: Exception) {
                Log.w(TAG, "playOnPlayer failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun enqueue(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uri, option = "add")
            } catch (e: Exception) {
                Log.w(TAG, "enqueue failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun enqueueNext(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                musicRepository.playMedia(queueId, uri, option = "next")
            } catch (e: Exception) {
                Log.w(TAG, "enqueueNext failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun startRadio(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.RADIO_SMART)
                musicRepository.playMedia(queueId, uri, radioMode = true)
            } catch (e: Exception) {
                Log.w(TAG, "startRadio failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun quickPlay(uri: String) {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        viewModelScope.launch {
            try {
                playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                musicRepository.playMedia(queueId, uri, option = "replace")
            } catch (e: Exception) {
                Log.w(TAG, "quickPlay failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun playTrack(track: Track) = playUri(track.uri)

    fun playAllTracks(option: String = "replace") {
        val queueId = playerRepository.requireSelectedPlayerId() ?: return
        val uris = _tracks.value
            .filter { t ->
                val uri = t.artistUri ?: return@filter true
                val name = t.artistNames.split(",").firstOrNull()?.trim().orEmpty()
                !playerRepository.isArtistBlocked(name, uri)
            }
            .map { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            try {
                if (option == "replace") {
                    playerRepository.setQueueFilterMode(queueId, PlayerRepository.QueueFilterMode.NORMAL)
                }
                musicRepository.playMedia(queueId, uris, option = option)
            } catch (e: Exception) {
                Log.w(TAG, "playAllTracks failed: ${e.message}")
                _error.tryEmit("Not connected to server")
            }
        }
    }

    fun toggleArtistFavorite() {
        val a = _artist.value ?: return
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(a.uri, MediaType.ARTIST, a.itemId, !a.favorite)
                _artist.update { it?.copy(favorite = !a.favorite) }
            } catch (e: Exception) {
                Log.w(TAG, "toggleArtistFavorite failed: ${e.message}")
            }
        }
    }

    fun toggleFavorite(uri: String, mediaType: MediaType, itemId: String, currentFavorite: Boolean) {
        viewModelScope.launch {
            try {
                musicRepository.setFavorite(uri, mediaType, itemId, !currentFavorite)
                when (mediaType) {
                    MediaType.ALBUM -> _albums.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    MediaType.TRACK -> _tracks.update { list ->
                        list.map { if (it.itemId == itemId) it.copy(favorite = !currentFavorite) else it }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
            }
        }
    }

    fun toggleArtistLibrary() {
        val a = _artist.value ?: return
        val inLibrary = _artistInLibrary.value
        viewModelScope.launch {
            try {
                if (inLibrary) {
                    musicRepository.removeFromLibrary(MediaType.ARTIST, a.uri, a.itemId)
                } else {
                    musicRepository.addToLibrary(a.uri)
                }
                _artistInLibrary.value = !inLibrary
            } catch (e: Exception) {
                Log.w(TAG, "toggleArtistLibrary failed: ${e.message}")
            }
        }
    }

    fun toggleLibrary(uri: String, mediaType: MediaType, itemId: String, currentlyInLibrary: Boolean) {
        viewModelScope.launch {
            try {
                if (currentlyInLibrary) {
                    musicRepository.removeFromLibrary(mediaType, uri, itemId)
                } else {
                    musicRepository.addToLibrary(uri)
                }
            } catch (e: Exception) {
                Log.w(TAG, "toggleLibrary failed: ${e.message}")
            }
        }
    }

    fun toggleArtistBlocked(artistUri: String?, artistName: String?) {
        val uri = MediaIdentity.canonicalArtistKey(uri = artistUri) ?: return
        viewModelScope.launch {
            val blocked = _blockedArtistUris.value.contains(uri)
            smartListeningRepository.setArtistBlocked(uri, artistName, blocked = !blocked)
        }
    }
}
