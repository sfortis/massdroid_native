package net.asksakis.massdroidv2.ui.screens.nowplaying.components

/**
 * How large a Now Playing control draws itself.
 *
 * [Compact] and [Regular] are the two sizes a phone uses, chosen per control: in portrait the
 * seek bar is compact and the rest regular, in landscape the track text is compact and the rest
 * regular. [Large] is the tablet's size for every control, about a quarter larger than
 * [Regular], so the controls do not look lost on a screen twice as wide (#89).
 */
internal enum class NowPlayingSize { Compact, Regular, Large }
