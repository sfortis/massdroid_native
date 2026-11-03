package net.asksakis.massdroidv2.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import net.asksakis.massdroidv2.BuildConfig
import net.asksakis.massdroidv2.ui.MainActivity

// android.car's Car.CAR_INTENT_ACTION_MEDIA_TEMPLATE and Car.CAR_EXTRA_MEDIA_COMPONENT; the
// android.car library is not on this module's classpath.
private const val ACTION_MEDIA_TEMPLATE = "android.car.intent.action.MEDIA_TEMPLATE"
private const val EXTRA_MEDIA_COMPONENT = "android.car.intent.extra.MEDIA_COMPONENT"

/**
 * Opens the Android Automotive media center showing MassDroid's [PlaybackService]. It starts a
 * task of its own, so a media center with a standard launch mode does not open inside
 * MassDroid's task.
 */
fun carMediaCenterIntent(context: Context): Intent =
    Intent(ACTION_MEDIA_TEMPLATE)
        .putExtra(EXTRA_MEDIA_COMPONENT, ComponentName(context, PlaybackService::class.java).flattenToString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * What opening MassDroid from the media session or a notification starts: MainActivity on the
 * phone, and the media center in the car, where MainActivity is disabled. Sign-in stays reachable
 * in the car through the media center's gear and the signed-out session error.
 */
fun carOrPhoneLaunchIntent(context: Context): Intent =
    if (BuildConfig.IS_AUTOMOTIVE) {
        carMediaCenterIntent(context)
    } else {
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }
