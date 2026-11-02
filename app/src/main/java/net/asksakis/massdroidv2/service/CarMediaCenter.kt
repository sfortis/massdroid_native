package net.asksakis.massdroidv2.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent

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
