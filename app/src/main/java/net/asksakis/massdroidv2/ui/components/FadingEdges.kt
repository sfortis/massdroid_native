package net.asksakis.massdroidv2.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.fadingEdges(
    topSize: Dp = 12.dp,
    bottomSize: Dp = 12.dp
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (topSize > 0.dp) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = topSize.toPx()
                ),
                blendMode = BlendMode.DstIn
            )
        }
        if (bottomSize > 0.dp) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - bottomSize.toPx(),
                    endY = size.height
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }
