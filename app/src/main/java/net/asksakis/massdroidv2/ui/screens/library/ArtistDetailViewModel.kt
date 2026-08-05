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
private const val LIBRARY_PROVIDER = "library"
private const val SIMILAR_ARTIST_LIMIT = 8

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val smartListeningRepository: SmartListeningRepository,
    private val musicBrainzGenreResolver: net.asksakis.massdroidv2.data.musicbrainz.MusicBrainzGenreResolver,
    private val artistBioResolver: net.asksakis.massdroidv2.data.musicbrainz.ArtistBioResolver,
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
            val artist = musicRepository.getArtist(itemId, provider, lazy = lazy)
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

            // Auto-refresh only if the artist has no loadable image. The canonical MA 2.9 proxy_id
            // route (/imageproxy/<id>) and any direct/remote URL are real images; only the legacy
            // /imageproxy?path=<non-http> URI fallback (placeholder-grade, 400 on 2.9+) counts as "no
            // real image". The old check treated every /imageproxy URL as fake, so it refetched almost
            // every artist on 2.9+ (proxy_id images live at /imageproxy/<id>).
            val hasRealImage = _artist.value?.imageUrl?.let { url ->
                val legacyUriFallback = url.contains("imageproxy?path=") && !url.contains("path=http")
                !legacyUriFallback
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
            viewModelScope.launch { loadSimilarArtists(itemId, provider) }
            viewModelScope.launch { enrichArtistGenres(name) }
            // Music Assistant only describes LIBRARY artists, so almost everything
            // opened from a provider arrived with an empty screen. This fills it
            // with no API key: MusicBrainz by name, then the article it links to.
            if (_artist.value?.description.isNullOrBlank()) {
                viewModelScope.launch { loadArtistBio(name) }
            }
        }
    }

    /**
     * Similar artists, straight from Music Assistant.
     *
     * This used to ask Last.fm for names and then search every provider for
     * each one, keeping a resolution cache and validating matches by genre
     * overlap because a name match is not an identity match. Music Assistant
     * answers with playable items, so all of that is gone - along with the API
     * key the listener had to create for it. The answers are Last.fm's, fetched
     * with Music Assistant's own built-in key.
     *
     * Only a library item answers: a provider item returns nothing (measured:
     * 25 against 0 for the same artist). `get_artist` on a provider item
     * resolves to the library one, which is what makes this work either way.
     */
    private suspend fun loadSimilarArtists(itemId: String, provider: String) {
        try {
            val resolved = if (provider == LIBRARY_PROVIDER) {
                itemId to provider
            } else {
                val artist = musicRepository.getArtist(itemId, provider) ?: return
                val uri = artist.uri
                val p = uri.substringBefore("://", "").ifBlank { artist.provider }
                val id = uri.substringAfter("://", "").trim('/').substringAfterLast('/')
                    .ifBlank { artist.itemId }
                id to p
            }
            val similar = musicRepository
                .getSimilarArtists(resolved.first, resolved.second, limit = SIMILAR_ARTIST_LIMIT)
                .filter { it.imageUrl != null }
            _similarArtists.value = similar
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Load similar artists failed: ${e.message}")
        }
    }

    private suspend fun loadArtistBio(artistName: String) {
        try {
            val bio = artistBioResolver.resolve(artistName, _artist.value?.mbid) ?: return
            // Only fill a still-empty description: a server answer that arrived
            // meanwhile is the better source.
            _artist.update { current ->
                if (current == null || !current.description.isNullOrBlank()) current
                else current.copy(description = bio)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Load artist bio failed: ${e.message}")
        }
    }

    private suspend fun enrichArtistGenres(artistName: String) {
        try {
            val lastFmGenres = musicBrainzGenreResolver.resolve(artistName, _artist.value?.mbid)
            if (lastFmGenres.isNotEmpty()) {
                _artist.update { current ->
                    current?.copy(genres = lastFmGenres)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Enrich artist genres failed: ${e.message}")
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
