package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.tv.material3.Card
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.model.Album

/** An artist's albums in a 10-foot grid; click an album to play it on the selected player. */
@Composable
fun TvArtistScreen(viewModel: TvArtistViewModel = hiltViewModel()) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()

    // The albums load async after navigation; the shared grid-focus helper focuses the
    // first card on load so focus never escapes to the floating mini player pill, and
    // restores the scrolled card (not the top) when returning from the mini player.
    val gridState = rememberLazyGridState()
    val albumKeys = remember(albums) { albums.map { it.uri } }
    val gridFocus = rememberGridItemFocus(albumKeys, gridState)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp, bottom = 40.dp)) {
            Text(
                name.ifBlank { "Artist" },
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = ARTIST_EDGE)
            )
            Spacer(Modifier.height(20.dp))
            if (albums.isEmpty()) {
                Text(
                    "No albums",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = ARTIST_EDGE)
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    // Fixed columns + fill-width cards keep the art aligned across the
                    // grid; generous padding/spacing leaves room for the focus scale so
                    // the selected card is not clipped at the edges.
                    columns = GridCells.Fixed(ARTIST_GRID_COLUMNS),
                    contentPadding = PaddingValues(horizontal = ARTIST_EDGE, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    items(albums, key = { it.uri }) { album ->
                        AlbumCard(album, modifier = gridFocus.modifierFor(album.uri)) { viewModel.playMedia(album.uri) }
                    }
                }
            }
        }
    }
}

private val ARTIST_EDGE = 56.dp
private const val ARTIST_GRID_COLUMNS = 5

@Composable
private fun AlbumCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column {
            AsyncImage(
                model = album.imageUrl,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                Text(
                    album.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = album.year?.toString() ?: album.albumType.orEmpty()
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
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
