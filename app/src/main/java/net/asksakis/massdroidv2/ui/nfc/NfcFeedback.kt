package net.asksakis.massdroidv2.ui.nfc

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View

private const val TAG = "NfcFeedback"

/**
 * What the phone says when a tag is written.
 *
 * Reading a tag needs nothing from us: the platform's own tag sound already plays, because
 * the tap arrives through the ordinary dispatcher. Writing is silent by comparison, since
 * the write prompt holds the reader with the platform sounds turned off, and the hand
 * holding the phone against a tag cannot see the screen. So the confirmation is a short
 * tone and a buzz, and it fires on the outcome rather than on the tag, which is the whole
 * point: a tag that was seen but not written must not sound like one that was.
 *
 * No sound file and no vibrate permission. The tone comes from the platform's own
 * generator and the buzz from the view's haptic feedback, both of which respect the
 * phone's silent and vibration settings without being asked to.
 */
object NfcFeedback {

    /** The tag went on. A rising two-note acknowledgement and a confirm buzz. */
    fun written(view: View) {
        playTone(ToneGenerator.TONE_PROP_ACK, ACK_MS)
        view.performHapticFeedback(constantFor(confirm = true))
    }

    /** The tag did not go on, for any reason. A flat tone and a reject buzz. */
    fun failed(view: View) {
        playTone(ToneGenerator.TONE_PROP_NACK, NACK_MS)
        view.performHapticFeedback(constantFor(confirm = false))
    }

    /**
     * On the notification stream rather than the music one, so a confirmation never ducks
     * or interrupts what is playing, and stays quiet when the phone is silenced.
     *
     * `startTone` only schedules the tone, so the generator is released on the main looper
     * once it has had time to finish rather than straight away, which would cut it off.
     */
    private fun playTone(tone: Int, durationMs: Int) {
        val generator = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
        } catch (e: RuntimeException) {
            // A device with every tone slot taken throws rather than staying quiet, and a
            // missing confirmation is not worth taking the write down with it.
            Log.w(TAG, "Could not open the tone generator: ${e.message}")
            return
        }
        generator.startTone(tone, durationMs)
        Handler(Looper.getMainLooper()).postDelayed(
            { runCatching { generator.release() } },
            (durationMs + TONE_RELEASE_GRACE_MS).toLong()
        )
    }

    /**
     * `CONFIRM` and `REJECT` say what happened rather than that something was touched, and
     * phones render them as distinct patterns. They arrived in API 30, so older phones get
     * the long press buzz that every button in the app already uses.
     */
    private fun constantFor(confirm: Boolean): Int = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> HapticFeedbackConstants.LONG_PRESS
        confirm -> HapticFeedbackConstants.CONFIRM
        else -> HapticFeedbackConstants.REJECT
    }

    /** Below full, so a confirmation at arm's length is heard without being startling. */
    private const val TONE_VOLUME = 80
    private const val ACK_MS = 200
    private const val NACK_MS = 300
    private const val TONE_RELEASE_GRACE_MS = 100
}
