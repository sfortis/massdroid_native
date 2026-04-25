package net.asksakis.massdroidv2.ui.components

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    isActive: Boolean,
    remainingMinutes: Int,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showTimePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SheetDefaults.HeaderTitle(
                text = if (isActive) "Sleep Timer (${remainingMinutes}min left)" else "Sleep Timer",
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = SheetDefaults.HeaderVerticalPadding
                )
            )

            val chipModifier = Modifier.weight(1f)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 45).forEach { min ->
                        FilterChip(
                            selected = false,
                            onClick = { onStart(min); onDismiss() },
                            label = {
                                Text("$min min", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            },
                            modifier = chipModifier
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(60, 90, 120).forEach { min ->
                        FilterChip(
                            selected = false,
                            onClick = { onStart(min); onDismiss() },
                            label = {
                                Text("$min min", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            },
                            modifier = chipModifier
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                AssistChip(
                    onClick = { showTimePicker = true },
                    label = { Text("Custom time", style = MaterialTheme.typography.titleSmall) },
                    leadingIcon = {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }

            if (isActive) {
                MdTextButton(
                    onClick = { onCancel(); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel Timer", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showTimePicker) {
        SleepTimerPickerDialog(
            onSelect = { minutes -> showTimePicker = false; onStart(minutes); onDismiss() },
            onDismiss = { showTimePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerPickerDialog(
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberTimePickerState(initialHour = 1, initialMinute = 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Sleep Timer") },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = pickerState)
            }
        },
        confirmButton = {
            MdTextButton(onClick = {
                val totalMinutes = pickerState.hour * 60 + pickerState.minute
                if (totalMinutes > 0) onSelect(totalMinutes)
            }) { Text("OK") }
        },
        dismissButton = { MdTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
