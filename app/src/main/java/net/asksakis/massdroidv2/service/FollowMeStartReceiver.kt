package net.asksakis.massdroidv2.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.proximity.ProximityConfigStore

/**
 * Brings Follow Me back after a reboot or an app update.
 *
 * A START_STICKY restart of [FollowMeService] after an update runs from the background, and
 * Android 12+ refuses its `startForeground` (that refusal used to crash the app, see
 * `FollowMeService.enterForeground`). These two broadcasts are the platform's own exemptions
 * from that rule, so a service started from here may go foreground. The config is loaded
 * first: a disabled Follow Me must never leave a `startForegroundService` without a matching
 * `startForeground`, which the platform treats as a crash of its own.
 */
class FollowMeStartReceiver : BroadcastReceiver() {

    /** Read from the singleton graph directly; a receiver is too short-lived for field injection. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun proximityConfigStore(): ProximityConfigStore
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val proximityConfigStore = EntryPointAccessors
            .fromApplication(context.applicationContext, Dependencies::class.java)
            .proximityConfigStore()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                proximityConfigStore.load()
                if (proximityConfigStore.config.value.enabled) {
                    FollowMeService.startFromExemption(context, action.substringAfterLast('.'))
                } else {
                    Log.d(TAG, "Follow Me disabled, not starting after ${action.substringAfterLast('.')}")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FollowMeStart"
    }
}
