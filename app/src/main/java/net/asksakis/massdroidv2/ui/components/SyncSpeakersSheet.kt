package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import net.asksakis.massdroidv2.data.sendspin.OutputFineTune
import net.asksakis.massdroidv2.domain.model.FormatOption
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

/**
 * Dedicated multi-speaker tuner for a sync group: one section per member so the
 * whole group is set up from one place. Each section has a delay slider to line
 * the speakers up acoustically by ear, and the output channel selector
 * (stereo/left/right/mono) that turns two members into a stereo pair. Remote
 * members write their server-side `sendspin_static_delay` (per-player config);
 * our own player writes the fine-tune of the output it plays on. Sliders are
 * debounced; the channel selector applies on tap.
 *
 * The members are sections rather than cards, with a divider between them, for the
 * reason the settings rows have no cards: on the grayscale palette a column of cards
 * reads as one grey slab.
 *
 * The members' configs are loaded here, once per member, so the sections can be
 * ordered by channel (left, right, then the rest by name) before any is drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSpeakersSheet(
    members: List<Player>,
    ourPlayerId: String?,
    localFineTune: OutputFineTune?,
    onLocalFineTuneChanged: (routeKey: String, ms: Int) -> Unit,
    onLoadConfig: suspend (playerId: String) -> PlayerConfig?,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
    // Bumped when something outside the sliders rewrote the delays (Auto sync),
    // so the sections reload the configs and show the new values.
    configRevision: Int = 0,
    autoSync: (@Composable () -> Unit)? = null,
) {
    val sheetState = SheetDefaults.sheetState()
    // Keyed on the ids, not the list: the caller rebuilds the list on every player
    // state event (volume, position), and that must not reload the configs.
    val memberIds = members.map { it.playerId }
    var configs by remember(memberIds, configRevision) { mutableStateOf<Map<String, PlayerConfig?>?>(null) }
    LaunchedEffect(memberIds, configRevision) {
        configs = coroutineScope {
            memberIds.map { id -> async { id to onLoadConfig(id) } }.awaitAll().toMap()
        }
    }
    ModalBottomSheet(
        sheetMaxWidth = SheetDefaults.maxWidth(),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        // The section headers carry the settings row inset themselves, so the sheet adds
        // only the rest of its 20dp edge and everything else takes the inset explicitly.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_EDGE_PADDING, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = SETTINGS_ROW_INSET),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SheetDefaults.HeaderTitle(text = "Sync speakers")
                Text(
                    "Line the group up by ear with a delay per speaker, and pick which channel each one plays.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                autoSync?.invoke()
            }
            val loaded = configs
            if (loaded == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                // Ordered once from the loaded configs, so a tap on a channel does not
                // move the section out from under the finger; the new order shows on reopen.
                val ordered = remember(memberIds, loaded) {
                    members.sortedWith(
                        compareBy<Player>({ channelRank(loaded[it.playerId]?.outputChannels) })
                            .thenBy { it.displayName.lowercase() }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                ordered.forEachIndexed { index, member ->
                    if (index > 0) SettingsSectionDivider()
                    SyncSpeakerSection(
                        player = member,
                        isOurPlayer = member.playerId == ourPlayerId,
                        config = loaded[member.playerId],
                        localFineTune = localFineTune,
                        onLocalFineTuneChanged = onLocalFineTuneChanged,
                        onSave = onSave,
                    )
                }
            }
        }
    }
}

/** One member of the group: its name as the section header, its controls underneath. */
@Composable
private fun SyncSpeakerSection(
    player: Player,
    isOurPlayer: Boolean,
    config: PlayerConfig?,
    localFineTune: OutputFineTune?,
    onLocalFineTuneChanged: (routeKey: String, ms: Int) -> Unit,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
) {
    // Which source channels this member renders (stereo/left/right/mono). Applied on
    // tap like everything else in this sheet: MA writes it to the player and reloads it,
    // so a pair is set up from one place, one member on left and the other on right.
    val channelsKey = config?.outputChannelsKey
    val channelOptions = config?.outputChannelsOptions.orEmpty()
    var channels by remember(config) { mutableStateOf(config?.outputChannels) }
    val channelSuffix = channels
        ?.takeIf { channelsKey != null && it != OUTPUT_CHANNELS_STEREO }
        ?.let { " · ${outputChannelsShortTitle(it, channelOptions)}" }
        .orEmpty()
    val channelsFooter: (@Composable () -> Unit)? =
        if (channelsKey != null && channelOptions.isNotEmpty()) {
            {
                OutputChannelsSelector(
                    value = channels,
                    options = channelOptions,
                    onSelect = { value ->
                        if (value == channels) return@OutputChannelsSelector
                        channels = value
                        onSave(player.playerId, mapOf(channelsKey to value))
                    }
                )
            }
        } else {
            null
        }

    val deviceSuffix = if (isOurPlayer) " · this device" else ""
    SettingsSectionHeader(title = player.displayName + deviceSuffix + channelSuffix)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SETTINGS_ROW_INSET)) {
        if (isOurPlayer) {
            LocalSpeakerControls(
                localFineTune = localFineTune,
                onLocalFineTuneChanged = onLocalFineTuneChanged,
                footer = channelsFooter
            )
        } else {
            RemoteSpeakerControls(
                player = player,
                config = config,
                onSave = onSave,
                footer = channelsFooter
            )
        }
    }
}

/**
 * Our own player has no server-side delay (we are the client); it moves by the
 * fine-tune of the output it plays on, debounced as the slider fires rapidly.
 */
