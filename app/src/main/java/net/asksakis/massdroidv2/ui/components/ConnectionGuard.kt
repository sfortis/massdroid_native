package net.asksakis.massdroidv2.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Returns a guard function that checks connection before executing an action.
 * If disconnected, shows a brief toast instead.
 */
@Composable
fun rememberConnectionGuard(): ((() -> Unit) -> Unit) {
    val isConnected = LocalIsConnected.current
    val context = LocalContext.current
    return remember(isConnected) {
        { action: () -> Unit ->
            if (isConnected) {
                action()
            } else {
                Toast.makeText(context, "Not connected to Music Assistant", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
