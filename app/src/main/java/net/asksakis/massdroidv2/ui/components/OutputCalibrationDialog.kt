package net.asksakis.massdroidv2.ui.components

import android.Manifest
import android.content.Context
import android.media.AudioManager
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
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator
import net.asksakis.massdroidv2.data.sendspin.CalibrationProgress
import net.asksakis.massdroidv2.data.sendspin.CalibrationOutcome
import net.asksakis.massdroidv2.data.sendspin.OutputKind
import net.asksakis.massdroidv2.ui.permissions.AppPermissions

/** Which output an [OutputCalibrationDialog] measures. */
enum class OutputCalibrationTarget { SPEAKER, BLUETOOTH }

/** The calibration that measures an output of this kind, or null when it cannot be calibrated. */
fun OutputKind.calibrationTarget(): OutputCalibrationTarget? = when (this) {
    OutputKind.SPEAKER -> OutputCalibrationTarget.SPEAKER
    OutputKind.BLUETOOTH -> OutputCalibrationTarget.BLUETOOTH
    OutputKind.WIRED, OutputKind.USB -> null
}

/**
 * Measures how much later than its reported timestamp an output is heard (see
 * [AcousticCalibrationCoordinator]): a short sequence of chirps plays while the
 * microphone listens, and the result is stored for the route.
 *
 * For the built-in speaker the media volume is raised to about 80 % for the
 * measurement and put back afterwards, so the chirps stand well above the room.
 * A Bluetooth device keeps the listener's volume: it may be a car or a loud
 * speaker, and the measurement does not need it louder.
 */
@Composable
fun OutputCalibrationDialog(
    coordinator: AcousticCalibrationCoordinator,
    target: OutputCalibrationTarget,
    onDismiss: () -> Unit,
    routeName: String = "",
) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(OutputCalPhase.INSTRUCTIONS) }
    var resultText by remember { mutableStateOf("") }
    var failureReason by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<CalibrationProgress?>(null) }
    val outputName = when (target) {
        OutputCalibrationTarget.SPEAKER -> "this phone's speaker"
        OutputCalibrationTarget.BLUETOOTH -> routeName.ifBlank { "the Bluetooth device" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        phase = if (granted) OutputCalPhase.MEASURING else OutputCalPhase.PERMISSION_DENIED
    }

    fun startMeasurement() {
        val missing = AppPermissions.missing(context, AppPermissions.acousticCalibrationRequired())
        if (missing.isNotEmpty()) {
            phase = OutputCalPhase.REQUESTING_PERMISSION
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        phase = OutputCalPhase.MEASURING
    }

    if (phase == OutputCalPhase.MEASURING) {
        LaunchedEffect(Unit) {
            progress = null
            val outcome = when (target) {
                OutputCalibrationTarget.SPEAKER ->
                    withSpeakerVolume(context) { coordinator.calibrateSpeaker { progress = it } }
                OutputCalibrationTarget.BLUETOOTH -> coordinator.calibrateBluetooth { progress = it }
            }
            when (outcome) {
                is CalibrationOutcome.Success -> {
                    resultText = "Heard ${outcome.latencyUs / 1000} ms after its timestamp"
                    phase = OutputCalPhase.RESULT
                }
                is CalibrationOutcome.Failure -> {
                    failureReason = outcome.reason
                    phase = OutputCalPhase.ERROR
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (phase != OutputCalPhase.MEASURING) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (target == OutputCalibrationTarget.SPEAKER) Icons.Default.Speaker else Icons.Default.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(if (target == OutputCalibrationTarget.SPEAKER) "Speaker calibration" else "Bluetooth calibration")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (phase) {
                    OutputCalPhase.INSTRUCTIONS -> {
                        Text("Measure how late $outputName plays, for tighter group sync.")
                        Text(
                            when (target) {
                                OutputCalibrationTarget.SPEAKER ->
                                    "Keep the room quiet and the phone where you listen. Chirps play for about " +
                                        "fifteen seconds, and the music in the group pauses meanwhile."
                                OutputCalibrationTarget.BLUETOOTH ->
                                    "Keep the room quiet and the phone where you listen. Chirps play until the " +
                                        "speaker's delay holds steady, which takes up to two minutes on some " +
                                        "speakers. The music in the group pauses meanwhile."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutputCalPhase.REQUESTING_PERMISSION, OutputCalPhase.PERMISSION_DENIED -> {
                        Text(
                            if (phase == OutputCalPhase.PERMISSION_DENIED) {
                                "Calibration needs the microphone."
                            } else {
                                "Asking for the microphone..."
                            }
                        )
                        Text(
                            "The microphone only measures the delay. The recording stays on the phone and is not kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutputCalPhase.MEASURING -> {
                        val current = progress
                        val lastMs = current?.lastLatencyUs?.div(1000)
                        Text(
                            when {
                                lastMs == null -> "Listening for the test sound"
                                current.settling -> "The speaker is still adjusting its delay ($lastMs ms)"
                                else -> "Heard $lastMs ms after its timestamp. Checking that it holds."
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        current?.let {
                            Text(
                                "%d:%02d".format(it.elapsedS / 60, it.elapsedS % 60),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutputCalPhase.RESULT -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Text(resultText)
                        }
                        Text(
                            "Grouped playback now starts that much earlier on this output.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutputCalPhase.ERROR -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            Text(failureReason)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                OutputCalPhase.INSTRUCTIONS -> MdTextButton(onClick = { startMeasurement() }) { Text("Start") }
                OutputCalPhase.RESULT -> MdTextButton(onClick = onDismiss) { Text("Done") }
                OutputCalPhase.ERROR -> MdTextButton(onClick = { phase = OutputCalPhase.INSTRUCTIONS }) { Text("Retry") }
                OutputCalPhase.PERMISSION_DENIED -> MdTextButton(onClick = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Allow microphone") }
                else -> {}
            }
        },
        // Cancel also stops a measurement in progress: leaving the composition
        // cancels it, and the group's playback is resumed.
        dismissButton = {
            if (phase != OutputCalPhase.RESULT) {
                MdTextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Runs [block] with the media volume at about 80 %, then puts the listener's level back. */
private suspend fun <T> withSpeakerVolume(context: Context, block: suspend () -> T): T {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return block()
    val saved = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    val target = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 4 / 5).coerceAtLeast(1)
    runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    return try {
        block()
    } finally {
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
    }
}

private enum class OutputCalPhase {
    INSTRUCTIONS,
    REQUESTING_PERMISSION,
    PERMISSION_DENIED,
    MEASURING,
    RESULT,
    ERROR
}
