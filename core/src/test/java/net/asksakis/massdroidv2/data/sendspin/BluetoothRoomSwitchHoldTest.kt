package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins when Bluetooth is allowed to hold off a Follow Me room switch.
 *
 * The rule exists for one situation: someone is listening over Bluetooth, most
 * obviously driving, and a room being recognised must not pull the player out
 * from under them. Everything else has to let the switch through.
 *
 * It is worth pinning because getting it wrong is invisible and permanent. The
 * previous version asked whether ANY Bluetooth output was available and whether
 * the Sendspin client was connected to the server - neither of which describes
 * what is playing. Measured on a device: audio had returned to the phone
 * speaker five minutes earlier, Sendspin was idle, the car lock was already
 * released, and a room confirmed with full confidence still refused to select,
 * every day, because a Bluetooth device was connected somewhere.
 */
class BluetoothRoomSwitchHoldTest {

    @Test
    fun `listening over bluetooth holds the switch`() {
        assertThat(
            bluetoothHoldsRoomSwitch(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, audioFlowing = true)
        ).isTrue()
    }

    @Test
    fun `LE audio counts as bluetooth too`() {
        assertThat(bluetoothHoldsRoomSwitch(AudioDeviceInfo.TYPE_BLE_HEADSET, true)).isTrue()
        assertThat(bluetoothHoldsRoomSwitch(AudioDeviceInfo.TYPE_BLE_SPEAKER, true)).isTrue()
    }

    @Test
    fun `bluetooth that is not playing does not hold anything`() {
        // Parked, walked inside, buds still connected in a pocket.
        assertThat(
            bluetoothHoldsRoomSwitch(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, audioFlowing = false)
        ).isFalse()
    }

    @Test
    fun `playing through the phone speaker does not hold anything`() {
        // The measured case: the route had already moved off Bluetooth.
        assertThat(
            bluetoothHoldsRoomSwitch(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audioFlowing = true)
        ).isFalse()
    }

    @Test
    fun `wired headphones do not hold anything`() {
        assertThat(
            bluetoothHoldsRoomSwitch(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, audioFlowing = true)
        ).isFalse()
    }

    @Test
    fun `no route at all does not hold anything`() {
        // Nothing is playing, so there is nobody to protect.
        assertThat(bluetoothHoldsRoomSwitch(null, audioFlowing = false)).isFalse()
        assertThat(bluetoothHoldsRoomSwitch(null, audioFlowing = true)).isFalse()
    }
}
