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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
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
import net.asksakis.massdroidv2.ui.components.SettingsConfirmDialog
import net.asksakis.massdroidv2.ui.components.SettingsRow
import net.asksakis.massdroidv2.ui.components.SettingsSectionDivider
import net.asksakis.massdroidv2.ui.components.SettingsSectionHeader
import net.asksakis.massdroidv2.ui.components.SettingsTone
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

    SettingsScreenColumn(modifier = modifier) {
        SettingsSectionHeader("Bug reports")
        VersionsItem(serverVersion = serverVersion)
        ShareLogsItem(serverVersion = serverVersion)
        SettingsSectionDivider()
        SettingsSectionHeader("Battery")
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
    // The click is set here rather than through onClick so it can carry a label: a
    // screen reader announces "Copy versions" instead of a bare "double tap to activate".
    SettingsRow(
        title = "Versions",
        icon = Icons.Default.Info,
        supporting = summary,
        modifier = Modifier.clickable(onClickLabel = "Copy versions") { copyToClipboard(context, summary) },
        trailing = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
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
    // Not destructive, so the action keeps the plain colour: sharing loses nothing.
    if (confirming) {
        SettingsConfirmDialog(
            title = "Share logs?",
            text = "The zip holds what you played, your players and rooms and your " +
                "server address. Send it only to someone you trust.",
            confirmLabel = "Share",
            confirmTone = SettingsTone.NORMAL,
            onConfirm = {
                confirming = false
                building = true
                scope.launch {
                    val intent = try {
                        PersistentLogcatWriter.buildShareIntent(context, serverVersion)
                    } finally {
                        building = false
                    }
                    if (intent == null) {
                        status = "Couldn't share the logs. Android does not always let an " +
                            "app read its own logcat."
                        return@launch
                    }
                    status = null
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(intent, "Share MassDroid logs")
                        )
                    }.onFailure {
                        status = "Couldn't share the logs. ${it.message ?: "No app could receive them."}"
                    }
                }
            },
            onDismiss = { confirming = false }
        )
    }

    // A failure replaces the supporting line rather than sitting in a line of its own
    // under the row, so the row is the one place that says what happened to the share.
    SettingsRow(
        title = if (building) "Collecting logs" else "Share logs",
        icon = Icons.Default.Share,
        supporting = status ?: "A zip of the last day",
        onClick = { confirming = true },
        enabled = !building,
        tone = if (status != null) SettingsTone.ERROR else SettingsTone.NORMAL
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
    SettingsRow(
        title = "Battery optimization",
        icon = Icons.Default.BatteryChargingFull,
        supporting = if (excluded) {
            "Excluded, so background reconnects survive deep doze"
        } else {
            "Playback and room detection work either way, and excluding the app keeps it reconnecting in deep doze"
        },
        onClick = if (excluded) null else openSystemScreen
    )
}
