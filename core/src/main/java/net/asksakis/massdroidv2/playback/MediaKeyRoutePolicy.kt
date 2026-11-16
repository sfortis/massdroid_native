package net.asksakis.massdroidv2.playback

import net.asksakis.massdroidv2.domain.repository.PlaybackIntentCause

/**
 * Where a media key event that reaches the media session goes: a headset or car button, a
 * watch, or another app that sends key events instead of transport commands.
 *
 * A key from the phone's own hardware (a wired headset, a USB keyboard, an AAOS steering
 * wheel) goes to Sendspin whenever Sendspin is up and is dropped otherwise, as it did before
 * #88. Any other key goes to Sendspin under the same condition as a transport command: the
 * coordinator's Bluetooth rule, the Sendspin player being the selected one, or a key from the
 * Android Auto host while Sendspin is up. Otherwise it goes to the session player, which drives
 * the selected player, except a key from the Bluetooth stack, the Android Auto host or this app
 * itself (the media button receiver while the service is down, or Media3 sending a key on):
 * those are dropped, so a stray headset or car button cannot start or stop a speaker at home
 * (#84). A watch shows the selected player's session, so its keys go to that player (#88);
 * there is deliberately no list of watch apps, which would miss every watch not on it.
 */
object MediaKeyRoutePolicy {

    enum class Route {
        /** Sendspin's own transport handles the key. */
        SENDSPIN,

        /** Media3's default handling runs it on the session player, which drives the selected player. */
        SESSION_PLAYER,

        /** The key is consumed and does nothing. */
        DROPPED
    }

    /** The Bluetooth stack: AVRCP keys from a headset or a car's head unit. */
    val BLUETOOTH_STACK_PACKAGES = setOf("com.google.android.bluetooth", "com.android.bluetooth")

    /**
     * The phone's own input devices, whose keys the platform dispatches as "android". On API 26
     * and 27 Media3 cannot read a key's caller, so every key there, a watch's included, reports
     * the legacy controller name below and follows this rule. #88 therefore stays unfixed on
     * those versions, by the owner's decision to keep the old behaviour for hardware keys.
     */
    val LOCAL_HARDWARE_PACKAGES = setOf("android", "android.media.session.MediaController")

    /** How a session command from the app's own media notification names its sender. */
    const val NOTIFICATION_SOURCE = "the media notification"

    /** The Android Auto host on the phone, and its embedded variant. */
    val CAR_HOST_PACKAGES = setOf("com.google.android.projection.gearhead", "com.google.android.embedded.projection")

    /**
     * @param appPackage this app's package, which a key carries when the media button receiver
     * or Media3 hands it to the session.
     * @param fromMediaNotification a button of the app's media notification; only its intents
     * carry the session's URI.
     * @param shouldRouteToSendspin the coordinator's rule: Bluetooth A2DP with another player
     * selected, or the AAOS build with Sendspin up.
     */
    @Suppress("LongParameterList")
    fun route(
        sourcePackage: String,
        appPackage: String,
        fromMediaNotification: Boolean,
        sendspinActive: Boolean,
        sendspinPlayerId: String?,
        selectedPlayerId: String?,
        shouldRouteToSendspin: Boolean
    ): Route {
        // The notification's buttons stand for the session player, like its transport commands.
        if (fromMediaNotification) return Route.SESSION_PLAYER
        val sendspinUp = sendspinActive && sendspinPlayerId != null
        if (sourcePackage in LOCAL_HARDWARE_PACKAGES) return if (sendspinUp) Route.SENDSPIN else Route.DROPPED
        val fromCarHost = sourcePackage in CAR_HOST_PACKAGES
        val sendspinIsTarget = sendspinUp &&
            (shouldRouteToSendspin || selectedPlayerId == sendspinPlayerId || fromCarHost)
        return when {
            sendspinIsTarget -> Route.SENDSPIN
            sourcePackage in BLUETOOTH_STACK_PACKAGES || fromCarHost || sourcePackage == appPackage -> Route.DROPPED
            else -> Route.SESSION_PLAYER
        }
    }

    /**
     * Whether a key is a button of the app's media notification. Media3 puts the session URI on
     * its notification's intents; the media button receiver and the platform send none, and also
     * come from the notification controller.
     */
    fun isNotificationKey(isNotificationController: Boolean, hasIntentData: Boolean): Boolean =
        isNotificationController && hasIntentData

    /**
     * Whether the key's sender must be recorded before Media3 runs it on the session player, which
     * Media3 does as its notification controller. A notification key keeps the notification as
     * its source, so its pause stays the listener's own.
     */
    fun recordsKeySource(route: Route, fromMediaNotification: Boolean): Boolean =
        route == Route.SESSION_PLAYER && !fromMediaNotification

    /**
     * Who sent the session command being handled. The notification controller runs in this
     * process, so it is named by the key it runs for, or as the notification itself.
     */
    fun commandSource(isNotificationController: Boolean, controllerPackage: String, mediaKeySource: String?): String =
        if (isNotificationController) mediaKeySource ?: NOTIFICATION_SOURCE else controllerPackage

    /**
     * The cause a session pause carries. The app's own media notification is the listener; every
     * other sender (a car head unit, a watch) is external, and its pause during an interruption
     * keeps the resume owed to it, see [AudioFocusPolicy.pauseIsListenerDecision].
     */
    fun pauseCause(commandSource: String?): PlaybackIntentCause =
        if (commandSource == NOTIFICATION_SOURCE) PlaybackIntentCause.LISTENER else PlaybackIntentCause.EXTERNAL_CONTROL
}
