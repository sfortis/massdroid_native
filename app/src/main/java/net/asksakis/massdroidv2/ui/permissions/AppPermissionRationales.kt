package net.asksakis.massdroidv2.ui.permissions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.ui.graphics.vector.ImageVector

data class PermissionRationaleSpec(
    val icon: ImageVector,
    val title: String,
    val message: String,
    val confirmLabel: String = "Continue",
    val dismissLabel: String = "Not now"
)

object AppPermissionRationales {
    val sleepTimer = PermissionRationaleSpec(
        icon = Icons.Default.NotificationsActive,
        title = "Allow notifications",
        message = "The sleep timer shows the time left in a notification, with a button to cancel it. " +
            "The timer works without it."
    )

    val followMe = PermissionRationaleSpec(
        icon = Icons.AutoMirrored.Filled.BluetoothSearching,
        title = "Allow Follow Me permissions",
        message = "Follow Me needs Bluetooth, location, and activity access to detect room changes, " +
            "scan nearby beacons, and speed up speaker hand-offs while you move. " +
            "Notifications show its room prompts and tell you when it stops."
    )
}
