package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Shared Bluetooth-route helpers. The A2DP/BLE sink type triple and the
 * `bt:NAME` route-key form were duplicated across the volume coordinator, the
 * DI wiring, and the audio controller; centralising them keeps the BT route
 * model in one place.
 */

/** True if [type] is a Bluetooth audio sink (classic A2DP or LE audio). */
internal fun isBluetoothSink(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER

/** True if any connected output device is a Bluetooth sink. */
internal fun AudioManager.anyBluetoothSinkConnected(): Boolean =
    getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isBluetoothSink(it.type) }

/**
 * Product name of the connected Bluetooth sink ONLY when exactly one is present,
 * else null. Used as the route-key fallback while the Oboe stream is settling: with
 * a single sink it is unambiguous, but with multiple connected sinks `firstOrNull`
 * would pick an arbitrary one (maybe not the routed device), so we defer to the
 * authoritative Oboe-routed name instead of guessing.
 */
internal fun AudioManager.soleBluetoothSinkName(): String? =
    getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { isBluetoothSink(it.type) }
        .singleOrNull()
        ?.productName?.toString()
