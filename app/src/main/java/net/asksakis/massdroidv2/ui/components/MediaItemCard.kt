package net.asksakis.massdroidv2.ui.components

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import coil.request.CachePolicy
import net.asksakis.massdroidv2.data.provider.ProviderManifestCache

private fun artworkPlaceholderVariantForIcon(
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector
): ArtworkPlaceholderVariant = when (fallbackIcon) {
    Icons.Default.Person -> ArtworkPlaceholderVariant.ARTIST
    Icons.Default.Album,
    Icons.Default.Radio,
    Icons.Default.QueueMusic -> ArtworkPlaceholderVariant.COLLECTION
    else -> ArtworkPlaceholderVariant.TRACK
}

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
    isBlocked: Boolean = false,
    providerDomains: List<String> = emptyList(),
    providerCache: ProviderManifestCache? = null,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val context = LocalContext.current
    val showProviderBadges = providerCache != null && providerDomains.distinct().size > 1
    val resolvedFallbackIcon = fallbackIcon ?: Icons.Default.MusicNote
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
            if (subtitle.isNotBlank() || showProviderBadges) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (showProviderBadges) {
                        ProviderBadges(
                            providerDomains = providerDomains,
                            cache = providerCache!!,
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
                    .size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                MediaArtwork(
                    model = imageModel,
                    contentDescription = null,
                    fallbackIcon = resolvedFallbackIcon,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (isBlocked) 0.2f else 1f },
                    shape = MaterialTheme.shapes.small,
                    iconSize = 24.dp,
                    variant = artworkPlaceholderVariantForIcon(resolvedFallbackIcon)
                )
                if (showEqualizer) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.84f))
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        EqualizerBars(
                            modifier = Modifier.height(12.dp),
                            barWidth = 2.dp,
                            spacing = 1.dp,
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
                        MdIconButton(
                            onClick = onPlayClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (onMoreClick != null) {
                        MdIconButton(onClick = onMoreClick, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(20.dp))
                        }
                    }
                    if (dragHandle != null) {
                        dragHandle()
                    }
                }
            }
        },
        modifier = modifier.hapticCombinedClickable(
            onLongClick = onLongClick,
            onClick = onClick
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
    isBlocked: Boolean = false,
    providerDomains: List<String> = emptyList(),
    providerCache: ProviderManifestCache? = null,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val context = LocalContext.current
    val resolvedFallbackIcon = fallbackIcon ?: Icons.Default.MusicNote
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
        modifier = modifier.hapticCombinedClickable(
            onLongClick = onLongClick,
            onClick = onClick
        )
    ) {
        MediaArtwork(
            model = imageModel,
            contentDescription = null,
            fallbackIcon = resolvedFallbackIcon,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer { alpha = if (isBlocked) 0.2f else 1f },
            shape = MaterialTheme.shapes.medium,
            iconSize = 48.dp,
            variant = artworkPlaceholderVariantForIcon(resolvedFallbackIcon)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

