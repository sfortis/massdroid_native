package net.asksakis.massdroidv2.ui.components

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.proximity.RoomConfig
import net.asksakis.massdroidv2.data.repository.DynamicQueueSource
import net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.data.sendspin.outputNameForRouteKey
import net.asksakis.massdroidv2.domain.model.AutoplayConfig
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.FormatOption
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.QueueConfigOption
import net.asksakis.massdroidv2.domain.model.QueueSettings
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsDialog(
    player: Player,
    initialAutoplayEnabled: Boolean?,
    isSendspinPlayer: Boolean = false,
    isLocalPlayer: Boolean = false,
    initialAudioFormat: SendspinAudioFormat = SendspinAudioFormat.AUTOMATIC,
    onLoadConfig: suspend (playerId: String) -> PlayerConfig?,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    onAutoplayEnabledChanged: ((enabled: Boolean) -> Unit)?,
    /** Set while the queue is dynamic: the server refills it, so Autoplay is shown locked. */
    dynamicSource: DynamicQueueSource? = null,
    /**
     * Whether crossfade is on for this player's queue, or null on a server that keeps
     * crossfade in the player config (before MA 2.10), where the mode carries the off.
     */
    initialCrossfadeEnabled: Boolean? = null,
    /** Turn crossfade on or off. Needs no admin rights: it is queue state, not config. */
    onCrossfadeEnabledChanged: ((enabled: Boolean) -> Unit)? = null,
    /**
     * Configuration of this player's queue, or null to leave those settings out (a server
     * before MA 2.10, or an account that may not read queue config).
     */
    onLoadQueueSettings: (suspend (queueId: String) -> QueueSettings?)? = null,
    /**
     * Apply one queue config value. Returns false when the server refused it, which is
     * what a non-admin account gets, and the control then goes back to what it was.
     */
    onQueueConfigChanged: (suspend (key: String, value: String) -> Boolean)? = null,
    /**
     * Apply a new Autoplay strategy. Returns false when the server refused it, which is
     * what a non-admin account gets, and the selector then goes back to what it was.
     */
    onAutoplayChanged: (suspend (mode: String, playlistUri: String?) -> Boolean)? = null,
    onAudioFormatChanged: ((SendspinAudioFormat) -> Unit)? = null,
    acoustic: AcousticCalibrationCoordinator? = null,
    syncHistory: List<SendspinManager.SyncSample> = emptyList(),
    /**
     * Follow Me rooms as they are configured now. While it is empty, which is the case until
     * the first room has been set up, the room control is left out of the dialog.
     */
    rooms: List<RoomConfig> = emptyList(),
    /**
     * Point one of [rooms] at this player. The assignment is written when it is picked, not on
     * Save, because it is app state rather than player config.
     */
    onAssignRoom: ((roomId: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    // Key all remembered state on player.playerId so swapping the dialog
    // target player (without dismissing first) clears everything instead of
    // carrying over name/format/load-state from the previous player.
    var isLoading by remember(player.playerId) { mutableStateOf(true) }
    var name by remember(player.playerId) { mutableStateOf(player.displayName) }
    var crossfadeMode by remember(player.playerId) { mutableStateOf(CrossfadeMode.DISABLED) }
    var volumeNormalization by remember(player.playerId) { mutableStateOf(false) }
    var crossfadeOn by remember(player.playerId, initialCrossfadeEnabled) {
        mutableStateOf(initialCrossfadeEnabled ?: false)
    }
    var autoplayOn by remember(player.playerId, initialAutoplayEnabled) {
        mutableStateOf(initialAutoplayEnabled ?: false)
    }
    var selectedFormatValue by remember(player.playerId) { mutableStateOf<String?>(null) }
    var formatOptions by remember(player.playerId) {
        mutableStateOf<List<net.asksakis.massdroidv2.domain.model.FormatOption>>(emptyList())
    }
    // Exact config key for the format (plain or protocol-wrapped), carried from the load
    // so the save lands on universal players too.
    var formatKey by remember(player.playerId) { mutableStateOf<String?>(null) }
    var audioFormat by remember(player.playerId, initialAudioFormat) { mutableStateOf(initialAudioFormat) }
    // Generic per-provider output codec (MA `output_codec`, e.g. Sonos flac/mp3/aac/wav).
    var outputCodec by remember(player.playerId) { mutableStateOf<String?>(null) }
    var outputCodecOptions by remember(player.playerId) {
        mutableStateOf<List<net.asksakis.massdroidv2.domain.model.FormatOption>>(emptyList())
    }
    // Output channel mix (MA `output_channels`: stereo/left/right/mono), shown for any
    // player whose config exposes it. The key is carried from the load because a
    // universal player wraps it per output protocol; the loaded value is kept so Save
    // only writes it when it changed (the server reloads the player on that write).
    var outputChannels by remember(player.playerId) { mutableStateOf<String?>(null) }
    var loadedOutputChannels by remember(player.playerId) { mutableStateOf<String?>(null) }
    var outputChannelsKey by remember(player.playerId) { mutableStateOf<String?>(null) }
    var outputChannelsOptions by remember(player.playerId) {
        mutableStateOf<List<net.asksakis.massdroidv2.domain.model.FormatOption>>(emptyList())
    }
    // Server-side spec field sendspin_static_delay (per-player config).
    // Range 0..5000, positive compensates for known external delay (spec
    // sign). Only available on MA servers with PR #3689 deployed; otherwise
    // the load returns null and the row stays hidden.
    var staticDelayMs by remember(player.playerId) { mutableIntStateOf(0) }
    var hasServerStaticDelay by remember(player.playerId) { mutableStateOf(false) }
    // Exact config key for the static delay (plain or protocol-wrapped), carried
    // from the loaded config so the save lands on wrapped players too.
    var staticDelayKey by remember(player.playerId) { mutableStateOf<String?>(null) }
    // Server-side per-player Sendspin sync delay (MA "Sync delay (ms)", range
    // -1000..1000, positive = play later). Tunable on REMOTE sendspin receivers
    // for acoustic alignment; the exact config key varies per player so it is
    // carried from the loaded config. Hidden when the player does not expose it.
    var syncDelayServerMs by remember(player.playerId) { mutableIntStateOf(0) }
    var syncDelayKey by remember(player.playerId) { mutableStateOf<String?>(null) }
    var syncDelayDefault by remember(player.playerId) { mutableIntStateOf(0) }
    var hasServerSyncDelay by remember(player.playerId) { mutableStateOf(false) }
    var queueSettings by remember(player.playerId) { mutableStateOf<QueueSettings?>(null) }
    val scope = rememberCoroutineScope()

    // Loaded separately from the player config: from MA 2.10 these are queue
    // configuration, and a server that does not have them (or an account that may not
    // read them) simply leaves this null while the rest of the dialog still works.
    LaunchedEffect(player.playerId, onLoadQueueSettings) {
        queueSettings = onLoadQueueSettings?.invoke(player.playerId)
    }

    /**
     * Show a new queue setting at once, then keep it only if the server accepted it.
     * Writing queue config needs an admin account, so a refusal is a normal outcome and
     * must not leave the dialog claiming a change that did not happen.
     *
     * These apply immediately rather than on Save, like the Autoplay source does and
     * unlike the player config below, because they are not part of the batch the Save
     * button sends.
     */
    fun applyQueueChoice(choice: QueueChoice, value: String) {
        if (value == choice.value) return
        queueSettings = queueSettings?.with(choice.copy(value = value))
        scope.launch {
            if (onQueueConfigChanged?.invoke(choice.key, value) != true) {
                // Only this one setting goes back. Restoring a whole snapshot would also
                // undo a different chip the listener tapped while this call was in flight,
                // leaving the screen disagreeing with the server about that one.
                queueSettings = queueSettings?.with(choice)
            }
        }
    }

    LaunchedEffect(player.playerId) {
        val loaded = onLoadConfig(player.playerId)
        if (loaded != null) {
            name = loaded.name.ifBlank { player.displayName }
            crossfadeMode = loaded.crossfadeMode
            volumeNormalization = loaded.volumeNormalization
            formatOptions = loaded.sendspinFormatOptions
            formatKey = loaded.sendspinFormatKey
            selectedFormatValue = loaded.sendspinFormat
            outputCodecOptions = loaded.outputCodecOptions
            // Fall back to the first option so the shown selection always matches what Save persists,
            // even if the server didn't report a current value.
            outputCodec = loaded.outputCodec ?: loaded.outputCodecOptions.firstOrNull()?.value
            outputChannelsOptions = loaded.outputChannelsOptions
            outputChannelsKey = loaded.outputChannelsKey
            loadedOutputChannels = loaded.outputChannels
            outputChannels = loaded.outputChannels ?: loaded.outputChannelsOptions.firstOrNull()?.value
            val loadedStaticDelay = loaded.sendspinStaticDelayMs
            if (!isLocalPlayer && loadedStaticDelay != null) {
                hasServerStaticDelay = true
                staticDelayMs = loadedStaticDelay
                staticDelayKey = loaded.sendspinStaticDelayKey
            }
            if (!isLocalPlayer && loaded.sendspinSyncDelayKey != null) {
                hasServerSyncDelay = true
                syncDelayKey = loaded.sendspinSyncDelayKey
                syncDelayServerMs = loaded.sendspinSyncDelayMs ?: 0
                syncDelayDefault = loaded.sendspinSyncDelayDefault ?: 0
            }
            Log.d("PlayerSettings", "Loaded: provider=${player.provider} format=${loaded.sendspinFormat} options=${loaded.sendspinFormatOptions.map { it.value }}")
        }
        isLoading = false
    }

    // Debounced server push for remote sendspin players. Triggered only when
    // the server advertises the config key so we don't fire into v2.8.6
    // servers that silently ignore the key.
    if (hasServerStaticDelay) {
        LaunchedEffect(player.playerId, isLoading) {
            if (isLoading) return@LaunchedEffect
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            androidx.compose.runtime.snapshotFlow { staticDelayMs }
                .drop(1)
                .debounce(250L)
                .collect { v ->
                    // Use the discovered key so the save lands on protocol-wrapped
                    // players too; fall back to the plain key for safety.
                    onSave(player.playerId, mapOf((staticDelayKey ?: "sendspin_static_delay") to v))
                }
        }
    }

    // Debounced server push of the per-player Sendspin sync delay. Writes the
    // exact discovered key so it lands on plain and protocol-wrapped players
    // alike; MA applies it to the running sync group for live tuning.
    if (hasServerSyncDelay) {
        LaunchedEffect(player.playerId, isLoading) {
            if (isLoading) return@LaunchedEffect
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            androidx.compose.runtime.snapshotFlow { syncDelayServerMs }
                .drop(1)
                .debounce(250L)
                .collect { v ->
                    syncDelayKey?.let { onSave(player.playerId, mapOf(it to v)) }
                }
        }
    }

    /**
     * Show a new Autoplay source at once, then keep it only if the server accepted it.
     * Writing queue config needs an admin account, so a refusal is a normal outcome and
     * must not leave the UI claiming a change that did not happen.
     */
    suspend fun applyAutoplaySource(config: AutoplayConfig, mode: String, playlistUri: String?) {
        val previous = queueSettings
        queueSettings = previous?.copy(autoplay = config.copy(mode = mode, playlistUri = playlistUri))
        if (onAutoplayChanged?.invoke(mode, playlistUri) != true) {
            queueSettings = previous
        }
    }

    // BasicAlertDialog + custom layout so the action buttons don't eat the
    // vertical space that the Material3 AlertDialog reserves for its default
    // title/content/buttons sections.
    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 320.dp, max = 480.dp)
            // Bumped from 560 to 720 so calibration rows at the bottom of the
            // scrollable content aren't clipped on phones with ~800-900 dp
            // available height. Adaptive devices (tablets, foldables) cap at
            // 720 still: generous but not full-screen.
            .heightIn(max = 720.dp)
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            ),
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        androidx.compose.material3.Surface(
            // The surface Material's AlertDialog draws, so this dialog looks like every other
            // dialog in the app. It used to sit a step lower so the setting cards had a ground
            // to stand out against; the rows have no container that needs one.
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            // The rows carry ListItem's own inset, so the dialog adds only enough to bring
            // their content to the 24dp edge an AlertDialog uses.
            Column(modifier = Modifier.padding(horizontal = DIALOG_EDGE_PADDING, vertical = 20.dp)) {
                // The type and gap of an AlertDialog title, so this dialog's heading
                // matches every other dialog in the app.
                Text(
                    "Player settings",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .padding(horizontal = SETTINGS_ROW_INSET)
                        .padding(bottom = 16.dp)
                )
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else {
                    Column(
                        modifier = Modifier
                            // weight(1f) (fill = true, default): claim all the
                            // remaining vertical space inside the dialog so the
                            // verticalScroll has a bounded viewport. With the
                            // earlier fill = false, the column took only its
                            // measured (intrinsic) height, fine until content
                            // exceeded that, and the bottom Cancel/Save row
                            // could push it up so the last items got clipped
                            // without engaging the scroll.
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            // Applied INSIDE the scroll, so it is trailing space in the
                            // content rather than a smaller viewport: without it the last
                            // row ends flush against the clip and its ripple is cut where
                            // the Save row begins.
                            .padding(bottom = 6.dp)
                    ) {
                    // Two groups, because they are saved in two different ways. Player
                    // settings wait for the Save button; queue settings go to the server
                    // the moment they are chosen, like the Autoplay source always has.
                    // Without the split the dialog silently mixed the two and Cancel
                    // appeared to undo changes that had already been written. The player
                    // group comes first: it is what the dialog is named after, and the
                    // name field that heads it is always there.
                    val queue = queueSettings
                    val queueCrossfade = queue?.crossfadeMode
                    val hasQueueSection = initialAutoplayEnabled != null ||
                        queueCrossfade != null ||
                        queue?.volumeNormalization != null ||
                        queue?.smartShuffle != null

                    SettingsSectionHeader("Player", caption = "Saved with the Save button")

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Player name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SETTINGS_ROW_INSET, vertical = 4.dp)
                    )

                    // Where this server still keeps them: before MA 2.10 both are player
                    // config, saved with the button below rather than on selection, so
                    // they belong in this group and not the queue one.
                    if (queueCrossfade == null) {
                        SettingsChoiceRow(
                            title = "Crossfade",
                            icon = Icons.Default.GraphicEq,
                            options = CrossfadeMode.entries.map {
                                QueueConfigOption(value = it.apiValue, title = it.label)
                            },
                            selectedValue = crossfadeMode.apiValue,
                            onSelect = { value -> crossfadeMode = CrossfadeMode.fromApi(value) }
                        )
                    }

                    if (queue?.volumeNormalization == null) {
                        SettingsSwitchRow(
                            title = "Volume normalization",
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            checked = volumeNormalization,
                            onCheckedChange = { volumeNormalization = it }
                        )
                    }

                    // Which source channels this player renders. It is a setting of the
                    // player and stays with it in and out of groups, which is how two
                    // speakers become a stereo pair: one set to left, the other to right.
                    if (outputChannelsOptions.isNotEmpty()) {
                        SettingsChoiceRow(
                            title = "Output channels",
                            icon = Icons.Default.SurroundSound,
                            options = outputChannelsOptions.map {
                                QueueConfigOption(
                                    value = it.value,
                                    title = outputChannelsShortTitle(it.value, outputChannelsOptions)
                                )
                            },
                            selectedValue = outputChannels,
                            onSelect = { outputChannels = it }
                        )
                    }

                    // Offered wherever the config has it: a universal player carries the
                    // Sendspin format under its protocol entry and is not provider "sendspin".
                    if (formatOptions.isNotEmpty()) {
                        AudioFormatRow(
                            formatOptions = formatOptions,
                            selectedValue = selectedFormatValue ?: SendspinAudioFormat.SERVER_AUTOMATIC,
                            isLocalPlayer = isLocalPlayer,
                            onSelect = { selectedFormatValue = it }
                        )
                    }

                    // Generic per-provider output codec (e.g. Sonos: flac/mp3/aac/wav).
                    // Shown for any non-Sendspin player whose MA config exposes it.
                    if (!isSendspinPlayer && outputCodecOptions.isNotEmpty()) {
                        SettingsChoiceRow(
                            title = "Output codec",
                            icon = Icons.Default.AudioFile,
                            options = outputCodecOptions.map {
                                QueueConfigOption(value = it.value, title = it.title)
                            },
                            selectedValue = outputCodec ?: outputCodecOptions.firstOrNull()?.value,
                            onSelect = { outputCodec = it }
                        )
                    }

                    // Delays and calibration are for the rare occasion when a room is out
                    // of step, so they stay folded away rather than filling the dialog
                    // every time someone opens it to rename a player. The phone's own
                    // player gets the Sync row for the output it plays on; a remote
                    // player gets the delays its server config offers.
                    if (isLocalPlayer && acoustic != null) {
                        LocalSyncRow(acoustic)
                    } else if (hasServerStaticDelay || hasServerSyncDelay) {
                        RemoteTimingRow(
                            staticDelayMs = staticDelayMs.takeIf { hasServerStaticDelay },
                            onStaticDelayChange = { staticDelayMs = it },
                            syncDelayMs = syncDelayServerMs.takeIf { hasServerSyncDelay },
                            syncDelayDefaultMs = syncDelayDefault,
                            onSyncDelayChange = { syncDelayServerMs = it }
                        )
                    }

                    if (hasQueueSection) {
                        SettingsSectionDivider()
                        SettingsSectionHeader("Queue", caption = "Changes apply immediately")
                        QueueChoiceRows(
                            queue = queue,
                            // initialCrossfadeEnabled stays null until the queue toggles are
                            // seeded. Drawing the switch then would show "off" over a
                            // crossfade that may be on, so the row waits for a real answer.
                            // The player-config pair does NOT step in meanwhile: on a server
                            // that keeps crossfade on the queue, that key is dead.
                            crossfadeOn = crossfadeOn.takeIf {
                                onCrossfadeEnabledChanged != null && initialCrossfadeEnabled != null
                            },
                            onCrossfadeToggle = {
                                crossfadeOn = it
                                onCrossfadeEnabledChanged?.invoke(it)
                            },
                            onChoice = { choice, value -> applyQueueChoice(choice, value) }
                        )
                        if (initialAutoplayEnabled != null) {
                            AutoplayRow(
                                autoplayOn = autoplayOn,
                                dynamicSource = dynamicSource,
                                sourceConfig = queue?.autoplay
                                    ?.takeIf { autoplayOn && onAutoplayChanged != null },
                                onToggle = {
                                    autoplayOn = it
                                    // Sent now rather than on Save, so the whole group
                                    // behaves the one way its heading promises.
                                    onAutoplayEnabledChanged?.invoke(it)
                                },
                                onSourceChanged = { config, mode, playlistUri ->
                                    applyAutoplaySource(config, mode, playlistUri)
                                }
                            )
                        }
                    }

                    if (rooms.isNotEmpty() && onAssignRoom != null) {
                        SettingsSectionDivider()
                        FollowMeRoomSection(player = player, rooms = rooms, onAssignRoom = onAssignRoom)
                    }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, end = SETTINGS_ROW_INSET),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MdTextButton(onClick = onDismiss) { Text("Cancel") }
                    MdTextButton(
                        onClick = {
                            val values = mutableMapOf<String, Any>()
                            // Only where they still live on the player. From MA 2.10 both
                            // are queue config, already applied on selection, and sending
                            // them here would write keys the server no longer knows.
                            if (queueSettings?.crossfadeMode == null) {
                                values["smart_fades_mode"] = crossfadeMode.apiValue
                            }
                            if (queueSettings?.volumeNormalization == null) {
                                values["volume_normalization"] = volumeNormalization
                            }
                            if (name.isNotBlank() && name.trim() != player.displayName) {
                                values["name"] = name.trim()
                            }
                            val newFormat = selectedFormatValue
                            val newFormatKey = formatKey
                            if (newFormatKey != null && newFormat != null) {
                                values[newFormatKey] = newFormat
                                if (isLocalPlayer) {
                                    val localFormat = when {
                                        newFormat == SendspinAudioFormat.SERVER_AUTOMATIC -> SendspinAudioFormat.AUTOMATIC
                                        newFormat.startsWith("opus") -> SendspinAudioFormat.OPUS
                                        newFormat.startsWith("flac") -> SendspinAudioFormat.FLAC
                                        newFormat.startsWith("pcm") -> SendspinAudioFormat.PCM
                                        else -> null
                                    }
                                    if (localFormat != null) onAudioFormatChanged?.invoke(localFormat)
                                }
                            }
                            if (!isSendspinPlayer && outputCodecOptions.isNotEmpty()) {
                                outputCodec?.let { values["output_codec"] = it }
                            }
                            // Writing this entry makes the server reload the player, so it
                            // goes out only when the listener actually changed it.
                            val channelsKey = outputChannelsKey
                            val newChannels = outputChannels
                            if (channelsKey != null && newChannels != null && newChannels != loadedOutputChannels) {
                                values[channelsKey] = newChannels
                            }
                            // On MA 2.10 crossfade and volume normalization are queue config
                            // and already applied, so this map can be empty. Sending it would
                            // fire an admin-gated command for nothing, and a non-admin would
                            // get the permission notice for a write they never made.
                            if (values.isNotEmpty()) onSave(player.playerId, values)
                            onDismiss()
                        },
                        enabled = !isLoading
                    ) { Text("Save") }
                }
            }
        }
    }
}

