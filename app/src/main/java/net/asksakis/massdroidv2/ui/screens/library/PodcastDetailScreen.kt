package net.asksakis.massdroidv2.ui.screens.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.Podcast
import net.asksakis.massdroidv2.domain.model.PodcastEpisode
import net.asksakis.massdroidv2.ui.components.EqualizerBars
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MediaActionSheet
import net.asksakis.massdroidv2.ui.components.fadingEdges
import net.asksakis.massdroidv2.ui.util.formatPlaybackTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    onBack: () -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel()
) {
    val podcast by viewModel.podcast.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val podcastName by viewModel.podcastName.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val currentTrackUri by viewModel.currentTrackUri.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val elapsedTime by viewModel.elapsedTime.collectAsStateWithLifecycle()

    var actionSheetEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = podcastName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    MdIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    MdIconButton(onClick = { viewModel.togglePodcastFavorite() }) {
                        Icon(
                            if (podcast?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle favorite",
                            tint = if (podcast?.favorite == true) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                expandedHeight = 48.dp
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().fadingEdges(),
                contentPadding = PaddingValues(bottom = LocalMiniPlayerPadding.current + 24.dp)
            ) {
                item(key = "podcast-header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PodcastHeader(podcast = podcast, podcastName = podcastName, episodeCount = episodes.size)
                    }
                }
                items(episodes, key = { it.uri }) { episode ->
                    PodcastEpisodeItem(
                        episode = episode,
                        isCurrent = episode.uri == currentTrackUri,
                        isPlaying = isPlaying,
                        // Only the current row gets the live position (others stay 0,
                        // so they don't recompose every tick).
                        liveElapsedSeconds = if (episode.uri == currentTrackUri) elapsedTime else 0.0,
                        onPlay = { viewModel.playEpisode(episode) },
                        onAction = { actionSheetEpisode = it }
                    )
                }
            }
        }
    }

    actionSheetEpisode?.let { target ->
        val players by viewModel.players.collectAsStateWithLifecycle()
        val inProgress = !target.fullyPlayed && target.resumePositionMs > 0
        MediaActionSheet(
            title = target.name,
            subtitle = target.podcastName ?: podcastName,
            imageUrl = target.imageUrl ?: podcast?.imageUrl,
            players = players,
            selectedPlayerId = players.firstOrNull()?.playerId,
            favorite = target.favorite,
            onToggleFavorite = {
                viewModel.toggleEpisodeFavorite(target.uri, target.itemId, target.favorite)
            },
            onPlayNow = { viewModel.playUri(target.uri) },
            onPlayOnPlayer = { player -> viewModel.playOnPlayer(target.uri, player.playerId) },
            onPlayNext = { viewModel.enqueueNext(target.uri) },
            // Offer "Mark as played" while not fully played; "Mark as unplayed"
            // once fully played or partway through.
            onMarkPlayed = if (!target.fullyPlayed) {
                { viewModel.setEpisodePlayed(target, true) }
            } else {
                null
            },
            onMarkUnplayed = if (target.fullyPlayed || inProgress) {
                { viewModel.setEpisodePlayed(target, false) }
            } else {
                null
            },
            onAddToQueue = { viewModel.enqueue(target.uri) },
            onDismiss = { actionSheetEpisode = null }
        )
    }
}

@Composable
private fun PodcastHeader(
    podcast: Podcast?,
    podcastName: String,
    episodeCount: Int
) {
    AsyncImage(
        model = podcast?.imageUrl,
        contentDescription = "Podcast art",
        modifier = Modifier.size(200.dp).clip(MaterialTheme.shapes.medium),
        contentScale = ContentScale.Crop
    )
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = podcastName,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    podcast?.publisher?.takeIf { it.isNotBlank() }?.let { publisher ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = publisher,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val count = podcast?.totalEpisodes ?: episodeCount
    if (count > 0) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$count episodes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val description = podcast?.description
    if (!description.isNullOrBlank()) {
        var expanded by remember { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.animateContentSize()
        )
        Text(
            text = if (expanded) "Show less" else "Show more",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { expanded = !expanded }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastEpisodeItem(
    episode: PodcastEpisode,
    isCurrent: Boolean,
    isPlaying: Boolean,
    liveElapsedSeconds: Double,
    onPlay: () -> Unit,
    onAction: (PodcastEpisode) -> Unit
) {
    // For the currently-playing episode use the live position so its progress
    // advances in-place; others use the last server-known resume position.
    val resumeSeconds = if (isCurrent && liveElapsedSeconds > 0) {
        liveElapsedSeconds
    } else {
        episode.resumePositionMs / 1000.0
    }
    val inProgress = !episode.fullyPlayed && resumeSeconds > 0 && episode.duration > 0
    ListItem(
        headlineContent = {
            Text(
                episode.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
        },
        supportingContent = {
            Column {
                Text(
                    buildString {
                        if (episode.duration > 0) append(formatPlaybackTime(episode.duration))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    episode.fullyPlayed -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Played",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    inProgress -> {
                        val fraction = (resumeSeconds / episode.duration).coerceIn(0.0, 1.0).toFloat()
                        val remaining = (episode.duration - resumeSeconds).coerceAtLeast(0.0)
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                        )
                        Text(
                            "${formatPlaybackTime(remaining)} left",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        },
        leadingContent = {
            when {
                isCurrent && isPlaying -> EqualizerBars(modifier = Modifier.size(24.dp))
                inProgress -> {
                    // Partially-played ring (like the MA web UI): a determinate
                    // circle at the played fraction with a small play glyph.
                    val fraction = (resumeSeconds / episode.duration).coerceIn(0.0, 1.0).toFloat()
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
                        CircularProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 2.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Resume episode",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                else -> Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = "Play episode",
                    tint = if (isCurrent) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            MdIconButton(
                onClick = { onAction(episode) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .combinedClickable(
                onClick = onPlay,
                onLongClick = { onAction(episode) }
            )
    )
}
