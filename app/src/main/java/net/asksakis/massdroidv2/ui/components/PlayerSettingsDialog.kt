package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat

@Composable
fun PlayerSettingsDialog(
    player: Player,
    initialDstmEnabled: Boolean?,
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
    var audioFormat by remember { mutableStateOf(initialAudioFormat) }
    var staticDelayMsText by remember { mutableStateOf(initialStaticDelayMs.toString()) }

    LaunchedEffect(player.playerId) {
        val loaded = onLoadConfig(player.playerId)
        if (loaded != null) {
            name = loaded.name.ifBlank { player.displayName }
            crossfadeMode = loaded.crossfadeMode
            volumeNormalization = loaded.volumeNormalization
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

                    if (isLocalPlayer) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Audio format", style = MaterialTheme.typography.labelMedium)
                            Text(
                                when (audioFormat) {
                                    SendspinAudioFormat.SMART -> "FLAC on WiFi, Opus on mobile data"
                                    SendspinAudioFormat.OPUS -> "Low bandwidth (~128kbps)"
                                    SendspinAudioFormat.FLAC -> "Lossless (~800kbps)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SendspinAudioFormat.entries.forEachIndexed { index, fmt ->
                                SegmentedButton(
                                    selected = audioFormat == fmt,
                                    onClick = { audioFormat = fmt },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = SendspinAudioFormat.entries.size
                                    ),
                                    label = { Text(fmt.label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = staticDelayMsText,
                            onValueChange = { value ->
                                staticDelayMsText = value.filter { it.isDigit() }.take(4)
                            },
                            label = { Text("Static delay (ms)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
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
                    onSave(player.playerId, values)
                    if (initialDstmEnabled != null && dontStopTheMusic != initialDstmEnabled) {
                        onDstmChanged?.invoke(dontStopTheMusic)
                    }
                    if (isLocalPlayer && audioFormat != initialAudioFormat) {
                        onAudioFormatChanged?.invoke(audioFormat)
                    }
                    if (isLocalPlayer) {
                        val parsedStaticDelayMs = staticDelayMsText.toIntOrNull()?.coerceIn(0, 5000) ?: 0
                        if (parsedStaticDelayMs != initialStaticDelayMs) {
                            onStaticDelayChanged?.invoke(parsedStaticDelayMs)
                        }
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
