package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.data.provider.ProviderManifestCache

val LocalProviderManifestCache = staticCompositionLocalOf<ProviderManifestCache> {
    error("ProviderManifestCache not provided")
}

private val FALLBACK_ICONS = mapOf(
    "harddisk" to Icons.Default.Storage,
    "network" to Icons.Default.Folder,
    "cast" to Icons.Default.Cloud,
    "cast-variant" to Icons.Default.Cloud,
    "speaker-multiple" to Icons.Default.Album,
    "radio" to Icons.Default.Radio
)

@Composable
fun ProviderBadges(
    providerDomains: List<String>,
    cache: ProviderManifestCache,
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
    maxIcons: Int = 3,
    withShadow: Boolean = false
) {
    if (providerDomains.isEmpty()) return
    val dark = isSystemInDarkTheme()
    val entries = providerDomains
        .take(maxIcons)
        .mapNotNull { domain ->
            val bitmap = cache.getIcon(domain, dark)
            if (bitmap != null) return@mapNotNull ProviderIconEntry.Svg(domain, bitmap)
            val fallbackName = cache.getFallbackIconName(domain)
            val fallbackIcon = fallbackName?.let { FALLBACK_ICONS[it] }
            if (fallbackIcon != null) return@mapNotNull ProviderIconEntry.Vector(domain, fallbackIcon)
            null
        }
    if (entries.isEmpty()) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (entry in entries) {
            if (withShadow) {
                val glowSize = iconSize + 8.dp
                Box(
                    modifier = Modifier.size(glowSize),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.5f),
                                    Color.Black.copy(alpha = 0.25f),
                                    Color.Black.copy(alpha = 0.08f),
                                    Color.Transparent
                                ),
                                center = Offset(size.width / 2, size.height / 2),
                                radius = size.minDimension / 2
                            )
                        )
                    }
                    RenderIcon(entry, cache, Modifier.size(iconSize))
                }
            } else {
                RenderIcon(entry, cache, Modifier.size(iconSize))
            }
        }
    }
}

@Composable
private fun RenderIcon(entry: ProviderIconEntry, cache: ProviderManifestCache, modifier: Modifier) {
    when (entry) {
        is ProviderIconEntry.Svg -> Image(
            bitmap = entry.bitmap,
            contentDescription = cache.getName(entry.domain),
            modifier = modifier
        )
        is ProviderIconEntry.Vector -> Icon(
            imageVector = entry.icon,
            contentDescription = cache.getName(entry.domain),
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

private sealed class ProviderIconEntry {
    abstract val domain: String
    data class Svg(override val domain: String, val bitmap: ImageBitmap) : ProviderIconEntry()
    data class Vector(override val domain: String, val icon: ImageVector) : ProviderIconEntry()
}
