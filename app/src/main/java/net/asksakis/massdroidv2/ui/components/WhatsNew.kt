package net.asksakis.massdroidv2.ui.components

import androidx.annotation.DrawableRes

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
    sinceVersionCode = 40,
    versionName = "2.37.0",
    items = listOf(
        WhatsNewItem(
            title = "Audiobooks in search",
            body = "Audiobooks and podcasts show up when you search, each with its own " +
                "filter, and an audiobook starts straight from the results. The audiobook " +
                "library no longer comes up empty either."
        ),
        WhatsNewItem(
            title = "Search",
            body = "It waits long enough for a slow server to answer instead of reporting " +
                "a failure, and it no longer leaves the previous results under new text."
        ),
        WhatsNewItem(
            title = "Full player",
            body = "It opens all the way to the top however you reach it, the cover keeps " +
                "its shadow while you swipe, and the background has a fine grain."
        ),
        WhatsNewItem(
            title = "Landscape",
            body = "The cover is larger and the controls beside it are no longer pushed " +
                "against the edge."
        ),
        WhatsNewItem(
            title = "Local covers",
            body = "Artwork for local music loads when you reach your server from outside " +
                "your home network."
        )
    )
)
