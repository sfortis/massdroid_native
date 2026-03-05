package net.asksakis.massdroidv2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.MediaType
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player

data class ActionSheetItem(
    val title: String,
    val subtitle: String = "",
    val uri: String,
    val imageUrl: String?,
    val favorite: Boolean,
    val mediaType: MediaType,
    val itemId: String,
    val primaryArtistUri: String? = null,
    val primaryArtistName: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaActionSheet(
    title: String,
    subtitle: String = "",
    imageUrl: String?,
    players: List<Player>,
    selectedPlayerId: String?,
    favorite: Boolean = false,
    artistBlocked: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onToggleArtistBlocked: (() -> Unit)? = null,
    onPlayNow: () -> Unit,
    onPlayOnPlayer: (Player) -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showSpeakers by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            // Header
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Favorite toggle
            if (onToggleFavorite != null) {
                ListItem(
                    headlineContent = {
                        Text(if (favorite) "Remove from Favorites" else "Add to Favorites")
                    },
                    leadingContent = {
                        Icon(
                            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (favorite) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                    },
                    modifier = Modifier.clickable {
                        onToggleFavorite()
                        onDismiss()
                    }
                )
            }

            if (onToggleArtistBlocked != null) {
                ListItem(
                    headlineContent = {
                        Text(if (artistBlocked) "Allow this Artist" else "Do Not Play this Artist")
                    },
                    leadingContent = {
                        Icon(
                            if (artistBlocked) Icons.Default.Person else Icons.Default.Block,
                            contentDescription = null,
                            tint = if (artistBlocked) LocalContentColor.current else MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable {
                        onToggleArtistBlocked()
                        onDismiss()
                    }
                )
            }

            // Play Now
            ListItem(
                headlineContent = { Text("Play Now") },
                leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                modifier = Modifier.clickable {
                    onPlayNow()
                    onDismiss()
                }
            )

            // Play on Speaker
            ListItem(
                headlineContent = { Text("Play on Speaker") },
                leadingContent = { Icon(Icons.Default.Speaker, contentDescription = null) },
                trailingContent = {
                    Icon(
                        if (showSpeakers) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                },
                modifier = Modifier.clickable { showSpeakers = !showSpeakers }
            )

            AnimatedVisibility(visible = showSpeakers) {
                Column {
                    players.filter { it.available }.sortedBy { it.displayName.lowercase() }.forEach { player ->
                        ListItem(
                            headlineContent = { Text(player.displayName) },
                            leadingContent = {
                                Spacer(modifier = Modifier.width(24.dp))
                            },
                            trailingContent = {
                                if (player.state == PlaybackState.PLAYING) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .clickable {
                                    onPlayOnPlayer(player)
                                    onDismiss()
                                }
                        )
                    }
                }
            }

            // Add to Queue
            ListItem(
                headlineContent = { Text("Add to Queue") },
                leadingContent = {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.QueueMusic, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    onAddToQueue()
                    onDismiss()
                }
            )

            // Start Radio
            ListItem(
                headlineContent = { Text("Start Radio") },
                leadingContent = { Icon(Icons.Default.Radio, contentDescription = null) },
                modifier = Modifier.clickable {
                    onStartRadio()
                    onDismiss()
                }
            )
        }
    }
}
