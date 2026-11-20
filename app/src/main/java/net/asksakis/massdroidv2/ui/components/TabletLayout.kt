package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The smallest screen width, in dp, from which the app lays a screen out as a tablet. */
private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

/** Whether the app runs on a tablet: a screen at least 600dp wide in both orientations. */
@Composable
fun isTablet(): Boolean = LocalConfiguration.current.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP

/**
 * The widest a column of rows, cards or text gets, so a list on a tablet does not run its
 * text, sliders and chevrons across the whole screen. [Dp.Unspecified] on a phone, which
 * keeps every phone layout exactly as it was, in landscape too.
 */
@Composable
fun contentMaxWidth(): Dp = if (isTablet()) TabletContentMaxWidth else Dp.Unspecified

/**
 * Takes the full width up to [maxWidth] and centres itself in the rest. With
 * [Dp.Unspecified], as [contentMaxWidth] gives on a phone, it is the same as fillMaxWidth.
 */
fun Modifier.centeredMaxWidth(maxWidth: Dp): Modifier =
    this.fillMaxWidth().wrapContentWidth().widthIn(max = maxWidth).fillMaxWidth()

private val TabletContentMaxWidth = 720.dp

/**
 * The largest the cover gets in the side pane of an album or artist page on a tablet, where
 * it grows with the pane; a phone in landscape keeps its 120dp cover.
 */
val TabletDetailArtMaxWidth = 320.dp

/** The share of the side pane's width the cover takes on a tablet, up to [TabletDetailArtMaxWidth]. */
const val TABLET_DETAIL_ART_FRACTION = 0.7f
