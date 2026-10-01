package net.asksakis.massdroidv2.ui.nfc

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import net.asksakis.massdroidv2.data.nfc.NfcTagRecord
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.nfc.NfcTagAction
import net.asksakis.massdroidv2.domain.nfc.NfcTagPayload
import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdTextButton
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.components.SoundWaveIcon
import net.asksakis.massdroidv2.ui.screens.home.PlayerIcon

/**
 * Which part of writing a tag the sheet is showing. Modelled as one value rather than a
 * handful of booleans, because the three are mutually exclusive and the reader mode has to
 * be running for exactly one of them.
 */
private sealed interface WriteStage {
    /** Only reached when the caller offers more than one thing to write. */
    data class ChoosingMedia(val choices: List<NfcWriteChoice>) : WriteStage
    data class ChoosingPlayer(val media: NfcWriteChoice) : WriteStage
    data class Waiting(val payload: NfcTagPayload, val playerName: String?) : WriteStage
    data class Done(val result: NfcWriteResult) : WriteStage
}

/**
 * Something a tag could be written with.
 *
 * The player screen can offer two: the playlist or album the queue is playing from, which
 * the server reports, and the album of the track itself. They are genuinely different
 * requests and neither is obviously the one meant, so the choice is the user's.
 */
data class NfcWriteChoice(
    val action: NfcTagAction,
    /** What the row says, which is the thing itself for media and the deed for a speaker. */
    val title: String,
    /** The line under it, which says what tapping will actually do. */
    val detail: String,
    /**
     * What goes on the tag and is read back out on a tap. Separate from [title] because a
     * speaker row is named after the action, and a toast saying "Playing Move what is
     * playing here" would be nonsense.
     */
    val tagLabel: String
)

/**
 * The two things a tag written for a speaker can do.
 *
 * Both are about the speaker rather than about any particular music, which is what makes a
 * tag by the door worth having: it moves the listening into the room instead of starting
 * something new. Lives here rather than in either screen that offers it, because both do.
 */
fun speakerTagChoices(player: Player): List<NfcWriteChoice> = listOf(
    NfcWriteChoice(
        action = NfcTagAction.TransferQueue,
        title = "Move what is playing here",
        detail = "Takes over from wherever the music is, at the same point",
        // Distinct from the other row's, because this is what the toast reads back and
        // what names the tag in settings. Two tags for one speaker that both said just
        // the speaker's name were indistinguishable in both places.
        tagLabel = "${player.displayName}, moved here"
    ),
    NfcWriteChoice(
        action = NfcTagAction.Resume,
        title = "Just send play",
        detail = "Starts whatever is already in its queue",
        tagLabel = "${player.displayName}, play"
    )
)