/**
 * The Sendspin audio format. "automatic" lets the client decide, and this app decides by
 * network, so on the phone's own player the option is renamed and says what it will pick.
 */
@Composable
private fun AudioFormatRow(
    formatOptions: List<FormatOption>,
    selectedValue: String,
    isLocalPlayer: Boolean,
    onSelect: (String) -> Unit
) {
    SettingsChoiceRow(
        title = "Audio format",
        icon = Icons.Default.HighQuality,
        options = formatOptions.map {
            val isAutomatic = it.value == SendspinAudioFormat.SERVER_AUTOMATIC
            QueueConfigOption(
                value = it.value,
                title = if (isLocalPlayer && isAutomatic) "Automatic" else it.title,
                description = "FLAC on Wi-Fi, Opus on mobile data".takeIf { isLocalPlayer && isAutomatic }
            )
        },
        selectedValue = selectedValue,
        onSelect = onSelect
    )
}

/**
 * The queue settings MA 2.10 moved from the player to the queue: crossfade, volume
 * normalization and smart shuffle. Which ones show follows what the server actually sent
 * rather than a version number.
 *
 * [crossfadeOn] is null while the crossfade switch cannot be drawn truthfully, and the
 * crossfade rows are then left out.
 */
@Composable
private fun QueueChoiceRows(
    queue: QueueSettings?,
    crossfadeOn: Boolean?,
    onCrossfadeToggle: (Boolean) -> Unit,
    onChoice: (QueueChoice, String) -> Unit
) {
    val crossfade = queue?.crossfadeMode
    if (crossfade != null && crossfadeOn != null) {
        SettingsSwitchRow(
            title = "Crossfade",
            icon = Icons.Default.GraphicEq,
            checked = crossfadeOn,
            onCheckedChange = onCrossfadeToggle
        )
        // The type only matters while crossfade is on, which is also why the server no
        // longer offers "off" as a type.
        if (crossfadeOn) {
            SettingsChoiceRow(
                title = "Crossfade type",
                icon = Icons.Default.Merge,
                choice = crossfade.withShortTitles(),
                onSelect = { value -> onChoice(crossfade, value) }
            )
        }
    }

    queue?.volumeNormalization?.let { normalization ->
        SettingsChoiceRow(
            title = "Volume normalization",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            choice = normalization,
            onSelect = { value -> onChoice(normalization, value) }
        )
    }

    // No older counterpart: smart shuffle arrived with the queue config, so it shows only
    // where the server offers it.
    queue?.smartShuffle?.let { smartShuffle ->
        SettingsChoiceRow(
            title = "Smart shuffle",
            icon = Icons.Default.Shuffle,
            choice = smartShuffle,
            onSelect = { value -> onChoice(smartShuffle, value) }
        )
    }
}

