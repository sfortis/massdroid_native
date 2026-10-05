package net.asksakis.massdroidv2.ui.screens.settings

import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdTextButton
import net.asksakis.massdroidv2.ui.components.SETTINGS_ROW_INSET
import net.asksakis.massdroidv2.ui.components.SETTINGS_SCREEN_BOTTOM_PADDING
import net.asksakis.massdroidv2.ui.components.SETTINGS_TEXT_INSET
import net.asksakis.massdroidv2.ui.components.SettingsConfirmDialog
import net.asksakis.massdroidv2.ui.components.SettingsRow
import net.asksakis.massdroidv2.ui.components.SettingsSectionDivider
import net.asksakis.massdroidv2.ui.components.SettingsSectionHeader
import net.asksakis.massdroidv2.ui.components.SettingsTone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.domain.repository.AlbumScore
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.BlockedArtistInfo
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.domain.repository.TrackScore

/**
 * What the recommendation engine has learned, and the actions that clear it.
 *
 * Laid out as plain rows in sections like every other settings surface: the database
 * actions, one section per ranked list, the blocked artists, and a row that explains how
 * the scores are made. An entry in a ranked list is a link: an artist or an album opens
 * its page through [onNavigateToArtist] and [onNavigateToAlbum], a track plays and a genre
 * starts its Genre Radio through the ViewModel.
 */
