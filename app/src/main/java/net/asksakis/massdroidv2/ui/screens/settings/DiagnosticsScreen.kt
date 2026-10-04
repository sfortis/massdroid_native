package net.asksakis.massdroidv2.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.BuildConfig
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.ui.components.MdTextButton
import net.asksakis.massdroidv2.util.PersistentLogcatWriter

/**
 * What somebody needs when they are reporting a problem: the versions in play, the logs,
 * and the one system setting that changes how the app behaves in the background.
 *
 * Its own settings category rather than a group inside About, because About answers
 * "which app is this" and this screen answers "why is it doing that".
 */
@Composable
fun DiagnosticsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverVersion = (connectionState as? ConnectionState.Connected)?.serverInfo?.serverVersion

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        VersionsItem(serverVersion = serverVersion)
        HorizontalDivider()
        ShareLogsItem(serverVersion = serverVersion)
        HorizontalDivider()
        BatteryOptimizationItem()
    }
}

/**
 * The three versions a bug report always asks for, on one line and copyable in one tap.
 */
@Composable
private fun VersionsItem(serverVersion: String?) {
    val context = LocalContext.current
    val summary = "App ${BuildConfig.VERSION_NAME}, " +
        "server ${serverVersion ?: "not connected"}, " +
        "Android ${Build.VERSION.RELEASE}"
    ListItem(
        headlineContent = { Text("Versions") },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
        trailingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
        modifier = Modifier.clickable { copyToClipboard(context, summary) }
    )
}

/**
 * Android 13 shows its own confirmation for a copy, so a second toast would double it.
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("MassDroid versions", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ShareLogsItem(serverVersion: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    // Disabled while a build runs: each tap used to start its own coroutine, and two
    // within the same second wrote the same second-stamped zip at once.
    var building by remember { mutableStateOf(false) }

    // The warning sits in the dialog rather than under the row. What the zip holds
    // matters at the moment of sending it, and as a permanent paragraph it was the
    // longest thing on the screen.
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Share logs") },
            text = {
                Text(
                    "The zip holds what you played, your players and rooms and your " +
                        "server address. Send it only to someone you trust."
                )
            },
            confirmButton = {
                MdTextButton(onClick = {
                    confirming = false
                    building = true
                    scope.launch {
                        val intent = try {
                            PersistentLogcatWriter.buildShareIntent(context, serverVersion)
                        } finally {
                            building = false
                        }
                        if (intent == null) {
                            status = "No logs to share. Android does not always let an app " +
                                "read its own logcat."
                            return@launch
                        }
                        status = null
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(intent, "Share MassDroid logs")
                            )
                        }.onFailure { status = "Share failed: ${it.message}" }
                    }
                }) { Text("Share") }
            },
            dismissButton = {
                MdTextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }

    ListItem(
        headlineContent = { Text(if (building) "Collecting logs…" else "Share logs") },
        supportingContent = {
            val message = status
            if (message == null) {
                Text("A zip of the last day")
            } else {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        },
        leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
        modifier = Modifier.clickable(enabled = !building) { confirming = true }
    )
}

@Composable
private fun BatteryOptimizationItem() {
    val context = LocalContext.current
    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    var excluded by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    // Re-read on resume: the user grants it in the system screen and returns here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                excluded = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val openSystemScreen = {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}")
                )
            )
        }
        Unit
    }
    ListItem(
        headlineContent = { Text("Battery optimization") },
        supportingContent = {
            Text(
                if (excluded) {
                    "Excluded. Background reconnects survive deep doze."
                } else {
                    "Not excluded. Playback and room detection still work. Tap to exclude."
                }
            )
        },
        leadingContent = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null) },
        modifier = if (excluded) Modifier else Modifier.clickable(onClick = openSystemScreen)
    )
}
