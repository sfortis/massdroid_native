package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.Playlist

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    isLoading: Boolean,
    addingToPlaylistId: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit = {},
    onRemoveFromPlaylist: (Playlist) -> Unit = {},
    containsTrack: Set<String> = emptySet()
) {
    var newPlaylistName by remember { mutableStateOf("") }
    var showCreateField by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist") },
        text = {
            Column {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> {
                        if (showCreateField) {
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focusRequester.requestFocus() }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newPlaylistName,
                                    onValueChange = { newPlaylistName = it },
                                    placeholder = { Text("Playlist name") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            if (newPlaylistName.isNotBlank()) {
                                                onCreatePlaylist(newPlaylistName.trim())
                                            }
                                        }
                                    ),
                                    modifier = Modifier.weight(1f).focusRequester(focusRequester)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        if (newPlaylistName.isNotBlank()) {
                                            onCreatePlaylist(newPlaylistName.trim())
                                        }
                                    },
                                    enabled = newPlaylistName.isNotBlank()
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Create")
                                }
                            }
                        } else {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { showCreateField = true },
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        "New Playlist",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        if (playlists.isEmpty() && !showCreateField) {
                            Text("No playlists available.")
                            TextButton(onClick = onRetry) { Text("Reload") }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(playlists, key = { it.uri }) { playlist ->
                                    val isAdding = addingToPlaylistId == playlist.itemId
                                    val alreadyAdded = playlist.uri in containsTrack
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.medium)
                                            .clickable(enabled = !isAdding) {
                                                if (alreadyAdded) onRemoveFromPlaylist(playlist) else onPlaylistClick(playlist)
                                            },
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (!playlist.imageUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = playlist.imageUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(42.dp)
                                                        .clip(MaterialTheme.shapes.small),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                InitialsBox(name = playlist.name, size = 42.dp)
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = playlist.name,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isAdding) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else if (alreadyAdded) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Already added",
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
