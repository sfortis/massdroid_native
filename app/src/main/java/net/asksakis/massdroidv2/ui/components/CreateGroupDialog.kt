package net.asksakis.massdroidv2.ui.components

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.GroupProviderOption
import net.asksakis.massdroidv2.domain.model.Player

@Composable
fun CreateGroupDialog(
    candidates: List<Player>,
    loadProviders: suspend () -> List<GroupProviderOption>,
    onConfirm: (provider: String, name: String, memberIds: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    // Click-order list: first element becomes the sync leader.
    var selected by remember { mutableStateOf(emptyList<String>()) }
    var providers by remember { mutableStateOf<List<GroupProviderOption>?>(null) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        val list = loadProviders()
        providers = list
        if (list.size == 1) selectedProvider = list[0].instanceId
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New speaker group") },
        text = {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            when {
                providers == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
                providers!!.isEmpty() -> {
                    Text(
                        "Your Music Assistant server does not have a provider that supports creating groups.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Group name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (providers!!.size > 1) {
                            Text("Group type", style = MaterialTheme.typography.labelLarge)
                            Column {
                                providers!!.forEach { option ->
                                    val isChecked = selectedProvider == option.instanceId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = isChecked,
                                                onClick = { selectedProvider = option.instanceId }
                                            )
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isChecked,
                                            onClick = { selectedProvider = option.instanceId }
                                        )
                                        Column {
                                            Text(option.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                option.domain,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Anchor = first clicked player. Server's sync_group uses this
                        // member's can_group_with to filter the rest; we mirror that here
                        // so incompatible members aren't offered silently.
                        val anchorId = selected.firstOrNull()
                        val anchor = anchorId?.let { id -> candidates.firstOrNull { it.playerId == id } }
                        val compatibleIds: Set<String> = anchor?.let {
                            it.canGroupWith.toSet() + it.playerId
                        } ?: candidates.map { it.playerId }.toSet()
                        val visibleCandidates = candidates.filter { it.playerId in compatibleIds }
                        val anchorIsLeaderless = anchor != null && anchor.canGroupWith.isEmpty()

                        if (anchorIsLeaderless) {
                            Text(
                                "${anchor!!.displayName} cannot lead a sync group. " +
                                    "Tap it again to deselect and pick a different first member.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (visibleCandidates.isEmpty()) {
                            Text(
                                "No players available for grouping.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text("Members", style = MaterialTheme.typography.labelLarge)
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                visibleCandidates.forEach { player ->
                                    val isSelected = player.playerId in selected
                                    val isAnchor = player.playerId == anchorId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selected = if (isSelected) selected - player.playerId
                                                else selected + player.playerId
                                            }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                selected = if (checked) selected + player.playerId
                                                else selected - player.playerId
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            player.displayName,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (isAnchor) {
                                            Spacer(Modifier.width(6.dp))
                                            PlayerBadgeChip {
                                                Text(
                                                    text = "Leader",
                                                    color = androidx.compose.ui.graphics.Color.Black,
                                                    style = MaterialTheme.typography.labelSmall
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
            }
        },
        confirmButton = {
            MdTextButton(
                onClick = {
                    val provider = selectedProvider ?: return@MdTextButton
                    onConfirm(provider, name.trim(), selected)
                    onDismiss()
                },
                enabled = providers?.isNotEmpty() == true
                    && selectedProvider != null
                    && name.isNotBlank()
                    && selected.size >= 2
                    && selected.firstOrNull()?.let { id ->
                        candidates.firstOrNull { it.playerId == id }?.canGroupWith?.isNotEmpty() == true
                    } == true
            ) { Text("Create") }
        },
        dismissButton = {
            MdTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
