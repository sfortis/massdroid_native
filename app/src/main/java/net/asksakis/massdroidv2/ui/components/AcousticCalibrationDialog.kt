package net.asksakis.massdroidv2.ui.components


import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator
import net.asksakis.massdroidv2.data.sendspin.CalibrationOutcome
import net.asksakis.massdroidv2.data.sendspin.CalibrationProgress
import net.asksakis.massdroidv2.ui.permissions.AppPermissions

/**
 * Bluetooth route acoustic calibration dialog. Drives the two-pass
 * algorithm in [AcousticCalibrationCoordinator]:
 *
 * 1. **Phone-speaker reference pass** (only when no `mic_path` is cached):
 *    chirp through the built-in speaker via Oboe `setDeviceId`, extract
 *    `outputHAL` from the playback timestamp, compute and cache
 *    `mic_path = roundTrip - outputHAL`.
 * 2. **BT speaker pass**: chirp through the default output (BT), compute
 *    `compensation = bt_roundTrip - mic_path`, persist as the route
 *    correction.
 *
 * When the algorithm cannot run (no built-in speaker, routing override
 * silently ignored, Oboe output timestamp unavailable), the dialog offers
 * a legacy single-pass mode that uses Oboe's reported input latency as
 * an approximation. Tag-along device support without blocking users on
 * platforms that don't honour the proper algorithm.
 */
