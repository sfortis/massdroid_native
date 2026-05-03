package net.asksakis.massdroidv2.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

object AndroidAutoMediaCommands {
    const val ACTION_TOGGLE_FAVORITE = "net.asksakis.massdroidv2.AA_TOGGLE_FAVORITE"
    const val ACTION_TOGGLE_SHUFFLE = "net.asksakis.massdroidv2.AA_TOGGLE_SHUFFLE"

    val ToggleFavorite = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    val ToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)

    /**
     * ICON_UNDEFINED forces the AA host to render our drawable instead of substituting its own
     * asset for the standard icon constants — gearhead was rendering a flag for
     * ICON_HEART_FILLED and a rewind glyph for ICON_SHUFFLE_ON on some head units.
     *
     * Both setIconResId (for the legacy MediaSessionCompat compat path that requires a resource
     * id when building a CustomAction — Media3 throws otherwise) and setIconUri (for the modern
     * path) are provided. The drawables come straight from media3-session, the same assets the
     * stock Media3 player uses.
     */
    @Suppress("DEPRECATION")
    fun buttons(context: Context, isFavorite: Boolean, shuffleEnabled: Boolean): List<CommandButton> {
        val heartResId = if (isFavorite) {
            androidx.media3.session.R.drawable.media3_icon_heart_filled
        } else {
            androidx.media3.session.R.drawable.media3_icon_heart_unfilled
        }
        val shuffleResId = if (shuffleEnabled) {
            androidx.media3.session.R.drawable.media3_icon_shuffle_on
        } else {
            androidx.media3.session.R.drawable.media3_icon_shuffle_off
        }
        return listOf(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setDisplayName(if (isFavorite) "Unlike" else "Like")
                .setIconResId(heartResId)
                .setIconUri(resourceUri(context, heartResId))
                .setSessionCommand(ToggleFavorite)
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
                .setIconResId(shuffleResId)
                .setIconUri(resourceUri(context, shuffleResId))
                .setSessionCommand(ToggleShuffle)
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build()
        )
    }

    private fun resourceUri(context: Context, resId: Int): Uri =
        Uri.Builder()
            .scheme("android.resource")
            .authority(context.packageName)
            .appendPath(resId.toString())
            .build()
}
