package net.asksakis.massdroidv2.tv.ui

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.data.update.AppUpdateChecker
import net.asksakis.massdroidv2.domain.whatsnew.WhatsNewParser

private val UPDATE_DIALOG_WIDTH = 560.dp
private const val UPDATE_NOTES_MAX_ENTRIES = 6
private const val BYTES_PER_MB = 1024 * 1024

/**
 * Offers a new GitHub release: what it brings, and Download & install or Later. Shown over
 * whatever screen is open, once the updater has found a newer version.
 */
@Composable
fun TvUpdatePrompt(viewModel: TvUpdateViewModel = hiltViewModel()) {
    val pending by viewModel.pendingUpdate.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.installIntent.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w("TvUpdate", "No package installer: ${e.message}")
            }
        }
    }
    val info = pending ?: return
    UpdateDialog(
        info = info,
        download = download,
        onInstall = viewModel::downloadAndInstall,
        onLater = viewModel::dismiss
    )
}

@Composable
private fun UpdateDialog(
    info: AppUpdateChecker.UpdateInfo,
    download: TvUpdateDownload,
    onInstall: () -> Unit,
    onLater: () -> Unit
) {
    val installFocus = remember { FocusRequester() }
    // The notes are the release's WHATSNEW.md. The shared parser drops the animation tags and
    // issue numbers, so the dialog lists the same entries as the phone's What's New sheet.
    val entries = remember(info) {
        WhatsNewParser.parse(info.releaseNotes, info.version).sections
            .flatMap { it.entries }
            .map { it.text }
            .take(UPDATE_NOTES_MAX_ENTRIES)
    }
    val running = download is TvUpdateDownload.Running
    Dialog(onDismissRequest = onLater) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp).width(UPDATE_DIALOG_WIDTH)) {
                Text("Update available", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                val sizeMb = info.fileSizeBytes / BYTES_PER_MB
                Text(
                    "MassDroid TV ${info.version} is available" + if (sizeMb > 0) " ($sizeMb MB)." else ".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entries.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    entries.forEach { entry ->
                        Text(
                            "• $entry",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
                if (download is TvUpdateDownload.Failed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "The download failed. Check the network and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onInstall,
                        enabled = !running,
                        modifier = Modifier
                            .focusRequester(installFocus)
                            .pointerClick(enabled = !running, onClick = onInstall)
                    ) {
                        Text(
                            when (download) {
                                is TvUpdateDownload.Running -> "Downloading ${download.percent}%"
                                TvUpdateDownload.Failed -> "Try again"
                                TvUpdateDownload.Idle -> "Download & install"
                            }
                        )
                    }
                    Button(
                        onClick = onLater,
                        enabled = !running,
                        modifier = Modifier.pointerClick(enabled = !running, onClick = onLater)
                    ) {
                        Text("Later")
                    }
                }
            }
        }
    }
    LaunchedEffect(info) { runCatching { installFocus.requestFocus() } }
}