@Composable
private fun LocalSpeakerControls(
    localFineTune: OutputFineTune?,
    onLocalFineTuneChanged: (routeKey: String, ms: Int) -> Unit,
    footer: (@Composable () -> Unit)?,
) {
    if (localFineTune == null) {
        SpeakerNote(note = "Can't fine-tune while several Bluetooth devices are connected", footer = footer)
        return
    }
    val routeKey = localFineTune.routeKey
    var value by remember(routeKey, localFineTune.ms) { mutableIntStateOf(localFineTune.ms) }
    // Restart with the stored value: the apply round-trips through DataStore
    // and re-keys the remember above (new state object), so the snapshotFlow
    // must re-bind to it. Without this the observer goes stale after the
    // first apply and later changes (e.g. Reset) are silently dropped.
    LaunchedEffect(routeKey, localFineTune.ms) {
        @OptIn(FlowPreview::class)
        snapshotFlow { value }.drop(1).debounce(250L).collect { onLocalFineTuneChanged(routeKey, it) }
    }
    SyncDelayControl(
        label = "Fine-tune",
        valueMs = value,
        defaultMs = 0,
        onValueChange = { value = it.coerceIn(-FINE_TUNE_MAX_MS, FINE_TUNE_MAX_MS) },
        compact = true,
        minMs = -FINE_TUNE_MAX_MS,
        maxMs = FINE_TUNE_MAX_MS,
        footer = footer
    )
}

/**
 * A remote member's server-side delay, written back under the exact key the load found
 * (a universal player wraps it per protocol), debounced; MA applies it to the live group.
 * A server that offers the signed sync delay (-1000..1000) gets that; otherwise the static
 * playback delay (0..5000), which MA offers only for a client that declares
 * set_static_delay in its hello, so the key may be absent altogether.
 */
@Composable
private fun RemoteSpeakerControls(
    player: Player,
    config: PlayerConfig?,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    footer: (@Composable () -> Unit)?,
) {
    val syncKey = config?.sendspinSyncDelayKey
    val key = syncKey ?: config?.sendspinStaticDelayKey
    val isStatic = syncKey == null
    val minMs = if (isStatic) STATIC_DELAY_MIN_MS else SYNC_DELAY_MIN_MS
    val maxMs = if (isStatic) STATIC_DELAY_MAX_MS else SYNC_DELAY_MAX_MS
    val defaultMs = if (isStatic) 0 else config?.sendspinSyncDelayDefault ?: 0
    var value by remember(config) {
        mutableIntStateOf(if (isStatic) config?.sendspinStaticDelayMs ?: 0 else config?.sendspinSyncDelayMs ?: 0)
    }

    if (key != null) {
        LaunchedEffect(config, key) {
            @OptIn(FlowPreview::class)
            snapshotFlow { value }.drop(1).debounce(250L).collect { v ->
                onSave(player.playerId, mapOf(key to v))
            }
        }
        SyncDelayControl(
            label = if (isStatic) "Static playback delay" else "Sync delay",
            valueMs = value,
            defaultMs = defaultMs,
            onValueChange = { value = it.coerceIn(minMs, maxMs) },
            compact = true,
            minMs = minMs,
            maxMs = maxMs,
            footer = footer
        )
    } else {
        // A client whose firmware does not take a delay from the server (the ESPHome
        // Sendspin component, for one): it is still a member, in a section like the
        // others, with the note where the slider would be.
        SpeakerNote(note = "Sync delay is set on the device", footer = footer)
    }
}

/** A note where a member's delay slider would be, above its channel selector. */
@Composable
private fun SpeakerNote(note: String, footer: (@Composable () -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        footer?.invoke()
    }
}

/**
 * The sheet's own horizontal padding. With the settings row inset inside it, content
 * sits at the 20dp edge the sheet has always used.
 */
private val SHEET_EDGE_PADDING = 4.dp

/** Range of this phone's fine-tune either side of 0. */
private const val FINE_TUNE_MAX_MS = SettingsRepository.OUTPUT_FINE_TUNE_MAX_MS

/** MA's range for `sendspin_static_delay`. */
private const val STATIC_DELAY_MIN_MS = 0
private const val STATIC_DELAY_MAX_MS = 5000

/** Range of the signed per-player sync delay, where a server offers it. */
private const val SYNC_DELAY_MIN_MS = -1000
private const val SYNC_DELAY_MAX_MS = 1000

internal const val OUTPUT_CHANNELS_STEREO = "stereo"

/** Sort key for the member sections: the stereo pair first, in room order, then everyone else. */
private fun channelRank(value: String?): Int = when (value) {
    "left" -> 0
    "right" -> 1
    else -> 2
}

/**
 * A one-word name for an output channel value. The server's full titles ("Stereo (both
 * channels)", "Left channel only") wrap a chip row onto three lines, in the player
 * settings as much as in a member section here. Unknown values keep the server title.
 */
internal fun outputChannelsShortTitle(value: String, options: List<FormatOption>): String =
    when (value) {
        OUTPUT_CHANNELS_STEREO -> "Stereo"
        "left" -> "Left"
        "right" -> "Right"
        "mono" -> "Mono"
        else -> options.firstOrNull { it.value == value }?.title ?: value
    }

/**
 * The channel choice as one row of segmented buttons: four short labels fit one line
 * at phone width, where chips with a leading label wrapped onto two.
 */
@Composable
private fun OutputChannelsSelector(
    value: String?,
    options: List<FormatOption>,
    onSelect: (String) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option.value == value,
                onClick = { onSelect(option.value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        outputChannelsShortTitle(option.value, options),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            )
        }
    }
}