@Composable
fun RecommendationInsightsScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (itemId: String, provider: String, name: String) -> Unit,
    onNavigateToAlbum: (itemId: String, provider: String, name: String) -> Unit,
    /** Called once a genre's radio has been started; its progress and outcome show on Discover. */
    onNavigateToDiscover: () -> Unit,
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

    val actionsEnabled = smartListeningEnabled && !recommendationBusy

    LaunchedEffect(Unit) {
        viewModel.refreshRecommendationData()
    }

    // The outcome of an action shows as a snackbar, which floats over the list instead of
    // inserting a line that pushes the rows down and pulls them back up when it goes.
    // The message is cleared once the snackbar is gone; a newer message cancels the
    // effect, which dismisses the old snackbar and shows the new one.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(recommendationMessage) {
        val message = recommendationMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearRecommendationMessage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { InsightsTopBar(onBack) }
    ) { paddingValues ->
        // No outer padding: the rows carry ListItem's own inset. Headers and the ranked
        // entries line up with the rows' icons through SETTINGS_ROW_INSET, and notes with
        // their text through SETTINGS_TEXT_INSET.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = SETTINGS_SCREEN_BOTTOM_PADDING)
        ) {
            if (!smartListeningEnabled) SmartListeningOffSection()
            DatabaseSection(
                smartListeningEnabled = smartListeningEnabled,
                enabled = actionsEnabled,
                onRefresh = { viewModel.refreshRecommendationData() },
                onReset = { viewModel.resetRecommendationDatabase() }
            )
            SettingsSectionDivider()
            RankedListsSections(
                rankings = InsightsScores(topArtists, topGenres, topTracks, topAlbums),
                onOpen = { target ->
                    when (target) {
                        is InsightsTarget.Artist -> onNavigateToArtist(target.itemId, target.provider, target.name)
                        is InsightsTarget.Album -> onNavigateToAlbum(target.itemId, target.provider, target.name)
                        is InsightsTarget.Track -> viewModel.playInsightsTrack(target.uri)
                        is InsightsTarget.Genre -> {
                            viewModel.startInsightsGenreRadio(target.genre)
                            onNavigateToDiscover()
                        }
                    }
                }
            )
            SettingsSectionDivider()
            BlockedArtistsSection(
                blockedArtists = blockedArtists,
                smartListeningEnabled = smartListeningEnabled,
                enabled = actionsEnabled,
                onUnblock = { viewModel.unblockArtist(it.artistUri, it.artistName) },
                onClearAll = { viewModel.resetBlockedArtists() }
            )
            SettingsSectionDivider()
            ScoringHelpRow()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsightsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text("Recommendation insights") },
        navigationIcon = {
            MdIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

/** Why the lists are empty and the actions disabled, as a section like the others. */
@Composable
private fun SmartListeningOffSection() {
    SettingsSectionHeader("Smart Listening")
    SettingsRow(
        title = "Smart Listening is off",
        icon = Icons.Default.Psychology,
        supporting = "Turn it on in Settings to collect and view scores."
    )
    SettingsSectionDivider()
}

/**
 * Refresh and reset. [onReset] runs only after the confirmation dialog this section owns.
 * Their outcome, like that of every action on this screen, shows in the screen's snackbar.
 */
@Composable
private fun DatabaseSection(
    smartListeningEnabled: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onReset: () -> Unit
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SettingsSectionHeader("Database")
    SettingsRow(
        title = "Refresh stats",
        icon = Icons.Default.Refresh,
        supporting = "Reload the lists below.",
        onClick = onRefresh,
        enabled = enabled
    )
    SettingsRow(
        title = "Reset database",
        icon = Icons.Default.RestartAlt,
        supporting = "Delete play history, track scores and feedback.",
        onClick = { confirming = true },
        enabled = enabled,
        tone = SettingsTone.DESTRUCTIVE
    )
    if (confirming && smartListeningEnabled) {
        ResetDatabaseDialog(
            onConfirm = {
                confirming = false
                onReset()
            },
            onDismiss = { confirming = false }
        )
    }
}

/**
 * The four ranked lists, each in its own section. The scores are mapped to entries here,
 * once per change, so the URI parsing does not run on every recomposition.
 */
@Composable
private fun RankedListsSections(rankings: InsightsScores, onOpen: (InsightsTarget) -> Unit) {
    val artists = remember(rankings.artists) { rankings.artists.map { it.toRankedEntry() } }
    val genres = remember(rankings.genres) { rankings.genres.map { it.toRankedEntry() } }
    val tracks = remember(rankings.tracks) { rankings.tracks.map { it.toRankedEntry() } }
    val albums = remember(rankings.albums) { rankings.albums.map { it.toRankedEntry() } }
    RankedListSection("Top artists", caption = "By recent listening", entries = artists, onOpen = onOpen)
    SettingsSectionDivider()
    RankedListSection("Top genres", caption = "By recent listening", entries = genres, onOpen = onOpen)
    SettingsSectionDivider()
    RankedListSection("Top tracks", caption = "By play count", entries = tracks, onOpen = onOpen)
    SettingsSectionDivider()
    RankedListSection("Top albums", caption = "By play count", entries = albums, onOpen = onOpen)
}

/**
 * One ranked list: the first [RANKED_PREVIEW_COUNT] entries, and a row that unfolds the
 * rest in place. Four full lists one above the other made the screen a long scroll of
 * names, while the top few are what a listener looks at.
 */
@Composable
private fun RankedListSection(
    title: String,
    caption: String,
    entries: List<RankedEntry>,
    onOpen: (InsightsTarget) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsSectionHeader(title, caption = caption)
    if (entries.isEmpty()) {
        InsightsNote("No data yet")
        return
    }
    entries.take(RANKED_PREVIEW_COUNT).forEachIndexed { index, entry ->
        RankedEntryRow(rank = index + 1, entry = entry, onOpen = onOpen)
    }
    if (entries.size <= RANKED_PREVIEW_COUNT) return
    AnimatedVisibility(visible = expanded) {
        Column {
            entries.drop(RANKED_PREVIEW_COUNT).forEachIndexed { index, entry ->
                RankedEntryRow(rank = RANKED_PREVIEW_COUNT + index + 1, entry = entry, onOpen = onOpen)
            }
        }
    }
    // The same expand arrow as SettingsExpandableRow, so it reads as every other row that
    // unfolds in place. The text already names the action, so the arrow has no description.
    CompactRow(onClick = { expanded = !expanded }) {
        Text(
            if (expanded) "Show less" else "Show all",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One entry of a ranked list on a single line: the rank and the name, the score, and a
 * chevron that says the entry opens something. An entry with no [RankedEntry.target] is
 * shown but cannot be tapped, so it has no chevron; it keeps the chevron's space so its
 * score stays in line with the scores above and below it.
 */
@Composable
private fun RankedEntryRow(rank: Int, entry: RankedEntry, onOpen: (InsightsTarget) -> Unit) {
    val target = entry.target
    CompactRow(
        onClick = target?.let { { onOpen(it) } },
        onClickLabel = target?.actionLabel
    ) {
        Text(
            "$rank. ${entry.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            entry.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp)
        )
        if (target != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.width(CHEVRON_SIZE))
        }
    }
}

/**
 * A single-line row, shorter than a settings row but never below the 48dp touch minimum,
 * starting at [SETTINGS_ROW_INSET]: a ranked entry's number stands where a row's icon
 * would, so it carries no icon of its own. Not a
 * [SettingsRow], because its 56dp ListItem is taller than a one-line name and score needs.
 */
@Composable
private fun CompactRow(
    onClick: (() -> Unit)?,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .heightIn(min = COMPACT_ROW_MIN_HEIGHT)
            .padding(horizontal = SETTINGS_ROW_INSET),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** The blocked artists, each with its own unblock. [onClearAll] runs only after confirmation. */
@Composable
private fun BlockedArtistsSection(
    blockedArtists: List<BlockedArtistInfo>,
    smartListeningEnabled: Boolean,
    enabled: Boolean,
    onUnblock: (BlockedArtistInfo) -> Unit,
    onClearAll: () -> Unit
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (confirming && smartListeningEnabled) {
        ClearBlockedArtistsDialog(
            onConfirm = {
                confirming = false
                onClearAll()
            },
            onDismiss = { confirming = false }
        )
    }
    SettingsSectionHeader("Blocked artists")
    if (blockedArtists.isEmpty()) {
        InsightsNote("No blocked artists")
        return
    }
    blockedArtists.forEach { blocked ->
        SettingsRow(
            title = blocked.artistName?.ifBlank { blocked.artistUri } ?: blocked.artistUri,
            icon = Icons.Default.Block,
            trailing = {
                MdTextButton(onClick = { onUnblock(blocked) }, enabled = enabled) {
                    Text("Unblock")
                }
            }
        )
    }
    // Clearing the whole list is its own action, kept here next to the list rather than
    // with the database actions above: a block is something the listener stated, not
    // something the engine learned, so it does not belong to "reset the stats".
    SettingsRow(
        title = "Unblock all",
        icon = Icons.Default.LockOpen,
        supporting = "Every blocked artist becomes eligible again.",
        onClick = { confirming = true },
        enabled = enabled,
        // Unblocking loses no data (history and scores stay), so it is not drawn as a
        // destructive action; only Reset above is.
        tone = SettingsTone.NORMAL
    )
}

/** A line of text that is not a row, such as an empty list, aligned with the rows' text. */
@Composable
private fun InsightsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SETTINGS_TEXT_INSET, end = SETTINGS_ROW_INSET, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun ResetDatabaseDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SettingsConfirmDialog(
        title = "Reset the recommendation data?",
        text = "This deletes your play history, track scores and feedback, and keeps your blocked artists.",
        confirmLabel = "Reset",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun ClearBlockedArtistsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SettingsConfirmDialog(
        title = "Unblock all artists?",
        text = "Every artist you blocked can be recommended again, and your history and scores are kept.",
        confirmLabel = "Unblock all",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        // The confirm button matches the row: nothing is lost, so it is not in the error colour.
        confirmTone = SettingsTone.NORMAL
    )
}

/** One row that opens [ScoringHelpDialog]. */
@Composable
private fun ScoringHelpRow() {
    var showing by rememberSaveable { mutableStateOf(false) }
    SettingsRow(
        title = "How scoring works",
        icon = Icons.AutoMirrored.Filled.HelpOutline,
        onClick = { showing = true }
    )
    if (showing) ScoringHelpDialog(onDismiss = { showing = false })
}

/**
 * How the scores are made, behind its row so the explanation does not sit on the screen.
 * It only informs, so it has a single Close button and no confirm/cancel pair.
 */
@Composable
private fun ScoringHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How scoring works") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SCORING_HELP_PARAGRAPHS.forEach { Text(it) }
            }
        },
        confirmButton = {
            MdTextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private val SCORING_HELP_PARAGRAPHS = listOf(
    "Artists and genres: recent plays count more than old ones, and a play counts more the longer you listened. " +
        "Last 90 days.",
    "Tracks and albums: how many times you played them in the last 90 days.",
    "Likes and full listens raise a score. Skips, unlikes and \"Not for me\" lower it, and the effect fades over time.",
    "An artist with a strongly negative score is left out of recommendations. You can also block one yourself."
)

/** What tapping a ranked entry does. */
private sealed interface InsightsTarget {
    /** The screen reader's name for the action, as in "Double-tap to open artist". */
    val actionLabel: String

    data class Artist(val itemId: String, val provider: String, val name: String) : InsightsTarget {
        override val actionLabel get() = "Open artist"
    }

    data class Album(val itemId: String, val provider: String, val name: String) : InsightsTarget {
        override val actionLabel get() = "Open album"
    }

    data class Track(val uri: String) : InsightsTarget {
        override val actionLabel get() = "Play"
    }

    data class Genre(val genre: String) : InsightsTarget {
        override val actionLabel get() = "Start Genre Radio"
    }
}

/** One line of a ranked list: the name, the score as shown, and what a tap does. */
private data class RankedEntry(val name: String, val value: String, val target: InsightsTarget?)

/** The four ranked lists as the ViewModel holds them. */
private data class InsightsScores(
    val artists: List<ArtistScore>,
    val genres: List<GenreScore>,
    val tracks: List<TrackScore>,
    val albums: List<AlbumScore>
)

private fun ArtistScore.toRankedEntry() = RankedEntry(
    name = artistName,
    value = String.format("%.2f", score),
    target = parseMediaRef(artistUri, "artist")?.let { (provider, itemId) ->
        InsightsTarget.Artist(itemId, provider, artistName)
    }
)

private fun GenreScore.toRankedEntry() = RankedEntry(
    name = genre,
    value = String.format("%.2f", score),
    target = genre.takeIf { it.isNotBlank() }?.let { InsightsTarget.Genre(it) }
)

private fun TrackScore.toRankedEntry() = RankedEntry(
    name = trackName,
    value = "${score.toInt()} plays",
    target = parseMediaRef(trackUri, "track")?.let { InsightsTarget.Track(trackUri) }
)

private fun AlbumScore.toRankedEntry() = RankedEntry(
    name = albumName,
    value = "${score.toInt()} plays",
    target = parseMediaRef(albumUri, "album")?.let { (provider, itemId) ->
        InsightsTarget.Album(itemId, provider, albumName)
    }
)

/**
 * The provider and item id of a stored `provider://type/itemId` URI, or null when the URI
 * has another shape or names another media type. The item id is everything after the
 * type, not the last segment, because a filesystem provider's item id is a path that can
 * hold slashes of its own.
 */
private fun parseMediaRef(uri: String, type: String): Pair<String, String>? {
    val provider = uri.substringBefore("://", missingDelimiterValue = "")
    val itemId = uri.substringAfter("://", missingDelimiterValue = "")
        .takeIf { it.startsWith("$type/") }
        ?.removePrefix("$type/")
    return if (provider.isNotBlank() && !itemId.isNullOrBlank()) provider to itemId else null
}

/** How many entries a ranked list shows before "Show all". */
private const val RANKED_PREVIEW_COUNT = 5

/** Material's minimum touch target, the height of a ranked entry. */
private val COMPACT_ROW_MIN_HEIGHT = 48.dp

/** The default icon size, kept free on an entry that has no chevron. */
private val CHEVRON_SIZE = 24.dp
