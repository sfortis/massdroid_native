package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import net.asksakis.massdroidv2.data.sendspin.DelayControl
import net.asksakis.massdroidv2.data.sendspin.MemberDelay
import net.asksakis.massdroidv2.data.sendspin.SyncMemberSetup
import net.asksakis.massdroidv2.data.sendspin.OutputFineTune
import net.asksakis.massdroidv2.domain.model.FormatOption
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

/**
 * Dedicated multi-speaker tuner for a sync group: one section per member so the
 * whole group is set up from one place. Each section has a delay slider to line
 * the speakers up acoustically by ear, and the output channel chips
 * (left/stereo/right) that turn two members into a stereo pair. Remote
 * members write their server-side delay (per-player config); our own player
 * writes the fine-tune of the output it plays on. Delays are debounced; the
 * channel choice applies when it is picked.
 *
 * The members are sections rather than cards, with a divider between them, for the
 * reason the settings rows have no cards: on the grayscale palette a column of cards
 * reads as one grey slab.
 *
 * The members' configs are loaded here, once per member, so the sections can be
 * ordered by channel (left, right, then the rest by name) before any is drawn. A member loads
 * again when the output MA plays it through changes: MA lists a Sendspin over Cast output's
 * delay only while that output plays, so the slider appears when playback starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSpeakersSheet(
    members: List<Player>,
    ourPlayerId: String?,
    localFineTune: OutputFineTune?,
    onLocalFineTuneChanged: (routeKey: String, ms: Int) -> Unit,
    onLoadMember: suspend (playerId: String) -> SyncMemberSetup,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    // A save the sheet waits for, true when MA accepted it.
    onApply: suspend (playerId: String, values: Map<String, Any>) -> Boolean,
    onDismiss: () -> Unit,
    // Bumped when something outside the sliders rewrote the delays (Auto sync),
    // so the sections reload the configs and show the new values.
    configRevision: Int = 0,
    autoSync: (@Composable () -> Unit)? = null,
) {
    val sheetState = SheetDefaults.sheetState()
    // Keyed on each member's active output, not the list: the caller rebuilds the list on
    // every player state event (volume, position), and that must not reload the configs. Only
    // a member that joined or whose output changed loads; the others keep their sliders.
    val activeOutputs = members.associate { it.playerId to it.activeOutputProtocol }
    var loadedMembers by remember(configRevision) { mutableStateOf<Map<String, LoadedMember>?>(null) }
    LaunchedEffect(activeOutputs, configRevision) {
        val current = loadedMembers.orEmpty()
        val stale = activeOutputs.keys.filter { id -> id !in current || current.getValue(id).output != activeOutputs[id] }
        val fresh = coroutineScope {
            stale.map { id -> async { id to LoadedMember(onLoadMember(id), activeOutputs[id]) } }.awaitAll()
        }
        loadedMembers = current.filterKeys { it in activeOutputs } + fresh
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
            }
            // Outside the inset column: Auto sync is drawn as settings rows, which carry
            // the inset themselves.
            autoSync?.invoke()
            val loaded = loadedMembers
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
                // Ordered when the set of members changes, not on every reload, so a tap on a
                // channel does not move the section out from under the finger; the new order
                // shows on reopen. Each section draws the member's current Player.
                val byId = members.associateBy { it.playerId }
                val ordered = remember(loaded.keys) {
                    loaded.keys.sortedWith(
                        compareBy<String>({ channelRank(loaded[it]?.setup?.config?.outputChannels) })
                            .thenBy { byId[it]?.displayName?.lowercase() }
                    )
                }
                // A divider before every speaker, the first one included, so the intro
                // is set off from the speakers the way the speakers are from each other.
                ordered.forEach { id ->
                    val member = byId[id] ?: return@forEach
                    val setup = loaded[id]?.setup ?: return@forEach
                    key(id) {
                        SettingsSectionDivider()
                        SyncSpeakerSection(
                            player = member,
                            isOurPlayer = id == ourPlayerId,
                            setup = setup,
                            localFineTune = localFineTune,
                            onLocalFineTuneChanged = onLocalFineTuneChanged,
                            onSave = onSave,
                            onApply = onApply,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One member of the group in two lines: its name with the output channel chips, and its delay
 * as a slider with the value.
 *
 * The delay used to be a slider over the whole 0 to 5000 ms. A tap anywhere on its track jumped
 * there, and in a sheet that scrolls one stray tap wrote 4871 ms to a speaker (2026-10-11). The
 * slider now spans at most [SLIDER_SPAN_MS] either side of zero, where speakers need their delay,
 * and the step buttons reach the rest of the range.
 */
