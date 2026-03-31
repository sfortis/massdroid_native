package net.asksakis.massdroidv2.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.domain.repository.SearchResult
import net.asksakis.massdroidv2.ui.components.MediaItemGrid
import net.asksakis.massdroidv2.ui.components.MediaItemRow
import net.asksakis.massdroidv2.ui.components.LocalProviderManifestCache
import net.asksakis.massdroidv2.ui.components.formatAlbumTypeYear

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val focusRequester = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var selectedProviders by remember { mutableStateOf(emptySet<String>()) }
    val gridMode by viewModel.gridMode.collectAsStateWithLifecycle()

    val providerCache = LocalProviderManifestCache.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Reset filter when results change
    LaunchedEffect(results) {
        if (selectedProviders.isNotEmpty()) {
            val availableProviders = collectProviderCounts(results).keys
            val stillValid = selectedProviders.filter { it in availableProviders }.toSet()
            if (stillValid != selectedProviders) selectedProviders = stillValid
        }
    }

    val providerCounts = remember(results) { collectProviderCounts(results) }
    val hasResults = results.artists.isNotEmpty() || results.albums.isNotEmpty() ||
        results.tracks.isNotEmpty() || results.playlists.isNotEmpty() || results.radios.isNotEmpty()
    val totalCount = providerCounts.values.sum()

    val filtered = if (selectedProviders.isEmpty()) results else filterByProviders(results, selectedProviders)

    // Sorted provider list for chips
    val sortedProviders = remember(providerCounts) {
        providerCounts.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    // Color mapping
    val providerColors = providerBadgeColors(sortedProviders.map { it.first })
    val showBadges = providerCounts.size > 1

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        if (isLandscape) {
            // Landscape: search bar + chips + grid toggle in one row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val hasChips = hasResults && !isSearching
                TextField(
                    value = query,
                    onValueChange = { viewModel.updateQuery(it) },
                    modifier = Modifier
                        .then(if (hasChips) Modifier.widthIn(min = 200.dp, max = 320.dp) else Modifier.weight(1f))
                        .height(44.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text("Global search...", style = MaterialTheme.typography.labelSmall) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { focusManager.clearFocus() }
                    ),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.updateQuery("")
                                focusRequester.requestFocus()
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                if (hasResults && !isSearching) {
                    Spacer(modifier = Modifier.width(8.dp))
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            val allSelected = selectedProviders.isEmpty()
                            FilterChip(
                                selected = allSelected,
                                onClick = { selectedProviders = emptySet() },
                                label = { Text("All $totalCount") },
                                colors = if (allSelected) FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) else FilterChipDefaults.filterChipColors()
                            )
                        }
                        items(sortedProviders) { (provider, count) ->
                            val isSelected = provider in selectedProviders
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedProviders = if (isSelected) {
                                        val s = selectedProviders - provider; if (s.isEmpty()) emptySet() else s
                                    } else selectedProviders + provider
                                },
                                label = { Text("${formatProviderName(provider)} $count") }
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.toggleGridMode() }, modifier = Modifier.size(32.dp)) {
                        @Suppress("DEPRECATION")
                        Icon(
                            if (gridMode) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle view", modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else {
            // Portrait: search bar on top, chips below
            TextField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("Global search...") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                ),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.updateQuery("")
                            focusRequester.requestFocus()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )

            if (hasResults && !isSearching) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                // "All" chip
                item {
                    val allSelected = selectedProviders.isEmpty()
                    FilterChip(
                        selected = allSelected,
                        onClick = { selectedProviders = emptySet() },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("All")
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "$totalCount",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        },
                        colors = if (allSelected) {
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            FilterChipDefaults.filterChipColors()
                        }
                    )
                }
                // Provider chips
                items(sortedProviders) { (provider, count) ->
                    val isSelected = provider in selectedProviders
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedProviders = if (isSelected) {
                                val newSet = selectedProviders - provider
                                if (newSet.isEmpty()) emptySet() else newSet
                            } else {
                                selectedProviders + provider
                            }
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatProviderName(provider))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "$count",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    )
                }
            }
            IconButton(onClick = { viewModel.toggleGridMode() }) {
                @Suppress("DEPRECATION")
                Icon(
                    if (gridMode) Icons.Default.ViewList else Icons.Default.GridView,
                    contentDescription = "Toggle view"
                )
            }
            }
            }
        }

        // Content
        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (gridMode) {
            SearchResultsGrid(
                filtered, providerCache, onArtistClick, onAlbumClick,
                onPlaylistClick, { viewModel.playTrack(it) }, { viewModel.playRadio(it) }
            )
        } else {
            SearchResultsList(
                filtered, providerCache, onArtistClick, onAlbumClick,
                onPlaylistClick, { viewModel.playTrack(it) }, { viewModel.playRadio(it) }
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    filtered: SearchResult,
    providerCache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onTrackClick: (Track) -> Unit,
    onRadioClick: (Radio) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = LocalMiniPlayerPadding.current)) {
        if (filtered.artists.isNotEmpty()) {
            item { SectionHeader("Artists") }
            items(filtered.artists, key = { it.uri }) { artist ->
                MediaItemRow(
                    title = artist.name, subtitle = "", imageUrl = artist.imageUrl,
                    onClick = { onArtistClick(artist) },
                    providerDomains = artist.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.Person
                )
            }
        }
        if (filtered.albums.isNotEmpty()) {
            item { SectionHeader("Albums") }
            items(filtered.albums, key = { it.uri }) { album ->
                MediaItemRow(
                    title = album.name,
                    subtitle = formatAlbumTypeYear(album.albumType, album.year).ifBlank { album.artistNames },
                    imageUrl = album.imageUrl, onClick = { onAlbumClick(album) },
                    providerDomains = album.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.Album
                )
            }
        }
        if (filtered.tracks.isNotEmpty()) {
            item { SectionHeader("Tracks") }
            items(filtered.tracks, key = { it.uri }) { track ->
                MediaItemRow(
                    title = track.name, subtitle = track.artistNames, imageUrl = track.imageUrl,
                    onClick = { onTrackClick(track) },
                    providerDomains = track.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.MusicNote
                )
            }
        }
        if (filtered.playlists.isNotEmpty()) {
            item { SectionHeader("Playlists") }
            @Suppress("DEPRECATION")
            items(filtered.playlists, key = { it.uri }) { playlist ->
                MediaItemRow(
                    title = playlist.name, subtitle = "", imageUrl = playlist.imageUrl,
                    onClick = { onPlaylistClick(playlist) },
                    providerDomains = playlist.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.QueueMusic
                )
            }
        }
        if (filtered.radios.isNotEmpty()) {
            item { SectionHeader("Radios") }
            items(filtered.radios, key = { it.uri }) { radio ->
                MediaItemRow(
                    title = radio.name, subtitle = "", imageUrl = radio.imageUrl,
                    onClick = { onRadioClick(radio) },
                    providerDomains = radio.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.Radio
                )
            }
        }
    }
}

