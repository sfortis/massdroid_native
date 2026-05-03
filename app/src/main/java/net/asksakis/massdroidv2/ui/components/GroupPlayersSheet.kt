package net.asksakis.massdroidv2.ui.components

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

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
    onJoinLeader: (leaderId: String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val canGroupIds = remember(targetPlayer) { targetPlayer.canGroupWith.toSet() }
    val isLeaderCapable = canGroupIds.isNotEmpty()
    val staticIds = remember(targetPlayer) { targetPlayer.staticGroupMembers.toSet() }
    // Union canGroupWith with existing static members so the sheet always shows
    // every current member even if the server never advertised them via can_group_with.
    val visibleIds = remember(canGroupIds, staticIds) { canGroupIds + staticIds }

    val otherPlayers = remember(allPlayers, targetPlayer.playerId, visibleIds) {
        allPlayers.filter {
            it.playerId != targetPlayer.playerId && it.available && it.playerId in visibleIds
        }.sortedBy { it.displayName.lowercase() }
    }

    // Reverse lookup: leaders that can host the target as a member.
    val potentialLeaders = remember(allPlayers, targetPlayer.playerId) {
        allPlayers.filter {
            it.playerId != targetPlayer.playerId && it.available &&
                targetPlayer.playerId in it.canGroupWith
        }.sortedBy { it.displayName.lowercase() }
    }

    val initialSelected = remember(targetPlayer) { targetPlayer.groupChilds.toSet() }
    var selected by remember { mutableStateOf(initialSelected) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            val headerText = if (isLeaderCapable) {
                "Synchronize ${targetPlayer.displayName} with"
            } else {
                "Add ${targetPlayer.displayName} to a group led by"
            }
            SheetDefaults.HeaderTitle(
                text = headerText,
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = SheetDefaults.HeaderVerticalPadding
                )
            )
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))

            if (!isLeaderCapable) {
                // Reverse-lookup flow: target is a member-only player.
                if (potentialLeaders.isEmpty()) {
                    Text(
                        "No player on this server can host ${targetPlayer.displayName} in a sync group.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    potentialLeaders.forEach { leader ->
                        ListItem(
                            colors = SheetDefaults.listItemColors(),
                            headlineContent = { Text(leader.displayName) },
                            supportingContent = {
                                val extra = leader.groupChilds.filter { it != leader.playerId }
                                val label = if (extra.isEmpty()) "no current members"
                                else "${extra.size} current member${if (extra.size == 1) "" else "s"}"
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                Icon(Icons.Default.SpeakerGroup, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                onJoinLeader(leader.playerId)
                                onDismiss()
                            }
                        )
                    }
                }
                return@Column
            }

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
                    val isStatic = player.playerId in staticIds
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text(player.displayName) },
                        supportingContent = if (isStatic) {
                            { Text("permanent member", style = MaterialTheme.typography.bodySmall) }
                        } else null,
                        leadingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = if (isStatic) null else { checked ->
                                    selected = if (checked) selected + player.playerId else selected - player.playerId
                                },
                                enabled = !isStatic
                            )
                        },
                        modifier = if (isStatic) Modifier else Modifier.clickable {
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
                    // "Unsync All" would call set_members(remove=all) which fails on
                    // static members. Keep it only when the group has no statics.
                    if (initialSelected.isNotEmpty() && staticIds.isEmpty()) {
                        MdTextButton(onClick = {
                            onApply(emptyList())
                            onDismiss()
                        }) {
                            Text("Unsync All")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    MdButton(
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
