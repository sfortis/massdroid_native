package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig

/**
 * Dedicated multi-speaker sync-delay tuner for a sync group: one slider per
 * member so you can line them all up acoustically by ear from one place.
 * Remote members write their server-side `sendspin_sync_delay` (per-player
 * config); our own player uses the local client-side nudge. Members that expose
 * no sync delay are omitted. Each control is debounced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSpeakersSheet(
    members: List<Player>,
    ourPlayerId: String?,
    localSyncDelayMs: Int,
    onLocalSyncDelayChanged: (Int) -> Unit,
    onLoadConfig: suspend (playerId: String) -> PlayerConfig?,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SheetDefaults.HeaderTitle(text = "Sync speakers")
            Text(
                "Tune each speaker to line the group up by ear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            members.sortedBy { it.displayName.lowercase() }.forEach { member ->
                SyncSpeakerRow(
                    player = member,
                    isOurPlayer = member.playerId == ourPlayerId,
                    localSyncDelayMs = localSyncDelayMs,
                    onLocalSyncDelayChanged = onLocalSyncDelayChanged,
                    onLoadConfig = onLoadConfig,
                    onSave = onSave,
                )
            }
        }
    }
}

@Composable
private fun SyncSpeakerRow(
    player: Player,
    isOurPlayer: Boolean,
    localSyncDelayMs: Int,
    onLocalSyncDelayChanged: (Int) -> Unit,
    onLoadConfig: suspend (playerId: String) -> PlayerConfig?,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
) {
    if (isOurPlayer) {
        // Our own player has no server-side sync delay (we are the client); use
        // the local client-side nudge, debounced as the slider fires rapidly.
        var value by remember(player.playerId, localSyncDelayMs) { mutableIntStateOf(localSyncDelayMs) }
        // Restart with localSyncDelayMs: the apply round-trips through DataStore
        // and re-keys the remember above (new state object), so the snapshotFlow
        // must re-bind to it. Without this the observer goes stale after the
        // first apply and later changes (e.g. Reset) are silently dropped.
        LaunchedEffect(player.playerId, localSyncDelayMs) {
            @OptIn(FlowPreview::class)
            snapshotFlow { value }.drop(1).debounce(250L).collect { onLocalSyncDelayChanged(it) }
        }
        SyncDelayCard(
            label = "${player.displayName} · this device",
            valueMs = value,
            defaultMs = 0,
            onValueChange = { value = it.coerceIn(-1000, 1000) },
            compact = true
        )
        return
    }

    // Remote member: discover its server-side sync delay (key + value + default)
    // and write that exact key back, debounced. MA applies it to the live group.
    var key by remember(player.playerId) { mutableStateOf<String?>(null) }
    var value by remember(player.playerId) { mutableIntStateOf(0) }
    var defaultMs by remember(player.playerId) { mutableIntStateOf(0) }
    var loaded by remember(player.playerId) { mutableStateOf(false) }
    var hasDelay by remember(player.playerId) { mutableStateOf(false) }

    LaunchedEffect(player.playerId) {
        val config = onLoadConfig(player.playerId)
        val loadedKey = config?.sendspinSyncDelayKey
        if (loadedKey != null) {
            key = loadedKey
            value = config.sendspinSyncDelayMs ?: 0
            defaultMs = config.sendspinSyncDelayDefault ?: 0
            hasDelay = true
        }
        loaded = true
    }

    if (hasDelay) {
        LaunchedEffect(player.playerId, loaded) {
            if (!loaded) return@LaunchedEffect
            @OptIn(FlowPreview::class)
            snapshotFlow { value }.drop(1).debounce(250L).collect { v ->
                key?.let { onSave(player.playerId, mapOf(it to v)) }
            }
        }
        SyncDelayCard(
            label = player.displayName,
            valueMs = value,
            defaultMs = defaultMs,
            onValueChange = { value = it.coerceIn(-1000, 1000) },
            compact = true
        )
    } else if (loaded) {
        // Native sendspin client (e.g. a laptop running the app): it does its
        // own clock sync on-device, so MA exposes no server-side delay key for
        // us to write. Still list it as a member so the group is complete, with
        // a note that its delay is tuned on the device itself.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Text(player.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Sync delay is set on the device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