/**
 * Autoplay on or off, with its refill source as a row of its own underneath while it is on
 * and the server lets the source be changed. [sourceConfig] is null when there is no source
 * to show.
 *
 * The switch and the source are two rows, the way Crossfade and its type are. A switch
 * inside the row that opened the source list gave one setting two click targets, and the
 * switch had none of the feedback every other switch gives.
 */
@Composable
private fun AutoplayRow(
    autoplayOn: Boolean,
    dynamicSource: DynamicQueueSource?,
    sourceConfig: AutoplayConfig?,
    onToggle: (Boolean) -> Unit,
    onSourceChanged: suspend (config: AutoplayConfig, mode: String, playlistUri: String?) -> Unit
) {
    val title = "Autoplay"
    // The icon Now Playing gives Autoplay, so the setting is recognisable in both places.
    val icon = Icons.Default.AllInclusive
    // The server refills a dynamic queue whether Autoplay is on or not, so there is
    // nothing to switch and the row only says so. A disabled switch here rendered as a
    // lone dark dot on the grayscale palette and looked broken.
    if (dynamicSource != null) {
        SettingsRow(
            title = title,
            icon = icon,
            supporting = autoplayRefillText(dynamicSource.name)
        )
        return
    }
    SettingsSwitchRow(
        title = title,
        icon = icon,
        checked = autoplayOn,
        onCheckedChange = onToggle
    )
    // The sources are whole phrases from the server ("Automatic, similar tracks falling
    // back to your library"), so they stay a list that opens under the row.
    sourceConfig?.let { config ->
        SettingsExpandableRow(
            title = "Autoplay source",
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            supporting = config.summary()
        ) {
            AutoplaySourceSection(
                config = config,
                onChanged = { mode, playlistUri -> onSourceChanged(config, mode, playlistUri) }
            )
        }
    }
}

