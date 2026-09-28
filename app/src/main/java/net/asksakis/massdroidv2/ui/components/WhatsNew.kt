package net.asksakis.massdroidv2.ui.components

import androidx.annotation.DrawableRes
import net.asksakis.massdroidv2.R

/**
 * One thing worth telling a returning listener about, in their words rather than ours.
 *
 * This is not the release notes. `WHATSNEW.md` lists everything that changed and goes to
 * the release page; this is the short list of what is worth stopping someone for, and most
 * releases add nothing to it.
 */
data class WhatsNewItem(
    val title: String,
    val body: String,
    /** An animation shown above the text, or null for an entry that needs no picture. */
    @DrawableRes val animation: Int? = null
)

/**
 * What to show, and from which version.
 *
 * [sinceVersionCode] is the version the entries arrived in. Somebody upgrading from
 * anything older sees them; somebody who already ran that version does not. Keeping the
 * version beside the entries rather than in the caller means adding a release is adding
 * one object here.
 */
data class WhatsNewRelease(
    val sinceVersionCode: Int,
    val versionName: String,
    val items: List<WhatsNewItem>
)

/**
 * The current release's highlights.
 *
 * Replace this wholesale on a release that has something to show, and leave it alone on
 * one that does not: an unchanged [WhatsNewRelease.sinceVersionCode] means nobody is
 * interrupted twice for the same news.
 */
val currentWhatsNew = WhatsNewRelease(
    // Bump both on the release that carries the news, and leave them alone otherwise.
    sinceVersionCode = 39,
    versionName = "2.36.0",
    items = listOf(
        WhatsNewItem(
            title = "NFC tags",
            body = "Write an album, a playlist or a speaker onto an NFC tag and leave it " +
                "where you listen. Tapping your phone on it starts that music on that " +
                "speaker, or moves what is already playing into that room, at the volume " +
                "you chose.",
            animation = R.drawable.whatsnew_nfc_tag
        ),
        WhatsNewItem(
            title = "Queue title",
            body = "The queue is named after the playlist or album playing."
        ),
        WhatsNewItem(
            title = "Play All",
            body = "However long the playlist is. Your sort order is kept and blocked " +
                "artists are skipped."
        ),
        WhatsNewItem(
            title = "Library sorting",
            body = "Albums by album artist, tracks by artist or length, playlists by when " +
                "they changed."
        ),
        WhatsNewItem(
            title = "Follow Me rooms",
            body = "Put a player in a Follow Me room without opening the setup. Follow Me " +
                "also scans less and costs less battery."
        ),
        WhatsNewItem(
            title = "Now Playing",
            body = "A swipe lands on the cover you were heading to, and shuffle and repeat " +
                "read as set or unset."
        ),
        WhatsNewItem(
            title = "Search",
            body = "The keyboard's search key runs the search, and Deezer albums no longer " +
                "go missing."
        ),
        WhatsNewItem(
            title = "Playback stability",
            body = "The phone as a speaker holds its connection, and a call pauses the " +
                "music once instead of several times."
        )
    )
)
