package net.asksakis.massdroidv2.data.whatsnew

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.asksakis.massdroidv2.BuildConfig
import net.asksakis.massdroidv2.domain.whatsnew.WHATS_NEW_NOTES_ASSET
import net.asksakis.massdroidv2.domain.whatsnew.WhatsNewParser
import net.asksakis.massdroidv2.domain.whatsnew.WhatsNewRelease
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WhatsNew"

/**
 * The current release's news, read from the release notes that shipped with the app.
 *
 * There is no second copy of this text in the source: `WHATSNEW.md` at the root of the
 * repo is written once, goes to the GitHub release page, and is copied into the assets by
 * the build. Reading it from the assets rather than over the network matters because the
 * sheet appears on the first launch after an update, which is exactly when depending on
 * the network would be worst.
 */
@Singleton
class WhatsNewRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** The release notes, or null when the asset is missing or has nothing in it. */
    suspend fun load(): WhatsNewRelease? = withContext(Dispatchers.IO) {
        val markdown = try {
            context.assets.open(WHATS_NEW_NOTES_ASSET).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.w(TAG, "Release notes asset missing", e)
            return@withContext null
        }
        WhatsNewParser.parse(markdown, BuildConfig.VERSION_NAME).takeUnless { it.isEmpty }
    }
}
