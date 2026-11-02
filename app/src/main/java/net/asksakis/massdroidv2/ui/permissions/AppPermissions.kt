package net.asksakis.massdroidv2.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object AppPermissions {
    fun followMeRequired(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    fun acousticCalibrationRequired(): List<String> = listOf(Manifest.permission.RECORD_AUDIO)

    /**
     * Notifications, a runtime permission from Android 13. Playback controls do not need it,
     * because media session notifications are exempt; the sleep timer, Follow Me and the
     * connection status notification do. Asked by the features that use it, never at launch.
     */
    fun notifications(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
