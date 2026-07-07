package net.asksakis.massdroidv2.auto

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media.utils.MediaConstants

/**
 * AA / AAOS browse-tree extras builders.
 *
 * These keys are read by the legacy MediaBrowserCompat consumer that the Auto host on the phone
 * still uses, even when the service is a Media3 MediaLibraryService. Without
 * BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED the search button never appears in Auto, even if
 * onSearch is implemented (androidx/media#645).
 */
object AutoBrowseExtras {

    /**
     * Root LibraryParams extras: enables the search affordance and asks Auto to render root
     * browse categories as icon tiles. Hosts can still choose a stricter layout, but without this
     * hint Android Auto defaults to the plain category list.
     */
    fun rootExtras(): Bundle = Bundle().apply {
        putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
        // Root as a single LIST, not a category grid: the gearhead grid caps the
        // root at ~4 tiles and folds the rest into an auto-generated "More",
        // which then wraps our own entries (a confusing nested "More"). A list
        // has no such cap, so every category shows in one logical sequence.
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
    }

    /**
     * Content URI for a per-item initials placeholder, served by PlaceholderArtworkProvider.
     * Mirrors the in-app InitialsBox style so missing-artwork items render consistently across
     * phone and AA. Same name → same color, deterministic palette via hashCode.
     */
    fun placeholderArtworkUri(context: Context, name: String): Uri =
        PlaceholderArtworkProvider.uri("${context.packageName}.placeholder", name)

    /**
     * Per-item extras that mark a row as the start of a labeled group in search results.
     * AA renders the title above the row when contiguous siblings share the same group title
     * (per doc §3.5 / DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE).
     */
    fun groupTitleExtras(title: String): Bundle = Bundle().apply {
        putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title)
    }

    fun rootTileExtras(groupTitle: String): Bundle = Bundle().apply {
        putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )
    }

    fun listItemExtras(): Bundle = Bundle().apply {
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
    }

    /**
     * Marks a browsable node so its CHILDREN render as a GRID of square/visual tiles
     * (covers, artist circles) - used for Albums/Artists, whose contents are visual.
     * SINGLE_ITEM is LIST on purpose: the category itself stays a normal list ROW in
     * its parent (the Library tab), so the Library list looks uniform; only the
     * children (the actual albums/artists) become a grid when you open the category.
     */
    fun gridItemExtras(): Bundle = Bundle().apply {
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )
        putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
    }
}
