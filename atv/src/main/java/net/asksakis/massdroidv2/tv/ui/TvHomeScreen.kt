package net.asksakis.massdroidv2.tv.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import net.asksakis.massdroidv2.domain.discover.MixLauncher
import net.asksakis.massdroidv2.presentation.discover.genrePalette
import net.asksakis.massdroidv2.domain.model.GenreItem
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Composable
fun TvHomeScreen(
    onOpenSettings: () -> Unit,
    onOpenArtist: (itemId: String, provider: String) -> Unit,
    onOpenBrowse: () -> Unit,
    viewModel: TvHomeViewModel = hiltViewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val mixBuilding by viewModel.mixBuilding.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.messageShown()
    }

    // Back-stack focus restoration: remember the last focused card key (survives the
    // detail screen via rememberSaveable) and return focus there when home recomposes,
    // so Back lands you where you were instead of jumping to a player. The row/scroll
    // positions restore on their own (LazyListState/ScrollState are saveable).
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val restoreRequester = remember { FocusRequester() }
    // The mini player asks us (via TvFocusMemory) to put the cursor back on the card it
    // stole it from; the same key/requester pair already serves back-stack restoration.
    val focusMemory = LocalTvFocusMemory.current
    DisposableEffect(focusMemory) {
        val hook: () -> Boolean = {
            lastFocusedKey != null && runCatching { restoreRequester.requestFocus() }.isSuccess
        }
        focusMemory.restoreToLastFocused = hook
        // Only clear our own hook: on navigation the NEW screen registers before the old
        // one disposes, and an unconditional null here would wipe the new screen's hook.
        onDispose {
            if (focusMemory.restoreToLastFocused === hook) focusMemory.restoreToLastFocused = null
        }
    }
    val focusModifierFor: (String) -> Modifier = { key ->
        Modifier
            .onFocusChanged { if (it.isFocused) lastFocusedKey = key }
            .then(if (key == lastFocusedKey) Modifier.focusRequester(restoreRequester) else Modifier)
    }
    LaunchedEffect(Unit) {
        if (lastFocusedKey == null) return@LaunchedEffect
        // The target card composes once its row's saved scroll position restores;
        // retry until the requester is attached.
        repeat(FOCUS_RESTORE_TRIES) {
            if (runCatching { restoreRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(FOCUS_RESTORE_RETRY_MS)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = 24.dp)
        ) {
            // No logo or title: the screen is for the content, as on the phone.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = EDGE),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onOpenBrowse, modifier = Modifier.pointerClick(onClick = onOpenBrowse)) {
                    Icon(Icons.Filled.GridView, contentDescription = "Browse")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onOpenSettings, modifier = Modifier.pointerClick(onClick = onOpenSettings)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
            Spacer(Modifier.height(8.dp))

            MixShelf(
                genres = sections.filterIsInstance<DiscoverSection.GenreRadioSection>().firstOrNull()?.genres.orEmpty(),
                building = mixBuilding,
                onSmartMix = viewModel::startSmartMix,
                onGenreRadio = viewModel::startGenreRadio,
                focusModifierFor = focusModifierFor
            )
            sections.forEach { section ->
                when (section) {
                    is DiscoverSection.AlbumSection -> ContentShelf(
                        section.title,
                        section.albums.map { MediaCardData(it.uri, it.imageUrl, it.name, it.artistNames) },
                        onClick = { viewModel.playMedia(it.uri) },
                        focusModifierFor = sectionFocus(section.title, focusModifierFor)
                    )
                    is DiscoverSection.ArtistSection -> ContentShelf(
                        section.title,
                        section.artists.map { MediaCardData(it.uri, it.imageUrl, it.name, null, it.itemId, it.provider) },
                        onClick = { onOpenArtist(it.itemId, it.provider) },
                        circular = true,
                        focusModifierFor = sectionFocus(section.title, focusModifierFor)
                    )
                    is DiscoverSection.PlaylistSection -> ContentShelf(
                        section.title,
                        section.playlists.map { MediaCardData(it.uri, it.imageUrl, it.name, null) },
                        onClick = { viewModel.playMedia(it.uri) },
                        focusModifierFor = sectionFocus(section.title, focusModifierFor)
                    )
                    is DiscoverSection.TrackSection -> ContentShelf(
                        section.title,
                        section.tracks.map { MediaCardData(it.uri, it.imageUrl, it.name, it.artistNames) },
                        onClick = { viewModel.playMedia(it.uri) },
                        focusModifierFor = sectionFocus(section.title, focusModifierFor)
                    )
                    // Drawn above, together with Smart Mix.
                    is DiscoverSection.GenreRadioSection -> Unit
                }
            }
            if (sections.isEmpty() && isLoading) {
                Text(
                    "Loading recommendations\u2026",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = EDGE)
                )
            }
        }
    }
}

