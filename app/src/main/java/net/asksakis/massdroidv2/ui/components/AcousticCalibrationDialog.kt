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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.NativeAcousticCalibrator
import net.asksakis.massdroidv2.ui.permissions.AppPermissions

/**
 * Dialog for acoustic latency calibration.
 * Phone speaker calibration is stored as the baseline. BT routes store their
 * own measured round-trip and show the phone baseline for comparison.
 */
@Composable
fun AcousticCalibrationDialog(
    routeName: String,
    hasPhoneBaseline: Boolean,
    phoneBaselineUs: Long = 0L,
    isBtRoute: Boolean = true,
    isPlaybackActive: Boolean,
    calibrator: NativeAcousticCalibrator,
    onPausePlayback: () -> Unit = {},
    onResumePlayback: () -> Unit = {},
    onDismiss: () -> Unit,
    onBaselineComplete: (baselineUs: Long) -> Unit,
    onCalibrationComplete: (correctionUs: Long, quality: String) -> Unit
) {
    val context = LocalContext.current

    var phase by remember { mutableStateOf(
        if (isPlaybackActive) CalibrationPhase.PLAYBACK_ACTIVE
        else if (isBtRoute && !hasPhoneBaseline) CalibrationPhase.NEED_BASELINE
        else CalibrationPhase.INSTRUCTIONS
    )}
    var progress by remember { mutableIntStateOf(0) }
    var totalSteps by remember { mutableIntStateOf(6) }
    var resultText by remember { mutableStateOf("") }
    var resultQuality by remember { mutableStateOf("") }
    var resultCorrectionUs by remember { mutableStateOf(0L) }
    var baselineUs by remember(phoneBaselineUs) { mutableStateOf(phoneBaselineUs) }
    var pendingBaselineMeasurement by remember { mutableStateOf(false) }
    var didPausePlayback by remember { mutableStateOf(false) }

    // Resume playback if we paused it and the dialog is being dismissed
    val dismissWithResume = {
        if (didPausePlayback) onResumePlayback()
        onDismiss()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            phase = if (pendingBaselineMeasurement) CalibrationPhase.MEASURING_BASELINE
            else CalibrationPhase.MEASURING_BT
        } else {
            phase = CalibrationPhase.PERMISSION_DENIED
        }
    }

    fun startMeasurement(forBaseline: Boolean) {
        val missing = AppPermissions.missing(context, AppPermissions.acousticCalibrationRequired())
        pendingBaselineMeasurement = forBaseline
        if (missing.isNotEmpty()) {
            phase = CalibrationPhase.REQUESTING_PERMISSION
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        phase = if (forBaseline) CalibrationPhase.MEASURING_BASELINE else CalibrationPhase.MEASURING_BT
    }

    // Run calibration when phase changes to measuring
    if (phase == CalibrationPhase.MEASURING_BASELINE || phase == CalibrationPhase.MEASURING_BT) {
        val isBaseline = phase == CalibrationPhase.MEASURING_BASELINE
        androidx.compose.runtime.LaunchedEffect(phase) {
            calibrator.onProgress = { step, total ->
                progress = step
                totalSteps = total
            }
            val maxDelay = if (isBaseline) 150 else 500
            val result = calibrator.measureRoundTrip(maxDelayMs = maxDelay)
            calibrator.onProgress = null

            when (result.quality) {
                NativeAcousticCalibrator.Quality.FAILED -> {
                    resultText = "Calibration failed. Too much noise or speaker too quiet."
                    phase = CalibrationPhase.ERROR
                }
                else -> {
                    if (isBaseline) {
                        baselineUs = result.roundTripUs
                        onBaselineComplete(result.roundTripUs)
                        if (isBtRoute) {
                            phase = CalibrationPhase.BASELINE_DONE
                        } else {
                            resultText = "Phone speaker baseline: ${result.roundTripUs / 1000}ms"
                            resultQuality = result.quality.name
                            phase = CalibrationPhase.BASELINE_RESULT
                        }
                    } else {
                        // Store full BT round-trip as correction (not the difference).
                        // The engine's acousticExtraUs() subtracts measuredPipeline,
                        // giving totalComp = btRoundTrip. The phone baseline is shown
                        // for reference but not used in the compensation math.
                        val correction = result.roundTripUs.coerceIn(0, 500_000)
                        val extraOverPhone = (result.roundTripUs - baselineUs).coerceAtLeast(0)
                        resultCorrectionUs = correction
                        resultQuality = result.quality.name
                        resultText = "BT delay: ${correction / 1000}ms (+${extraOverPhone / 1000}ms over phone)"
                        phase = CalibrationPhase.RESULT
                    }
                }
            }
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
                    if (phase == CalibrationPhase.NEED_BASELINE || phase == CalibrationPhase.MEASURING_BASELINE)
                        Icons.Default.PhoneAndroid else Icons.Default.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    when (phase) {
                        CalibrationPhase.NEED_BASELINE,
                        CalibrationPhase.MEASURING_BASELINE,
                        CalibrationPhase.BASELINE_RESULT ->
                            "Phone Speaker Baseline"
                        CalibrationPhase.BASELINE_DONE -> "Baseline Complete"
                        else -> if (isBtRoute) "BT Speaker Calibration" else "Phone Speaker Calibration"
                    }
                )
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
                    CalibrationPhase.NEED_BASELINE -> {
                        Text("First, calibrate the phone speaker as a baseline.")
                        Text(
                            "Disconnect Bluetooth and switch audio to the phone speaker, then tap Calibrate Baseline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    CalibrationPhase.INSTRUCTIONS -> {
                        Text("Calibrate \"$routeName\" for tighter sync.")
                        Text(
                            if (isBtRoute) {
                                "Place the phone near the BT speaker. Set media volume around 50-70%, then keep the room quiet."
                            } else {
                                "Use the phone speaker. Set media volume around 50-70%, then keep the room quiet."
                            },
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
                    CalibrationPhase.MEASURING_BASELINE, CalibrationPhase.MEASURING_BT -> {
                        Text(
                            if (phase == CalibrationPhase.MEASURING_BASELINE)
                                "Measuring phone speaker..." else "Measuring BT speaker..."
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress.toFloat() / totalSteps },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Playing tone $progress/$totalSteps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CalibrationPhase.BASELINE_DONE -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Baseline: ${baselineUs / 1000}ms")
                        }
                        Text(
                            "Now connect the Bluetooth speaker and tap Continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CalibrationPhase.BASELINE_RESULT -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Text(resultText)
                        }
                        Text(
                            "Quality: $resultQuality",
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
                            Text(resultText)
                        }
                        Text(
                            "Move the phone closer to the speaker, raise media volume if needed, and try again.",
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
	                    TextButton(onClick = {
	                        onPausePlayback()
	                        didPausePlayback = true
	                        phase = if (isBtRoute && !hasPhoneBaseline) CalibrationPhase.NEED_BASELINE
	                            else CalibrationPhase.INSTRUCTIONS
	                    }) { Text("Pause and Continue") }
	                }
                CalibrationPhase.NEED_BASELINE -> {
                    TextButton(onClick = { startMeasurement(forBaseline = true) }) {
                        Text("Calibrate Baseline")
                    }
                }
	                CalibrationPhase.INSTRUCTIONS -> {
	                    TextButton(onClick = { startMeasurement(forBaseline = !isBtRoute) }) {
	                        Text("Start")
	                    }
	                }
                CalibrationPhase.BASELINE_DONE -> {
                    TextButton(onClick = {
                        phase = CalibrationPhase.INSTRUCTIONS
                    }) { Text("Continue") }
                }
	                CalibrationPhase.RESULT -> {
	                    TextButton(onClick = {
	                        onCalibrationComplete(resultCorrectionUs, resultQuality)
	                        onDismiss()
	                    }) { Text("Save") }
	                }
	                CalibrationPhase.BASELINE_RESULT -> {
	                    TextButton(onClick = onDismiss) { Text("Done") }
	                }
	                CalibrationPhase.ERROR -> {
	                    TextButton(onClick = {
	                        phase = if (isBtRoute && !hasPhoneBaseline && baselineUs == 0L)
	                            CalibrationPhase.NEED_BASELINE else CalibrationPhase.INSTRUCTIONS
	                    }) { Text("Retry") }
	                }
                CalibrationPhase.PERMISSION_DENIED -> {
                    TextButton(onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("Grant Permission") }
                }
                else -> {} // measuring states: no confirm button
            }
        },
        dismissButton = {
            if (phase != CalibrationPhase.MEASURING_BASELINE && phase != CalibrationPhase.MEASURING_BT) {
                TextButton(onClick = dismissWithResume) { Text("Cancel") }
            }
        }
    )
}

private enum class CalibrationPhase {
    PLAYBACK_ACTIVE,
    NEED_BASELINE,
    INSTRUCTIONS,
    REQUESTING_PERMISSION,
    PERMISSION_DENIED,
    MEASURING_BASELINE,
    MEASURING_BT,
	    BASELINE_DONE,
	    BASELINE_RESULT,
	    RESULT,
    ERROR
}
