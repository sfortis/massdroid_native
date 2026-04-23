package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    isBtRoute: Boolean = false,
    acousticCorrectionMs: Int = 0,
    calibrator: net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator? = null,
    hasPhoneBaseline: Boolean = false,
    phoneBaselineUs: Long = 0L,
    isPlaybackActive: Boolean = false,
    btRouteName: String = "",
    onPausePlayback: (() -> Unit)? = null,
    onResumePlayback: (() -> Unit)? = null,
    onBaselineComplete: ((Long) -> Unit)? = null,
    onAcousticCalibrationComplete: ((correctionUs: Long, quality: String) -> Unit)? = null,
    onResetPhoneBaseline: (() -> Unit)? = null,
    onResetBtCalibration: (() -> Unit)? = null,
    syncHistory: List<SendspinManager.SyncSample> = emptyList(),
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

    // BasicAlertDialog + custom layout so the action buttons don't eat the
    // vertical space that the Material3 AlertDialog reserves for its default
    // title/content/buttons sections.
    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 320.dp, max = 480.dp)
            .heightIn(max = 560.dp)
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            ),
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    "Player Settings",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
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
                        SteppedValueRow(
                            label = "Static delay",
                            valueLabel = "${staticDelayMs}ms",
                            onDecrement = {
                                staticDelayMs = (staticDelayMs - 2).coerceAtLeast(-500)
                                onStaticDelayChanged?.invoke(staticDelayMs)
                            },
                            onIncrement = {
                                staticDelayMs = (staticDelayMs + 2).coerceAtMost(500)
                                onStaticDelayChanged?.invoke(staticDelayMs)
                            }
                        )
                    }

                    // Acoustic calibration: phone baseline plus optional BT route calibration.
                    if (isLocalPlayer && calibrator != null) {
                        var showPhoneCalibrationDialog by remember { mutableStateOf(false) }
                        var showBtCalibrationDialog by remember { mutableStateOf(false) }
                        val phoneBaselineMs = phoneBaselineUs / 1000
                        val btDeviceName = btRouteName.ifBlank { "Bluetooth speaker" }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Phone speaker calibration", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (hasPhoneBaseline) "Baseline: ${phoneBaselineMs}ms" else "Not calibrated",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (hasPhoneBaseline) {
                                    TextButton(onClick = { onResetPhoneBaseline?.invoke() }) {
                                        Text("Reset")
                                    }
                                }
                                TextButton(onClick = { showPhoneCalibrationDialog = true }) {
                                    Text(if (hasPhoneBaseline) "Recalibrate" else "Calibrate")
                                }
                            }
                        }
                        if (showPhoneCalibrationDialog) {
                            AcousticCalibrationDialog(
                                routeName = "Phone speaker",
                                hasPhoneBaseline = hasPhoneBaseline,
                                phoneBaselineUs = phoneBaselineUs,
                                isBtRoute = false,
                                isPlaybackActive = isPlaybackActive,
                                calibrator = calibrator,
                                onPausePlayback = { onPausePlayback?.invoke() },
                                onResumePlayback = { onResumePlayback?.invoke() },
                                onDismiss = { showPhoneCalibrationDialog = false },
                                onBaselineComplete = { baselineUs ->
                                    onBaselineComplete?.invoke(baselineUs)
                                },
                                onCalibrationComplete = { correctionUs, quality ->
                                    onAcousticCalibrationComplete?.invoke(correctionUs, quality)
                                }
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Bluetooth device calibration", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    when {
                                        !isBtRoute -> "Connect a Bluetooth device to calibrate"
                                        acousticCorrectionMs > 0 -> "$btDeviceName: ${acousticCorrectionMs}ms"
                                        !hasPhoneBaseline -> "Phone baseline required first"
                                        else -> "$btDeviceName not calibrated"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isBtRoute && acousticCorrectionMs > 0) {
                                    TextButton(onClick = { onResetBtCalibration?.invoke() }) {
                                        Text("Reset")
                                    }
                                }
                                TextButton(
                                    enabled = isBtRoute,
                                    onClick = { showBtCalibrationDialog = true }
                                ) {
                                    Text(if (acousticCorrectionMs > 0) "Recalibrate" else "Calibrate")
                                }
                            }
                        }
                        if (showBtCalibrationDialog) {
                            AcousticCalibrationDialog(
                                routeName = btDeviceName,
                                hasPhoneBaseline = hasPhoneBaseline,
                                phoneBaselineUs = phoneBaselineUs,
                                isBtRoute = true,
                                isPlaybackActive = isPlaybackActive,
                                calibrator = calibrator,
                                onPausePlayback = { onPausePlayback?.invoke() },
                                onResumePlayback = { onResumePlayback?.invoke() },
                                onDismiss = { showBtCalibrationDialog = false },
                                onBaselineComplete = { baselineUs ->
                                    onBaselineComplete?.invoke(baselineUs)
                                },
                                onCalibrationComplete = { correctionUs, quality ->
                                    onAcousticCalibrationComplete?.invoke(correctionUs, quality)
                                }
                            )
                        }
                    }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
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
                }
            }
        }
    }
}