@Composable
fun AcousticCalibrationDialog(
    routeName: String,
    isPlaybackActive: Boolean,
    coordinator: AcousticCalibrationCoordinator,
    onPausePlayback: () -> Unit = {},
    onResumePlayback: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var phase by remember {
        mutableStateOf(
            if (isPlaybackActive) CalibrationPhase.PLAYBACK_ACTIVE
            else CalibrationPhase.INSTRUCTIONS
        )
    }
    var didPausePlayback by remember { mutableStateOf(false) }

    // Progress UI state — updated from the orchestrator's callbacks.
    var phaseNumber by remember { mutableIntStateOf(1) }
    var totalPhases by remember { mutableIntStateOf(1) }
    var phaseLabel by remember { mutableStateOf("Measuring") }
    var toneIndex by remember { mutableIntStateOf(0) }
    var totalTones by remember { mutableIntStateOf(6) }

    // Final result.
    var resultText by remember { mutableStateOf("") }
    var resultDetail by remember { mutableStateOf("") }
    var resultQuality by remember { mutableStateOf("") }
    var failureReason by remember { mutableStateOf("") }
    var fallbackAvailable by remember { mutableStateOf(false) }

    val dismissWithResume = {
        if (didPausePlayback) onResumePlayback()
        onDismiss()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        phase = if (granted) CalibrationPhase.MEASURING else CalibrationPhase.PERMISSION_DENIED
    }

    fun startMeasurement() {
        val missing = AppPermissions.missing(context, AppPermissions.acousticCalibrationRequired())
        if (missing.isNotEmpty()) {
            phase = CalibrationPhase.REQUESTING_PERMISSION
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        phase = CalibrationPhase.MEASURING
    }

    fun handleProgress(p: CalibrationProgress) {
        when (p) {
            is CalibrationProgress.Phase -> {
                phaseNumber = p.current
                totalPhases = p.total
                phaseLabel = p.label
                toneIndex = 0
            }
            is CalibrationProgress.Tone -> {
                toneIndex = p.current
                totalTones = p.total
            }
        }
    }

    fun handleOutcome(outcome: CalibrationOutcome) {
        when (outcome) {
            is CalibrationOutcome.Success -> {
                val detail = if (outcome.micPathUs > 0L) {
                    "BT round trip ${outcome.btRoundTripUs / 1000}ms minus mic path ${outcome.micPathUs / 1000}ms"
                } else {
                    "Legacy single-pass measurement"
                }
                resultText = "BT delay: ${outcome.compensationUs / 1000}ms"
                resultDetail = detail
                resultQuality = outcome.quality
                phase = CalibrationPhase.RESULT
            }
            is CalibrationOutcome.Failure -> {
                failureReason = outcome.reason
                fallbackAvailable = outcome.fallbackAvailable
                phase = CalibrationPhase.ERROR
            }
        }
    }

    // Drive the orchestrator from the MEASURING phase.
    if (phase == CalibrationPhase.MEASURING) {
        LaunchedEffect(Unit) {
            handleOutcome(coordinator.runBtCalibration(::handleProgress))
        }
    }

    // Legacy single-pass fallback (chosen explicitly by the user from the
    // ERROR phase when `fallbackAvailable` is true).
    if (phase == CalibrationPhase.MEASURING_LEGACY) {
        LaunchedEffect(Unit) {
            phaseNumber = 1
            totalPhases = 1
            phaseLabel = "Measuring (simple mode)"
            handleOutcome(
                coordinator.runLegacyBtCalibration { idx, total ->
                    toneIndex = idx
                    totalTones = total
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = dismissWithResume,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text("Bluetooth Calibration")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (phase) {
                    CalibrationPhase.PLAYBACK_ACTIVE -> {
                        Text("Playback will be paused during calibration.")
                        Text(
                            "Calibration requires the microphone and cannot run while music is playing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CalibrationPhase.INSTRUCTIONS -> {
                        Text("Calibrate \"$routeName\" for tighter sync.")
                        Text(
                            "Place the phone near the BT speaker. Set media volume around 50-70%, then keep the room quiet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CalibrationPhase.REQUESTING_PERMISSION, CalibrationPhase.PERMISSION_DENIED -> {
                        Text(
                            if (phase == CalibrationPhase.PERMISSION_DENIED)
                                "Microphone permission is required for calibration."
                            else "Requesting microphone access..."
                        )
                        Text(
                            "The microphone is used only to measure speaker delay. Audio is processed locally and not stored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CalibrationPhase.MEASURING, CalibrationPhase.MEASURING_LEGACY -> {
                        val phaseHeader = if (totalPhases > 1) {
                            "$phaseLabel ($phaseNumber/$totalPhases)"
                        } else {
                            phaseLabel
                        }
                        Text(phaseHeader)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { toneIndex.toFloat() / totalTones.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Playing tone $toneIndex/$totalTones",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CalibrationPhase.RESULT -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Text(resultText)
                        }
                        Text(
                            resultDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Quality: $resultQuality",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CalibrationPhase.ERROR -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            Text(failureReason)
                        }
                        Text(
                            if (fallbackAvailable) {
                                "This device blocked part of the proper algorithm. " +
                                    "You can try simple mode, which works on more devices but is less accurate."
                            } else {
                                "Move the phone closer to the speaker, raise media volume if needed, and try again."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                CalibrationPhase.PLAYBACK_ACTIVE -> {
                    MdTextButton(onClick = {
                        onPausePlayback()
                        didPausePlayback = true
                        phase = CalibrationPhase.INSTRUCTIONS
                    }) { Text("Pause and Continue") }
                }
                CalibrationPhase.INSTRUCTIONS -> {
                    MdTextButton(onClick = { startMeasurement() }) { Text("Start") }
                }
                CalibrationPhase.RESULT -> {
                    MdTextButton(onClick = dismissWithResume) { Text("Done") }
                }
                CalibrationPhase.ERROR -> {
                    if (fallbackAvailable) {
                        MdTextButton(onClick = {
                            phase = CalibrationPhase.MEASURING_LEGACY
                        }) { Text("Try simple mode") }
                    } else {
                        MdTextButton(onClick = {
                            phase = CalibrationPhase.INSTRUCTIONS
                        }) { Text("Retry") }
                    }
                }
                CalibrationPhase.PERMISSION_DENIED -> {
                    MdTextButton(onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("Grant Permission") }
                }
                else -> {} // measuring + requesting: no confirm button
            }
        },
        dismissButton = {
            if (phase != CalibrationPhase.MEASURING && phase != CalibrationPhase.MEASURING_LEGACY) {
                MdTextButton(onClick = dismissWithResume) { Text("Cancel") }
            }
        }
    )
}

private enum class CalibrationPhase {
    PLAYBACK_ACTIVE,
    INSTRUCTIONS,
    REQUESTING_PERMISSION,
    PERMISSION_DENIED,
    MEASURING,
    MEASURING_LEGACY,
    RESULT,
    ERROR
}
