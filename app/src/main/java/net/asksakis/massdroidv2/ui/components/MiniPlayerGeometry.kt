package net.asksakis.massdroidv2.ui.components

import androidx.compose.ui.unit.dp

/**
 * Where the collapsed player sits, for everything that has to agree with it.
 *
 * Two places need these figures and they used to hold their own copies: the activity, which
 * turns them into the bottom padding that scrollable content and the Smart Mix button keep
 * clear of, and the player sheet, which positions the bar itself. Changing one without the
 * other moved the button away from the player it is supposed to sit above.
 */
object MiniPlayerGeometry {
    /** The height of the bar when it is collapsed. */
    val CollapsedHeight = 72.dp

    /** The gap between the bar and the bottom bar under it. */
    val Margin = 16.dp

    /**
     * The widest the bar gets in landscape, where it stops and centres instead of running
     * across a screen twice as wide as it is tall. The volume overlay uses the same figure
     * so the two floating bars match.
     */
    val LandscapeMaxWidth = 440.dp
}
