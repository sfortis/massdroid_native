package net.asksakis.massdroidv2.ui.screens.search

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import net.asksakis.massdroidv2.ui.components.LocalMiniPlayerPadding
import net.asksakis.massdroidv2.ui.components.fadingEdges
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import net.asksakis.massdroidv2.ui.components.ActionSheetItem
import net.asksakis.massdroidv2.ui.components.MediaActionSheet
import net.asksakis.massdroidv2.ui.components.MediaItemGrid
import net.asksakis.massdroidv2.ui.components.MediaItemRow
import net.asksakis.massdroidv2.ui.components.RemoveFromLibraryDialog
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
    val players by viewModel.players.collectAsStateWithLifecycle()
    var actionSheetItem by remember { mutableStateOf<ActionSheetItem?>(null) }
    var pendingLibraryRemove by remember { mutableStateOf<ActionSheetItem?>(null) }

    // Dismiss the soft keyboard once the user starts scrolling the results.
    val dismissKeyboardOnScroll = remember(focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }

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

    // Type filter (issue #65): one tap shows just the albums (or tracks, ...)
    // instead of scrolling past every other section. Modeled as chips like the
    // Music Assistant web UI, and stacked ON TOP of the provider filter so the
    // counts always describe what is actually visible.
    var selectedType by remember { mutableStateOf<SearchTypeFilter?>(null) }
    LaunchedEffect(filtered) {
        val st = selectedType
        if (st != null && st.count(filtered) == 0) selectedType = null
    }
    val typed = selectedType?.slice(filtered) ?: filtered

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
                            MdIconButton(onClick = {
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
                    MdIconButton(onClick = { viewModel.toggleGridMode() }, modifier = Modifier.size(32.dp)) {
                        @Suppress("DEPRECATION")
                        Icon(
                            if (gridMode) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle view", modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (hasResults && !isSearching) {
                TypeFilterChips(
                    result = filtered,
                    selected = selectedType,
                    onSelect = {
                        selectedType = it
                        it?.let { t -> viewModel.deepenSearch(t.mediaType) }
                    }
                )
            }
        } else {
            // Portrait: search bar with the layout toggle at its right, chips in
            // two clean rows below (providers, then types). The toggle used to sit
            // at the end of the provider-chip row, which squeezed the chips and
            // hid it whenever there were no results to toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            TextField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                modifier = Modifier
                    .weight(1f)
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
                        MdIconButton(onClick = {
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
            Spacer(Modifier.width(4.dp))
            MdIconButton(onClick = { viewModel.toggleGridMode() }) {
                @Suppress("DEPRECATION")
                Icon(
                    if (gridMode) Icons.Default.ViewList else Icons.Default.GridView,
                    contentDescription = "Toggle view"
                )
            }
            }

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
            }
            TypeFilterChips(
                result = filtered,
                selected = selectedType,
                onSelect = {
                    selectedType = it
                    // Narrowing to one category is the moment to fetch more of it.
                    it?.let { t -> viewModel.deepenSearch(t.mediaType) }
                }
            )
            }
        }

        // Content
        Box(modifier = Modifier.fillMaxSize().nestedScroll(dismissKeyboardOnScroll)) {
            if (isSearching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (gridMode) {
                SearchResultsGrid(
                    typed, providerCache, onArtistClick, onAlbumClick,
                    onPlaylistClick, { viewModel.playTrack(it) }, { viewModel.playRadio(it) },
                    onLongPress = { actionSheetItem = it }
                )
            } else {
                SearchResultsList(
                    typed, providerCache, onArtistClick, onAlbumClick,
                    onPlaylistClick, { viewModel.playTrack(it) }, { viewModel.playRadio(it) },
                    onLongPress = { actionSheetItem = it }
                )
            }
        }
    }

    actionSheetItem?.let { target ->
        MediaActionSheet(
            title = target.title,
            subtitle = target.subtitle,
            imageUrl = target.imageUrl,
            players = players,
            selectedPlayerId = players.firstOrNull()?.playerId,
            favorite = target.favorite,
            onToggleFavorite = {
                viewModel.toggleFavorite(target.uri, target.mediaType, target.itemId, target.favorite)
            },
            inLibrary = target.inLibrary,
            onToggleLibrary = {
                if (target.inLibrary) {
                    pendingLibraryRemove = target
                } else {
                    viewModel.toggleLibrary(target.uri, target.mediaType, target.itemId, false)
                }
                actionSheetItem = null
            },
            onPlayNow = { viewModel.playUri(target.uri) },
            onPlayOnPlayer = { player -> viewModel.playOnPlayer(target.uri, player.playerId) },
            onPlayNext = { viewModel.enqueueNext(target.uri) },
            onAddToQueue = { viewModel.enqueue(target.uri) },
            onStartRadio = if (target.mediaType == MediaType.RADIO) null else {
                { viewModel.startRadio(target.uri) }
            },
            onDismiss = { actionSheetItem = null }
        )
    }

    pendingLibraryRemove?.let { target ->
        RemoveFromLibraryDialog(
            itemTitle = target.title,
            onConfirm = {
                viewModel.toggleLibrary(target.uri, target.mediaType, target.itemId, true)
            },
            onDismiss = { pendingLibraryRemove = null }
        )
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
    onRadioClick: (Radio) -> Unit,
    onLongPress: (ActionSheetItem) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize().fadingEdges(), contentPadding = PaddingValues(bottom = LocalMiniPlayerPadding.current)) {
        if (filtered.artists.isNotEmpty()) {
            item { SectionHeader("Artists") }
            items(filtered.artists, key = { it.uri }) { artist ->
                MediaItemRow(
                    title = artist.name, subtitle = "", imageUrl = artist.imageUrl,
                    onClick = { onArtistClick(artist) },
                    onLongClick = { onLongPress(artist.toActionSheetItem()) },
                    inLibrary = artist.uri.startsWith("library://"),
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
                    subtitle = albumSearchSubtitle(album),
                    imageUrl = album.imageUrl, onClick = { onAlbumClick(album) },
                    onLongClick = { onLongPress(album.toActionSheetItem()) },
                    inLibrary = album.uri.startsWith("library://"),
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
                    onLongClick = { onLongPress(track.toActionSheetItem()) },
                    inLibrary = track.uri.startsWith("library://"),
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
    onRadioClick: (Radio) -> Unit,
    onLongPress: (ActionSheetItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = Modifier.fillMaxSize().fadingEdges(),
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
                    onLongClick = { onLongPress(artist.toActionSheetItem()) },
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
                    subtitle = albumSearchSubtitle(album),
                    imageUrl = album.imageUrl, onClick = { onAlbumClick(album) },
                    onLongClick = { onLongPress(album.toActionSheetItem()) },
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
                    onLongClick = { onLongPress(track.toActionSheetItem()) },
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

/**
 * WHOSE album. The subtitle used to show type/year INSTEAD of the artist whenever
 * type/year existed, so a search for a common album title gave a wall of covers
 * reading "Album • 2016" with no way to tell them apart. The listener's ask was
 * explicit: the artist name, exactly as track results do, with the type/year kept
 * only as the fallback when the artist is unknown.
 */
private fun albumSearchSubtitle(album: Album): String =
    album.artistNames.ifBlank { formatAlbumTypeYear(album.albumType, album.year) }

/**
 * The five result sections, as a one-tap filter (issue #65). Each knows how to
 * count itself in and cut a [SearchResult] down to itself, so the chip row and
 * the renderers cannot disagree about what a selection means.
 */
private enum class SearchTypeFilter(val label: String, val mediaType: MediaType) {
    ARTISTS("Artists", MediaType.ARTIST) {
        override fun count(r: SearchResult) = r.artists.size
        override fun slice(r: SearchResult) = SearchResult(artists = r.artists)
    },
    ALBUMS("Albums", MediaType.ALBUM) {
        override fun count(r: SearchResult) = r.albums.size
        override fun slice(r: SearchResult) = SearchResult(albums = r.albums)
    },
    TRACKS("Tracks", MediaType.TRACK) {
        override fun count(r: SearchResult) = r.tracks.size
        override fun slice(r: SearchResult) = SearchResult(tracks = r.tracks)
    },
    PLAYLISTS("Playlists", MediaType.PLAYLIST) {
        override fun count(r: SearchResult) = r.playlists.size
        override fun slice(r: SearchResult) = SearchResult(playlists = r.playlists)
    },
    RADIOS("Radios", MediaType.RADIO) {
        override fun count(r: SearchResult) = r.radios.size
        override fun slice(r: SearchResult) = SearchResult(radios = r.radios)
    };

    abstract fun count(r: SearchResult): Int
    abstract fun slice(r: SearchResult): SearchResult
}

/**
 * One chip per non-empty section, plus All. Single-select on purpose: the ask
 * behind it is "jump me to the albums", not set arithmetic, and tapping the
 * active chip (or All) returns to everything.
 */
@Composable
private fun TypeFilterChips(
    result: SearchResult,
    selected: SearchTypeFilter?,
    onSelect: (SearchTypeFilter?) -> Unit
) {
    val present = SearchTypeFilter.entries.filter { it.count(result) > 0 }
    // With one section there is nothing to filter; the row would be noise.
    if (present.size < 2) return
    LazyRow(
        modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                colors = if (selected == null) FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) else FilterChipDefaults.filterChipColors()
            )
        }
        items(present) { type ->
            val isSelected = selected == type
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else type) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(type.label)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${type.count(result)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            )
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

private fun Artist.toActionSheetItem() = ActionSheetItem(
    title = name, subtitle = "", uri = uri, imageUrl = imageUrl,
    favorite = favorite, mediaType = MediaType.ARTIST, itemId = itemId,
    inLibrary = uri.startsWith("library://")
)

private fun Album.toActionSheetItem() = ActionSheetItem(
    title = name, subtitle = artistNames, uri = uri, imageUrl = imageUrl,
    favorite = favorite, mediaType = MediaType.ALBUM, itemId = itemId,
    inLibrary = uri.startsWith("library://")
)

private fun Track.toActionSheetItem() = ActionSheetItem(
    title = name, subtitle = artistNames, uri = uri, imageUrl = imageUrl,
    favorite = favorite, mediaType = MediaType.TRACK, itemId = itemId,
    inLibrary = uri.startsWith("library://"),
    primaryArtistUri = artistUri,
    primaryArtistName = artistNames.split(",").firstOrNull()?.trim()
)
