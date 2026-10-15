package net.asksakis.massdroidv2.domain.discover

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.recommendation.MixPlaybackOrchestrator
import net.asksakis.massdroidv2.domain.recommendation.MixPlaybackOrchestrator.MixResult
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MixLauncher"
// A repeat press on the same genre within this window is taken as a double press.
private const val GENRE_RADIO_REPEAT_WINDOW_MS = 1_500L

/**
 * Starts Smart Mix and Genre Radio for every caller: the phone's Home, the TV's Home, Android
 * Auto and the headless car. The build itself is [MixPlaybackOrchestrator]'s; this class adds
 * what each caller used to repeat: one mix at a time, a cheap genre check before the overlay
 * shows, a guard against a double press, and the library maps of the Discover feed.
 *
 * A start returns at once and says whether it was taken. [activity] says what is being built,
 * for an overlay or a progress indicator, and every finished start is published on [outcomes],
 * for a message. An account switch cancels the build; a cancelled build publishes nothing and
 * never touches the activity of a build started after it.
 */
@Singleton
class MixLauncher @Inject constructor(
    private val mixOrchestrator: MixPlaybackOrchestrator,
    private val feedStore: DiscoverFeedStore,
    sessionEventBus: SessionEventBus,
) {

    sealed interface Mix {
        data object SmartMix : Mix
        data class GenreRadio(val genre: String) : Mix
    }

    data class Outcome(val mix: Mix, val result: MixResult)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _activity = MutableStateFlow<Mix?>(null)
    val activity: StateFlow<Mix?> = _activity.asStateFlow()
    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 4)
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    // The time source of the double press guard; a test replaces it to make the window exact.
    internal var clock: () -> Long = System::currentTimeMillis

    // Guards [job] and the double press bookkeeping, and every write of [_activity].
    private val lock = Any()
    private var job: Job? = null
    private var lastRadioGenre: String? = null
    private var lastRadioAtMs = 0L

    init {
        scope.launch { sessionEventBus.resets.collect { reset() } }
    }

    /**
     * Builds a Smart Mix and plays it on [queueId]. Returns false when the press was ignored
     * because another mix is building; true when it started or its outcome is already published.
     */
    fun startSmartMix(queueId: String): Boolean = start(queueId, Mix.SmartMix)

    /**
     * Builds a radio for [genre] and plays it on [queueId]. Returns false when the press was
     * ignored (another mix is building, or a double press); true otherwise.
     */
    fun startGenreRadio(queueId: String, genre: String): Boolean {
        val context = feedStore.libraryContext()
        // Without a built feed the orchestrator loads the library itself, so only a loaded feed
        // can say that a genre has no artists.
        if (context != null && !context.hasArtistsForGenre(genre)) {
            Log.d(TAG, "Genre Radio '$genre' refused: no artists for it in the Discover feed")
            _outcomes.tryEmit(Outcome(Mix.GenreRadio(genre), MixResult.NoMatchingArtists))
            return true
        }
        return start(queueId, Mix.GenreRadio(genre))
    }

    private fun start(queueId: String, mix: Mix): Boolean {
        if (queueId.isBlank()) {
            Log.d(TAG, "$mix refused: no player")
            _outcomes.tryEmit(Outcome(mix, MixResult.NoPlayer))
            return true
        }
        synchronized(lock) {
            if (job?.isActive == true) {
                Log.d(TAG, "$mix ignored: ${_activity.value} is building")
                return false
            }
            if (mix is Mix.GenreRadio) {
                val now = clock()
                if (lastRadioGenre == mix.genre && now - lastRadioAtMs < GENRE_RADIO_REPEAT_WINDOW_MS) {
                    Log.d(TAG, "Genre Radio '${mix.genre}' ignored: repeat press")
                    return false
                }
                lastRadioGenre = mix.genre
                lastRadioAtMs = now
            }
            // Created before it runs, so the job can tell whether it is still the current one.
            val started = scope.launch(start = CoroutineStart.LAZY) { build(queueId, mix) }
            job = started
            _activity.value = mix
            started.start()
        }
        return true
    }

    private suspend fun build(queueId: String, mix: Mix) {
        val self = currentCoroutineContext()[Job]
        try {
            val context = feedStore.libraryContext()
            val result = when (mix) {
                Mix.SmartMix -> mixOrchestrator.playSmartMix(queueId, context)
                is Mix.GenreRadio -> mixOrchestrator.playGenreRadio(queueId, mix.genre, context)
            }
            Log.d(TAG, "$mix on $queueId -> $result")
            synchronized(lock) {
                if (job === self) _outcomes.tryEmit(Outcome(mix, result))
            }
        } finally {
            synchronized(lock) {
                if (job === self) {
                    job = null
                    _activity.value = null
                }
            }
        }
    }

    private fun reset() {
        synchronized(lock) {
            job?.cancel()
            job = null
            lastRadioGenre = null
            lastRadioAtMs = 0L
            _activity.value = null
        }
    }
}
