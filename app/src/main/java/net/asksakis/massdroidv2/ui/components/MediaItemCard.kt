package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import net.asksakis.massdroidv2.data.provider.ProviderManifestCache

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItemRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified,
    favorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
    showEqualizer: Boolean = false,
    providerDomains: List<String> = emptyList(),
    providerCache: ProviderManifestCache? = null,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val context = LocalContext.current
    val imageModel = remember(imageUrl, context) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(192)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    ListItem(
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = titleColor
            )
        },
        supportingContent = {
            if (subtitle.isNotBlank() || (providerDomains.isNotEmpty() && providerCache != null)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (providerCache != null && providerDomains.isNotEmpty()) {
                        ProviderBadges(
                            providerDomains = providerDomains,
                            cache = providerCache,
                            iconSize = 14.dp
                        )
                    }
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        if (imageUrl.isNullOrBlank() && fallbackIcon != null)
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl.isNullOrBlank() && fallbackIcon != null) {
                    Icon(
                        fallbackIcon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                if (showEqualizer) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        EqualizerBars(
                            modifier = Modifier.height(24.dp),
                            barWidth = 3.dp,
                            spacing = 2.dp,
                            barCount = 4,
                            bpm = 90
                        )
                    }
                }
            }
        },
        trailingContent = {
            if (favorite || onPlayClick != null || onMoreClick != null || dragHandle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (favorite) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    if (onPlayClick != null) {
                        OutlinedIconButton(
                            onClick = onPlayClick,
                            modifier = Modifier.size(32.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = IconButtonDefaults.outlinedIconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (onMoreClick != null) {
                        IconButton(onClick = onMoreClick, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(20.dp))
                        }
                    }
                    if (dragHandle != null) {
                        dragHandle()
                    }
                }
            }
        },
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaItemGrid(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    providerDomains: List<String> = emptyList(),
    providerCache: ProviderManifestCache? = null,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val context = LocalContext.current
    val imageModel = remember(imageUrl, context) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(384)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    Column(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Box {
            if (imageUrl.isNullOrBlank() && fallbackIcon != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        fallbackIcon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                AsyncImage(
                    model = imageModel,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop
                )
            }
            if (providerCache != null && providerDomains.isNotEmpty()) {
                ProviderBadges(
                    providerDomains = providerDomains,
                    cache = providerCache,
                    iconSize = 14.dp,
                    withShadow = true,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle.ifBlank { "\u00A0" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
