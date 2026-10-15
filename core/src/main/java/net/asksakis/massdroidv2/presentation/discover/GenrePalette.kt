package net.asksakis.massdroidv2.presentation.discover

import androidx.compose.ui.graphics.Color
import net.asksakis.massdroidv2.domain.recommendation.genreKey
import kotlin.math.absoluteValue

/**
 * The colours of a Genre Radio tile: the two ends of its gradient and the glow drawn over it.
 * The phone and the TV draw the same genre in the same colours. The choice is by genre identity,
 * so every spelling of a genre gets the same colours.
 */
fun genrePalette(name: String): Triple<Color, Color, Color> {
    val palettes = listOf(
        Triple(Color(0xFF233247), Color(0xFF101722), Color(0x553DB7FF)),
        Triple(Color(0xFF35273E), Color(0xFF16121B), Color(0x554DA3FF)),
        Triple(Color(0xFF21383A), Color(0xFF0F1718), Color(0x5540D6C3)),
        Triple(Color(0xFF40311F), Color(0xFF17120D), Color(0x55FF9F43)),
        Triple(Color(0xFF2B2B46), Color(0xFF11111C), Color(0x556C8CFF)),
        Triple(Color(0xFF3A2531), Color(0xFF160E12), Color(0x55FF6FAE))
    )
    return palettes[(genreKey(name).hashCode().absoluteValue) % palettes.size]
}
