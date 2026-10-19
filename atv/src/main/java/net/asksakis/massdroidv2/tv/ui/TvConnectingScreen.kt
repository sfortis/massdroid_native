package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Shown while auto-connecting to the saved server. Keeps the user here (with a
 * retry running in the VM) on transient failures instead of forcing re-login;
 * a button lets them switch servers if needed.
 */
@Composable
fun TvConnectingScreen(server: String, onChangeServer: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(64.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Connecting to Music Assistant", style = MaterialTheme.typography.headlineMedium)
            if (server.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    server,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = onChangeServer, modifier = Modifier.pointerClick(onClick = onChangeServer)) {
                Text("Change server")
            }
        }
    }
}

/**
 * Shown for the moment it takes to read whether a server is saved. It carries no text, because
 * at that point the app does not yet know whether it will connect or ask for a server.
 */
@Composable
fun TvStartupScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TvLogo(Modifier.size(STARTUP_LOGO_SIZE))
        }
    }
}

private val STARTUP_LOGO_SIZE = 128.dp
