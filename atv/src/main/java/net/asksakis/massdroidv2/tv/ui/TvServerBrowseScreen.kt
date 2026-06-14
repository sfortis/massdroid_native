package net.asksakis.massdroidv2.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.model.BrowseItem as ServerBrowseItem

/** Server provider folder browse (RadioBrowser, ORF, Filesystem…) with drill-down. */
@Composable
fun TvServerBrowseScreen(viewModel: TvServerBrowseViewModel = hiltViewModel()) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canGoBack.collectAsStateWithLifecycle()

    // Back pops one folder; at the root it is disabled so the system Back exits.
    BackHandler(enabled = canGoBack) { viewModel.back() }

    // Drilling into a folder replaces the whole list; the shared grid-focus helper keeps
    // the cursor on a content item so it never escapes to the mini player pill.
    val gridState = rememberLazyGridState()
    val entryKeys = remember(entries) { entries.map { it.uri } }
    val gridFocus = rememberGridItemFocus(entryKeys, gridState)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp, bottom = 24.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = SERVER_EDGE)
            )
            Spacer(Modifier.height(16.dp))
            if (entries.isEmpty()) {
                Text(
                    "Nothing here",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = SERVER_EDGE)
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(SERVER_GRID_COLUMNS),
                    contentPadding = PaddingValues(horizontal = SERVER_EDGE, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(entries, key = { it.uri }) { entry ->
                        BrowseTreeCard(entry, modifier = gridFocus.modifierFor(entry.uri)) { viewModel.open(entry) }
                    }
                }
            }
        }
    }
}

private val SERVER_EDGE = 56.dp
private const val SERVER_GRID_COLUMNS = 6

@Composable
private fun BrowseTreeCard(item: ServerBrowseItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                if (item.imageUrl != null) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                } else {
                    Icon(
                        if (item.isFolder) Icons.Filled.Folder else Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = LocalContentColor.current.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(10.dp)
            )
        }
    }
}
