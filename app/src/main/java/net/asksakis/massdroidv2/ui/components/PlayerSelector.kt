package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.ui.screens.home.PlayerIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSelector(
    players: List<Player>,
    selectedPlayerId: String?,
    onPlayerSelected: (Player) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column {
            Text(
                text = "Select Player",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = SheetDefaults.HeaderVerticalPadding
                )
            )
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        }

        LazyColumn(
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            items(players.filter { it.available }.sortedBy { it.displayName.lowercase() }) { player ->
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text(player.displayName) },
                    supportingContent = {
                        Text(
                            when (player.state) {
                                PlaybackState.PLAYING -> "Playing"
                                PlaybackState.PAUSED -> "Paused"
                                PlaybackState.IDLE -> "Idle"
                            }
                        )
                    },
                    leadingContent = {
                        val isPlaying = player.state == PlaybackState.PLAYING
                        val iconTint = if (isPlaying || player.playerId == selectedPlayerId)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                        SoundWaveIcon(
                            isPlaying = isPlaying,
                            waveColor = iconTint
                        ) {
                            PlayerIcon(
                                player = player,
                                modifier = Modifier.size(32.dp),
                                tint = iconTint
                            )
                        }
                    },
                    trailingContent = {
                        if (player.playerId == selectedPlayerId) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onPlayerSelected(player)
                        onDismiss()
                    }
                )
            }
        }
    }
}
