package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.data.websocket.ConnectionState

@Composable
fun TvHomeScreen(viewModel: TvHomeViewModel = hiltViewModel()) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()

    val status = when (val c = connection) {
        is ConnectionState.Connected -> "Connected"
        ConnectionState.Connecting -> "Connecting..."
        ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Error -> "Error: ${c.message}"
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1B1B2F))
                .padding(48.dp)
        ) {
            Text("MassDroid TV", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Status: $status", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Text("Players (${players.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(players, key = { it.playerId }) { player ->
                    Text(
                        player.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}
