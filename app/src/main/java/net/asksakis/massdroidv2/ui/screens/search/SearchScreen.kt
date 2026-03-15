package net.asksakis.massdroidv2.ui.screens.search

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.domain.model.*
import net.asksakis.massdroidv2.ui.components.LocalProviderManifestCache
import net.asksakis.massdroidv2.ui.components.ProviderBadges
import net.asksakis.massdroidv2.ui.components.formatAlbumTypeYear
import net.asksakis.massdroidv2.ui.components.MediaItemRow

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

    val providerCache = LocalProviderManifestCache.current
    val allProviders by providerCache.musicProvidersFlow.collectAsStateWithLifecycle()
    var selectedProviders by remember { mutableStateOf(emptySet<String>()) }
    val dark = isSystemInDarkTheme()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Client-side provider filtering
    val filteredResults = if (selectedProviders.isEmpty()) results else {
        fun <T> filterByProvider(items: List<T>, domains: (T) -> List<String>): List<T> =
            items.filter { item ->
                val d = domains(item)
                d.isEmpty() || d.any { domain ->
                    selectedProviders.any { instanceId ->
                        instanceId.startsWith(domain) || domain == instanceId
                    }
                }
            }
        results.copy(
            artists = filterByProvider(results.artists) { it.providerDomains },
            albums = filterByProvider(results.albums) { it.providerDomains },
            tracks = filterByProvider(results.tracks) { it.providerDomains },
            playlists = filterByProvider(results.playlists) { it.providerDomains },
            radios = filterByProvider(results.radios) { it.providerDomains }
        )
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { viewModel.updateQuery(it) },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.focusRequester(focusRequester),
                    placeholder = { Text("Search music...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    }
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (isLandscape) 8.dp else 16.dp,
                    vertical = if (isLandscape) 2.dp else 8.dp
                )
        ) {}

        // Provider filter dropdown
        if (allProviders.size > 1) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                SearchProviderFilterDropdown(
                    providers = allProviders,
                    selectedIds = selectedProviders,
                    cache = providerCache,
                    onToggle = { id ->
                        selectedProviders = if (id in selectedProviders)
                            selectedProviders - id else selectedProviders + id
                    },
                    onClear = { selectedProviders = emptySet() }
                )
            }
        }

        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn {
                if (filteredResults.artists.isNotEmpty()) {
                    item {
                        Text(
                            "Artists",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(filteredResults.artists) { artist ->
                        MediaItemRow(
                            title = artist.name,
                            subtitle = "",
                            imageUrl = artist.imageUrl,
                            onClick = { onArtistClick(artist) },
                            providerDomains = artist.providerDomains,
                            providerCache = providerCache
                        )
                    }
                }

                if (filteredResults.albums.isNotEmpty()) {
                    item {
                        Text(
                            "Albums",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(filteredResults.albums) { album ->
                        MediaItemRow(
                            title = album.name,
                            subtitle = formatAlbumTypeYear(album.albumType, album.year)
                                .ifBlank { album.artistNames },
                            imageUrl = album.imageUrl,
                            onClick = { onAlbumClick(album) },
                            providerDomains = album.providerDomains,
                            providerCache = providerCache
                        )
                    }
                }

                if (filteredResults.tracks.isNotEmpty()) {
                    item {
                        Text(
                            "Tracks",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(filteredResults.tracks) { track ->
                        MediaItemRow(
                            title = track.name,
                            subtitle = track.artistNames,
                            imageUrl = track.imageUrl,
                            onClick = { viewModel.playTrack(track) },
                            providerDomains = track.providerDomains,
                            providerCache = providerCache
                        )
                    }
                }

                if (filteredResults.playlists.isNotEmpty()) {
                    item {
                        Text(
                            "Playlists",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(filteredResults.playlists) { playlist ->
                        MediaItemRow(
                            title = playlist.name,
                            subtitle = "",
                            imageUrl = playlist.imageUrl,
                            onClick = { onPlaylistClick(playlist) },
                            providerDomains = playlist.providerDomains,
                            providerCache = providerCache
                        )
                    }
                }

                if (filteredResults.radios.isNotEmpty()) {
                    item {
                        Text(
                            "Radios",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(filteredResults.radios) { radio ->
                        MediaItemRow(
                            title = radio.name,
                            subtitle = "",
                            imageUrl = radio.imageUrl,
                            onClick = { viewModel.playRadio(radio) },
                            providerDomains = radio.providerDomains,
                            providerCache = providerCache
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchProviderFilterDropdown(
    providers: List<net.asksakis.massdroidv2.data.provider.MusicProvider>,
    selectedIds: Set<String>,
    cache: net.asksakis.massdroidv2.data.provider.ProviderManifestCache,
    onToggle: (String) -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasFilter = selectedIds.isNotEmpty()
    val dark = isSystemInDarkTheme()

    Box {
        FilterChip(
            selected = hasFilter,
            onClick = { expanded = true },
            label = {
                Text(if (hasFilter) "${selectedIds.size} selected" else "All providers")
            },
            leadingIcon = {
                Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (hasFilter) {
                DropdownMenuItem(
                    text = { Text("Show all") },
                    onClick = {
                        onClear()
                        expanded = false
                    }
                )
                HorizontalDivider()
            }
            providers.forEach { provider ->
                val isSelected = provider.instanceId in selectedIds
                DropdownMenuItem(
                    text = { Text(provider.name) },
                    onClick = { onToggle(provider.instanceId) },
                    leadingIcon = {
                        ProviderBadges(
                            providerDomains = listOf(provider.domain),
                            cache = cache,
                            iconSize = 20.dp,
                            maxIcons = 1
                        )
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}
