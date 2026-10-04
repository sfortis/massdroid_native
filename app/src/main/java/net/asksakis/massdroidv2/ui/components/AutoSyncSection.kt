package net.asksakis.massdroidv2.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.data.sendspin.DelayControl
import net.asksakis.massdroidv2.data.sendspin.GroupAutoSync
import net.asksakis.massdroidv2.data.sendspin.MemberPlan
import net.asksakis.massdroidv2.ui.permissions.AppPermissions

/**
 * The Auto sync control of the Sync speakers sheet: one button that measures
 * every member with the microphone and sets its delay (see GroupAutoSync), the
 * progress while it runs, and what it changed.
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
    val detail = MaterialTheme.typography.bodySmall
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (state) {
            GroupAutoSync.State.Idle -> {
                Text("Auto sync (debug)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (permissionRefused) {
                        "Couldn't start. The microphone permission was refused."
                    } else {
                        "Hold the phone where you listen. Each speaker plays alone for a few " +
                            "seconds, at 40% volume or more, while the phone listens; then every " +
                            "delay is set and the volumes are put back."
                    },
                    style = detail, color = detailColor
                )
                MdFilledTonalButton(onClick = start) { Text("Auto sync") }
            }
            is GroupAutoSync.State.Measuring -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "Listening to ${state.playerName} (${state.index} of ${state.total})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                MdTextButton(onClick = onCancel) { Text("Cancel") }
            }
            is GroupAutoSync.State.Done -> {
                Text("Auto sync finished", style = MaterialTheme.typography.bodyMedium)
                state.plan.members.forEach { member ->
                    Text(memberLine(state.names[member.playerId] ?: member.playerId, member), style = detail)
                }
                Text(
                    "A speaker running sendspin-cli moves to its new delay slowly; stop and play to apply it at once.",
                    style = detail, color = detailColor
                )
                MdTextButton(onClick = onDismiss) { Text("Done") }
            }
            is GroupAutoSync.State.Failed -> {
                Text(state.reason, style = MaterialTheme.typography.bodyMedium)
                MdTextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

private fun ms(us: Long): String = "%+.0f ms".format(us / 1000.0)

private fun memberLine(name: String, member: MemberPlan): String {
    val lag = member.lagUs ?: return "$name: not heard clearly, delay unchanged"
    val change = when (val c = member.control) {
        is DelayControl.Static -> "static delay ${c.currentMs} to ${member.newValueMs} ms"
        is DelayControl.Signed -> "delay ${c.currentMs} to ${member.newValueMs} ms"
        DelayControl.None -> "set on the device, unchanged"
    }
    val residual = member.residualUs?.takeIf { kotlin.math.abs(it) >= 2_000 }?.let { ", still ${ms(it)} off" }.orEmpty()
    return "$name: played at ${ms(lag)}, $change$residual"
}