@Composable
private fun SyncSpeakerSection(
    player: Player,
    isOurPlayer: Boolean,
    setup: SyncMemberSetup,
    localFineTune: OutputFineTune?,
    onLocalFineTuneChanged: (routeKey: String, ms: Int) -> Unit,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    onApply: suspend (playerId: String, values: Map<String, Any>) -> Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SETTINGS_ROW_INSET)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (isOurPlayer) "${player.displayName} (this device)" else player.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            ChannelChips(player = player, config = setup.config, onSave = onSave)
        }
        if (isOurPlayer) {
            LocalSpeakerControls(
                localFineTune = localFineTune,
                onLocalFineTuneChanged = onLocalFineTuneChanged
            )
        } else {
            RemoteSpeakerControls(player = player, setup = setup, onSave = onSave, onApply = onApply)
        }
    }
}

/**
 * Which source channels this member renders, as a chip each in room order: left, stereo,
 * right. Mono and any value this list does not know are shown only on the member that already
 * has one, so it stays visible and can be changed. Applied on selection: MA writes it to the
 * player and reloads it, so a pair is set up from one place. Left out when the member's config
 * has no such entry.
 */
@Composable
private fun ChannelChips(
    player: Player,
    config: PlayerConfig?,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
) {
    val channelsKey = config?.outputChannelsKey ?: return
    val channelOptions = config.outputChannelsOptions
    if (channelOptions.isEmpty()) return
    var channels by remember(config) { mutableStateOf(config.outputChannels) }
    val shown = channelOptions
        .filter { it.value in PAIR_CHANNELS || it.value == channels }
        .sortedBy { channelChipRank(it.value) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { option ->
            val title = outputChannelsShortTitle(option.value, channelOptions)
            MdFilterChip(
                selected = option.value == channels,
                onClick = {
                    if (option.value != channels) {
                        channels = option.value
                        onSave(player.playerId, mapOf(channelsKey to option.value))
                    }
                },
                label = { Text(channelChipLabel(option.value, title), maxLines = 1) },
                modifier = Modifier.semantics { contentDescription = "$title channel" }
            )
        }
    }
}

/**
 * Our own player has no server-side delay (we are the client); it moves by the
 * fine-tune of the output it plays on, debounced as a drag fires rapidly.
 */
@Composable
private fun LocalSpeakerControls(
    localFineTune: OutputFineTune?,
    onLocalFineTuneChanged: (routeKey: String, ms: Int) -> Unit,
) {
    if (localFineTune == null) {
        SpeakerNote(note = "Can't fine-tune while several Bluetooth devices are connected")
        return
    }
    val routeKey = localFineTune.routeKey
    var value by remember(routeKey, localFineTune.ms) { mutableIntStateOf(localFineTune.ms) }
    // Keyed on the stored value: the apply round-trips through DataStore and re-keys the
    // remember above (new state object), so the save must re-bind to it. Without this the
    // observer goes stale after the first apply and later changes (e.g. Reset) are dropped.
    SaveWhenSettled(key = routeKey to localFineTune.ms, value = { value }) { onLocalFineTuneChanged(routeKey, it) }
    DelaySlider(
        description = "Fine-tune",
        valueMs = value,
        minMs = -FINE_TUNE_MAX_MS,
        maxMs = FINE_TUNE_MAX_MS,
        onValueChange = { value = it }
    )
}

/**
 * A remote member's server-side delay, written back under the exact key the load found
 * (a universal player wraps it per protocol). The resolved [SyncMemberSetup.delay] says which setting
 * that is. Most delays MA applies to the live group, so they are saved as the slider moves,
 * debounced. A delay whose save restarts the group's playback (an AirPlay output's
 * sync_adjust) is saved only when Apply is tapped.
 */
