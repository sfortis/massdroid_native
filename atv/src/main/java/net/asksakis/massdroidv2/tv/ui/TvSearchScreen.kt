package net.asksakis.massdroidv2.tv.ui

import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
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
    voiceRequested: Boolean = false,
    onVoiceHandled: () -> Unit = {},
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

    val itemKeys = remember(items) { items.map { it.key } }
    val gridFocus = rememberGridItemFocus(itemKeys, gridState, autoFocusOnLoad = false)
    // A submitted search (the search key, a recent search, a spoken one) is final, so its
    // results take the cursor once they are in. Typing never does: the results change under
    // every letter, and the cursor must stay in the field. Without this, a recent search left
    // the cursor on a chip that had just vanished, and Compose sent it back to the field,
    // which reopened the keyboard over the results.
    var focusResults by remember { mutableStateOf(false) }
    LaunchedEffect(itemKeys.firstOrNull(), session.isSearching) {
        if (focusResults && itemKeys.isNotEmpty() && !session.isSearching) {
            focusResults = false
            gridFocus.requesterFor(itemKeys.first()).requestFocusWhenAttached()
        }
    }
    val submit = { focusResults = true; viewModel.submitQuery() }
    val searchAgain = { query: String -> focusResults = true; viewModel.searchAgain(query) }

    // Voice input through the TV's speech recogniser (the Google app on Android TV): what it
    // heard is searched at once, as a submitted query, since the listener has said it all.
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        when {
            !heard.isNullOrBlank() -> searchAgain(heard)
            // The recogniser came back without a word: cancelled, or voice is not set up on
            // this TV (remote not paired, no Assistant). Said once, so a dead key is not a riddle.
            result.resultCode != Activity.RESULT_OK ->
                Toast.makeText(context, "Voice input didn't start. Is voice set up on this TV?", Toast.LENGTH_LONG).show()
        }
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say an artist, album, track or playlist")
        try {
            voice.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "This TV has no voice input", Toast.LENGTH_SHORT).show()
        }
    }
    // The remote's microphone key, pressed here or on another screen on the way in.
    LaunchedEffect(voiceRequested) {
        if (voiceRequested) {
            onVoiceHandled()
            startVoice()
        }
    }

    // The field takes the cursor on the first entry, so one press of the centre key opens the
    // keyboard. A return from an artist has a saved card, which the grid focus helper restores.
    val fieldFocus = rememberInitialFocus(enabled = session.query.isBlank())

    TvGridScreen(
        title = "Search",
        header = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TV_GRID_EDGE)) {
                val focusManager = LocalFocusManager.current
                Row(verticalAlignment = Alignment.Bottom) {
                    // The field keeps LEFT and RIGHT for its cursor, so with nothing typed RIGHT
                    // would go nowhere; then it reaches the microphone button beside the field.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight &&
                                    session.query.isEmpty() && focusManager.moveFocus(FocusDirection.Right)
                            }
                    ) {
                        TvTextField(
                            label = "Artist, album, track, playlist, radio or audiobook",
                            value = session.query,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                            onImeAction = submit,
                            focusRequester = fieldFocus,
                            onValueChange = viewModel::updateQuery
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = startVoice, modifier = Modifier.pointerClick(onClick = startVoice)) {
                        Icon(Icons.Filled.Mic, contentDescription = "Search by voice")
                    }
                }
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
            SearchEmptyState(session, filter, recent, onSearchAgain = searchAgain)
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