/**
 * Which Follow Me room this player serves. It is app state rather than player or queue
 * config, so it gets a group of its own: the pick is written the moment it is made and the
 * Save button does not cover it. Setting a room up and calibrating it stays in
 * Settings > Follow Me; this only moves an existing room onto another speaker.
 */
@Composable
private fun FollowMeRoomSection(
    player: Player,
    rooms: List<RoomConfig>,
    onAssignRoom: (roomId: String) -> Unit
) {
    // The room a pick would take away from another player, held until that is confirmed.
    // Saved by id so the confirmation survives rotation; it closes if the room is gone.
    var roomToReassignId by rememberSaveable(player.playerId) { mutableStateOf<String?>(null) }
    val roomToReassign = roomToReassignId?.let { id -> rooms.firstOrNull { it.id == id } }

    SettingsSectionHeader("Follow Me", caption = "Applies immediately")

    val assignedRooms = rooms.filter { it.playerId == player.playerId }
    SettingsChoiceRow(
        title = "Room",
        icon = Icons.Default.LocationOn,
        options = rooms.map { room ->
            QueueConfigOption(
                value = room.id,
                title = room.name,
                description = room.playerName
                    .takeIf { room.playerId != player.playerId }
                    ?.let { "Now on $it" }
            )
        },
        // A single room is passed as the selection, so it is named on the row and gets the
        // radio in the list. Two rooms can name the same player and no single option
        // describes that, so their names are shown instead, as is a player in no room.
        selectedValue = assignedRooms.singleOrNull()?.id,
        supporting = when (assignedRooms.size) {
            0 -> "Not assigned"
            1 -> null
            else -> assignedRooms.joinToString(", ") { it.name }
        },
        onSelect = { roomId ->
            val target = rooms.firstOrNull { it.id == roomId }
            when {
                target == null || target.playerId == player.playerId -> Unit
                // Every room has a player, so picking one always takes it from whoever
                // has it now. Ask before that happens.
                else -> roomToReassignId = target.id
            }
        }
    )

    roomToReassign?.let { target ->
        // NORMAL tone: the move can be undone by picking the room again on the other player,
        // so it is not drawn as a destructive action.
        SettingsConfirmDialog(
            title = "Move room",
            text = "\"${target.name}\" plays on ${target.playerName}. " +
                "Use ${player.displayName} for it instead?",
            confirmLabel = "Move",
            onConfirm = {
                onAssignRoom(target.id)
                roomToReassignId = null
            },
            onDismiss = { roomToReassignId = null },
            confirmTone = SettingsTone.NORMAL
        )
    }
}