@Composable
private fun RemoteSpeakerControls(
    player: Player,
    setup: SyncMemberSetup,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    onApply: suspend (playerId: String, values: Map<String, Any>) -> Boolean,
) {
    when (val delay = setup.delay) {
        MemberDelay.WaitsForPlayback -> SpeakerNote(note = "Delay can be adjusted while this speaker plays")
        MemberDelay.OnDevice -> SpeakerNote(note = "Sync delay is set on the device")
        is MemberDelay.Adjustable -> if (delay.restartsPlayback) {
            AppliedDelay(delay, setup.config) { ms -> onApply(player.playerId, mapOf(delay.key to ms)) }
        } else {
            LiveDelay(delay, setup.config) { ms -> onSave(player.playerId, mapOf(delay.key to ms)) }
        }
    }
}

/** A delay saved as it changes, debounced as a drag fires rapidly. */
@Composable
private fun LiveDelay(delay: MemberDelay.Adjustable, config: PlayerConfig?, save: (Int) -> Unit) {
    var value by remember(config) { mutableIntStateOf(delay.control.currentMs) }
    SaveWhenSettled(key = config to delay.key, value = { value }, save = save)
    DelaySlider(
        description = delay.description,
        valueMs = value,
        minMs = delay.control.minMs,
        maxMs = delay.control.maxMs,
        largerPlaysEarlier = delay.control is DelayControl.Static,
        onValueChange = { value = it }
    )
}

/**
 * Saves [value] once it has not changed for [SAVE_DEBOUNCE_MS], as a drag or a held step button
 * fires rapidly. A change not yet saved when this leaves the composition (the sheet closed
 * right after a tap) is saved then. A new [key] means the value was reloaded and starts over.
 */
@Composable
private fun SaveWhenSettled(key: Any?, value: () -> Int, save: (Int) -> Unit) {
    val currentSave by rememberUpdatedState(save)
    var lastSaved by remember(key) { mutableIntStateOf(value()) }
    LaunchedEffect(key) {
        @OptIn(FlowPreview::class)
        snapshotFlow(value).drop(1).debounce(SAVE_DEBOUNCE_MS).collect {
            currentSave(it)
            lastSaved = it
        }
    }
    val saveUnsaved by rememberUpdatedState {
        val pending = value()
        if (pending != lastSaved) currentSave(pending)
    }
    DisposableEffect(Unit) {
        onDispose { saveUnsaved() }
    }
}

/**
 * A delay held until Apply is tapped, because each save restarts the group's playback. The
 * button and its note show while the value differs from the saved one. The value counts as
 * saved only once MA accepted it; a refused save keeps Apply and says so.
 */
@Composable
private fun AppliedDelay(delay: MemberDelay.Adjustable, config: PlayerConfig?, apply: suspend (Int) -> Boolean) {
    var saved by remember(config) { mutableIntStateOf(delay.control.currentMs) }
    var value by remember(config) { mutableIntStateOf(saved) }
    var applying by remember(config) { mutableStateOf(false) }
    var failed by remember(config) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DelaySlider(
        description = delay.description,
        valueMs = value,
        minMs = delay.control.minMs,
        maxMs = delay.control.maxMs,
        onValueChange = {
            value = it
            failed = false
        }
    )
    if (value != saved || applying) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    applying -> "Applying, playback restarts"
                    failed -> "Couldn't apply the delay"
                    else -> "Applying restarts playback"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            MdTextButton(
                enabled = !applying,
                onClick = {
                    val target = value
                    applying = true
                    failed = false
                    scope.launch {
                        val accepted = apply(target)
                        applying = false
                        if (accepted) saved = target else failed = true
                    }
                }
            ) { Text("Apply") }
        }
    }
}

/** Names the delay for accessibility, since the slider line has no label. */
private val MemberDelay.Adjustable.description: String
    get() = when {
        restartsPlayback -> "AirPlay sync adjust"
        control is DelayControl.Static -> "Static playback delay"
        else -> "Sync delay"
    }

private val DelayControl.currentMs: Int
    get() = when (this) {
        is DelayControl.Static -> currentMs
        is DelayControl.Signed -> currentMs
        DelayControl.None -> 0
    }

private val DelayControl.minMs: Int
    get() = when (this) {
        is DelayControl.Static -> minMs
        is DelayControl.Signed -> minMs
        DelayControl.None -> 0
    }

