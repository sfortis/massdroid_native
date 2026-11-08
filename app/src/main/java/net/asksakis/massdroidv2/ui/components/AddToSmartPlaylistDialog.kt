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
import net.asksakis.massdroidv2.domain.playlist.SmartPlaylistSeedController
import net.asksakis.massdroidv2.ui.failureMessage

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
                    // "Discover" is the name Music Assistant's own UI gives a seed-based smart playlist.
                    subtitle = "Discover · ${playlist.seedCount} of $SMART_PLAYLIST_MAX_SEEDS seeds",
                    busy = pendingPlaylistId == playlist.playlistId,
                    onClick = { onPlaylistClick(playlist) },
                    leading = { InitialsBox(name = playlist.name, size = 42.dp) }
                )
            }
        }
    }
}

/** What a screen says once a smart playlist add or create has an outcome. */
internal fun SmartPlaylistSeedController.Event.message(): String = when (this) {
    is SmartPlaylistSeedController.Event.Added -> "Added to $playlistName"
    is SmartPlaylistSeedController.Event.AlreadyPresent -> "$itemName is already in $playlistName"
    // The wording of Music Assistant's web UI for a playlist that holds MAX_SEEDS.
    is SmartPlaylistSeedController.Event.Full -> "Seed limit reached"
    is SmartPlaylistSeedController.Event.Created -> "Created $playlistName"
    is SmartPlaylistSeedController.Event.LoadFailed -> error.failureMessage("Couldn't load the smart playlists")
    is SmartPlaylistSeedController.Event.AddFailed -> error.failureMessage("Couldn't add that to the smart playlist")
    is SmartPlaylistSeedController.Event.CreateFailed -> error.failureMessage("Couldn't create the smart playlist")
}
