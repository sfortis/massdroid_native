package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.util.Log
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat

@Composable
fun PlayerSettingsDialog(
    player: Player,
    initialDstmEnabled: Boolean?,
    isSendspinPlayer: Boolean = false,
    isLocalPlayer: Boolean = false,
    initialAudioFormat: SendspinAudioFormat = SendspinAudioFormat.SMART,
    initialStaticDelayMs: Int = 0,
    onLoadConfig: suspend (playerId: String) -> PlayerConfig?,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    onDstmChanged: ((enabled: Boolean) -> Unit)?,
    onAudioFormatChanged: ((SendspinAudioFormat) -> Unit)? = null,
    onStaticDelayChanged: ((Int) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(player.displayName) }
    var crossfadeMode by remember { mutableStateOf(CrossfadeMode.DISABLED) }
    var volumeNormalization by remember { mutableStateOf(false) }
    var dontStopTheMusic by remember { mutableStateOf(initialDstmEnabled ?: false) }
    var selectedFormatValue by remember { mutableStateOf<String?>(null) }
    var formatOptions by remember { mutableStateOf<List<net.asksakis.massdroidv2.domain.model.FormatOption>>(emptyList()) }
    var audioFormat by remember(initialAudioFormat) { mutableStateOf(initialAudioFormat) }
    var staticDelayMs by remember(initialStaticDelayMs) { mutableIntStateOf(initialStaticDelayMs) }

    LaunchedEffect(player.playerId) {
        val loaded = onLoadConfig(player.playerId)
        if (loaded != null) {
            name = loaded.name.ifBlank { player.displayName }
            crossfadeMode = loaded.crossfadeMode
            volumeNormalization = loaded.volumeNormalization
            formatOptions = loaded.sendspinFormatOptions
            selectedFormatValue = loaded.sendspinFormat
            Log.d("PlayerSettings", "Loaded: provider=${player.provider} format=${loaded.sendspinFormat} options=${loaded.sendspinFormatOptions.map { it.value }}")
        }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Player Settings") },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Player name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Crossfade", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        CrossfadeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = crossfadeMode == mode,
                                onClick = { crossfadeMode = mode },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = CrossfadeMode.entries.size
                                ),
                                label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Volume normalization")
                        Switch(
                            checked = volumeNormalization,
                            onCheckedChange = { volumeNormalization = it }
                        )
                    }

                    if (initialDstmEnabled != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Don't stop the music")
                                Text(
                                    "Auto-fill queue when it runs out",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = dontStopTheMusic,
                                onCheckedChange = { dontStopTheMusic = it }
                            )
                        }
                    }

                    if (isSendspinPlayer && formatOptions.isNotEmpty()) {
                        val smartOption = net.asksakis.massdroidv2.domain.model.FormatOption(
                            title = "Smart", value = "smart"
                        )
                        val allOptions = if (isLocalPlayer) listOf(smartOption) + formatOptions else formatOptions
                        val currentValue = selectedFormatValue ?: "automatic"
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Audio format", style = MaterialTheme.typography.labelMedium)
                            var expanded by remember { mutableStateOf(false) }
                            val selectedTitle = allOptions.find { it.value == currentValue }?.title
                                ?: allOptions.firstOrNull()?.title ?: ""
                            Box {
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(selectedTitle, style = MaterialTheme.typography.bodyMedium)
                                            if (currentValue == "smart") {
                                                Text(
                                                    "Auto-switches codec based on network",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    allOptions.forEach { opt ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(opt.title, style = MaterialTheme.typography.bodyMedium)
                                                    if (opt.value == "smart") {
                                                        Text(
                                                            "FLAC on WiFi, Opus on mobile",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                selectedFormatValue = opt.value
                                                expanded = false
                                            },
                                            trailingIcon = if (currentValue == opt.value) {{
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }} else null
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isLocalPlayer) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Sync offset: ${if (staticDelayMs >= 0) "+$staticDelayMs" else "$staticDelayMs"} ms",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                IconButton(
                                    onClick = {
                                        staticDelayMs = (staticDelayMs - 2).coerceAtLeast(-500)
                                        onStaticDelayChanged?.invoke(staticDelayMs)
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Decrease",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Slider(
                                    value = staticDelayMs.toFloat(),
                                    onValueChange = {
                                        // Snap to 0 when within ±5ms
                                        staticDelayMs = if (it in -5f..5f) 0 else it.toInt()
                                    },
                                    onValueChangeFinished = {
                                        onStaticDelayChanged?.invoke(staticDelayMs)
                                    },
                                    valueRange = -500f..500f,
                                    steps = 0,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        staticDelayMs = (staticDelayMs + 2).coerceAtMost(500)
                                        onStaticDelayChanged?.invoke(staticDelayMs)
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Increase",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val values = mutableMapOf<String, Any>(
                        "smart_fades_mode" to crossfadeMode.apiValue,
                        "volume_normalization" to volumeNormalization
                    )
                    if (name.isNotBlank() && name.trim() != player.displayName) {
                        values["name"] = name.trim()
                    }
                    val newFormat = selectedFormatValue
                    if (isSendspinPlayer && newFormat != null) {
                        val serverValue = if (newFormat == "smart") "automatic" else newFormat
                        values["preferred_sendspin_format"] = serverValue
                        if (isLocalPlayer) {
                            val localFormat = when {
                                newFormat == "smart" -> SendspinAudioFormat.SMART
                                newFormat.startsWith("opus") -> SendspinAudioFormat.OPUS
                                newFormat.startsWith("flac") -> SendspinAudioFormat.FLAC
                                newFormat.startsWith("pcm") -> SendspinAudioFormat.PCM
                                else -> null
                            }
                            if (localFormat != null) onAudioFormatChanged?.invoke(localFormat)
                        }
                    }
                    onSave(player.playerId, values)
                    if (initialDstmEnabled != null && dontStopTheMusic != initialDstmEnabled) {
                        onDstmChanged?.invoke(dontStopTheMusic)
                    }
                    onDismiss()
                },
                enabled = !isLoading
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