/**
 * The server-side delays of a remote player, folded away under one row. A null value
 * means the server does not offer that delay for this player.
 */
@Composable
private fun RemoteTimingRow(
    staticDelayMs: Int?,
    onStaticDelayChange: (Int) -> Unit,
    syncDelayMs: Int?,
    syncDelayDefaultMs: Int,
    onSyncDelayChange: (Int) -> Unit
) {
    SettingsExpandableRow(
        title = "Advanced timing",
        icon = Icons.Default.Tune,
        supporting = "Sync delays and calibration"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            staticDelayMs?.let { ms ->
                // Static playback delay (SERVER-side spec field sendspin_static_delay,
                // available only on MA servers with PR #3689 deployed). Range 0..5000 ms,
                // positive compensates for external delay beyond the audio port (spec
                // sign). Saved via player config; affects ALL clients of this player.
                DelayStepperControl(
                    label = "Static playback delay",
                    helperText = "Makes up for the delay of a device after this player, such as an amplifier.",
                    valueMs = ms,
                    minValue = 0,
                    maxValue = STATIC_DELAY_MAX_MS,
                    onDecrement = { onStaticDelayChange((ms - STATIC_DELAY_STEP_MS).coerceAtLeast(0)) },
                    onIncrement = { onStaticDelayChange((ms + STATIC_DELAY_STEP_MS).coerceAtMost(STATIC_DELAY_MAX_MS)) },
                    onReset = { if (ms != 0) onStaticDelayChange(0) }
                )
            }
            syncDelayMs?.let { ms ->
                // Per-player Sendspin sync delay (server-side sendspin_sync_delay,
                // -1000..1000 ms; negative = earlier, positive = later, matching the MA web
                // UI). Slider for a quick sweep, 1 ms steppers for fine acoustic alignment;
                // Reset returns to the server default. MA applies it live.
                SyncDelayControl(
                    valueMs = ms,
                    defaultMs = syncDelayDefaultMs,
                    onValueChange = { onSyncDelayChange(it.coerceIn(-1000, 1000)) }
                )
            }
        }
    }
}

