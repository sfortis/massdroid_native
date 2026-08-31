package net.asksakis.massdroidv2.ui.components

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Tune
import net.asksakis.massdroidv2.domain.model.QueueConfigOption
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.sendspin.SendspinManager
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.QueueChoice
import net.asksakis.massdroidv2.domain.model.QueueSettings
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerConfig
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsDialog(
    player: Player,
    initialAutoplayEnabled: Boolean?,
    isSendspinPlayer: Boolean = false,
    isLocalPlayer: Boolean = false,
    initialAudioFormat: SendspinAudioFormat = SendspinAudioFormat.SMART,
    initialSyncDelayMs: Int = 0,
    onLoadConfig: suspend (playerId: String) -> PlayerConfig?,
    onSave: (playerId: String, values: Map<String, Any>) -> Unit,
    onAutoplayEnabledChanged: ((enabled: Boolean) -> Unit)?,
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
    onSyncDelayChanged: ((Int) -> Unit)? = null,
    isBtRoute: Boolean = false,
    acousticCorrectionMs: Int = 0,
    acoustic: net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator? = null,
    micPathCalibratedMs: Long = 0L,
    isPlaybackActive: Boolean = false,
    btRouteName: String = "",
    onPausePlayback: (() -> Unit)? = null,
    onResumePlayback: (() -> Unit)? = null,
    onResetBtCalibration: (() -> Unit)? = null,
    onResetMicPath: (() -> Unit)? = null,
    syncHistory: List<SendspinManager.SyncSample> = emptyList(),
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
    var audioFormat by remember(player.playerId, initialAudioFormat) { mutableStateOf(initialAudioFormat) }
    // Generic per-provider output codec (MA `output_codec`, e.g. Sonos flac/mp3/aac/wav).
    var outputCodec by remember(player.playerId) { mutableStateOf<String?>(null) }
    var outputCodecOptions by remember(player.playerId) {
        mutableStateOf<List<net.asksakis.massdroidv2.domain.model.FormatOption>>(emptyList())
    }
    // Local client-side UX sync nudge (DataStore-backed). Range -1000..+1000,
    // positive shifts playback later (intuitive sign, matches MA web UI's
    // "Sendspin sync delay" slider).
    var syncDelayMs by remember(player.playerId, initialSyncDelayMs) {
        mutableIntStateOf(initialSyncDelayMs)
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
            selectedFormatValue = loaded.sendspinFormat
            outputCodecOptions = loaded.outputCodecOptions
            // Fall back to the first option so the shown selection always matches what Save persists,
            // even if the server didn't report a current value.
            outputCodec = loaded.outputCodec ?: loaded.outputCodecOptions.firstOrNull()?.value
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

    // Debounced apply of the LOCAL client-side nudge. The slider fires rapidly,
    // and onSyncDelayChanged persists to DataStore + reanchors the engine, so
    // coalesce drags into one apply (steppers benefit too).
    if (isLocalPlayer && onSyncDelayChanged != null) {
        // Restart with initialSyncDelayMs: the apply round-trips through DataStore
        // and re-keys the syncDelayMs remember (new state object), so the
        // snapshotFlow must re-bind. Without this the observer goes stale after
        // the first apply and later changes (e.g. Reset) are silently dropped.
        LaunchedEffect(player.playerId, isLoading, initialSyncDelayMs) {
            if (isLoading) return@LaunchedEffect
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            androidx.compose.runtime.snapshotFlow { syncDelayMs }
                .drop(1)
                .debounce(250L)
                .collect { v -> onSyncDelayChanged?.invoke(v) }
        }
    }

    // BasicAlertDialog + custom layout so the action buttons don't eat the
    // vertical space that the Material3 AlertDialog reserves for its default
    // title/content/buttons sections.
    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 320.dp, max = 480.dp)
            // Bumped from 560 → 720 so calibration rows at the bottom of the
            // scrollable content aren't clipped on phones with ~800-900 dp
            // available height. Adaptive devices (tablets, foldables) cap at
            // 720 still — generous but not full-screen.
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
            shape = MaterialTheme.shapes.extraLarge,
            // The ground the setting cards sit on, so it has to stay below them. It used
            // to be surfaceContainerHigh, the colour of the cards themselves, which left
            // them nothing to stand out against. Measured on this device: the cards are
            // 42,42,42, plain surface was 18,18,18 (Edit Room's ground, too dark for a
            // dialog), and this token sits between the two.
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            // Tonal elevation would tint that ground back up and undo the separation; the
            // dialog lifts off the dimmed screen with a shadow instead.
            tonalElevation = 0.dp,
            shadowElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text(
                    "Player Settings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 18.dp)
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
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            // weight(1f) (fill = true, default): claim all the
                            // remaining vertical space inside the dialog so the
                            // verticalScroll has a bounded viewport. With the
                            // earlier fill = false, the column took only its
                            // measured (intrinsic) height — fine until content
                            // exceeded that — and the bottom Cancel/Save row
                            // could push it up so the last items got clipped
                            // without engaging the scroll.
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            // Applied INSIDE the scroll, so it is trailing space in the
                            // content rather than a smaller viewport: without it the last
                            // card ends flush against the clip and its bottom edge and
                            // ripple are cut where the Save row begins.
                            .padding(bottom = 6.dp)
                    ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Player name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Two groups, because they are saved in two different ways. Queue
                    // settings go to the server the moment they are chosen, like the
                    // Autoplay source always has; player settings wait for the Save
                    // button. Without the split the dialog silently mixed the two and
                    // Cancel appeared to undo changes that had already been written.
                    val queue = queueSettings
                    val queueCrossfade = queue?.crossfadeMode
                    val hasQueueSection = initialAutoplayEnabled != null ||
                        queueCrossfade != null ||
                        queue?.volumeNormalization != null ||
                        queue?.smartShuffle != null
                    // A remote player on MA 2.10 with nothing of its own to configure
                    // would otherwise get a heading over an empty card.
                    val hasPlayerSection = queueCrossfade == null ||
                        queue?.volumeNormalization == null ||
                        (isSendspinPlayer && formatOptions.isNotEmpty()) ||
                        (!isSendspinPlayer && outputCodecOptions.isNotEmpty()) ||
                        isLocalPlayer || hasServerStaticDelay || hasServerSyncDelay

                    if (hasQueueSection) {
                        SettingsSectionLabel("Queue", caption = "Changes apply immediately")

                            // Crossfade, volume normalization and smart shuffle moved from
                            // the player to the queue in MA 2.10. Which set of controls is
                            // shown follows what the server actually sent rather than a
                            // version number: a queue that reports these settings gets them
                            // here, anything older keeps the player-config pair below.
                            // initialCrossfadeEnabled stays null until the queue toggles are
                            // seeded. Drawing the switch then would show "off" over a
                            // crossfade that may be on, so the card waits for a real answer.
                            // The player-config pair does NOT step in meanwhile: on a server
                            // that keeps crossfade on the queue, that key is dead.
                            if (queueCrossfade != null && onCrossfadeEnabledChanged != null &&
                                initialCrossfadeEnabled != null
                            ) {
                                QueueChoiceCard(
                                    title = "Crossfade",
                                    icon = Icons.Default.GraphicEq,
                                    // The type only matters while crossfade is on, which is
                                    // also why the server no longer offers "off" as a type.
                                    choice = queueCrossfade.takeIf { crossfadeOn }?.withShortTitles(),
                                    onSelect = { value -> applyQueueChoice(queueCrossfade, value) },
                                    trailing = {
                                        Switch(
                                            checked = crossfadeOn,
                                            onCheckedChange = {
                                                crossfadeOn = it
                                                onCrossfadeEnabledChanged(it)
                                            }
                                        )
                                    }
                                )
                            }

                            queue?.volumeNormalization?.let { normalization ->
                                QueueChoiceCard(
                                    title = "Volume normalization",
                                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                                    choice = normalization,
                                    onSelect = { value -> applyQueueChoice(normalization, value) }
                                )
                            }

                            // No older counterpart: smart shuffle arrived with the queue
                            // config, so it shows only where the server offers it.
                            queue?.smartShuffle?.let { smartShuffle ->
                                QueueChoiceCard(
                                    title = "Smart shuffle",
                                    icon = Icons.Default.Shuffle,
                                    choice = smartShuffle,
                                    onSelect = { value -> applyQueueChoice(smartShuffle, value) }
                                )
                            }

                            if (initialAutoplayEnabled != null) {
                                val autoplaySwitch: @Composable () -> Unit = {
                                    Switch(
                                        checked = autoplayOn,
                                        onCheckedChange = {
                                            autoplayOn = it
                                            // Sent now rather than on Save, so the whole
                                            // group behaves the one way its heading promises.
                                            onAutoplayEnabledChanged?.invoke(it)
                                        }
                                    )
                                }
                                // The sources are whole phrases from the server ("Automatic,
                                // similar tracks falling back to your library"), too long for
                                // chips, so they stay a list that opens under the row.
                                val autoplayConfig = queue?.autoplay
                                    ?.takeIf { autoplayOn && onAutoplayChanged != null }
                                if (autoplayConfig == null) {
                                    SettingsSwitchCard(
                                        title = "Autoplay",
                                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                                        checked = autoplayOn,
                                        onCheckedChange = {
                                            autoplayOn = it
                                            onAutoplayEnabledChanged?.invoke(it)
                                        }
                                    )
                                } else {
                                    ExpandableSettingCard(
                                        title = "Autoplay",
                                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                                        value = autoplayConfig.summary(),
                                        trailing = autoplaySwitch
                                    ) {
                                        AutoplaySourceSection(
                                            config = autoplayConfig,
                                            onChanged = { mode, playlistUri ->
                                                // Show the choice immediately, then keep it
                                                // only if the server accepted it. Writing
                                                // queue config needs an admin account, so a
                                                // refusal is a normal outcome and must not
                                                // leave the UI claiming a change that did
                                                // not happen.
                                                val previous = queueSettings
                                                queueSettings = previous?.copy(
                                                    autoplay = autoplayConfig.copy(
                                                        mode = mode,
                                                        playlistUri = playlistUri
                                                    )
                                                )
                                                if (onAutoplayChanged?.invoke(mode, playlistUri) != true) {
                                                    queueSettings = previous
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                    }

                    // The one field that names what is being edited, so it stays at the
                    // top rather than inside a group. It is saved with the button below,
                    // like everything in the Player group.
                    if (hasPlayerSection) {
                        SettingsSectionLabel("Player")

                        // Where this server still keeps them: before MA 2.10 both are player
                        // config, saved with the button below rather than on selection, so
                        // they belong in this group and not the one above.
                        if (queueCrossfade == null) {
                            QueueChoiceCard(
                                title = "Crossfade",
                                icon = Icons.Default.GraphicEq,
                                choice = QueueChoice(
                                    key = QueueChoice.KEY_CROSSFADE_MODE,
                                    value = crossfadeMode.apiValue,
                                    options = CrossfadeMode.entries.map {
                                        QueueConfigOption(value = it.apiValue, title = it.label)
                                    }
                                ),
                                onSelect = { value -> crossfadeMode = CrossfadeMode.fromApi(value) }
                            )
                        }

                        if (queue?.volumeNormalization == null) {
                            SettingsSwitchCard(
                                title = "Volume normalization",
                                icon = Icons.AutoMirrored.Filled.VolumeUp,
                                checked = volumeNormalization,
                                onCheckedChange = { volumeNormalization = it }
                            )
                        }

                        if (isSendspinPlayer && formatOptions.isNotEmpty()) {
                            val smartOption = net.asksakis.massdroidv2.domain.model.FormatOption(
                                title = "Smart", value = "smart"
                            )
                            val allOptions =
                                if (isLocalPlayer) listOf(smartOption) + formatOptions else formatOptions
                            val currentValue = selectedFormatValue ?: "automatic"
                            QueueChoiceCard(
                                title = "Audio format",
                                icon = Icons.Default.HighQuality,
                                choice = QueueChoice(
                                    key = "audio_format",
                                    value = currentValue,
                                    options = allOptions.map {
                                        QueueConfigOption(
                                            value = it.value,
                                            title = it.title,
                                            description = "FLAC on WiFi, Opus on mobile"
                                                .takeIf { _ -> it.value == "smart" }
                                        )
                                    }
                                ),
                                onSelect = { selectedFormatValue = it }
                            )
                        }

                        // Generic per-provider output codec (e.g. Sonos: flac/mp3/aac/wav).
                        // Shown for any non-Sendspin player whose MA config exposes it.
                        if (!isSendspinPlayer && outputCodecOptions.isNotEmpty()) {
                            QueueChoiceCard(
                                title = "Output codec",
                                icon = Icons.Default.AudioFile,
                                choice = QueueChoice(
                                    key = "output_codec",
                                    value = outputCodec
                                        ?: outputCodecOptions.firstOrNull()?.value.orEmpty(),
                                    options = outputCodecOptions.map {
                                        QueueConfigOption(value = it.value, title = it.title)
                                    }
                                ),
                                onSelect = { outputCodec = it }
                            )
                        }

                        // Delays and acoustic calibration are for the rare occasion when a
                        // room is out of step, so they stay folded away rather than filling
                        // the dialog every time someone opens it to rename a player.
                        if (isLocalPlayer || hasServerStaticDelay || hasServerSyncDelay) {
                            ExpandableSettingCard(
                                title = "Advanced timing",
                                icon = Icons.Default.Tune,
                                value = "Sync delays and calibration"
                            ) {
                        if (isLocalPlayer) {
                            // Sendspin sync delay (LOCAL client-side UX nudge,
                            // DataStore-backed). Range -1000..+1000 ms, negative plays
                            // sooner / positive later. Applied locally in
                            // SendspinSyncEngine; not sent to the server.
                            SyncDelayCard(
                                label = "Sendspin sync delay",
                                valueMs = syncDelayMs,
                                defaultMs = 0,
                                // Debounced via the LaunchedEffect below (the slider
                                // fires rapidly and onSyncDelayChanged persists to
                                // DataStore + reanchors the engine).
                                onValueChange = { syncDelayMs = it.coerceIn(-1000, 1000) }
                            )
                        }

                        if (hasServerStaticDelay) {
                            // Static playback delay (SERVER-side spec field
                            // sendspin_static_delay, available only on MA servers
                            // with PR #3689 deployed). Range 0..5000 ms, positive
                            // compensates for external delay beyond the audio
                            // port (spec sign). Saved via player config; affects
                            // ALL clients of this player.
                            DelayStepperCard(
                                label = "Static playback delay",
                                helperText = "Server-side spec compensation for external device delay. Affects all clients of this player.",
                                valueMs = staticDelayMs,
                                minValue = 0,
                                maxValue = 5000,
                                onDecrement = {
                                    staticDelayMs = (staticDelayMs - 2).coerceAtLeast(0)
                                },
                                onIncrement = {
                                    staticDelayMs = (staticDelayMs + 2).coerceAtMost(5000)
                                },
                                onReset = {
                                    if (staticDelayMs != 0) {
                                        staticDelayMs = 0
                                    }
                                }
                            )
                        }

                        if (hasServerSyncDelay) {
                            // Per-player Sendspin sync delay (server-side
                            // sendspin_sync_delay, -1000..1000 ms; negative = earlier,
                            // positive = later, matching the MA web UI). Slider for a
                            // quick sweep, 1 ms steppers for fine acoustic alignment;
                            // Reset returns to the server default. MA applies it live.
                            SyncDelayCard(
                                valueMs = syncDelayServerMs,
                                defaultMs = syncDelayDefault,
                                onValueChange = { syncDelayServerMs = it.coerceIn(-1000, 1000) }
                            )
                        }

                        // Acoustic calibration for the active Bluetooth output route.
                        // No phone-speaker row: phone, wired and USB paths sync at
                        // the audio port via the AudioTrack pipeline measurement
                        // (per the Sendspin spec), so an acoustic chirp would
                        // double-count the listener air path. The BT row stays
                        // visible on local-player settings regardless of the
                        // current route, but the Calibrate button is enabled only
                        // while a BT route is connected (so users can review or
                        // reset a saved value even when BT is currently off).
                        //
                        // A second row reports the cached "mic path" reference
                        // (the phone-side mic chain latency measured once on the
                        // built-in speaker). It is reused across all BT speakers
                        // by the two-pass algorithm. A Reset button forces a
                        // re-measurement on the next BT calibration.
                        if (isLocalPlayer && acoustic != null) {
                            var showBtCalibrationDialog by remember { mutableStateOf(false) }
                            val btDeviceName = btRouteName.ifBlank { "Bluetooth speaker" }

                            // Built-in speaker self-calibration. Measures the true
                            // acoustic output delay to correct HALs that under-report
                            // getOutputLatency (e.g. Xiaomi). Auto-runs on group join
                            // when missing; also tunable here.
                            val speakerCalibrations by acoustic.acousticRouteCalibrations
                                .collectAsStateWithLifecycle(initialValue = emptyMap())
                            val speakerCal = speakerCalibrations[
                                net.asksakis.massdroidv2.data.sendspin.AcousticCalibrationCoordinator.SPEAKER_ROUTE_KEY
                            ]
                            val speakerCorrectionMs = ((speakerCal?.correctionUs ?: 0L) / 1000L).toInt()
                            var showSpeakerCalibrationDialog by remember { mutableStateOf(false) }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Speaker calibration", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (speakerCal != null) "This phone: ${speakerCorrectionMs}ms (${speakerCal.quality.lowercase()})"
                                        else "Not calibrated (runs automatically on group join)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (speakerCal != null) {
                                        MdTextButton(onClick = { acoustic.resetSpeakerCalibration() }) {
                                            Text("Reset")
                                        }
                                    }
                                    MdTextButton(onClick = { showSpeakerCalibrationDialog = true }) {
                                        Text(if (speakerCal != null) "Recalibrate" else "Calibrate")
                                    }
                                }
                            }
                            if (showSpeakerCalibrationDialog) {
                                SpeakerCalibrationDialog(
                                    coordinator = acoustic,
                                    onDismiss = { showSpeakerCalibrationDialog = false }
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Bluetooth calibration", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when {
                                            !isBtRoute -> "Connect a Bluetooth device to calibrate"
                                            acousticCorrectionMs > 0 -> "$btDeviceName: ${acousticCorrectionMs}ms"
                                            else -> "$btDeviceName not calibrated"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isBtRoute && acousticCorrectionMs > 0) {
                                        MdTextButton(onClick = { onResetBtCalibration?.invoke() }) {
                                            Text("Reset")
                                        }
                                    }
                                    MdTextButton(
                                        enabled = isBtRoute,
                                        onClick = { showBtCalibrationDialog = true }
                                    ) {
                                        Text(if (acousticCorrectionMs > 0) "Recalibrate" else "Calibrate")
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Mic path reference", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (micPathCalibratedMs > 0L) {
                                            "Calibrated: ${micPathCalibratedMs}ms (shared across BT routes)"
                                        } else {
                                            "Will be measured on the next BT calibration"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (micPathCalibratedMs > 0L) {
                                    MdTextButton(onClick = { onResetMicPath?.invoke() }) {
                                        Text("Reset")
                                    }
                                }
                            }
                            if (showBtCalibrationDialog) {
                                AcousticCalibrationDialog(
                                    routeName = btDeviceName,
                                    isPlaybackActive = isPlaybackActive,
                                    coordinator = acoustic,
                                    onPausePlayback = { onPausePlayback?.invoke() },
                                    onResumePlayback = { onResumePlayback?.invoke() },
                                    onDismiss = { showBtCalibrationDialog = false }
                                )
                            }
                        }
                            }
                        }
                    }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
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
                            if (isSendspinPlayer && newFormat != null) {
                                val serverValue = if (newFormat == "smart") "automatic" else newFormat
                                values["preferred_sendspin_format"] = serverValue
                                if (isLocalPlayer) {
                                    val localFormat = when {
                                        newFormat == "smart" -> SendspinAudioFormat.SMART
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

@Composable
private fun DelayStepperCard(
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
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = valueText ?: "${valueMs}ms",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
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
                    modifier = Modifier.size(36.dp)
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
                    modifier = Modifier.size(36.dp)
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Per-player Sendspin sync delay tuner: a coarse slider (earlier..later) plus
 * 1 ms steppers for fine acoustic alignment, a signed value, and Reset to the
 * server default. Range -1000..1000 ms; negative = earlier, positive = later.
 */
@Composable
internal fun SyncDelayCard(
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
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = if (compact) 8.dp else 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (signed && valueMs > 0) "+$valueMs ms" else "$valueMs ms",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = valueMs.toFloat().coerceIn(minMs.toFloat(), maxMs.toFloat()),
                onValueChange = { onValueChange(Math.round(it)) },
                valueRange = minMs.toFloat()..maxMs.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
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
                    modifier = Modifier.size(36.dp)
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
                    modifier = Modifier.size(36.dp)
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
}

@Composable
internal fun SyncErrorGraph(samples: List<SendspinManager.SyncSample>) {
    val maxAbsError = samples.maxOfOrNull { kotlin.math.abs(it.errorMs) } ?: 0f
    val rangeMs = maxOf(25f, kotlin.math.ceil(maxAbsError / 10f).toInt() * 10f).coerceAtMost(250f)
    val latest = samples.lastOrNull()
    val goodColor = MaterialTheme.colorScheme.primary
    val warnColor = MaterialTheme.colorScheme.tertiary
    val badColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
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

                // Grid: center line (0ms), lock band (±5ms), correction threshold (±20ms).
                drawLine(gridColor, Offset(0f, centerY), Offset(size.width, centerY), 1.dp.toPx())
                val lockMsY = graphHeight / 2f * (5f / rangeMs)
                drawLine(goodColor.copy(alpha = 0.45f), Offset(0f, centerY - lockMsY), Offset(size.width, centerY - lockMsY), 0.5.dp.toPx())
                drawLine(goodColor.copy(alpha = 0.45f), Offset(0f, centerY + lockMsY), Offset(size.width, centerY + lockMsY), 0.5.dp.toPx())
                val twentyMsY = graphHeight / 2f * (20f / rangeMs)
                drawLine(warnColor.copy(alpha = 0.6f), Offset(0f, centerY - twentyMsY), Offset(size.width, centerY - twentyMsY), 0.5.dp.toPx())
                drawLine(warnColor.copy(alpha = 0.6f), Offset(0f, centerY + twentyMsY), Offset(size.width, centerY + twentyMsY), 0.5.dp.toPx())

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

                    // Color based on latest error magnitude
                    val absErr = kotlin.math.abs(latest?.errorMs ?: 0f)
                    val lineColor = when {
                        absErr < 5f -> goodColor
                        absErr < 20f -> warnColor
                        else -> badColor
                    }

                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))

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
                val absErr = kotlin.math.abs(it.errorMs)
                val errColor = when {
                    absErr < 5f -> goodColor
                    absErr < 20f -> warnColor
                    else -> badColor
                }
                Text(
                    text = "${"%.1f".format(it.errorMs)}ms",
                    style = labelStyle,
                    color = errColor,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        // Output latency + filter error info line
        latest?.let {
            Text(
                text = "Sync=${"%.1f".format(it.errorMs)}ms  " +
                    "Output=${"%.0f".format(it.outputLatencyMs)}ms  Clock=${"%.1f".format(it.filterErrorMs)}ms",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Names a group of settings and, where it matters, says when the group is written.
 *
 * The queue group goes to the server on selection and the player group waits for Save, a
 * difference the dialog otherwise gave no way to see.
 */
@Composable
private fun SettingsSectionHeader(title: String, caption: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
