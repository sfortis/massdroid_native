package net.asksakis.massdroidv2.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.discover.DiscoverFeedStore
import net.asksakis.massdroidv2.domain.discover.HomeRowLayout
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.repository.HomeRowSettingsRepository
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import javax.inject.Inject

/** One Music Assistant row in the home row setting, as the settings screen lists it. */
data class HomeRowChoice(
    val row: RecommendationFolder,
    val source: String,
    val visible: Boolean,
    /** A row without a URI comes from a server that cannot store the choice. */
    val changeable: Boolean
) {
    /** A stable key for the list; the URI when there is one. */
    val key: String get() = row.uri ?: "${row.provider}/${row.itemId}"
}

data class HomeRowsUiState(
    val loading: Boolean = true,
    val rows: List<HomeRowChoice> = emptyList(),
    val error: String? = null,
    /** False when trying again cannot help, such as on a server without the setting. */
    val retryable: Boolean = true
)

/**
 * The home row setting: which Music Assistant rows show under the app's own sections. The
 * choice is the MA user's own, stored on the server, so it also changes the MA web UI and the
 * official apps for this user.
 */
@HiltViewModel
class HomeRowsViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val homeRowSettings: HomeRowSettingsRepository,
    private val discoverFeedStore: DiscoverFeedStore
) : ViewModel() {

    private val _state = MutableStateFlow(HomeRowsUiState())
    val state: StateFlow<HomeRowsUiState> = _state.asStateFlow()

    private var serverRows: List<RecommendationFolder> = emptyList()
    private var layout = HomeRowLayout()

    /** Reads the rows and the setting again; the page calls it each time it opens. */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                serverRows = musicRepository.getRecommendationRows().filterNot { it.itemId in APP_DRAWN_ROWS }
                val layout = homeRowSettings.load()
                if (layout == null) {
                    _state.value = HomeRowsUiState(
                        loading = false,
                        error = "This Music Assistant server has no home row setting. Music Assistant 2.10 or later has one.",
                        retryable = false
                    )
                } else {
                    show(layout)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the home rows: ${e.message}")
                _state.update { HomeRowsUiState(loading = false, error = "Couldn't load the rows from Music Assistant.") }
            }
        }
    }

    fun setVisible(choice: HomeRowChoice, visible: Boolean) {
        // Shown at once; the server's answer then confirms it or the screen goes back.
        _state.update { s -> s.copy(rows = s.rows.map { if (it.row == choice.row) it.copy(visible = visible) else it }) }
        viewModelScope.launch {
            try {
                show(homeRowSettings.setVisible(choice.row, visible))
                discoverFeedStore.invalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the home row setting: ${e.message}")
                _state.update { s ->
                    s.copy(
                        rows = s.rows.map { if (it.row == choice.row) it.copy(visible = !visible) else it },
                        error = "Couldn't save the change to Music Assistant."
                    )
                }
            }
        }
    }

    /** Moves a row on screen while it is dragged; [commitOrder] stores the result. */
    fun moveLocally(fromKey: String, toKey: String) {
        _state.update { s ->
            val from = s.rows.indexOfFirst { it.key == fromKey }
            val to = s.rows.indexOfFirst { it.key == toKey }
            if (from < 0 || to < 0 || from == to) return@update s
            s.copy(rows = s.rows.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    /** Stores the order on screen for this MA user; the screen goes back if the server refuses. */
    fun commitOrder() {
        val sequence = _state.value.rows.mapNotNull { it.row.uri }
        viewModelScope.launch {
            try {
                show(homeRowSettings.setOrder(serverRows, sequence))
                discoverFeedStore.invalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not save the row order: ${e.message}")
                show(layout)
                _state.update { it.copy(error = "Couldn't save the order to Music Assistant.") }
            }
        }
    }

    private fun show(layout: HomeRowLayout) {
        this.layout = layout
        _state.value = HomeRowsUiState(
            loading = false,
            rows = layout.orderedRows(serverRows).map { row ->
                HomeRowChoice(row, sourceOf(row), layout.isVisible(row), changeable = row.uri != null)
            }
        )
    }

    private fun sourceOf(row: RecommendationFolder): String =
        if (row.provider == LIBRARY_ROWS_PROVIDER || row.provider == "library") {
            "Library"
        } else {
            row.provider.substringBefore("--").replaceFirstChar { it.uppercase() }
        }

    private companion object {
        const val TAG = "HomeRowsVM"
        const val LIBRARY_ROWS_PROVIDER = "recommendations"
        /** Drawn by the app as its own live sections, so a switch for them would change nothing. */
        val APP_DRAWN_ROWS = setOf("recent_favorite_tracks", "recent_favorite_albums")
    }
}
