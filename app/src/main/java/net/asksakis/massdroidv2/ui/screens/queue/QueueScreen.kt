package net.asksakis.massdroidv2.ui.screens.queue

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.model.PlayerType
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.ui.components.MediaItemRow
import net.asksakis.massdroidv2.ui.components.PlayerNameWithBadge
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.components.SoundWaveIcon
import net.asksakis.massdroidv2.ui.screens.home.PlayerIcon
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private data class QueueActionItem(
    val queueItemId: String,
    val name: String,
    val artistNames: String,
    val imageUrl: String?,
    val index: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    onDismiss: () -> Unit,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val items by viewModel.queueItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentQueueItemId by viewModel.currentQueueItemId.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    val sendspinClientId by viewModel.sendspinClientId.collectAsStateWithLifecycle(initialValue = null)
    var actionSheetItem by remember { mutableStateOf<QueueActionItem?>(null) }
    var showQueueMenu by remember { mutableStateOf(false) }
    var showSaveQueueDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val displayItems = remember { mutableStateListOf<QueueItem>() }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var draggingQueueItemId by remember { mutableStateOf<String?>(null) }
    val lazyListState = rememberLazyListState()
    var hasScrolledInitially by remember { mutableStateOf(false) }
    val effectiveItems = if (draggingQueueItemId != null) displayItems else items

    LaunchedEffect(items) {
        if (!hasScrolledInitially && items.isNotEmpty()) {
            val idx = items.indexOfFirst { it.queueItemId == currentQueueItemId }
            if (idx >= 0) {
                lazyListState.scrollToItem(index = idx, scrollOffset = -200)
                hasScrolledInitially = true
            }
        }
    }

    LaunchedEffect(currentQueueItemId) {
        if (hasScrolledInitially) {
            val idx = items.indexOfFirst { it.queueItemId == currentQueueItemId }
            if (idx >= 0) {
                val visible = lazyListState.layoutInfo.visibleItemsInfo.any { it.index == idx }
                if (!visible) {
                    lazyListState.animateScrollToItem(index = idx, scrollOffset = -200)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.error.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            displayItems.move(from.index, to.index)
        }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MdIconButton(onClick = onDismiss) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Queue", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${items.size} tracks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    MdIconButton(onClick = {
                        viewModel.getPlaylists()
                        showSaveQueueDialog = true
                    }) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = "Save queue to playlist")
                    }
                    MdIconButton(onClick = { showQueueMenu = true }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Transfer queue")
                    }
                    MdIconButton(onClick = { viewModel.clearQueue() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear queue")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            // Snackbar host
            SnackbarHost(snackbarHostState)

            // Queue list
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    val currentIndex = effectiveItems.indexOfFirst { it.queueItemId == currentQueueItemId }
                    itemsIndexed(effectiveItems, key = { _, item -> item.queueItemId }) { index, item ->
                        val isPlayed = currentIndex >= 0 && index < currentIndex
                        ReorderableItem(
                            state = reorderableLazyListState,
                            key = item.queueItemId
                        ) { isDragging ->
                            Surface(
                                tonalElevation = if (isDragging) 8.dp else 0.dp,
                                shadowElevation = if (isDragging) 12.dp else 0.dp,
                                color = if (isDragging) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .then(if (isPlayed) Modifier.alpha(0.5f) else Modifier)
                            ) {
                                MediaItemRow(
                                    title = item.track?.name ?: item.name,
                                    subtitle = item.track?.artistNames ?: "",
                                    imageUrl = item.track?.imageUrl ?: item.imageUrl,
                                    onClick = { viewModel.playIndex(index) },
                                    titleColor = if (item.queueItemId == currentQueueItemId) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Unspecified
                                    },
                                    showEqualizer = item.queueItemId == currentQueueItemId && isPlaying,
                                    onMoreClick = {
                                        actionSheetItem = QueueActionItem(
                                            queueItemId = item.queueItemId,
                                            name = item.track?.name ?: item.name,
                                            artistNames = item.track?.artistNames ?: "",
                                            imageUrl = item.track?.imageUrl ?: item.imageUrl,
                                            index = index
                                        )
                                    },
                                    dragHandle = {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = "Reorder",
                                            tint = if (isDragging) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .longPressDraggableHandle(
                                                    onDragStarted = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        displayItems.clear()
                                                        displayItems.addAll(items)
                                                        dragStartIndex = index
                                                        draggingQueueItemId = item.queueItemId
                                                    },
                                                    onDragStopped = {
                                                        val queueItemId = draggingQueueItemId
                                                        val fromIndex = dragStartIndex
                                                        val toIndex = queueItemId?.let { id ->
                                                            displayItems.indexOfFirst { it.queueItemId == id }
                                                        } ?: -1

                                                        draggingQueueItemId = null
                                                        dragStartIndex = -1

                                                        if (queueItemId != null &&
                                                            fromIndex >= 0 &&
                                                            toIndex >= 0 &&
                                                            fromIndex != toIndex
                                                        ) {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            viewModel.moveItem(queueItemId, fromIndex, toIndex)
                                                        }
                                                    }
                                                )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Track action sheet
    actionSheetItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { actionSheetItem = null },
            containerColor = SheetDefaults.containerColor()
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = SheetDefaults.HeaderHorizontalPadding,
                        vertical = SheetDefaults.HeaderVerticalPadding
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.artistNames.isNotBlank()) {
                            Text(
                                text = item.artistNames,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Play Now") },
                    leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    modifier = Modifier.clickable {
                        viewModel.playIndex(item.index)
                        actionSheetItem = null
                    }
                )

                if (item.index > 1) {
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text("Play Next") },
                        leadingContent = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                        modifier = Modifier.clickable {
                            viewModel.playNext(item.queueItemId, item.index)
                            actionSheetItem = null
                        }
                    )
                }

                if (item.index > 0) {
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text("Move Up") },
                        leadingContent = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                        modifier = Modifier.clickable {
                            viewModel.moveItemUp(item.queueItemId)
                            actionSheetItem = null
                        }
                    )
                }

                if (item.index < items.size - 1) {
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = { Text("Move Down") },
                        leadingContent = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                        modifier = Modifier.clickable {
                            viewModel.moveItemDown(item.queueItemId)
                            actionSheetItem = null
                        }
                    )
                }

                ListItem(
                    colors = SheetDefaults.listItemColors(),
                    headlineContent = { Text("Remove") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable {
                        viewModel.removeItem(item.queueItemId)
                        actionSheetItem = null
                    }
                )
            }
        }
    }

    // Transfer queue sheet
    if (showQueueMenu) {
        val otherPlayers = players.filter { it.available && it.playerId != viewModel.selectedPlayerId }
            .sortedBy { it.displayName.lowercase() }

        ModalBottomSheet(
            onDismissRequest = { showQueueMenu = false },
            containerColor = SheetDefaults.containerColor()
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Column {
                    SheetDefaults.HeaderTitle(
                        text = "Transfer queue to",
                        modifier = Modifier.padding(
                            horizontal = SheetDefaults.HeaderHorizontalPadding,
                            vertical = SheetDefaults.HeaderVerticalPadding
                        )
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                }
                otherPlayers.forEach { target ->
                    val isPlaying = target.state == PlaybackState.PLAYING
                    val iconTint = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ListItem(
                        colors = SheetDefaults.listItemColors(),
                        headlineContent = {
                            PlayerNameWithBadge(
                                name = target.displayName,
                                isLocalPlayer = sendspinClientId != null && target.playerId == sendspinClientId
                            )
                        },
                        leadingContent = {
                            SoundWaveIcon(
                                isPlaying = isPlaying,
                                waveColor = iconTint
                            ) {
                                PlayerIcon(
                                    player = target,
                                    modifier = Modifier.size(32.dp),
                                    tint = iconTint
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.transferQueue(target.playerId)
                            showQueueMenu = false
                        }
                    )
                }
            }
        }
    }

    // Save queue to playlist dialog
    if (showSaveQueueDialog) {
        val playlists by viewModel.playlists.collectAsStateWithLifecycle()

        net.asksakis.massdroidv2.ui.components.AddToPlaylistDialog(
            playlists = playlists,
            isLoading = false,
            addingToPlaylistId = null,
            onDismiss = { showSaveQueueDialog = false },
            onRetry = { viewModel.getPlaylists() },
            onPlaylistClick = { playlist ->
                viewModel.saveQueueToPlaylist(playlist)
                showSaveQueueDialog = false
            },
            onCreatePlaylist = { name ->
                viewModel.saveQueueToNewPlaylist(name)
                showSaveQueueDialog = false
            },
            suggestedName = viewModel.suggestedPlaylistName()
        )
    }
}

private fun <T> MutableList<T>.move(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return
    val item = removeAt(fromIndex)
    add(toIndex, item)
}