/**
 * A delay with a Reset and two steppers, and a line explaining it. Drawn without a
 * container: it sits in the detail of a row, which already says what it belongs to.
 */
@Composable
private fun DelayStepperControl(
    label: String,
    helperText: String,
    valueMs: Int,
    minValue: Int,
    maxValue: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onReset: () -> Unit,
    valueText: String? = null,
    resetValue: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // The type scale of a settings row: the value is information, not an accent.
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = valueText ?: "$valueMs ms",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            MdTextButton(
                onClick = onReset,
                enabled = valueMs != resetValue,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 6.dp, vertical = 0.dp
                )
            ) { Text("Reset", style = MaterialTheme.typography.labelMedium) }
            RepeatingIconButton(
                onClick = onDecrement,
                enabled = valueMs > minValue,
                modifier = Modifier.size(STEPPER_TOUCH_TARGET)
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Decrease $label",
                    modifier = Modifier.size(18.dp)
                )
            }
            RepeatingIconButton(
                onClick = onIncrement,
                enabled = valueMs < maxValue,
                modifier = Modifier.size(STEPPER_TOUCH_TARGET)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Increase $label",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            helperText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Per-player Sendspin sync delay tuner: a coarse slider (earlier..later) plus
 * 1 ms steppers for fine acoustic alignment, a signed value, and Reset to the
 * server default. Range -1000..1000 ms; negative = earlier, positive = later.
 *
 * Drawn without a container or a horizontal inset of its own, so the place it sits in (the
 * detail of a settings row, a speaker's section in the sync sheet) decides its alignment.
 */
@Composable
internal fun SyncDelayControl(
    valueMs: Int,
    defaultMs: Int,
    onValueChange: (Int) -> Unit,
    label: String = "Sync delay",
    compact: Boolean = false,
    minMs: Int = -1000,
    maxMs: Int = 1000,
) {
    // Signed (+/-) presentation + earlier/later hints only make sense for a
    // bipolar range (sync delay); a positive-only range (static playback delay,
    // 0..5000) shows a plain "X ms".
    val signed = minMs < 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (signed && valueMs > 0) "+$valueMs ms" else "$valueMs ms",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // The app slider at its natural height. A fixed 28dp height shrank the touch area
        // below the 48dp minimum that every other slider keeps. The value still follows the
        // drag, because the callers apply or debounce it themselves. The empty finish
        // callback is there only so the drag ends with the same haptic as the other sliders.
        MdSlider(
            value = valueMs.toFloat().coerceIn(minMs.toFloat(), maxMs.toFloat()),
            onValueChange = { onValueChange(Math.round(it)) },
            onValueChangeFinished = {},
            valueRange = minMs.toFloat()..maxMs.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        // earlier/later hints are redundant in compact rows (the signed
        // value + steppers already convey direction); drop them to save
        // vertical space when many speakers are stacked.
        if (!compact && signed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "earlier",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "later",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RepeatingIconButton(
                onClick = { onValueChange(valueMs - 1) },
                enabled = valueMs > minMs,
                modifier = Modifier.size(STEPPER_TOUCH_TARGET)
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "1 ms earlier",
                    modifier = Modifier.size(18.dp)
                )
            }
            RepeatingIconButton(
                onClick = { onValueChange(valueMs + 1) },
                enabled = valueMs < maxMs,
                modifier = Modifier.size(STEPPER_TOUCH_TARGET)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "1 ms later",
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            MdTextButton(
                onClick = { onValueChange(defaultMs) },
                enabled = valueMs != defaultMs,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 6.dp, vertical = 0.dp
                )
            ) { Text("Reset", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/**
 * The sync error of the last samples against the lock band (5 ms) and the correction
 * threshold (20 ms).
 *
 * The three states are told apart by line style, not by colour. The palette is grayscale,
 * so primary and tertiary drew "locked" and "converging" in two greys that could not be
 * told apart and only the error colour read. Locked is a solid trace, converging a dashed
 * one, and only an error past the threshold is coloured. The two guide lines are dotted
 * (lock band) and dashed (threshold) for the same reason.
 */
@Composable
internal fun SyncErrorGraph(samples: List<SendspinManager.SyncSample>) {
    val maxAbsError = samples.maxOfOrNull { kotlin.math.abs(it.errorMs) } ?: 0f
    val rangeMs = maxOf(25f, kotlin.math.ceil(maxAbsError / 10f).toInt() * 10f).coerceAtMost(250f)
    val latest = samples.lastOrNull()
    val traceColor = MaterialTheme.colorScheme.onSurface
    val badColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val guideColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Sync convergence",
            style = labelStyle,
            color = labelColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 30.dp, end = 36.dp)
            ) {
                val topInset = 4.dp.toPx()
                val bottomInset = 4.dp.toPx()
                val graphHeight = size.height - topInset - bottomInset
                val centerY = topInset + graphHeight / 2f
                val stepX = size.width / (samples.size.coerceAtLeast(2) - 1).toFloat()

                // Grid: center line (0 ms), lock band (±5 ms, dotted), correction
                // threshold (±20 ms, dashed).
                drawLine(gridColor, Offset(0f, centerY), Offset(size.width, centerY), 1.dp.toPx())
                val guideWidth = 1.dp.toPx()
                val dotted = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))
                val dashed = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                val lockMsY = graphHeight / 2f * (SYNC_LOCK_MS / rangeMs)
                val twentyMsY = graphHeight / 2f * (SYNC_WARN_MS / rangeMs)
                listOf(lockMsY to dotted, twentyMsY to dashed).forEach { (dy, effect) ->
                    listOf(centerY - dy, centerY + dy).forEach { y ->
                        drawLine(guideColor, Offset(0f, y), Offset(size.width, y), guideWidth, pathEffect = effect)
                    }
                }

                // Actual sync convergence: anchor error moving toward 0ms.
                val points = samples.mapIndexed { i, s ->
                    val x = stepX * i
                    val normalized = (s.errorMs / rangeMs).coerceIn(-1f, 1f)
                    val y = centerY - normalized * (graphHeight / 2f)
                    Offset(x, y)
                }

                if (points.size >= 2) {
                    val path = Path()
                    path.moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        val prev = points[i - 1]
                        val curr = points[i]
                        val midX = (prev.x + curr.x) / 2f
                        val midY = (prev.y + curr.y) / 2f
                        path.quadraticTo(prev.x, prev.y, midX, midY)
                    }
                    path.lineTo(points.last().x, points.last().y)

                    // Style from the latest error: solid when locked, dashed while
                    // converging, and the error colour only past the threshold.
                    val absErr = kotlin.math.abs(latest?.errorMs ?: 0f)
                    val lineColor = if (absErr < SYNC_WARN_MS) traceColor else badColor
                    val traceEffect = if (absErr >= SYNC_LOCK_MS && absErr < SYNC_WARN_MS) dashed else null

                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), pathEffect = traceEffect))

                    // Endpoint dot
                    drawCircle(lineColor, radius = 3.dp.toPx(), center = points.last())
                }
            }

            // Labels
            Text(
                text = "+${rangeMs.toInt()}",
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Text(
                text = "-${rangeMs.toInt()}",
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.BottomStart)
            )
            latest?.let {
                val errColor = if (kotlin.math.abs(it.errorMs) < SYNC_WARN_MS) labelColor else badColor
                Text(
                    text = "${"%.1f".format(it.errorMs)} ms",
                    style = labelStyle,
                    color = errColor,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // The latest sync error, output latency and clock filter error, each as its own
        // label spread across the width rather than one dot-joined string.
        latest?.let {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(
                    "Sync ${"%.1f".format(it.errorMs)} ms",
                    "Output ${"%.0f".format(it.outputLatencyMs)} ms",
                    "Clock ${"%.1f".format(it.filterErrorMs)} ms"
                ).forEach { metric ->
                    Text(metric, style = MaterialTheme.typography.bodySmall, color = labelColor)
                }
            }
        }
    }
}

