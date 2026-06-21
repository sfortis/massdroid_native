package net.asksakis.massdroidv2

/**
 * Volume increment (in MA 0-100 units) applied per hardware-rocker press, on BOTH the phone rocker
 * ([net.asksakis.massdroidv2.ui.MainActivity]) and the Android Auto rocker
 * ([net.asksakis.massdroidv2.service.AndroidAutoController]). Single source of truth so the two stay
 * in lockstep - they used to drift (2 vs 3).
 *
 * This is the user-facing step, intentionally separate from RemoteControlPlayer.VOLUME_SCALE, which
 * is the MediaSession 0-100 <-> remote-grid scaling (must divide 100) and is a different concern.
 */
const val ROCKER_VOLUME_STEP = 3
