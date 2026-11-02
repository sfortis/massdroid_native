package net.asksakis.massdroidv2.ui.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.edit

/**
 * Asks for the notifications permission at the moment a feature that uses it is turned on,
 * with [spec] explaining why. The returned function does nothing below Android 13, when the
 * permission is granted, or after the user said Not now or denied it once since the app
 * started, so turning the feature on again does not ask again.
 *
 * After two denials Android no longer shows its dialog, and the request returns at once. The
 * app cannot tell that apart from a first ask except by remembering that it asked before, so
 * it keeps that one fact, and then the rationale's button opens the app's notification
 * settings instead of a request that would do nothing.
 */
@Composable
fun rememberNotificationPermissionAsk(spec: PermissionRationaleSpec): () -> Unit {
    val context = LocalContext.current
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var settingsOnly by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) NotificationAskSession.declined = true
    }

    if (showRationale) {
        PermissionRationaleDialog(
            spec = if (settingsOnly) spec.copy(confirmLabel = "Open settings") else spec,
            onConfirm = {
                showRationale = false
                if (settingsOnly) {
                    NotificationAskSession.declined = true
                    openNotificationSettings(context)
                } else {
                    AppPermissions.notifications().firstOrNull()?.let { permission ->
                        markAsked(context)
                        launcher.launch(permission)
                    }
                }
            },
            onDismiss = {
                showRationale = false
                NotificationAskSession.declined = true
            }
        )
    }

    return remember(context) {
        {
            val permission = AppPermissions.missing(context, AppPermissions.notifications()).firstOrNull()
            if (permission != null && !NotificationAskSession.declined) {
                val activity = context as? Activity
                // Asked before, and Android would no longer explain it: the dialog will not show.
                settingsOnly = wasAsked(context) && activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                showRationale = true
            }
        }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private const val PREFS = "permission_asks"
private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"

private fun wasAsked(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIFICATIONS_ASKED, false)

private fun markAsked(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
}

/** Whether the user turned the notifications permission down since the process started. */
private object NotificationAskSession {
    @Volatile var declined = false
}
