package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.sendspin.SyncState
import net.asksakis.massdroidv2.domain.model.AudioFormatInfo
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.components.SteppedValueRow
import net.asksakis.massdroidv2.ui.components.SyncErrorGraph
import net.asksakis.massdroidv2.ui.screens.nowplaying.SendspinStatusUi

private fun formatMs(valueMs: Float): String =
    String.format(java.util.Locale.US, "%.1fms", valueMs)

/**
 * Bottom sheet shown when the user taps the audio-quality badge while the
 * local Sendspin player is selected. Exposes the Sendspin transport state,
 * input/output format, sync details, buffer fill graph and static-delay
 * stepper. Pure display + the static-delay callback — all state comes in
 * via [status] and [syncHistory].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendspinStatusSheet(
    status: SendspinStatusUi,
    inputAudioFormat: AudioFormatInfo? = null,
    syncHistory: List<SendspinManager.SyncSample> = emptyList(),
    onSyncDelayChanged: (Int) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bufferSeconds = status.activeBufferMs / 1000f
    val maxSeconds = 30f
    val bufferColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    val syncAbsMs = kotlin.math.abs(status.absoluteSyncMs)
    val stateLabel = when (status.syncState) {
        SyncState.IDLE -> "Idle"
        SyncState.SYNCHRONIZED -> "Synchronized"
        SyncState.HOLDOVER_PLAYING_FROM_BUFFER -> "Holdover"
        SyncState.SYNC_ERROR_REBUFFERING -> "Rebuffering"
    }
    val transportLabel = when (status.connectionState) {
        // "Streaming" only when audio is actually flowing. The Sendspin
        // server keeps us in the STREAMING transport state across
        // pauses/sleeps (WS stays open, ready for the next stream),
        // but a "Streaming" label paired with playback=Idle reads as
        // a contradiction in the status sheet. Surface "Ready" for
        // the connected-but-not-playing case.
        SendspinState.STREAMING -> if (status.syncState == SyncState.IDLE) "Ready" else "Streaming"
        SendspinState.SYNCING -> "Syncing"
        SendspinState.HANDSHAKING -> "Handshaking"
        SendspinState.AUTHENTICATING -> "Authenticating"
        SendspinState.CONNECTING -> "Connecting"
        SendspinState.ERROR -> "Error"
        SendspinState.DISCONNECTED -> "Disconnected"
    }
    val syncLockLabel = when {
        // DIRECT (solo) is a pure FIFO — no peer to phase-lock to, no sync loop.
        status.correctionMode.equals("DIRECT", ignoreCase = true) -> "Direct (no sync)"
        status.syncState != SyncState.SYNCHRONIZED -> stateLabel
        // While muted (startup/seek/resume) the callback snaps onto the timeline;
        // it only unmutes once the drift is already inside the lock window.
        status.syncMuted -> "Aligning (muted)"
        // Live drift = intended timeline minus DAC presentation (the value the
        // callback's resampler drives toward 0). <5ms = locked.
        syncAbsMs < 5f -> "Locked (${formatMs(status.absoluteSyncMs)})"
        syncAbsMs < 20f -> "Correcting (${formatMs(status.absoluteSyncMs)})"
        else -> "Converging (${formatMs(status.absoluteSyncMs)})"
    }
    // SYNC (grouped) only: the sync-error value and the convergence graph plot
    // write-scheduling error against the group timeline. In DIRECT (solo) there
    // is no peer to converge to, so those readouts are meaningless — hide them.
    val isSyncMode = !status.correctionMode.equals("DIRECT", ignoreCase = true)
    val routeCorrectionMs = status.acousticCorrectionMs
    val routeExtraMs = (routeCorrectionMs - status.outputLatencyMs).coerceAtLeast(0L)
    // The in-device output latency is COMPUTED (the full AudioManager output
    // latency, not just the HAL buffer), so playback lands on the group timeline
    // automatically with no manual nudge. Acoustic calibration is only the
    // beyond-DAC layer for Bluetooth routes; non-BT routes need no calibration.
    val latencyPrimary = when {
        routeCorrectionMs > 0L -> "${routeCorrectionMs}ms calibrated"
        status.outputLatencyMs > 0L -> "${status.outputLatencyMs}ms output latency"
        else -> "Measuring"
    }
    val latencyDetail = when {
        routeCorrectionMs > 0L -> "output ${status.outputLatencyMs}ms + BT route ${routeExtraMs}ms"
        status.isBtRoute && status.outputLatencyMs > 0L -> "calibrate in player settings for tighter BT sync"
        status.outputLatencyMs > 0L -> "computed, auto-synced"
        else -> "waiting for output timestamp"
    }
    val clockLabel = "${status.clockSamples} samples / ${formatMs(status.clockErrorUs / 1000f)} error"
    val rttLabel = formatMs(status.clockRttUs / 1000f)
    val driftLabel = String.format(java.util.Locale.US, "%.1f ppm", status.clockDriftPpm)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SheetDefaults.HeaderTitle(text = "Streaming Status")

            StatusLine(label = "Transport", value = transportLabel)
            StatusLine(label = "Playback", value = stateLabel)
            // Source format (what the queue track is encoded as on the
            // server) → transport format (what the server actually pushes
            // through the Sendspin WS after re-encode). Showing both side
            // by side makes it obvious whether the server is upsampling or
            // transcoding. Codec/Mode were less informative on their own.
            StatusLine(label = "Input", value = formatAudioDescriptor(inputAudioFormat) ?: "Unknown")
            StatusLine(label = "Output", value = formatOutputDescriptor(status) ?: "Unknown")
            StatusLine(label = "Network", value = status.networkMode)

            HorizontalDivider()

            // Sync details in compact layout
            val smallStyle = MaterialTheme.typography.labelMedium
            val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
            val valueColor = MaterialTheme.colorScheme.onSurface
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallStatusLine("Sync mode", status.correctionMode, smallStyle, dimColor, valueColor)
                SmallStatusLine("Sync lock", syncLockLabel, smallStyle, dimColor, valueColor)
                if (isSyncMode) {
                    SmallStatusLine("Sync error", formatMs(status.absoluteSyncMs), smallStyle, dimColor, valueColor)
                }
                DetailStatusLine("Latency", latencyPrimary, latencyDetail, smallStyle, dimColor, valueColor)
                // Clock sync only drives the grouped (SYNC) timeline; DIRECT is a
                // pure FIFO with no clock dependency, so these are hidden there.
                if (isSyncMode) {
                    SmallStatusLine("Clock sync", clockLabel, smallStyle, dimColor, valueColor)
                    SmallStatusLine("Server RTT", rttLabel, smallStyle, dimColor, valueColor)
                    SmallStatusLine("Clock drift", driftLabel, smallStyle, dimColor, valueColor)
                    SmallStatusLine("Resyncs", "${status.resyncs}", smallStyle, dimColor, valueColor)
                }
                SmallStatusLine(
                    "Buffer",
                    String.format(java.util.Locale.US, "%.1fs  /  %d KB", bufferSeconds, status.bufferBytes / 1000),
                    smallStyle, dimColor, valueColor,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                val progress = (bufferSeconds / maxSeconds).coerceIn(0f, 1f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = bufferColor,
                        size = Size(size.width * progress, size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0s", style = MaterialTheme.typography.labelSmall, color = dimColor)
                Text("30s", style = MaterialTheme.typography.labelSmall, color = dimColor)
            }

            HorizontalDivider()
            var syncDelayMs by remember(status.syncDelayMs) { mutableIntStateOf(status.syncDelayMs) }
            SteppedValueRow(
                label = "Sendspin sync delay",
                valueLabel = "${syncDelayMs}ms",
                onDecrement = {
                    // Range -1000..+1000 ms. Negative shifts playback sooner,
                    // positive shifts it later — intuitive sign convention
                    // matching the MA web UI's Sendspin sync delay slider.
                    syncDelayMs = (syncDelayMs - 2).coerceAtLeast(-1000)
                    onSyncDelayChanged(syncDelayMs)
                },
                onIncrement = {
                    syncDelayMs = (syncDelayMs + 2).coerceAtMost(1000)
                    onSyncDelayChanged(syncDelayMs)
                },
                labelStyle = MaterialTheme.typography.labelMedium
            )

            if (isSyncMode && syncHistory.size >= 2) {
                SyncErrorGraph(syncHistory)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