/**
 * The phone's own timing, for the output it plays on now: that output's
 * measured calibration with Calibrate and Reset, and one fine-tune slider.
 * Other calibrated outputs are only listed; each is tuned while it plays.
 */
@Composable
private fun LocalSyncRow(acoustic: AcousticCalibrationCoordinator) {
    val output by acoustic.currentOutput.collectAsStateWithLifecycle(initialValue = null)
    val calibrations by acoustic.acousticRouteCalibrations.collectAsStateWithLifecycle(initialValue = emptyMap())
    val fineTunes by acoustic.outputFineTuneMs.collectAsStateWithLifecycle(initialValue = emptyMap())
    // Saveable and held outside the folded row, so a running calibration
    // survives a rotation, which collapses the row.
    var calibrating by rememberSaveable { mutableStateOf<OutputCalibrationTarget?>(null) }
    val current = output
    val routeKey = current?.routeKey
    val calibration = routeKey?.let { calibrations[it] }
    // One short phrase rather than "name · state": the row's supporting line has room for a
    // sentence, and a dot-joined list of facts reads as notes rather than a status.
    val summary = when {
        current == null -> null
        calibration != null -> "Calibrated for ${current.name}"
        current.canCalibrate && routeKey != null -> "${current.name} is not calibrated"
        else -> current.name
    }
    SettingsExpandableRow(title = "Sync", icon = Icons.Default.Tune, supporting = summary) {
        if (current != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val target = current.kind.calibrationTarget()?.takeIf { current.canCalibrate }
                // The collapsed row already names the output and whether it is calibrated,
                // so the detail adds only what it does not say: the measured correction.
                if (calibration != null || target != null) {
                    OutputCalibrationRow(
                        correctionMs = calibration?.let { it.correctionUs / 1000 },
                        onReset = routeKey?.let { key -> { acoustic.resetCalibration(key) } },
                        onCalibrate = target?.let { t -> { calibrating = t } },
                    )
                }
                if (routeKey != null) {
                    OutputFineTuneControl(
                        routeKey = routeKey,
                        storedMs = fineTunes[routeKey] ?: 0,
                        onApply = acoustic::setOutputFineTuneMs,
                    )
                }
                val others = calibrations.keys.filter { it != routeKey }.map(::outputNameForRouteKey).sorted()
                if (others.isNotEmpty()) {
                    Text(
                        "Also calibrated: ${others.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    calibrating?.let { target ->
        OutputCalibrationDialog(
            coordinator = acoustic,
            target = target,
            routeName = current?.name.orEmpty(),
            onDismiss = { calibrating = null },
        )
    }
}

/**
 * The fine-tune of output [routeKey], in the range the settings allow,
 * debounced like the other delay sliders because each write reaches DataStore
 * and the engine.
 */
@Composable
private fun OutputFineTuneControl(routeKey: String, storedMs: Int, onApply: (String, Int) -> Unit) {
    val max = SettingsRepository.OUTPUT_FINE_TUNE_MAX_MS
    var value by remember(routeKey, storedMs) { mutableIntStateOf(storedMs) }
    // Restarted with storedMs: the apply round-trips through DataStore and
    // re-keys the state above, so the snapshotFlow must bind to the new one.
    LaunchedEffect(routeKey, storedMs) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        androidx.compose.runtime.snapshotFlow { value }
            .drop(1)
            .debounce(250L)
            .collect { onApply(routeKey, it) }
    }
    SyncDelayControl(
        label = "Fine-tune",
        valueMs = value,
        defaultMs = 0,
        minMs = -max,
        maxMs = max,
        onValueChange = { value = it.coerceIn(-max, max) },
    )
}

/**
 * The current output's calibration: the measured [correctionMs], or null before it has
 * been measured, with Reset and Calibrate where they apply. The output's name and state are
 * on the row above, so they are not repeated here.
 */
@Composable
private fun OutputCalibrationRow(
    correctionMs: Long?,
    onReset: (() -> Unit)?,
    onCalibrate: (() -> Unit)?,
) {
    val calibrated = correctionMs != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Calibration", style = MaterialTheme.typography.bodyLarge)
            correctionMs?.let {
                Text(
                    "$it ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (calibrated && onReset != null) {
                MdTextButton(onClick = onReset) { Text("Reset") }
            }
            if (onCalibrate != null) {
                MdTextButton(onClick = onCalibrate) {
                    Text(if (calibrated) "Recalibrate" else "Calibrate")
                }
            }
        }
    }
}

/**
 * The dialog's own horizontal padding. The rows add ListItem's 16dp inset inside it, which
 * puts their content at the 24dp edge Material's AlertDialog uses.
 */
private val DIALOG_EDGE_PADDING = 8.dp

/**
 * The size of a delay stepper. The icon inside stays small, but the button keeps Material's
 * 48dp minimum: at 36dp the steppers were the smallest targets in the dialog, and they are
 * the ones pressed repeatedly.
 */
private val STEPPER_TOUCH_TARGET = 48.dp

/** The sync graph's lock band and correction threshold, either side of 0. */
private const val SYNC_LOCK_MS = 5f
private const val SYNC_WARN_MS = 20f

/** MA's range for `sendspin_static_delay`, and the step of its steppers. */
private const val STATIC_DELAY_MAX_MS = 5000
private const val STATIC_DELAY_STEP_MS = 2
