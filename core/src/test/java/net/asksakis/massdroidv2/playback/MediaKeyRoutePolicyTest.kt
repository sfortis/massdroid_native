package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.repository.PlaybackIntentCause
import net.asksakis.massdroidv2.playback.MediaKeyRoutePolicy.Route
import org.junit.Test

/**
 * Media key routing. Issue #88: a Pixel Watch showed the selected remote player, but its keys
 * went to the phone's Sendspin player while Sendspin was up, and were dropped while it was not.
 */
class MediaKeyRoutePolicyTest {

    private val watch = "com.google.android.apps.wear.companion"
    private val bluetooth = "com.google.android.bluetooth"
    private val gearhead = "com.google.android.projection.gearhead"
    private val embeddedProjection = "com.google.android.embedded.projection"
    private val localHardware = "android"
    private val app = "net.asksakis.massdroidv2.debug"
    private val sendspinId = "sendspin-phone"
    private val homeSpeaker = "kitchen"

    private fun route(
        source: String,
        sendspinActive: Boolean = true,
        selected: String? = homeSpeaker,
        shouldRouteToSendspin: Boolean = false,
        fromNotification: Boolean = false
    ): Route = MediaKeyRoutePolicy.route(
        sourcePackage = source,
        appPackage = app,
        fromMediaNotification = fromNotification,
        sendspinActive = sendspinActive,
        sendspinPlayerId = sendspinId,
        selectedPlayerId = selected,
        shouldRouteToSendspin = shouldRouteToSendspin
    )

    /** The issue's first case; the old handler sent this key to Sendspin. */
    @Test
    fun `a watch key with a remote player selected goes to that player while Sendspin is up`() {
        assertThat(route(watch)).isEqualTo(Route.SESSION_PLAYER)
    }

    /** The issue's second case; the old handler dropped this key. */
    @Test
    fun `a watch key goes to the selected player while Sendspin is down`() {
        assertThat(route(watch, sendspinActive = false)).isEqualTo(Route.SESSION_PLAYER)
    }

    @Test
    fun `a watch key goes to Sendspin when Sendspin is the selected player`() {
        assertThat(route(watch, selected = sendspinId)).isEqualTo(Route.SENDSPIN)
    }

    @Test
    fun `any key goes to Sendspin under the Bluetooth rule`() {
        assertThat(route(watch, shouldRouteToSendspin = true)).isEqualTo(Route.SENDSPIN)
        assertThat(route(bluetooth, shouldRouteToSendspin = true)).isEqualTo(Route.SENDSPIN)
    }

    /** #84: a stray headset button must not start or stop a speaker at home. */
    @Test
    fun `a Bluetooth key is dropped when Sendspin is not the target`() {
        assertThat(route(bluetooth, sendspinActive = false)).isEqualTo(Route.DROPPED)
        assertThat(route(bluetooth)).isEqualTo(Route.DROPPED)
        assertThat(route("com.android.bluetooth", sendspinActive = false)).isEqualTo(Route.DROPPED)
    }

    /** The car host's transport commands go to Sendspin whenever it is up; its keys do too. */
    @Test
    fun `an Android Auto key goes to Sendspin whenever Sendspin is up`() {
        assertThat(route(gearhead)).isEqualTo(Route.SENDSPIN)
    }

    @Test
    fun `an Android Auto key is dropped while Sendspin is down`() {
        assertThat(route(gearhead, sendspinActive = false)).isEqualTo(Route.DROPPED)
    }

    @Test
    fun `a notification key goes to the session player`() {
        assertThat(route("com.android.systemui", fromNotification = true)).isEqualTo(Route.SESSION_PLAYER)
        assertThat(route(bluetooth, sendspinActive = false, fromNotification = true)).isEqualTo(Route.SESSION_PLAYER)
    }

    @Test
    fun `Sendspin without a player id is never the target`() {
        val result = MediaKeyRoutePolicy.route(
            sourcePackage = watch,
            appPackage = app,
            fromMediaNotification = false,
            sendspinActive = true,
            sendspinPlayerId = null,
            selectedPlayerId = null,
            shouldRouteToSendspin = true
        )
        assertThat(result).isEqualTo(Route.SESSION_PLAYER)
    }

    /** A wired headset or a steering wheel key went to Sendspin before #88 and still does. */
    @Test
    fun `a local hardware key goes to Sendspin whenever Sendspin is up`() {
        assertThat(route(localHardware)).isEqualTo(Route.SENDSPIN)
        assertThat(route(localHardware, selected = sendspinId)).isEqualTo(Route.SENDSPIN)
        assertThat(route("android.media.session.MediaController")).isEqualTo(Route.SENDSPIN)
    }

    @Test
    fun `a local hardware key is dropped while Sendspin is down`() {
        assertThat(route(localHardware, sendspinActive = false)).isEqualTo(Route.DROPPED)
        assertThat(route(localHardware, sendspinActive = false, selected = null)).isEqualTo(Route.DROPPED)
    }