private val DelayControl.maxMs: Int
    get() = when (this) {
        is DelayControl.Static -> maxMs
        is DelayControl.Signed -> maxMs
        DelayControl.None -> 0
    }

/**
 * The delay as a slider over at most [SLIDER_SPAN_MS] either side of zero, 1 ms steps either side
 * of it, and the value. The steps reach the whole of [minMs]..[maxMs]; a value beyond the slider
 * leaves its thumb at the end. A signed range (the sync delay, the fine-tune) shows a sign on later
 * values. [description] names the delay for accessibility, since the line has no label, and
 * [largerPlaysEarlier] (a static delay) names the steps the right way round.
 */
@Composable
private fun DelaySlider(
    description: String,
    valueMs: Int,
    minMs: Int,
    maxMs: Int,
    largerPlaysEarlier: Boolean = false,
    onValueChange: (Int) -> Unit,
) {
    val signed = minMs < 0
    val low = maxOf(minMs, -SLIDER_SPAN_MS)
    val high = minOf(maxMs, SLIDER_SPAN_MS)
    val valueText = if (signed && valueMs > 0) "+$valueMs ms" else "$valueMs ms"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StepButton(
            icon = Icons.Default.Remove,
            description = if (largerPlaysEarlier) "1 ms later" else "1 ms earlier",
            enabled = valueMs > minMs,
            onClick = { onValueChange((valueMs - 1).coerceIn(minMs, maxMs)) }
        )
        MdSlider(
            value = valueMs.coerceIn(low, high).toFloat(),
            onValueChange = { onValueChange(Math.round(it).coerceIn(minMs, maxMs)) },
            valueRange = low.toFloat()..high.toFloat(),
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = description
                    stateDescription = valueText
                }
        )
        StepButton(
            icon = Icons.Default.Add,
            description = if (largerPlaysEarlier) "1 ms earlier" else "1 ms later",
            enabled = valueMs < maxMs,
            onClick = { onValueChange((valueMs + 1).coerceIn(minMs, maxMs)) }
        )
        Text(
            valueText,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(DELAY_VALUE_WIDTH)
        )
    }
}

/**
 * A 1 ms step beside the slider, drawn like the channel chips: a small rounded square with an
 * outline. Held, it repeats and speeds up.
 */
@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.extraSmall
    RepeatingIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(STEP_BUTTON_SIZE)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clip(shape)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(16.dp))
    }
}

/** A note where a member's delay slider would be. */
@Composable
private fun SpeakerNote(note: String) {
    Text(
        note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}

/** The channels that set up a stereo pair, offered on every member. */
private val PAIR_CHANNELS = setOf("left", OUTPUT_CHANNELS_STEREO, "right")

/** Chip order for the output channels: the pair in room order, then anything else. */
private fun channelChipRank(value: String): Int = when (value) {
    "left" -> 0
    OUTPUT_CHANNELS_STEREO -> 1
    "right" -> 2
    else -> 3
}

/** The chip text: a letter or two, so three chips fit beside a speaker's name. */
private fun channelChipLabel(value: String, title: String): String = when (value) {
    "left" -> "L"
    OUTPUT_CHANNELS_STEREO -> "St"
    "right" -> "R"
    "mono" -> "M"
    else -> title
}

/** The visible size of a step button; the touch target stays at the 48 dp minimum. */
private val STEP_BUTTON_SIZE = 32.dp

/** How long a delay must stay still before it is saved. */
private const val SAVE_DEBOUNCE_MS = 250L

/** How far either side of zero the slider reaches; the step buttons go beyond it. */
private const val SLIDER_SPAN_MS = 500

/** Fits "+1000 ms" and "5000 ms", so the slider keeps its width as the value changes. */
private val DELAY_VALUE_WIDTH = 64.dp

/**
 * The sheet's own horizontal padding. With the settings row inset inside it, content
 * sits at the 20dp edge the sheet has always used.
 */
private val SHEET_EDGE_PADDING = 4.dp

/** Range of this phone's fine-tune either side of 0. */
private const val FINE_TUNE_MAX_MS = SettingsRepository.OUTPUT_FINE_TUNE_MAX_MS

internal const val OUTPUT_CHANNELS_STEREO = "stereo"

/** A member's loaded setup and the active output it was loaded for. */
private data class LoadedMember(val setup: SyncMemberSetup, val output: String?)

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
