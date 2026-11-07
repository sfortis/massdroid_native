package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.SMART_PLAYLIST_MAX_SEEDS
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSummary

/** Picks a seed-based smart playlist to add a track or artist to, or names a new one. */
@Composable
fun AddToSmartPlaylistDialog(
    playlists: List<SmartPlaylistSummary>,
    isLoading: Boolean,
    pendingPlaylistId: String?,
    suggestedName: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onPlaylistClick: (SmartPlaylistSummary) -> Unit,
    onCreatePlaylist: (String) -> Unit
) {
    PlaylistPickerFrame(
        title = "Add to Smart Playlist",
        newItemLabel = "New Smart Playlist",
        isLoading = isLoading,
        isEmpty = playlists.isEmpty(),
        onDismiss = onDismiss,
        onRetry = onRetry,
        onCreate = onCreatePlaylist,
        suggestedName = suggestedName,
        emptyText = "No smart playlists built from tracks or artists yet."
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(playlists, key = { it.playlistId }) { playlist ->
                PlaylistPickerRow(
                    name = playlist.name,
                    subtitle = "${playlist.seedCount} of $SMART_PLAYLIST_MAX_SEEDS seeds",
                    busy = pendingPlaylistId == playlist.playlistId,
                    onClick = { onPlaylistClick(playlist) },
                    leading = { InitialsBox(name = playlist.name, size = 42.dp) }
                )
            }
        }
    }
}