@Composable
private fun SearchResultsGrid(
    filtered: SearchResult,
    providerCache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onTrackClick: (Track) -> Unit,
    onRadioClick: (Radio) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = LocalMiniPlayerPadding.current),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (filtered.artists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Artists") }
            items(filtered.artists, key = { it.uri }) { artist ->
                MediaItemGrid(
                    title = artist.name, subtitle = "", imageUrl = artist.imageUrl,
                    onClick = { onArtistClick(artist) },
                    providerDomains = artist.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.Person
                )
            }
        }
        if (filtered.albums.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Albums") }
            items(filtered.albums, key = { it.uri }) { album ->
                MediaItemGrid(
                    title = album.name,
                    subtitle = formatAlbumTypeYear(album.albumType, album.year).ifBlank { album.artistNames },
                    imageUrl = album.imageUrl, onClick = { onAlbumClick(album) },
                    providerDomains = album.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.Album
                )
            }
        }
        if (filtered.tracks.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Tracks") }
            items(filtered.tracks, key = { it.uri }) { track ->
                MediaItemGrid(
                    title = track.name, subtitle = track.artistNames, imageUrl = track.imageUrl,
                    onClick = { onTrackClick(track) },
                    providerDomains = track.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.MusicNote
                )
            }
        }
        if (filtered.playlists.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Playlists") }
            @Suppress("DEPRECATION")
            items(filtered.playlists, key = { it.uri }) { playlist ->
                MediaItemGrid(
                    title = playlist.name, subtitle = "", imageUrl = playlist.imageUrl,
                    onClick = { onPlaylistClick(playlist) },
                    providerDomains = playlist.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.QueueMusic
                )
            }
        }
        if (filtered.radios.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Radios") }
            items(filtered.radios, key = { it.uri }) { radio ->
                MediaItemGrid(
                    title = radio.name, subtitle = "", imageUrl = radio.imageUrl,
                    onClick = { onRadioClick(radio) },
                    providerDomains = radio.providerDomains, providerCache = providerCache,
                    fallbackIcon = Icons.Default.Radio
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            thickness = 1.dp
        )
    }
}

private fun formatProviderName(provider: String): String {
    val name = provider.substringBefore("--").replace("_", " ")
    return name.replaceFirstChar { it.uppercase() }
        .take(16)
        .let { if (it.length < name.length) "$it..." else it }
}

private fun collectProviderCounts(results: SearchResult): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    fun addDomains(domains: List<String>) {
        for (d in domains) {
            val key = d.substringBefore("--")
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    results.artists.forEach { addDomains(it.providerDomains) }
    results.albums.forEach { addDomains(it.providerDomains) }
    results.tracks.forEach { addDomains(it.providerDomains) }
    results.playlists.forEach { addDomains(it.providerDomains) }
    results.radios.forEach { addDomains(it.providerDomains) }
    return counts
}

private fun filterByProviders(results: SearchResult, selected: Set<String>): SearchResult {
    fun <T> filter(items: List<T>, domains: (T) -> List<String>): List<T> =
        items.filter { item ->
            val d = domains(item)
            d.isEmpty() || d.any { domain ->
                domain.substringBefore("--") in selected
            }
        }
    return results.copy(
        artists = filter(results.artists) { it.providerDomains },
        albums = filter(results.albums) { it.providerDomains },
        tracks = filter(results.tracks) { it.providerDomains },
        playlists = filter(results.playlists) { it.providerDomains },
        radios = filter(results.radios) { it.providerDomains }
    )
}

@Composable
private fun providerBadgeColors(providers: List<String>): Map<String, Pair<Color, Color>> {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    )
    return providers.mapIndexed { i, p -> p to palette[i % palette.size] }.toMap()
}
