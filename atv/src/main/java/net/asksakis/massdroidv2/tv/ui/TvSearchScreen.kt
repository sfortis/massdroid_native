package net.asksakis.massdroidv2.tv.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.search.SearchSession

/**
 * Search across the server and its providers (Spotify, YouTube Music, the local library...),
 * the TV counterpart of the phone's search: a text field for the remote's keyboard, filter
 * chips, and the results as the grid of cards Browse uses. Tracks, albums, playlists, radios
 * and audiobooks play on the selected player; artists open their albums.
 */
@Composable
fun TvSearchScreen(
    onOpenArtist: (itemId: String, provider: String) -> Unit,
    viewModel: TvSearchViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val recent by viewModel.recentSearches.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gridState = rememberLazyGridState()

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // The field takes the cursor on the first entry, so one press of the centre key opens the
    // keyboard. A return from an artist has a saved card, which the grid focus helper restores.
    val fieldFocus = rememberInitialFocus(enabled = session.query.isBlank())
    val itemKeys = remember(items) { items.map { it.key } }
    val gridFocus = rememberGridItemFocus(itemKeys, gridState, autoFocusOnLoad = false)

    TvGridScreen(
        title = "Search",
        header = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TV_GRID_EDGE)) {
                TvTextField(
                    label = "Artist, album, track, playlist, radio or audiobook",
                    value = session.query,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                    onImeAction = viewModel::submitQuery,
                    focusRequester = fieldFocus,
                    onValueChange = viewModel::updateQuery
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SearchFilter.entries.forEach { f ->
                        CategoryChip(f.label, selected = f == filter) { viewModel.selectFilter(f) }
                    }
                }
            }
        }
    ) {
        if (items.isEmpty()) {
            SearchEmptyState(session, filter, recent, onSearchAgain = viewModel::searchAgain)
        } else {
            TvMediaGrid(gridState) {
                items(items, key = { it.key }) { item ->
                    TvMediaCard(
                        title = item.title,
                        imageUrl = item.imageUrl,
                        subtitle = item.subtitle,
                        circular = item.circular,
                        modifier = gridFocus.modifierFor(item.key)
                    ) {
                        if (item.isArtist) onOpenArtist(item.itemId, item.provider)
                        else viewModel.play(item.uri)
                    }
                }
            }
        }
    }
}

/**
 * What the grid shows while it has no cards: the recent searches before anything is typed,
 * progress while the server answers, and "nothing found" only once it has answered this
 * exact query. Between a keystroke and the answer it stays quiet, so stale text never
 * contradicts the field.
 */
@Composable
private fun SearchEmptyState(
    session: SearchSession,
    filter: SearchFilter,
    recent: List<String>,
    onSearchAgain: (String) -> Unit
) {
    when {
        session.query.isBlank() -> RecentSearches(recent, onSearchAgain)
        session.isSearching -> TvContentMessage("Searching…", TV_GRID_EDGE)
        session.resultsQuery == session.query -> {
            val what = if (filter == SearchFilter.ALL) "Nothing" else "No ${filter.label.lowercase()}"
            TvContentMessage("$what found for “${session.query}”", TV_GRID_EDGE)
        }
    }
}

@Composable
private fun RecentSearches(recent: List<String>, onSearchAgain: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TV_GRID_EDGE, vertical = 16.dp)) {
        if (recent.isEmpty()) {
            Text(
                "Type to search your library and your music providers.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("Recent searches", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                recent.take(RECENT_SEARCHES_SHOWN).forEach { q ->
                    CategoryChip(q, selected = false) { onSearchAgain(q) }
                }
            }
        }
    }
}

/** As many recent searches as fit one row of chips on a 1080p screen. */
private const val RECENT_SEARCHES_SHOWN = 6