@Composable
internal fun SyncErrorGraph(samples: List<SendspinManager.SyncSample>) {
    val maxAbsError = samples.maxOfOrNull { kotlin.math.abs(it.errorMs) } ?: 0f
    val rangeMs = maxOf(25f, kotlin.math.ceil(maxAbsError / 10f).toInt() * 10f).coerceAtMost(250f)
    val latest = samples.lastOrNull()
    val goodColor = MaterialTheme.colorScheme.primary
    val warnColor = MaterialTheme.colorScheme.tertiary
    val badColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Sync convergence",
            style = labelStyle,
            color = labelColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 30.dp, end = 36.dp)
            ) {
                val topInset = 4.dp.toPx()
                val bottomInset = 4.dp.toPx()
                val graphHeight = size.height - topInset - bottomInset
                val centerY = topInset + graphHeight / 2f
                val stepX = size.width / (samples.size.coerceAtLeast(2) - 1).toFloat()

                // Grid: center line (0ms), lock band (±5ms), correction threshold (±20ms).
                drawLine(gridColor, Offset(0f, centerY), Offset(size.width, centerY), 1.dp.toPx())
                val lockMsY = graphHeight / 2f * (5f / rangeMs)
                drawLine(goodColor.copy(alpha = 0.45f), Offset(0f, centerY - lockMsY), Offset(size.width, centerY - lockMsY), 0.5.dp.toPx())
                drawLine(goodColor.copy(alpha = 0.45f), Offset(0f, centerY + lockMsY), Offset(size.width, centerY + lockMsY), 0.5.dp.toPx())
                val twentyMsY = graphHeight / 2f * (20f / rangeMs)
                drawLine(warnColor.copy(alpha = 0.6f), Offset(0f, centerY - twentyMsY), Offset(size.width, centerY - twentyMsY), 0.5.dp.toPx())
                drawLine(warnColor.copy(alpha = 0.6f), Offset(0f, centerY + twentyMsY), Offset(size.width, centerY + twentyMsY), 0.5.dp.toPx())

                // Actual sync convergence: anchor error moving toward 0ms.
                val points = samples.mapIndexed { i, s ->
                    val x = stepX * i
                    val normalized = (s.errorMs / rangeMs).coerceIn(-1f, 1f)
                    val y = centerY - normalized * (graphHeight / 2f)
                    Offset(x, y)
                }

                if (points.size >= 2) {
                    val path = Path()
                    path.moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        val prev = points[i - 1]
                        val curr = points[i]
                        val midX = (prev.x + curr.x) / 2f
                        val midY = (prev.y + curr.y) / 2f
                        path.quadraticTo(prev.x, prev.y, midX, midY)
                    }
                    path.lineTo(points.last().x, points.last().y)

                    // Color based on latest error magnitude
                    val absErr = kotlin.math.abs(latest?.errorMs ?: 0f)
                    val lineColor = when {
                        absErr < 5f -> goodColor
                        absErr < 20f -> warnColor
                        else -> badColor
                    }

                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))

                    // Endpoint dot
                    drawCircle(lineColor, radius = 3.dp.toPx(), center = points.last())
                }
            }

            // Labels
            Text(
                text = "+${rangeMs.toInt()}",
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Text(
                text = "-${rangeMs.toInt()}",
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.BottomStart)
            )
            latest?.let {
                val absErr = kotlin.math.abs(it.errorMs)
                val errColor = when {
                    absErr < 5f -> goodColor
                    absErr < 20f -> warnColor
                    else -> badColor
                }
                Text(
                    text = "${"%.1f".format(it.errorMs)}ms",
                    style = labelStyle,
                    color = errColor,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // Output latency + filter error info line
        latest?.let {
            Text(
                text = "Sync=${"%.1f".format(it.errorMs)}ms  " +
                    "Output=${"%.0f".format(it.outputLatencyMs)}ms  Clock=${"%.1f".format(it.filterErrorMs)}ms",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
