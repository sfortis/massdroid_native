package net.asksakis.massdroidv2.ui.screens.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.ui.components.MediaItemRow
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
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val items by viewModel.queueItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentQueueItemId by viewModel.currentQueueItemId.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    var actionSheetItem by remember { mutableStateOf<QueueActionItem?>(null) }
    var showQueueMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val displayItems = remember { mutableStateListOf<QueueItem>() }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var draggingQueueItemId by remember { mutableStateOf<String?>(null) }
    val lazyListState = rememberLazyListState()

    LaunchedEffect(items, draggingQueueItemId) {
        if (draggingQueueItemId == null) {
            displayItems.clear()
            displayItems.addAll(items)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showQueueMenu = true }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Transfer queue")
                    }
                    IconButton(onClick = { viewModel.clearQueue() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear queue")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (isLoading) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                itemsIndexed(displayItems, key = { _, item -> item.queueItemId }) { index, item ->
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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

    actionSheetItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionSheetItem = null }) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                            style = MaterialTheme.typography.titleMedium,
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text("Play Now") },
                    leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    modifier = Modifier.clickable {
                        viewModel.playIndex(item.index)
                        actionSheetItem = null
                    }
                )

                if (item.index > 1) {
                    ListItem(
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
                        headlineContent = { Text("Move Down") },
                        leadingContent = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                        modifier = Modifier.clickable {
                            viewModel.moveItemDown(item.queueItemId)
                            actionSheetItem = null
                        }
                    )
                }

                ListItem(
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

    if (showQueueMenu) {
        val otherPlayers = players.filter { it.available && it.playerId != viewModel.selectedPlayerId }
            .sortedBy { it.displayName.lowercase() }

        ModalBottomSheet(onDismissRequest = { showQueueMenu = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "Transfer queue to:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                otherPlayers.forEach { target ->
                    ListItem(
                        headlineContent = { Text(target.displayName) },
                        modifier = Modifier.clickable {
                            viewModel.transferQueue(target.playerId)
                            showQueueMenu = false
                        }
                    )
                }
            }
        }
    }
}

private fun <T> MutableList<T>.move(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return
    val item = removeAt(fromIndex)
    add(toIndex, item)
}
