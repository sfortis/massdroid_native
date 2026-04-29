package net.asksakis.massdroidv2.service

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

object AndroidAutoMediaCommands {
    const val ACTION_TOGGLE_FAVORITE = "net.asksakis.massdroidv2.AA_TOGGLE_FAVORITE"
    const val ACTION_TOGGLE_SHUFFLE = "net.asksakis.massdroidv2.AA_TOGGLE_SHUFFLE"

    val ToggleFavorite = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    val ToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)

    fun buttons(isFavorite: Boolean, shuffleEnabled: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(
            if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName(if (isFavorite) "Unlike" else "Like")
            .setSessionCommand(ToggleFavorite)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(
            if (shuffleEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
            .setSessionCommand(ToggleShuffle)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()
    )
}
