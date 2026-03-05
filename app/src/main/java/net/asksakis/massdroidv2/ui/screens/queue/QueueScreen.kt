package net.asksakis.massdroidv2.ui.screens.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.ui.components.EqualizerBars
import net.asksakis.massdroidv2.ui.components.MediaItemRow

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

    LaunchedEffect(Unit) {
        viewModel.error.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

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
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                itemsIndexed(items, key = { _, item -> item.queueItemId }) { index, item ->
                    val isCurrent = item.queueItemId == currentQueueItemId
                    MediaItemRow(
                        title = item.track?.name ?: item.name,
                        subtitle = item.track?.artistNames ?: "",
                        imageUrl = item.track?.imageUrl ?: item.imageUrl,
                        onClick = { viewModel.playIndex(index) },
                        titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        showEqualizer = isCurrent && isPlaying,
                        onMoreClick = {
                            actionSheetItem = QueueActionItem(
                                queueItemId = item.queueItemId,
                                name = item.track?.name ?: item.name,
                                artistNames = item.track?.artistNames ?: "",
                                imageUrl = item.track?.imageUrl ?: item.imageUrl,
                                index = index
                            )
                        }
                    )
                }
            }
        }
    }

    actionSheetItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { actionSheetItem = null }
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                // Header
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

                // Play Now
                ListItem(
                    headlineContent = { Text("Play Now") },
                    leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    modifier = Modifier.clickable {
                        viewModel.playIndex(item.index)
                        actionSheetItem = null
                    }
                )

                // Play Next (only for items not already at position 1)
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

                // Move Up (not for first item)
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

                // Move Down (not for last item)
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

                // Remove
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
