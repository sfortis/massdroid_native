package net.asksakis.massdroidv2.ui.components

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.Player

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPlayersSheet(
    targetPlayer: Player,
    allPlayers: List<Player>,
    onApply: (selectedPlayerIds: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val canGroupIds = remember(targetPlayer) { targetPlayer.canGroupWith.toSet() }
    val otherPlayers = remember(allPlayers, targetPlayer.playerId, canGroupIds) {
        allPlayers.filter {
            it.playerId != targetPlayer.playerId && it.available && it.playerId in canGroupIds
        }.sortedBy { it.displayName.lowercase() }
    }

    val initialSelected = remember(targetPlayer) { targetPlayer.groupChilds.toSet() }
    var selected by remember { mutableStateOf(initialSelected) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            SheetDefaults.HeaderTitle(
                text = "Synchronize ${targetPlayer.displayName} with",
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = SheetDefaults.HeaderVerticalPadding
                )
            )
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))

            if (otherPlayers.isEmpty()) {
                Text(
                    "No other players available for grouping.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                otherPlayers.forEach { player ->
                    val isSelected = player.playerId in selected
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text(player.displayName) },
                        leadingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + player.playerId else selected - player.playerId
                                }
                            )
                        },
                        modifier = Modifier.clickable {
                            selected = if (isSelected) selected - player.playerId else selected + player.playerId
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Log.d("GroupSheet", "Rendering button row: selected=$selected initial=$initialSelected enabled=${selected.isNotEmpty() && selected != initialSelected}")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (initialSelected.isNotEmpty()) {
                        TextButton(onClick = {
                            onApply(emptyList())
                            onDismiss()
                        }) {
                            Text("Unsync All")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(
                        onClick = {
                            Log.d("GroupSheet", "Sync: target=${targetPlayer.playerId} selected=$selected")
                            onApply(selected.toList())
                            onDismiss()
                        },
                        enabled = selected.isNotEmpty() && selected != initialSelected
                    ) {
                        Icon(Icons.Default.SpeakerGroup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (initialSelected.isEmpty()) "Sync" else "Update")
                    }
                }
            }
        }
    }
}
