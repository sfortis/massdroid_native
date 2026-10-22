package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.BrowseItem as ServerBrowseItem
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * Server provider folder browse — the MA-UI "Browse" tree (RadioBrowser, ORF
 * Radiothek, Filesystem, Deezer…). Folders drill in via [MusicRepository.browse];
 * playable entries (radios, tracks, albums…) play on the selected player. Keeps
 * an in-screen path stack so Back pops one folder at a time.
 */
@HiltViewModel
class TvServerBrowseViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private data class Crumb(val path: String?, val title: String)

    private val stack = ArrayDeque<Crumb>().apply { addLast(Crumb(null, "Browse")) }

    /** What the current folder shows. A failed load is its own state, not an empty folder. */
    sealed interface FolderState {
        data object Loading : FolderState
        data class Loaded(val entries: List<ServerBrowseItem>) : FolderState
        data object Failed : FolderState
    }

    private val _state = MutableStateFlow<FolderState>(FolderState.Loading)
    val state: StateFlow<FolderState> = _state.asStateFlow()

    private val _title = MutableStateFlow("Browse")
    val title: StateFlow<String> = _title.asStateFlow()

    // Only the folder on top of the stack may write the state: a slow answer for a folder
    // the user already left would otherwise replace the one they are looking at.
    private var loadJob: Job? = null

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val crumb = stack.last()
        _title.value = crumb.title
        _canGoBack.value = stack.size > 1
        loadJob?.cancel()
        _state.value = FolderState.Loading
        loadJob = viewModelScope.launch {
            _state.value = try {
                FolderState.Loaded(browseEntries(musicRepository.browse(crumb.path)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FolderState.Failed
            }
        }
    }

    /** Load the current folder again, after a failure. */
    fun retry() = load()

    /** Open a folder, or play a playable entry. */
    fun open(item: ServerBrowseItem) {
        if (item.isFolder) {
            stack.addLast(Crumb(item.path ?: item.uri, item.name))
            load()
        } else if (item.isPlayable) {
            play(item.uri)
        }
    }

    /** Pop one folder. Returns false at the root (let the screen exit). */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        load()
        return true
    }

    private fun play(uri: String) {
        viewModelScope.launch {
            val target = playerRepository.selectedPlayer.value?.playerId
                ?: playerRepository.players.value.firstOrNull()?.playerId
                ?: return@launch
            playerRepository.selectPlayer(target)
            runCatching { musicRepository.playMedia(target, uri, option = "replace") }
        }
    }
}

/** Identity of an entry in a folder: one uri can be listed under more than one path. */
internal val ServerBrowseItem.browseKey: String get() = "$uri|${path.orEmpty()}"

/**
 * Drops MA's ".." parent entry (Back already goes up, as on the phone) and repeated entries,
 * whose equal keys crash the grid.
 */
private fun browseEntries(items: List<ServerBrowseItem>): List<ServerBrowseItem> =
    items.filter { it.name != PARENT_ENTRY_NAME }.distinctBy { it.browseKey }

private const val PARENT_ENTRY_NAME = ".."
