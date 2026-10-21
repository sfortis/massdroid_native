package net.asksakis.massdroidv2.auto

import android.content.Context
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * Decides which callers of the MediaLibraryService get the full command set.
 *
 * A caller is known when it is this app, one of the hosts below (Android Auto, Google
 * Assistant, the AAOS media center and the system), or a controller Media3 reports as
 * trusted. Media3 trusts the system, holders of MEDIA_CONTENT_CONTROL and apps the user
 * enabled as a notification listener, the platform's own rule for who may control media
 * sessions. That covers controllers such as AAIdrive and the Galaxy Watch plugin without
 * naming them, because they need notification access to work at all. The session gives an
 * unknown caller transport controls only, so it can neither read the library nor start
 * playback of an item.
 *
 * A package name alone can be taken by a sideloaded app while the real one is not
 * installed. Pinning the hosts' signing certificates (see UAMP
 * allowed_media_browser_callers.xml) is deferred to a follow-up.
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

    // ControllerInfo.isTrusted is marked unstable in Media3; it carries the answer of
    // androidx.media's MediaSessionManager.isTrustedForMediaControl for the caller.
    @OptIn(UnstableApi::class)
    fun isKnownCaller(@Suppress("UNUSED_PARAMETER") context: Context, controller: MediaSession.ControllerInfo): Boolean {
        if (controller.uid == Process.myUid()) return true
        return controller.packageName in ALLOWED_PACKAGES || controller.isTrusted
    }
}