/**
 * Writes an album or a playlist onto a tag, bound to the speaker the user picks.
 *
 * The tag is written with the whole instruction, so it works on any phone with the app.
 * The record kept locally is only ever used to list the tag on screen later.
 *
 * [choices] is what the tag could hold. A screen showing one thing passes one and the
 * sheet opens straight on the speaker; the player screen passes both what the queue is
 * playing from and the album of the current track, and the user says which.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcWriteSheet(
    choices: List<NfcWriteChoice>,
    onDismiss: () -> Unit,
    /**
     * The speaker this tag is for, when the caller already knows it. Passing one skips the
     * speaker step: a tag written from a speaker's own settings is about that speaker, and
     * asking again which speaker it is would be asking the same question twice.
     */
    fixedPlayer: Player? = null,
    viewModel: NfcWriteViewModel = hiltViewModel()
) {
    if (choices.isEmpty()) return
    val players by viewModel.players.collectAsStateWithLifecycle()
    val selectedPlayer by viewModel.selectedPlayer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val writer = remember(activity) { activity?.let { NfcTagWriter(it) } }
    var stage by remember(choices, fixedPlayer) {
        mutableStateOf(startingStage(choices, fixedPlayer))
    }
    // Null means the tag says nothing about volume and leaves it where the listener had
    // it. Offered on the last screen rather than as a step of its own, so a tag that does
    // not care about volume costs no extra tap.
    var tagVolume by remember(choices, fixedPlayer) { mutableStateOf<Int?>(null) }

    // Re-read on every resume. The adapter's own state is a plain getter, so reading it
    // once during composition left the sheet stuck on "NFC is off" after the user took
    // the button offered here, switched NFC on in system settings and came back.
    var nfcEnabled by remember(writer) { mutableStateOf(writer?.isEnabled == true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, writer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) nfcEnabled = writer?.isEnabled == true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Reader mode is held for as long as the sheet is open, not only while it is waiting
    // for a tag. Giving it up the moment a write succeeded handed the tag, still against
    // the phone, to the platform's own dispatcher, which opened the tap activity and
    // started playing the album the user had just written.
    //
    // Which tag gets written is decided per tag instead, from the stage as it stands when
    // one arrives. Read through rememberUpdatedState because the reader callback is
    // installed once and would otherwise keep looking at the stage this ran on.
    val currentStage by rememberUpdatedState(stage)
    val currentVolume by rememberUpdatedState(tagVolume)
    val currentPlayerName by rememberUpdatedState((stage as? WriteStage.Waiting)?.playerName)
    DisposableEffect(writer) {
        writer?.startWriting(
            payloadFor = {
                (currentStage as? WriteStage.Waiting)?.payload?.copy(volume = currentVolume)
            }
        ) { payload, result ->
            // Before anything on screen, because the phone is against a tag and the screen
            // is not being looked at.
            if (result is NfcWriteResult.Written) {
                NfcFeedback.written(view)
            } else {
                NfcFeedback.failed(view)
            }
            if (result is NfcWriteResult.Written) {
                viewModel.remember(
                    NfcTagRecord(
                        tagId = result.tagId,
                        mediaUri = payload.mediaUri.orEmpty(),
                        playerId = payload.playerId,
                        // From the payload, which was frozen when the speaker was chosen.
                        // Taking it live could record one album's name against another
                        // album's uri.
                        label = payload.label.orEmpty(),
                        playerName = currentPlayerName,
                        volume = payload.volume,
                        writtenAtMs = System.currentTimeMillis()
                    )
                )
            }
            stage = WriteStage.Done(result)
        }
        onDispose { writer?.stop() }
    }

    ModalBottomSheet(
        sheetMaxWidth = SheetDefaults.maxWidth(),
        onDismissRequest = onDismiss,
        sheetState = SheetDefaults.sheetState(),
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            SheetDefaults.HeaderTitle(
                text = "Write NFC tag",
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = SheetDefaults.HeaderVerticalPadding
                )
            )
            Text(
                // Who or what this tag is being made for, said once here so no row has to
                // repeat it. Falls back to the media once one is chosen, and to the
                // speaker whenever the caller already settled that.
                text = fixedPlayer?.displayName
                    ?: (stage as? WriteStage.ChoosingPlayer)?.media?.tagLabel
                    ?: (stage as? WriteStage.Waiting)?.payload?.label
                    ?: "Choose what it should start",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = 4.dp
                )
            )
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))

            when {
                writer == null || !writer.isAvailable -> NfcMessage(
                    icon = Icons.Default.ErrorOutline,
                    text = "This phone has no NFC chip, so tags cannot be written here."
                )
                !nfcEnabled -> Column {
                    NfcMessage(
                        icon = Icons.Default.Nfc,
                        text = "NFC is switched off. Turn it on to write a tag."
                    )
                    MdButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SheetDefaults.HeaderHorizontalPadding)
                    ) { Text("Open NFC settings") }
                }
                else -> when (val current = stage) {
                    is WriteStage.ChoosingMedia -> MediaChoice(
                        prompt = if (fixedPlayer != null) {
                            "What should a tap on this tag do?"
                        } else {
                            "What should this tag start?"
                        },
                        choices = current.choices,
                        onChosen = { chosen ->
                            stage = fixedPlayer
                                ?.let { waitingFor(chosen, it) }
                                ?: WriteStage.ChoosingPlayer(chosen)
                        }
                    )
                    is WriteStage.ChoosingPlayer -> PlayerChoice(
                        media = current.media,
                        players = players,
                        selectedPlayerId = selectedPlayer?.playerId,
                        onChosen = { player -> stage = waitingFor(current.media, player) }
                    )
                    is WriteStage.Waiting -> Column {
                        val what = current.payload.label ?: "this"
                        NfcMessage(
                            icon = Icons.Default.Nfc,
                            text = current.playerName
                                ?.let { "Hold a tag against the back of the phone to play $what on $it." }
                                ?: "Hold a tag against the back of the phone to play $what wherever you are."
                        )
                        VolumeChoice(volume = tagVolume, onVolumeChange = { tagVolume = it })
                        MdTextButton(
                            onClick = { stage = startingStage(choices, fixedPlayer) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SheetDefaults.HeaderHorizontalPadding)
                        ) { Text("Start over") }
                    }
                    is WriteStage.Done -> Column {
                        NfcMessage(
                            icon = if (current.result is NfcWriteResult.Written) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.ErrorOutline
                            },
                            text = resultMessage(current.result)
                        )
                        MdButton(
                            onClick = {
                                if (current.result is NfcWriteResult.Written) {
                                    onDismiss()
                                } else {
                                    stage = startingStage(choices, fixedPlayer)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SheetDefaults.HeaderHorizontalPadding)
                        ) {
                            Text(if (current.result is NfcWriteResult.Written) "Done" else "Try again")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The speaker step.
 *
 * The rows are the ones the player selector uses, down to the sound wave on whatever is
 * playing and the tick on the current selection, because this is the same question that
 * sheet asks and an answer that looks different reads as a different question. The tick
 * marks the current player without choosing it: what plays now and what a tag should start
 * later are not the same decision.
 *
 * The option that names no speaker sits at the end, under a divider, because it is the
 * exception rather than one of the speakers.
 */
/**
 * The first step when there is more than one thing the tag could start. Rows read like the
 * library's, because they are the same kind of thing: a container with a name and a type.
 */
@Composable
private fun MediaChoice(
    prompt: String,
    choices: List<NfcWriteChoice>,
    onChosen: (NfcWriteChoice) -> Unit
) {
    LazyColumn(modifier = Modifier.padding(bottom = 8.dp)) {
        item {
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = 12.dp
                )
            )
        }
        // Keyed on the action, which is the one part that cannot repeat: two rows can
        // carry the same title, as the queue's album and the current track's album do
        // when a library copy and a provider copy of one record are both on offer, and a
        // repeated key is a crash rather than a cosmetic problem.
        items(choices, key = { it.action.toString() }) { choice ->
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = { Text(choice.title) },
                supportingContent = { Text(choice.detail) },
                leadingContent = {
                    Icon(
                        imageVector = when (choice.action) {
                            is NfcTagAction.PlayMedia ->
                                if (choice.detail.startsWith("Playlist")) {
                                    Icons.AutoMirrored.Filled.QueueMusic
                                } else {
                                    Icons.Default.Album
                                }
                            NfcTagAction.TransferQueue -> Icons.AutoMirrored.Filled.CallMade
                            NfcTagAction.Resume -> Icons.Default.PlayArrow
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable { onChosen(choice) }
            )
        }
    }
}

/**
 * The sheet's first question, which depends on how much the caller already settled. One
 * thing to write and a known speaker leaves nothing to ask, so it goes straight to the tag.
 */
private fun startingStage(choices: List<NfcWriteChoice>, fixedPlayer: Player?): WriteStage {
    val only = choices.singleOrNull() ?: return WriteStage.ChoosingMedia(choices)
    return fixedPlayer?.let { waitingFor(only, it) } ?: WriteStage.ChoosingPlayer(only)
}

private fun waitingFor(media: NfcWriteChoice, player: Player?): WriteStage.Waiting =
    WriteStage.Waiting(
        NfcTagPayload(
            action = media.action,
            playerId = player?.playerId,
            label = media.tagLabel
        ),
        player?.displayName
    )

@Composable
private fun PlayerChoice(
    media: NfcWriteChoice,
    players: List<Player>,
    selectedPlayerId: String?,
    onChosen: (Player?) -> Unit
) {
    LazyColumn(modifier = Modifier.padding(bottom = 8.dp)) {
        item {
            Text(
                text = "Which speaker should ${media.tagLabel} start playing on?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = 12.dp
                )
            )
        }
        items(
            players.filter { it.available }.sortedBy { it.displayName.lowercase() },
            key = { it.playerId }
        ) { player ->
            val isPlaying = player.state == PlaybackState.PLAYING
            val iconTint = if (isPlaying || player.playerId == selectedPlayerId) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = { Text(player.displayName) },
                supportingContent = {
                    Text(
                        when (player.state) {
                            PlaybackState.PLAYING -> "Playing"
                            PlaybackState.PAUSED -> "Paused"
                            PlaybackState.IDLE -> "Idle"
                        }
                    )
                },
                leadingContent = {
                    SoundWaveIcon(isPlaying = isPlaying, waveColor = iconTint) {
                        PlayerIcon(
                            player = player,
                            modifier = Modifier.size(32.dp),
                            tint = iconTint
                        )
                    }
                },
                trailingContent = {
                    if (player.playerId == selectedPlayerId) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Currently selected",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier.clickable { onChosen(player) }
            )
        }
        // Offered only for media. A transfer or a resume names a speaker and nothing else,
        // so one written without one is refused on every tap: a tag that writes and is
        // dead is worse than an option that is not there.
        if (media.action is NfcTagAction.PlayMedia) {
            item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = { Text("Whichever is selected") },
                supportingContent = { Text("at the time of the tap") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Speaker,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable { onChosen(null) }
            )
            }
        }
    }
}

/**
 * The optional level the tag sets before it starts anything.
 *
 * Off by default, because most tags have no opinion about volume and a tag that quietly
 * changed it would be worse than one that did not offer to. Switching it on starts from
 * the middle rather than from whatever the speaker is at now: the speaker the tag names
 * may not be the one playing, and reading a level off the wrong one would be a guess
 * dressed up as a default.
 */
@Composable
private fun VolumeChoice(volume: Int?, onVolumeChange: (Int?) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = SheetDefaults.HeaderHorizontalPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Set the volume too", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = volume?.let { "The tag will set it to $it%" }
                        ?: "The tag will leave the volume alone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = volume != null,
                onCheckedChange = { on -> onVolumeChange(if (on) DEFAULT_TAG_VOLUME else null) }
            )
        }
        if (volume != null) {
            Slider(
                value = volume.toFloat(),
                onValueChange = { onVolumeChange(it.roundToInt()) },
                valueRange = NfcTagPayload.MIN_VOLUME.toFloat()..NfcTagPayload.MAX_VOLUME.toFloat()
            )
        }
    }
}

/** Where the slider starts when it is switched on. */
private const val DEFAULT_TAG_VOLUME = 50

@Composable
private fun NfcMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

private fun resultMessage(result: NfcWriteResult): String = when (result) {
    is NfcWriteResult.Written -> "Tag written. Tap it to start playing."
    is NfcWriteResult.TooSmall ->
        "This tag holds ${result.tagBytes} bytes and ${result.neededBytes} are needed. " +
            "An NTAG215 has room for it."
    NfcWriteResult.ReadOnly -> "This tag is locked and cannot be written."
    NfcWriteResult.Unsupported -> "This tag cannot hold the kind of message MassDroid writes."
    is NfcWriteResult.Failed -> "The tag moved away before it was written. Hold it still and try again."
}
