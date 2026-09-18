package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.ui.components.SheetDefaults

/**
 * Long-press / "more" sheet on the Now Playing screen. Surfaces:
 *
 *  - Player settings (rename, crossfade, volume normalization, sendspin format)
 *  - Transfer queue to another player (only when other players exist)
 *  - Start song radio (only when the queue has a current track)
 *  - Sleep timer (with live remaining label when active)
 *  - Block / unblock the current artist
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerOptionsSheet(
    artistBlocked: Boolean,
    canToggleArtistBlock: Boolean,
    hasOtherPlayers: Boolean,
    sleepTimerActive: Boolean,
    sleepTimerLabel: String,
    chapterCount: Int,
    onShowChapters: () -> Unit,
    onDismiss: () -> Unit,
    onPlayerSettings: () -> Unit,
    onTransferQueue: () -> Unit,
    onSleepTimer: () -> Unit,
    onStartSongRadio: (() -> Unit)?,
    onClick: () -> Unit
) {
    val sheetState = SheetDefaults.sheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Column {
                SheetDefaults.HeaderTitle(
                    text = "Player Options",
                    modifier = Modifier.padding(
                        horizontal = SheetDefaults.HeaderHorizontalPadding,
                        vertical = SheetDefaults.HeaderVerticalPadding
                    )
                )
                HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            }
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = { Text("Player Settings") },
                supportingContent = {
                    Text(
                        "Rename player, crossfade and volume normalization",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                modifier = Modifier.clickable(onClick = onPlayerSettings)
            )
            if (chapterCount > 0) {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Chapters") },
                    supportingContent = {
                        Text(
                            "Jump to a chapter ($chapterCount)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable {
                        onShowChapters()
                        onDismiss()
                    }
                )
            }
            if (hasOtherPlayers) {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Transfer Queue") },
                    supportingContent = {
                        Text(
                            "Move playback to another player",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable(onClick = onTransferQueue)
                )
            }
            if (onStartSongRadio != null) {
                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Start Song Radio") },
                    supportingContent = {
                        Text(
                            "Play similar tracks based on current song",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable {
                        onStartSongRadio()
                        onDismiss()
                    }
                )
            }
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = {
                    Text(if (sleepTimerActive) "Sleep Timer ($sleepTimerLabel)" else "Sleep Timer")
                },
                supportingContent = {
                    Text(
                        if (sleepTimerActive) "Tap to change or cancel" else "Stop playback after a set time",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = null,
                        tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                    )
                },
                modifier = Modifier.clickable {
                    onSleepTimer()
                    onDismiss()
                }
            )
            ListItem(
                colors = SheetDefaults.listItemColors(),
                headlineContent = {
                    Text(
                        if (artistBlocked) "Allow Artist Again" else "Block This Artist",
                        color = if (artistBlocked) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                },
                supportingContent = {
                    Text(
                        if (artistBlocked) "Artist can appear again in queue and recommendations"
                        else "Hide this artist from queue and smart listening",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = if (artistBlocked) Icons.Default.PersonAdd else Icons.Default.Block,
                        contentDescription = null,
                        tint = if (artistBlocked) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                },
                modifier = Modifier.clickable(enabled = canToggleArtistBlock, onClick = onClick)
            )
        }
    }
}
