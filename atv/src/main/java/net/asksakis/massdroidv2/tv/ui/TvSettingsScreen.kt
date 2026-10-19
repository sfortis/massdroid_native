package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat

@Composable
fun TvSettingsScreen(viewModel: TvSettingsViewModel = hiltViewModel()) {
    val outputDelay by viewModel.outputDelay.collectAsStateWithLifecycle()
    val audioFormat by viewModel.audioFormat.collectAsStateWithLifecycle()
    val playerName by viewModel.playerName.collectAsStateWithLifecycle()
    val renameStatus by viewModel.renameStatus.collectAsStateWithLifecycle()
    val updateCheck by viewModel.updateCheck.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize()) {
        // Scrollable: the sections are taller than the screen, and the scroll brings the
        // focused control into view.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 64.dp, vertical = 56.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            Spacer(Modifier.height(36.dp))
            PlayerNameSection(
                currentName = playerName,
                status = renameStatus,
                onEdited = viewModel::onPlayerNameEdited,
                onSave = viewModel::renamePlayer
            )

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

            Spacer(Modifier.height(40.dp))
            UpdatesSection(
                version = viewModel.appVersion,
                status = updateCheck,
                onCheck = viewModel::checkForUpdates
            )
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        modifier = Modifier.pointerClick(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}

/** The name Music Assistant shows for this TV, with a field to change it. */
@Composable
private fun PlayerNameSection(
    currentName: String?,
    status: PlayerRenameStatus,
    onEdited: () -> Unit,
    onSave: (String) -> Unit
) {
    // The field starts from the server's name and takes it again whenever the server reports
    // a new one, which is also how a saved rename comes back.
    var draft by remember(currentName) { mutableStateOf(currentName.orEmpty()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSave = currentName != null && draft.isNotBlank() && draft.trim() != currentName &&
        status != PlayerRenameStatus.SAVING
    val save: () -> Unit = {
        if (canSave) {
            keyboard?.hide()
            onSave(draft)
        }
    }

    Text("Player name", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "The name of this TV in Music Assistant and in the apps that control it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(modifier = Modifier.width(PLAYER_NAME_FIELD_WIDTH)) {
            TvTextField("Name", draft, KeyboardType.Text, ImeAction.Done, onImeAction = save) {
                draft = it
                onEdited()
            }
        }
        Button(onClick = save, enabled = canSave, modifier = Modifier.pointerClick(enabled = canSave, onClick = save)) {
            Text(if (status == PlayerRenameStatus.SAVING) "Saving..." else "Save")
        }
    }
    val message = when {
        currentName == null -> "This TV has not registered its player with Music Assistant yet."
        status == PlayerRenameStatus.SAVED -> "Saved."
        status == PlayerRenameStatus.FAILED -> "The player could not be renamed. Check the connection to Music Assistant."
        else -> null
    }
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (status == PlayerRenameStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private val PLAYER_NAME_FIELD_WIDTH = 480.dp

/** The installed version and a manual check for a new release. */
@Composable
private fun UpdatesSection(version: String, status: UpdateCheckStatus, onCheck: () -> Unit) {
    val checking = status == UpdateCheckStatus.CHECKING
    Text("Updates", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "MassDroid TV $version",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = onCheck, enabled = !checking, modifier = Modifier.pointerClick(enabled = !checking, onClick = onCheck)) {
        Text(if (checking) "Checking..." else "Check for updates")
    }
    val message = when (status) {
        UpdateCheckStatus.UP_TO_DATE -> "This is the latest version."
        UpdateCheckStatus.FAILED -> "Could not reach GitHub. Check the network and try again."
        UpdateCheckStatus.IDLE, UpdateCheckStatus.CHECKING, UpdateCheckStatus.AVAILABLE -> null
    }
    if (message != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (status == UpdateCheckStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
