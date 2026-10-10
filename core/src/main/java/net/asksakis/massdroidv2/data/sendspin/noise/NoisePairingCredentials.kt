package net.asksakis.massdroidv2.data.sendspin.noise

import android.util.Log
import java.io.IOException
import java.security.SecureRandom

/**
 * Every PSK the client can answer a handshake with, apart from the sentinel: the stored
 * long-term PSKs, plus the pairing PSK of an attempt in progress.
 *
 * The pairing PSK is made for one attempt, travels to the server inside the pairing token, and
 * lives only in memory. The long-term PSK made at finalize is saved before `client/pair-finalize`
 * goes out, as the server saves its side before acking. Saving it only after the ack would leave a
 * gap: if the app died after the server saved the record but before the client did, the server
 * would offer a PSK the client no longer has, and every handshake would fail until an operator
 * unpaired the phone in MA.
 *
 * Replacing a stored PSK there is safe. Pairing only starts from a sentinel connection, and the
 * server picks the sentinel only when it holds no pairing with this client, so an older PSK for
 * that server is already dead. If the finalize never lands, the saved PSK simply goes unused.
 */
class NoisePairingCredentials(
    private val store: SendspinIdentityStore,
    private val random: SecureRandom = SecureRandom(),
) : PskResolver {

    private var pairingPsk: ByteArray? = null

    /** A fresh pairing PSK for one attempt; it replaces any earlier one. */
    @Synchronized
    fun newPairingPsk(): ByteArray = randomPsk().also { pairingPsk = it }.copyOf()

    /**
     * Makes and saves the long-term PSK to send in `client/pair-finalize` to [serverId].
     *
     * @throws IOException when it cannot be saved; it must not be sent then.
     */
    @Synchronized
    fun beginFinalize(serverId: String): ByteArray {
        val psk = randomPsk()
        store.saveLongTermPsk(serverId, psk)
        return psk.copyOf()
    }

    /** The server acknowledged the finalize; the pairing PSK has served its purpose. */
    @Synchronized
    fun completeFinalize(serverId: String) {
        pairingPsk = null
        Log.i(TAG, "Paired with Sendspin server $serverId")
    }

    /** The pairing attempt ended without a finalize. */
    @Synchronized
    fun abandon() {
        pairingPsk = null
    }

    /** Drops the pairing with [serverId] after `server/unpair`. */
    @Synchronized
    fun forget(serverId: String) {
        try {
            store.removeLongTermPsk(serverId)
        } catch (e: IOException) {
            // The server has dropped its side, so a stale PSK here is only unused.
            Log.e(TAG, "Cannot remove the pairing with $serverId", e)
        }
    }

    @Synchronized
    override fun resolve(pskId: String): ResolvedPsk? {
        store.resolve(pskId)?.let { return it }
        val pairing = pairingPsk ?: return null
        if (SendspinNoise.pskId(pairing) != pskId) return null
        return ResolvedPsk(pskId, pairing.copyOf(), PskCategory.PAIRING, serverId = null)
    }

    private fun randomPsk(): ByteArray = ByteArray(SendspinNoise.PSK_SIZE).also(random::nextBytes)

    private companion object {
        const val TAG = "SendspinPairing"
    }
}
