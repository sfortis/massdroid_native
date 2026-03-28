package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

enum class ArtworkPlaceholderVariant {
    ARTIST,
    COLLECTION,
    TRACK
}

private data class ArtworkPlaceholderStyle(
    val accent: Color,
    val shade: Color,
    val glowAlpha: Float,
    val plateShape: Shape,
    val plateScale: Float,
    val plateAlpha: Float
)

@Composable
fun MediaArtwork(
    model: Any?,
    contentDescription: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
    iconSize: Dp = 32.dp,
    variant: ArtworkPlaceholderVariant = ArtworkPlaceholderVariant.COLLECTION,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    if (model == null) {
        ArtworkPlaceholder(
            fallbackIcon = fallbackIcon,
            modifier = modifier,
            shape = shape,
            iconSize = iconSize,
            variant = variant,
            containerColor = containerColor,
            iconTint = iconTint
        )
        return
    }

    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.clip(shape),
        contentScale = contentScale,
        loading = {
            ArtworkPlaceholder(
                fallbackIcon = fallbackIcon,
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                iconSize = iconSize,
                variant = variant,
                containerColor = containerColor,
                iconTint = iconTint
            )
        },
        error = {
            ArtworkPlaceholder(
                fallbackIcon = fallbackIcon,
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                iconSize = iconSize,
                variant = variant,
                containerColor = containerColor,
                iconTint = iconTint
            )
        }
    )
}

@Composable
fun ArtworkPlaceholder(
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    iconSize: Dp = 32.dp,
    variant: ArtworkPlaceholderVariant = ArtworkPlaceholderVariant.COLLECTION,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val style = when (variant) {
        ArtworkPlaceholderVariant.ARTIST -> ArtworkPlaceholderStyle(
            accent = lerp(containerColor, MaterialTheme.colorScheme.tertiaryContainer, 0.34f),
            shade = lerp(containerColor, MaterialTheme.colorScheme.surface, 0.18f),
            glowAlpha = 0.36f,
            plateShape = CircleShape,
            plateScale = 2.2f,
            plateAlpha = 0.50f
        )
        ArtworkPlaceholderVariant.TRACK -> ArtworkPlaceholderStyle(
            accent = lerp(containerColor, MaterialTheme.colorScheme.secondaryContainer, 0.22f),
            shade = lerp(containerColor, MaterialTheme.colorScheme.surface, 0.26f),
            glowAlpha = 0.24f,
            plateShape = MaterialTheme.shapes.small,
            plateScale = 1.9f,
            plateAlpha = 0.62f
        )
        ArtworkPlaceholderVariant.COLLECTION -> ArtworkPlaceholderStyle(
            accent = lerp(containerColor, MaterialTheme.colorScheme.primaryContainer, 0.28f),
            shade = lerp(containerColor, MaterialTheme.colorScheme.surface, 0.22f),
            glowAlpha = 0.32f,
            plateShape = MaterialTheme.shapes.medium,
            plateScale = 2.1f,
            plateAlpha = 0.56f
        )
    }
    val glow = lerp(style.accent, MaterialTheme.colorScheme.tertiaryContainer, 0.35f).copy(alpha = style.glowAlpha)
    val plateBackground = MaterialTheme.colorScheme.surface.copy(alpha = style.plateAlpha)

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(style.accent, style.shade)
                )
            )
            .drawWithContent {
                drawContent()
                drawCircle(
                    color = glow,
                    radius = size.minDimension * 0.28f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.24f)
                )
                drawCircle(
                    color = glow.copy(alpha = glow.alpha * 0.62f),
                    radius = size.minDimension * 0.22f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.78f)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(iconSize * style.plateScale)
                .clip(style.plateShape)
                .background(plateBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = iconTint.copy(alpha = 0.88f)
            )
        }
    }
}
