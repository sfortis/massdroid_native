package net.asksakis.massdroidv2.domain.discover

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.cache.DiscoverCache
import net.asksakis.massdroidv2.data.genre.GenreRepository
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.EventType
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.data.websocket.mediaType
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.GenreItem
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverContentLoader
import net.asksakis.massdroidv2.domain.recommendation.DiscoverFeedOrchestrator
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import net.asksakis.massdroidv2.domain.recommendation.MixPlaybackOrchestrator
import net.asksakis.massdroidv2.domain.recommendation.canonicalKey
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiscoverStore"
private const val LOAD_COOLDOWN_MS = 30_000L
private const val RECENT_TRACKS_QUERY_FACTOR = 5
private const val RECENT_FAVORITE_TRACKS_LIMIT = 10
// A media event is often one of a burst (a favourite toggled on several items, a sync); the
// section refresh waits this long so the burst collapses into one refresh.
private const val TRACK_EVENT_SETTLE_MS = 350L
private const val ALBUM_EVENT_SETTLE_MS = 500L

/**
 * The Discover feed shared by every screen that shows it: the phone's Home, the TV's Home and
 * the Android Auto Discover tab. One process builds the feed once, whoever asks for it.
 *
 * Nothing is built until a consumer calls [activate], so a process started only for a widget or
 * for Follow Me does not run the network-heavy build. After activation the store shows the last
 * rendered feed from [DiscoverCache] at once. It builds only while a screen observes [state],
 * or when Android Auto asks through [awaitFeed]: a reconnect or a media event with nobody
 * watching only marks the feed stale, and the next viewer rebuilds it. The first build of the
 * process skips the freshness check; later ones run when the feed is stale and the last build is
 * older than [LOAD_COOLDOWN_MS]. Media events replace the favourite sections in place.
 *
 * Every result is applied only if no account switch happened since it was started, so nothing
 * of a previous account reaches the screen, the mixes or the disk cache. A build that comes back
 * empty (the connection dropped while the library was read) keeps the feed already shown.
 *
 * The library maps of the last build are kept for the mixes ([libraryContext]), so Smart Mix
 * and Genre Radio do not load the library a second time.
 */