// The same item can be in two shelves (a favourite album that is also recommended), so the
// key that brings focus back after Back names the shelf as well.
private fun sectionFocus(title: String, focusModifierFor: (String) -> Modifier): (String) -> Modifier =
    { uri -> focusModifierFor("$title|$uri") }

/** Smart Mix first, then a radio per genre, as on the phone's Home. */
@Composable
private fun MixShelf(
    genres: List<GenreItem>,
    building: MixLauncher.Mix?,
    onSmartMix: () -> Unit,
    onGenreRadio: (String) -> Unit,
    focusModifierFor: (String) -> Modifier
) {
    Shelf("Mixes") {
        item(key = SMART_MIX_KEY) {
            MixTile(
                title = "Smart Mix",
                subtitle = if (building == MixLauncher.Mix.SmartMix) "Building\u2026" else null,
                colors = SMART_MIX_COLORS,
                icon = true,
                modifier = focusModifierFor(SMART_MIX_KEY),
                onClick = onSmartMix
            )
        }
        items(genres, key = { it.name }) { genre ->
            val (start, end, _) = remember(genre.name) { genrePalette(genre.name) }
            val starting = (building as? MixLauncher.Mix.GenreRadio)?.genre == genre.name
            MixTile(
                title = genre.name,
                subtitle = if (starting) "Starting\u2026" else null,
                colors = start to end,
                icon = false,
                modifier = focusModifierFor("genre|${genre.name}"),
                onClick = { onGenreRadio(genre.name) }
            )
        }
    }
}

@Composable
private fun MixTile(
    title: String,
    subtitle: String?,
    colors: Pair<Color, Color>,
    icon: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = modifier.width(MIX_TILE_WIDTH).pointerClick(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MIX_TILE_HEIGHT)
                .background(Brush.linearGradient(listOf(colors.first, colors.second))),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}

private const val SMART_MIX_KEY = "smart_mix"
private val SMART_MIX_COLORS = Color(0xFF3A2F6B) to Color(0xFF141026)
private val MIX_TILE_WIDTH = 180.dp
private val MIX_TILE_HEIGHT = 116.dp

private data class MediaCardData(
    val uri: String,
    val imageUrl: String?,
    val title: String,
    val subtitle: String?,
    val itemId: String = "",
    val provider: String = ""
)

@Composable
private fun ContentShelf(
    title: String,
    items: List<MediaCardData>,
    onClick: (MediaCardData) -> Unit,
    circular: Boolean = false,
    focusModifierFor: (String) -> Modifier = { Modifier }
) {
    if (items.isEmpty()) return
    Shelf(title) {
        items(items.distinctBy { it.uri }, key = { it.uri }) { item ->
            MediaCard(item, circular, modifier = focusModifierFor(item.uri)) { onClick(item) }
        }
    }
}

private const val FOCUS_RESTORE_TRIES = 25
private const val FOCUS_RESTORE_RETRY_MS = 60L

@Composable
private fun Shelf(
    title: String,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 28.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = EDGE)
        )
        Spacer(Modifier.height(12.dp))
        // Overscan-safe inset lives in the row's contentPadding (not the parent),
        // so focused edge cards can scale without being clipped at the screen edge.
        LazyRow(
            state = state,
            // At either end of the row there is no card left in that direction, and Compose
            // would search the whole screen instead: RIGHT on the last card jumped to the
            // Browse and Settings buttons at the top. LEFT and RIGHT stay in the row; UP and
            // DOWN still move between rows.
            modifier = Modifier
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Left ||
                            requestedFocusDirection == FocusDirection.Right
                        ) {
                            cancelFocusChange()
                        }
                    }
                }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = EDGE),
            content = content
        )
    }
}

/** Overscan-safe horizontal inset for 10-foot layout. */
private val EDGE = 56.dp

/** Home row card/art size. Kept compact so more items are visible per row. */
private val CARD_SIZE = 116.dp

@Composable
private fun MediaCard(
    item: MediaCardData,
    circular: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = modifier.width(CARD_SIZE).pointerClick(onClick = onClick)) {
        Column {
            val imageModifier = if (circular) {
                Modifier.size(CARD_SIZE).clip(CircleShape)
            } else {
                Modifier.size(CARD_SIZE)
            }
            Box(modifier = Modifier.size(CARD_SIZE)) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.subtitle != null) {
                    Text(
                        item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
