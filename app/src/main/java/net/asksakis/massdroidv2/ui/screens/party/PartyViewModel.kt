package net.asksakis.massdroidv2.ui.screens.party

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.PartyInfo
import net.asksakis.massdroidv2.domain.model.PartyJoinLink
import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.party.PartyTarget
import net.asksakis.massdroidv2.domain.repository.PartyRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.ui.failureMessage
import javax.inject.Inject

/** What the Party screen shows. */
sealed interface PartyUiState {
    data object Loading : PartyUiState

    /** The server gives no join link, which means guest access is turned off. */
    data object GuestAccessOff : PartyUiState

    data class Failed(val message: String) : PartyUiState

    data class Ready(
        val link: PartyJoinLink,
        val title: String,
        val qrText: String?,
        val target: PartyTarget
    ) : PartyUiState
}

@HiltViewModel
class PartyViewModel @Inject constructor(
    private val partyRepository: PartyRepository,
    private val playerRepository: PlayerRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    /** What the server answered, before the target is matched against the players. */
    private sealed interface Loaded {
        data object Loading : Loaded
        data object GuestAccessOff : Loaded
        data class Failed(val message: String) : Loaded
        data class Party(val link: PartyJoinLink, val targetQueueId: String?, val info: PartyInfo) : Loaded
    }

    private val loaded = MutableStateFlow<Loaded>(Loaded.Loading)
    private var loadJob: Job? = null

    /**
     * The target is matched live, so the warning follows when the listener picks another
     * player while the screen is open. The queue id the server named is read on each load and
     * again by [followTarget].
     */
    val state: StateFlow<PartyUiState> = combine(
        loaded,
        playerRepository.players,
        playerRepository.selectedPlayer,
        playerRepository.queueState,
        settingsRepository.sendspinClientId
    ) { loaded, players, selected, queue, thisPhone ->
        when (loaded) {
            Loaded.Loading -> PartyUiState.Loading
            Loaded.GuestAccessOff -> PartyUiState.GuestAccessOff
            is Loaded.Failed -> PartyUiState.Failed(loaded.message)
            is Loaded.Party -> PartyUiState.Ready(
                link = loaded.link,
                title = loaded.info.name ?: DEFAULT_TITLE,
                qrText = loaded.info.qrText,
                target = PartyTarget.resolve(
                    targetQueueId = loaded.targetQueueId,
                    mode = loaded.info.mode,
                    players = players,
                    // The queue the Queue screen shows for the selected player.
                    selectedQueueId = queue?.queueId ?: selected?.playerId,
                    thisPhonePlayerId = thisPhone
                )
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PartyUiState.Loading)

    /** The cover of the track playing on the selected player, behind the screen. */
    val artworkUrl: StateFlow<String?> = playerRepository.queueState
        .map { queue -> queue?.currentItem?.let { it.track?.imageUrl ?: it.imageUrl } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * False once the server no longer has the plugin, after it was turned off or after a
     * switch to a server without it. The screen then closes, because its link no longer works.
     */
    val available: StateFlow<Boolean> = partyRepository.available
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        // Loads on every connection, the current one included, so a reconnect or a switch to
        // another server never leaves the previous server's link on screen. While there is no
        // connection the screen stays on Loading.
        viewModelScope.launch {
            partyRepository.connections.collect { refresh() }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loaded.value = Loaded.Loading
        loadJob = viewModelScope.launch {
            loaded.value = try {
                coroutineScope {
                    // Only the link is required. The target and the settings fall back, so a
                    // valid QR still shows when one of them fails.
                    val target = async { orNull("party player") { partyRepository.targetQueueId() } }
                    val info = async { orNull("party settings") { partyRepository.info() } ?: DEFAULT_INFO }
                    partyRepository.joinLink()?.let { Loaded.Party(it, target.await(), info.await()) }
                        ?: Loaded.GuestAccessOff
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Party load failed: ${e.message}")
                Loaded.Failed(e.failureMessage("Couldn't load the party"))
            }
        }
    }

    /**
     * Reads the party player again whenever a player starts or stops, until cancelled. The
     * plugin's automatic choice follows whichever queue is playing, so in the car a home
     * speaker that starts playing takes the guests' songs; this keeps the screen's warning
     * true. The screen runs it only while it is resumed. Bursts of changes are folded into
     * one read [TARGET_SETTLE_MS] after the last of them.
     */
    suspend fun followTarget() {
        playerRepository.players
            .map { players -> players.map { it.playerId to it.state } }
            .distinctUntilChanged()
            .drop(1)
            .collectLatest {
                delay(TARGET_SETTLE_MS)
                val current = loaded.value as? Loaded.Party ?: return@collectLatest
                // A failed read keeps the target shown, so a stalled connection does not turn
                // a known player into "None right now".
                val target = try {
                    partyRepository.targetQueueId()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the party player again, keeping the current one: ${e.message}")
                    return@collectLatest
                }
                // Only onto the same load: a refresh that started meanwhile wins.
                loaded.compareAndSet(current, current.copy(targetQueueId = target))
            }
    }

    private suspend fun <T> orNull(what: String, read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the $what: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "PartyVM"
        const val DEFAULT_TITLE = "Party"
        const val STOP_TIMEOUT_MS = 5_000L
        const val TARGET_SETTLE_MS = 1_000L
        val DEFAULT_INFO = PartyInfo(name = null, qrText = null, mode = PartyMode.UNKNOWN)
    }
}
