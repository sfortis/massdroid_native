package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat

@Composable
fun TvSettingsScreen(viewModel: TvSettingsViewModel = hiltViewModel()) {
    val outputDelay by viewModel.outputDelay.collectAsStateWithLifecycle()
    val audioFormat by viewModel.audioFormat.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 56.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            Spacer(Modifier.height(36.dp))
            Text("Audio quality", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Smart uses lossless FLAC on Wi-Fi / Ethernet. Force FLAC or PCM for lossless, Opus to save bandwidth.",
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SendspinAudioFormat.entries.forEach { fmt ->
                    QualityChip(
                        label = fmt.label,
                        selected = fmt == audioFormat,
                        onClick = { viewModel.setAudioFormat(fmt) }
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
            OutputDelayControl(outputDelay = outputDelay, onChange = viewModel::setOutputDelay)
        }
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // Selected chip = filled primary container; content stays focus-aware so it
    // inverts correctly when focused.
    val colors = if (selected) {
        ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        ClickableSurfaceDefaults.colors()
    }
    Surface(
        onClick = onClick,
        colors = colors,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}
