package net.asksakis.massdroidv2.auto

import android.content.Context
import android.os.Process
import androidx.media3.session.MediaSession

/**
 * Allow-list of callers permitted to bind the MediaLibraryService.
 *
 * Includes Android Auto host, Google Assistant, AAOS reference media template, and the system.
 * Any other caller is rejected to prevent untrusted apps from enumerating the user's library.
 *
 * Signature pinning (Why: package names alone can be spoofed by sideloaded apps with the same id)
 * is intentionally deferred to a follow-up. Auto-host signatures are stable across releases and
 * can be verified against a trusted SHA-256 fingerprint list (see UAMP allowed_media_browser_callers.xml).
 */
object PackageValidator {

    private val ALLOWED_PACKAGES = setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.embedded.projection",
        "com.google.android.googlequicksearchbox",
        "com.google.android.carassistant",
        "com.google.android.car.media",
        "com.android.car.media",
        "com.android.systemui",
        "android",
    )

    fun isKnownCaller(@Suppress("UNUSED_PARAMETER") context: Context, controller: MediaSession.ControllerInfo): Boolean {
        if (controller.uid == Process.myUid()) return true
        return controller.packageName in ALLOWED_PACKAGES
    }
}
