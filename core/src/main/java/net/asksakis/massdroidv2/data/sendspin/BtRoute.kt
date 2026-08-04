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

/**
 * Whether Bluetooth playback should hold off a Follow Me room switch.
 *
 * The point of the hold is "do not yank the player out from under someone who
 * is listening over Bluetooth", most obviously while driving. Both halves of
 * that matter and both used to be approximated badly: the previous rule asked
 * whether ANY Bluetooth output was *available* and whether the Sendspin client
 * was *connected to the server*.
 *
 * Neither says anything about what is actually happening. Measured on a device:
 * the audio had moved back to the phone speaker five minutes earlier
 * (`Audio route changed: BT -> SPEAKER`), Sendspin sat at `playback=IDLE`, the
 * car lock had already been released - and a confirmed room still refused to
 * select, because a Bluetooth device was merely connected somewhere. That is
 * exactly the inference the project's own rule forbids: the active route comes
 * from the routed device, never from the connected ones.
 *
 * @param routedDeviceType the device the output stream is actually bound to, or
 *   null when nothing is playing.
 * @param audioFlowing whether audio is actually flowing right now, as opposed to
 *   a client that happens to be connected.
 */
fun bluetoothHoldsRoomSwitch(routedDeviceType: Int?, audioFlowing: Boolean): Boolean =
    audioFlowing && routedDeviceType != null && isBluetoothSink(routedDeviceType)

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

/**
 * `bt:NAME` route keys for EVERY connected Bluetooth sink (there can be several during a connect
 * handshake, e.g. a head unit's A2DP + LE endpoints, or the car plus paired buds). Used by the
 * car-audio pin to decide "is a car-flagged device connected?" without depending on the single
 * routed name, which is transiently null while the Oboe stream settles on connect.
 */
internal fun AudioManager.connectedBtSinkKeys(): List<String> =
    getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { isBluetoothSink(it.type) }
        .mapNotNull { it.productName?.toString() }
        .map { "bt:$it" }