    /** The media button receiver hands a key over in the app's own name, with no notification data. */
    @Test
    fun `a key in the app's own name is treated like a Bluetooth key`() {
        assertThat(route(app)).isEqualTo(Route.DROPPED)
        assertThat(route(app, sendspinActive = false)).isEqualTo(Route.DROPPED)
        assertThat(route(app, selected = sendspinId)).isEqualTo(Route.SENDSPIN)
        assertThat(route(app, shouldRouteToSendspin = true)).isEqualTo(Route.SENDSPIN)
    }

    @Test
    fun `an embedded projection key behaves like an Android Auto key`() {
        assertThat(route(embeddedProjection)).isEqualTo(Route.SENDSPIN)
        assertThat(route(embeddedProjection, sendspinActive = false)).isEqualTo(Route.DROPPED)
    }

    @Test
    fun `a Bluetooth key goes to Sendspin when Sendspin is the selected player`() {
        assertThat(route(bluetooth, selected = sendspinId)).isEqualTo(Route.SENDSPIN)
    }

    /** The session player applies the Bluetooth rule itself, like for the notification's commands. */
    @Test
    fun `a notification key goes to the session player under the Bluetooth rule`() {
        assertThat(route(app, shouldRouteToSendspin = true, fromNotification = true)).isEqualTo(Route.SESSION_PLAYER)
    }

    @Test
    fun `with no selected player a watch key still goes to the session player while Sendspin is up`() {
        assertThat(route(watch, selected = null)).isEqualTo(Route.SESSION_PLAYER)
    }

    @Test
    fun `a local hardware key is dropped while Sendspin has no player id`() {
        val result = MediaKeyRoutePolicy.route(
            sourcePackage = localHardware,
            appPackage = app,
            fromMediaNotification = false,
            sendspinActive = true,
            sendspinPlayerId = null,
            selectedPlayerId = homeSpeaker,
            shouldRouteToSendspin = false
        )
        assertThat(result).isEqualTo(Route.DROPPED)
    }

    @Test
    fun `a notification key from the app's own package goes to the session player while Sendspin is down`() {
        assertThat(route(app, sendspinActive = false, fromNotification = true)).isEqualTo(Route.SESSION_PLAYER)
    }

    /** M2: the media button receiver's key comes from the notification controller with no URI. */
    @Test
    fun `only a notification controller key with intent data is a notification key`() {
        assertThat(MediaKeyRoutePolicy.isNotificationKey(isNotificationController = true, hasIntentData = true)).isTrue()
        assertThat(MediaKeyRoutePolicy.isNotificationKey(isNotificationController = true, hasIntentData = false)).isFalse()
        assertThat(MediaKeyRoutePolicy.isNotificationKey(isNotificationController = false, hasIntentData = true)).isFalse()
    }

    /** R1: recording the app's own package for a notification key made its pause external. */
    @Test
    fun `a notification key does not record a key source`() {
        assertThat(MediaKeyRoutePolicy.recordsKeySource(Route.SESSION_PLAYER, fromMediaNotification = true)).isFalse()
    }

    @Test
    fun `a watch key routed to the session player records its source`() {
        val watchRoute = route(watch)
        assertThat(watchRoute).isEqualTo(Route.SESSION_PLAYER)
        assertThat(MediaKeyRoutePolicy.recordsKeySource(watchRoute, fromMediaNotification = false)).isTrue()
    }

    @Test
    fun `a dropped key or a Sendspin key records nothing`() {
        assertThat(MediaKeyRoutePolicy.recordsKeySource(Route.DROPPED, fromMediaNotification = false)).isFalse()
        assertThat(MediaKeyRoutePolicy.recordsKeySource(Route.SENDSPIN, fromMediaNotification = false)).isFalse()
    }

    @Test
    fun `the command source names a recorded key's sender, the notification or the controller`() {
        assertThat(MediaKeyRoutePolicy.commandSource(true, app, mediaKeySource = watch)).isEqualTo(watch)
        assertThat(MediaKeyRoutePolicy.commandSource(true, app, mediaKeySource = null))
            .isEqualTo(MediaKeyRoutePolicy.NOTIFICATION_SOURCE)
        assertThat(MediaKeyRoutePolicy.commandSource(false, gearhead, mediaKeySource = watch)).isEqualTo(gearhead)
    }

    @Test
    fun `a pause is the listener's only when it comes from the notification`() {
        assertThat(MediaKeyRoutePolicy.pauseCause(MediaKeyRoutePolicy.NOTIFICATION_SOURCE))
            .isEqualTo(PlaybackIntentCause.LISTENER)
        assertThat(MediaKeyRoutePolicy.pauseCause(watch)).isEqualTo(PlaybackIntentCause.EXTERNAL_CONTROL)
        assertThat(MediaKeyRoutePolicy.pauseCause(null)).isEqualTo(PlaybackIntentCause.EXTERNAL_CONTROL)
    }
}
