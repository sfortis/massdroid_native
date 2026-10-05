package net.asksakis.massdroidv2.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.data.sendspin.DelayControl
import net.asksakis.massdroidv2.data.sendspin.GroupAutoSync
import net.asksakis.massdroidv2.data.sendspin.MemberPlan
import net.asksakis.massdroidv2.ui.permissions.AppPermissions

/**
 * The Auto sync control of the Sync speakers sheet: it measures every member with the
 * microphone and sets its delay (see GroupAutoSync), shows the progress while it runs, and
 * lists what it changed.
 *
 * It is drawn as settings rows, like the rest of the sheet: an "Auto sync" row whose
 * supporting line is the current state, and the action for that state as a row of its own.
 * Before a run the "Auto sync" row is itself the action. The rows carry their own inset, so
 * the caller places this outside any padded column.
 */
@Composable
internal fun AutoSyncSection(
    state: GroupAutoSync.State,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var permissionRefused by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefused = !granted
        if (granted) onStart()
    }
    val start = {
        val missing = AppPermissions.missing(context, AppPermissions.acousticCalibrationRequired())
        if (missing.isEmpty()) onStart() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            GroupAutoSync.State.Idle -> SettingsRow(
                title = AUTO_SYNC_TITLE,
                icon = Icons.Default.Mic,
                supporting = if (permissionRefused) {
                    "Couldn't start. The microphone permission was refused."
                } else {
                    "Hold the phone where you listen while each speaker plays alone at 40% volume or more."
                },
                tone = if (permissionRefused) SettingsTone.ERROR else SettingsTone.NORMAL,
                onClick = start
            )
            is GroupAutoSync.State.Measuring -> {
                SettingsRow(
                    title = AUTO_SYNC_TITLE,
                    icon = Icons.Default.Mic,
                    supporting = "Listening to ${state.playerName} (${state.index} of ${state.total})",
                    trailing = {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                )
                SettingsRow(title = "Cancel", icon = Icons.Default.Close, onClick = onCancel)
            }
            is GroupAutoSync.State.Done -> AutoSyncResult(state, onDismiss)
            is GroupAutoSync.State.Failed -> {
                SettingsRow(
                    title = AUTO_SYNC_TITLE,
                    icon = Icons.Default.Mic,
                    supporting = state.reason,
                    tone = SettingsTone.ERROR
                )
                SettingsRow(title = "Close", icon = Icons.Default.Close, onClick = onDismiss)
            }
        }
    }
}

/** What a finished run changed: one row per member, then the action that clears it. */
@Composable
private fun AutoSyncResult(state: GroupAutoSync.State.Done, onDismiss: () -> Unit) {
    SettingsRow(title = AUTO_SYNC_TITLE, icon = Icons.Default.Mic, supporting = "Finished")
    state.plan.members.forEach { member ->
        SettingsRow(
            title = state.names[member.playerId] ?: member.playerId,
            icon = Icons.Default.Speaker,
            supporting = memberLine(member)
        )
    }
    Text(
        "A speaker running sendspin-cli reaches its new delay slowly unless you stop and play again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Under icon rows, so it starts at their text rather than at their icons.
        modifier = Modifier.padding(start = SETTINGS_TEXT_INSET, end = SETTINGS_ROW_INSET, top = 4.dp, bottom = 4.dp)
    )
    SettingsRow(title = "Done", icon = Icons.Default.Done, onClick = onDismiss)
}

private const val AUTO_SYNC_TITLE = "Auto sync"

private fun ms(us: Long): String = "%+.0f ms".format(us / 1000.0)

/** One member's outcome, for the supporting line under its name. */
private fun memberLine(member: MemberPlan): String {
    val lag = member.lagUs ?: return "Not heard clearly, delay unchanged"
    val change = when (val c = member.control) {
        is DelayControl.Static -> "static delay ${c.currentMs} to ${member.newValueMs} ms"
        is DelayControl.Signed -> "delay ${c.currentMs} to ${member.newValueMs} ms"
        DelayControl.None -> "set on the device, unchanged"
    }
    val residual = member.residualUs?.takeIf { kotlin.math.abs(it) >= 2_000 }?.let { ", still ${ms(it)} off" }.orEmpty()
    return "Played at ${ms(lag)}, $change$residual"
}
