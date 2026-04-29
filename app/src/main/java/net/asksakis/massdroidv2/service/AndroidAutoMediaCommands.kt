package net.asksakis.massdroidv2.service

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

object AndroidAutoMediaCommands {
    const val ACTION_TOGGLE_FAVORITE = "net.asksakis.massdroidv2.AA_TOGGLE_FAVORITE"
    const val ACTION_TOGGLE_SHUFFLE = "net.asksakis.massdroidv2.AA_TOGGLE_SHUFFLE"

    val ToggleFavorite = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    val ToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)

    /**
     * Use ICON_UNDEFINED so the AA host treats the supplied iconResId as authoritative; passing
     * ICON_HEART_FILLED / ICON_SHUFFLE_ON lets gearhead substitute its own mapping (we observed
     * a flag rendered for HEART and a rewind icon for SHUFFLE on some heads). The drawables come
     * from media3-session itself — the same assets the stock Media3 player uses.
     *
     * setIconResId is deprecated in 1.7+ in favour of setIconUri (which needs a Context to build
     * a content URI). Stays in until we bump compileSdk to 36 / Media3 1.10.
     */
    @Suppress("DEPRECATION")
    fun buttons(isFavorite: Boolean, shuffleEnabled: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(if (isFavorite) "Unlike" else "Like")
            .setIconResId(
                if (isFavorite) androidx.media3.session.R.drawable.media3_icon_heart_filled
                else androidx.media3.session.R.drawable.media3_icon_heart_unfilled
            )
            .setSessionCommand(ToggleFavorite)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
            .setIconResId(
                if (shuffleEnabled) androidx.media3.session.R.drawable.media3_icon_shuffle_on
                else androidx.media3.session.R.drawable.media3_icon_shuffle_off
            )
            .setSessionCommand(ToggleShuffle)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    )
}
