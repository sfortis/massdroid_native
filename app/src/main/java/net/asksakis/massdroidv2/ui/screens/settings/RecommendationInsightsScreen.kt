package net.asksakis.massdroidv2.ui.screens.settings

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationInsightsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val smartListeningEnabled by viewModel.smartListeningEnabled.collectAsStateWithLifecycle()
    val recommendationBusy by viewModel.recommendationBusy.collectAsStateWithLifecycle()
    val recommendationMessage by viewModel.recommendationMessage.collectAsStateWithLifecycle()
    val topArtists by viewModel.topArtists.collectAsStateWithLifecycle()
    val topTracks by viewModel.topTracks.collectAsStateWithLifecycle()
    val topAlbums by viewModel.topAlbums.collectAsStateWithLifecycle()
    val topGenres by viewModel.topGenres.collectAsStateWithLifecycle()
    val blockedArtists by viewModel.blockedArtists.collectAsStateWithLifecycle()

    var showResetConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshRecommendationData()
    }

    LaunchedEffect(recommendationMessage) {
        if (recommendationMessage != null) {
            delay(2500)
            viewModel.clearRecommendationMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Recommendation Insights") },
                navigationIcon = {
                    MdIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!smartListeningEnabled) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "Smart Listening is disabled. Enable it in Settings to collect and view recommendation scores.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Database Actions", style = MaterialTheme.typography.titleSmall)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MdOutlinedButton(
                            onClick = { viewModel.refreshRecommendationData() },
                            enabled = smartListeningEnabled && !recommendationBusy
                        ) {
                            Text("Refresh Stats")
                        }
                        MdButton(
                            onClick = { showResetConfirm = true },
                            enabled = smartListeningEnabled && !recommendationBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Reset DB")
                        }
                    }

                    recommendationMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Statistics", style = MaterialTheme.typography.titleSmall)

                    Text("Top Artists (BLL score)", style = MaterialTheme.typography.labelLarge)
                    if (topArtists.isEmpty()) {
                        Text(
                            "No data yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        topArtists.forEachIndexed { index, item ->
                            Text(
                                "${index + 1}. ${item.artistName} (${String.format("%.2f", item.score)})",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Top Genres (BLL score)", style = MaterialTheme.typography.labelLarge)
                    if (topGenres.isEmpty()) {
                        Text(
                            "No data yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        topGenres.forEachIndexed { index, item ->
                            Text(
                                "${index + 1}. ${item.genre} (${String.format("%.2f", item.score)})",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Top Tracks (play count)", style = MaterialTheme.typography.labelLarge)
                    if (topTracks.isEmpty()) {
                        Text(
                            "No data yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        topTracks.forEachIndexed { index, item ->
                            Text(
                                "${index + 1}. ${item.trackName} (${item.score.toInt()} plays)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Top Albums (play count)", style = MaterialTheme.typography.labelLarge)
                    if (topAlbums.isEmpty()) {
                        Text(
                            "No data yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        topAlbums.forEachIndexed { index, item ->
                            Text(
                                "${index + 1}. ${item.albumName} (${item.score.toInt()} plays)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Blocked Artists", style = MaterialTheme.typography.titleSmall)
                    if (blockedArtists.isEmpty()) {
                        Text(
                            "No blocked artists",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        blockedArtists.forEach { blocked ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    blocked.artistName?.ifBlank { blocked.artistUri } ?: blocked.artistUri,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                MdTextButton(
                                    onClick = {
                                        viewModel.unblockArtist(blocked.artistUri, blocked.artistName)
                                    },
                                    enabled = smartListeningEnabled && !recommendationBusy
                                ) {
                                    Text("Unblock")
                                }
                            }
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("How Scoring Works", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "These stats are scores, not just raw counts:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "- Artist/Genre use BLL score (Base-Level Learning): recent plays weigh more than old plays.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "- Track/Album lists are play-count rankings over the selected history window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "- Smart Listening feedback also applies decayed signals (like/listen increase, skip/unlike decrease).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "- Artists with persistently strong negative signals can be suppressed or manually blocked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showResetConfirm && smartListeningEnabled) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Recommendation DB?") },
            text = {
                Text(
                    "This will delete local recommendation data (play history cache, smart feedback, blocked artists). " +
                        "This action cannot be undone."
                )
            },
            confirmButton = {
                MdButton(
                    onClick = {
                        showResetConfirm = false
                        viewModel.resetRecommendationDatabase()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Yes, Reset")
                }
            },
            dismissButton = {
                MdOutlinedButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