@Singleton
class DiscoverFeedStore @Inject constructor(
    private val feedOrchestrator: DiscoverFeedOrchestrator,
    private val discoverCache: DiscoverCache,
    private val wsClient: MaWebSocketClient,
    private val musicRepository: MusicRepository,
    private val genreRepository: GenreRepository,
    private val sessionEventBus: SessionEventBus,
) {

    data class State(
        val sections: List<DiscoverSection> = emptyList(),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val contentLoader = DiscoverContentLoader(musicRepository, genreRepository)
    private val sectionCoordinator = DiscoverSectionCoordinator()
    private val activated = AtomicBoolean(false)
    // Completed once the disk cache has been read (or found missing), so an early caller does
    // not mistake the not-yet-loaded feed for an empty one.
    private val cacheLoaded = CompletableDeferred<Unit>()

    // Bookkeeping shared by the connection collector, the media events, a manual refresh and
    // Android Auto, which run on different threads. Every read and write goes through [lock],
    // and so does every write of a result, so a check and the write it guards cannot be split.
    private val lock = Any()
    // Bumped by every account switch; a result started under another session is dropped.
    private var session = 0L
    // Bumped by every full build; only the latest build may write its result.
    private var loadGeneration = 0L
    private var fullLoadJob: Job? = null
    private var mediaEventJob: Job? = null
    private var cacheStale = true
    private var lastSuccessfulLoadAt = 0L
    // The first build of the process (and of a new account) skips the freshness check, so the
    // time-sensitive sections are live; it waits for a viewer like every other build.
    private var firstBuildPending = true
    private var libraryContext: MixPlaybackOrchestrator.LibraryContext? = null
    // Every genre of the last build by display name, for the car's full Genre Radio list.
    private var genreItems = emptyList<GenreItem>()
    // The library artists of the last build, saved with the sections so the media event
    // refreshes can save the cache again without rebuilding them.
    private var topArtists = emptyList<Artist>()

    /** Starts the store for this process. Every consumer calls it; only the first call acts. */
    fun activate() {
        if (!activated.compareAndSet(false, true)) return
        scope.launch {
            try {
                loadFromCache()
            } finally {
                cacheLoaded.complete(Unit)
            }
            launch { observeViewers() }
            observeConnection()
        }
        scope.launch { observeMediaEvents() }
        scope.launch { sessionEventBus.resets.collect { reset() } }
    }

    /** A pull to refresh: asks MA to sync its library, then rebuilds the feed. */
    fun refresh() {
        if (wsClient.connectionState.value !is ConnectionState.Connected) {
            Log.d(TAG, "refresh skipped: not connected")
            return
        }
        synchronized(lock) {
            if (_state.value.isRefreshing) return
            _state.update { it.copy(isRefreshing = true) }
        }
        scope.launch {
            runCatching { musicRepository.requestLibrarySync(force = true) }
                .onFailure { if (it is CancellationException) throw it }
            load(manual = true)
        }
    }

    /**
     * The feed for a caller that needs it now, Android Auto browsing Discover. A feed older than
     * [maxAgeMs] is rebuilt first while MA is connected, and a build already running is waited
     * for, including one that replaced it meanwhile. Without a connection the last rendered feed
     * is returned, which may be empty.
     */
    suspend fun awaitFeed(maxAgeMs: Long): List<DiscoverSection> {
        activate()
        cacheLoaded.await()
        val connected = wsClient.connectionState.value is ConnectionState.Connected
        val tooOld = synchronized(lock) { System.currentTimeMillis() - lastSuccessfulLoadAt > maxAgeMs }
        var job = if (connected && tooOld) load(force = true) else runningLoad()
        while (job != null) {
            job.join()
            job = runningLoad()?.takeIf { it !== job }
        }
        return _state.value.sections
    }

    /** The library maps of the last build, or null before the first one. */
    fun libraryContext(): MixPlaybackOrchestrator.LibraryContext? = synchronized(lock) { libraryContext }

    /**
     * Every genre a Genre Radio can start from with the last build, by display name, or empty
     * before the first one. It is checked against the same maps [MixLauncher] checks a genre
     * against, so each entry starts a radio.
     */
    fun radioGenres(): List<GenreItem> = synchronized(lock) {
        val context = libraryContext ?: return@synchronized emptyList()
        genreItems.filter { context.hasArtistsForGenre(it.name) }
    }

    private fun runningLoad(): Job? = synchronized(lock) { fullLoadJob?.takeIf { it.isActive } }

    private fun hasViewers(): Boolean = _state.subscriptionCount.value > 0

    private suspend fun loadFromCache() {
        val startSession = synchronized(lock) { session }
        val cached = discoverCache.load()
        val genreData = cached?.topArtists?.takeIf { it.isNotEmpty() }?.let { genreDataFromCachedArtists(it) }
        synchronized(lock) {
            // A live build or an account switch got here first; the cache is older than both.
            if (session != startSession || lastSuccessfulLoadAt != 0L) return
            if (cached == null) {
                _state.update { it.copy(isLoading = false) }
                return
            }
            topArtists = cached.topArtists
            libraryContext = genreData?.first
            genreItems = genreData?.second.orEmpty()
            // Stale-while-revalidate: the last rendered feed shows at once and the first build
            // replaces it with a live one.
            _state.update { it.copy(sections = cached.sections, isLoading = false) }
            val hasMissingImages = cached.sections.any { section ->
                section is DiscoverSection.AlbumSection && section.albums.any { it.imageUrl == null }
            }
            cacheStale = discoverCache.isStale(cached) || hasMissingImages
        }
    }

    // The genre maps of a cached feed are rebuilt from its artists, so the mixes and the car's
    // genre list have them before the first live build. The genre tiles shown come from the
    // cached sections.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun genreDataFromCachedArtists(
        artists: List<Artist>,
    ): Pair<MixPlaybackOrchestrator.LibraryContext, List<GenreItem>> {
        val artistByUri = artists.mapNotNull { artist -> artist.canonicalKey()?.let { it to artist } }.toMap()
        val historyGenreArtists = try {
            genreRepository.genreArtistMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        val (items, genreArtists, strictGenreArtists) =
            contentLoader.buildGenreData(artists, historyGenreArtists, artistByUri)
        return MixPlaybackOrchestrator.LibraryContext(artistByUri, genreArtists, strictGenreArtists) to items
    }

    // A screen starting to watch is when a feed marked stale while nobody watched gets rebuilt.
    private suspend fun observeViewers() {
        _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { watched ->
            if (watched && wsClient.connectionState.value is ConnectionState.Connected) loadForViewer()
        }
    }

    private suspend fun observeConnection() {
        wsClient.connectionState.collect { state ->
            if (state !is ConnectionState.Connected) return@collect
            if (hasViewers()) {
                loadForViewer()
            } else {
                synchronized(lock) { cacheStale = true }
            }
        }
    }

    private fun loadForViewer() {
        val force = synchronized(lock) { firstBuildPending.also { firstBuildPending = false } }
        load(force = force)
    }

    private suspend fun observeMediaEvents() {
        wsClient.events.collect { event ->
            when (event.event) {
                EventType.MEDIA_ITEM_UPDATED -> replaceMediaEventJob(TRACK_EVENT_SETTLE_MS) { startSession ->
                    when (event.mediaType()) {
                        "track" -> refreshRecentFavoriteTracks(startSession)
                        "album" -> refreshRecentFavoriteAlbums(startSession)
                        else -> {
                            synchronized(lock) { cacheStale = true }
                            load()
                        }
                    }
                }
                EventType.MEDIA_ITEM_ADDED,
                EventType.MEDIA_ITEM_DELETED -> replaceMediaEventJob(ALBUM_EVENT_SETTLE_MS) { startSession ->
                    refreshRecentFavoriteAlbums(startSession)
                }
                else -> Unit
            }
        }
    }

    // With nobody watching, a media event only marks the feed stale for the next viewer.
    private fun replaceMediaEventJob(settleMs: Long, block: suspend (startSession: Long) -> Unit) {
        synchronized(lock) {
            mediaEventJob?.cancel()
            if (!hasViewers()) {
                cacheStale = true
                return
            }
            val startSession = session
            mediaEventJob = scope.launch {
                delay(settleMs)
                if (wsClient.connectionState.value is ConnectionState.Connected) block(startSession)
            }
        }
    }

    /**
     * Starts a full build unless one is not needed, and returns the build that is running, if
     * any. A manual refresh always builds; [force] skips the freshness and cooldown checks but
     * still joins a build already running.
     */
    private fun load(manual: Boolean = false, force: Boolean = false): Job? = synchronized(lock) {
        val running = fullLoadJob?.takeIf { it.isActive }
        when {
            !manual && !force && !cacheStale && _state.value.sections.isNotEmpty() -> {
                Log.d(TAG, "load skipped: cache fresh")
                return running
            }
            !manual && running != null -> {
                Log.d(TAG, "load skipped: in flight")
                return running
            }
            !manual && !force && System.currentTimeMillis() - lastSuccessfulLoadAt < LOAD_COOLDOWN_MS -> {
                Log.d(TAG, "load skipped: cooldown")
                return running
            }
        }
        val generation = ++loadGeneration
        val startSession = session
        fullLoadJob?.cancel()
        scope.launch { runLoad(generation, startSession, manual) }.also { fullLoadJob = it }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runLoad(generation: Long, startSession: Long, manual: Boolean) {
        Log.d(TAG, "load starting (manual=$manual)")
        try {
            val feed = feedOrchestrator.buildFeed()
            if (feed.sections.isEmpty()) {
                // DiscoverContentLoader turns a failed read into an empty list, so an empty
                // feed usually means the connection dropped mid-build. The feed on screen stays
                // and the next build tries again.
                Log.w(TAG, "Discover feed build came back empty; keeping the feed shown")
                return
            }
            val applied = synchronized(lock) {
                if (generation != loadGeneration || startSession != session) return@synchronized false
                libraryContext = MixPlaybackOrchestrator.LibraryContext(
                    artistByUri = feed.artistByUri,
                    genreArtists = feed.genreArtists,
                    strictGenreArtists = feed.strictGenreArtists,
                )
                genreItems = feed.genreItems
                topArtists = feed.topArtists
                _state.update { it.copy(sections = feed.sections) }
                cacheStale = false
                lastSuccessfulLoadAt = System.currentTimeMillis()
                true
            }
            if (!applied) return
            Log.d(TAG, "Built ${feed.sections.size} sections")
            persistSections(startSession)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The feed on screen stays; the next connect or refresh tries again.
            Log.e(TAG, "Discover feed build failed", e)
        } finally {
            synchronized(lock) {
                if (generation == loadGeneration && startSession == session) {
                    _state.update { it.copy(isLoading = false, isRefreshing = false) }
                }
            }
        }
    }

    private suspend fun refreshRecentFavoriteTracks(startSession: Long) {
        val tracks = loadRecentFavoriteTracks()
        val applied = synchronized(lock) {
            if (startSession != session) return@synchronized false
            _state.update {
                it.copy(sections = sectionCoordinator.updateRecentFavoriteTracks(current = it.sections, tracks = tracks))
            }
            true
        }
        if (applied) persistSections(startSession)
    }

    private suspend fun refreshRecentFavoriteAlbums(startSession: Long) {
        val albums = contentLoader.loadRecentFavoriteAlbums()
        val applied = synchronized(lock) {
            if (startSession != session) return@synchronized false
            _state.update {
                it.copy(sections = sectionCoordinator.updateRecentFavoriteAlbums(current = it.sections, albums = albums))
            }
            true
        }
        if (applied) persistSections(startSession)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadRecentFavoriteTracks(limit: Int = RECENT_FAVORITE_TRACKS_LIMIT): List<Track> {
        val favoritesByAdded = try {
            musicRepository.getTracks(
                orderBy = "timestamp_added_desc",
                limit = limit * RECENT_TRACKS_QUERY_FACTOR,
                favoriteOnly = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return favoritesByAdded
            .filter { it.favorite }
            .distinctBy { it.canonicalKey() ?: it.uri }
            .take(limit)
    }

    // Saves what is on screen as the next launch's placeholder, after a build and after each
    // media event refresh, so the cache always mirrors the last rendered feed. An account switch
    // can land while the file is written; the file is then removed again, because the switch
    // has already cleared it and an old account's feed must not come back on the next launch.
    private suspend fun persistSections(startSession: Long) {
        val data = synchronized(lock) {
            if (startSession != session) return
            DiscoverCache.CacheData(sections = _state.value.sections, topArtists = topArtists)
        }
        discoverCache.save(data)
        if (synchronized(lock) { startSession != session }) discoverCache.clear()
    }

    // Account switch: nothing of the previous account may stay on screen or feed a mix.
    private fun reset() {
        Log.d(TAG, "Session reset: dropping the Discover feed")
        synchronized(lock) {
            session++
            fullLoadJob?.cancel()
            mediaEventJob?.cancel()
            loadGeneration++
            cacheStale = true
            lastSuccessfulLoadAt = 0L
            firstBuildPending = true
            libraryContext = null
            genreItems = emptyList()
            topArtists = emptyList()
            _state.value = State(isLoading = false)
        }
    }
}
