package net.asksakis.massdroidv2.data.sendspin.noise

import android.util.Log
import java.io.IOException
import java.security.SecureRandom

/**
 * Every PSK the client can answer a handshake with, apart from the sentinel: the stored
 * long-term PSKs, plus the two that exist only during a pairing.
 *
 * The pairing PSK is made for one pairing attempt and travels to the server inside the pairing
 * token. The new long-term PSK is made when the server asks the client to finalize. Right after
 * its `server/pair-finalize` ack the server re-handshakes onto that PSK, and the socket reader
 * resolves it at once, possibly before the manager has handled the ack. So the pending PSK is
 * resolvable from the moment it is sent. It is saved on the ack, or on the first handshake that
 * uses it, because the server stores it before acking.
 */
class NoisePairingCredentials(
    private val store: SendspinIdentityStore,
    private val random: SecureRandom = SecureRandom(),
) : PskResolver {

    private class Pending(val serverId: String, val psk: ByteArray, val pskId: String)

    private var pairingPsk: ByteArray? = null
    private var pending: Pending? = null

    /** A fresh pairing PSK for one attempt; it replaces any earlier one. */
    @Synchronized
    fun newPairingPsk(): ByteArray = randomPsk().also { pairingPsk = it }.copyOf()

    /** The long-term PSK to send in `client/pair-finalize` to [serverId]. */
    @Synchronized
    fun beginFinalize(serverId: String): ByteArray {
        val psk = randomPsk()
        pending = Pending(serverId, psk, SendspinNoise.pskId(psk))
        return psk.copyOf()
    }

    /** The server acknowledged the finalize: save the pending PSK. */
    @Synchronized
    fun completeFinalize(serverId: String) {
        val current = pending ?: return
        if (current.serverId != serverId) return
        commit(current)
    }

    /** The pairing attempt ended without a finalize. */
    @Synchronized
    fun abandon() {
        pairingPsk = null
        pending = null
    }

    /** Drops the pairing with [serverId] after `server/unpair`. */
    @Synchronized
    fun forget(serverId: String) {
        if (pending?.serverId == serverId) pending = null
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
        pending?.takeIf { it.pskId == pskId }?.let { current ->
            commit(current)
            return ResolvedPsk(pskId, current.psk.copyOf(), PskCategory.LONG_TERM, current.serverId)
        }
        val pairing = pairingPsk ?: return null
        if (SendspinNoise.pskId(pairing) != pskId) return null
        return ResolvedPsk(pskId, pairing.copyOf(), PskCategory.PAIRING, serverId = null)
    }

    private fun commit(current: Pending) {
        try {
            store.saveLongTermPsk(current.serverId, current.psk)
        } catch (e: IOException) {
            // Kept in memory, so this session still works; the next ack or handshake retries.
            Log.e(TAG, "Cannot save the pairing with ${current.serverId}", e)
            return
        }
        pending = null
        pairingPsk = null
        Log.i(TAG, "Paired with Sendspin server ${current.serverId}")
    }

    private fun randomPsk(): ByteArray = ByteArray(SendspinNoise.PSK_SIZE).also(random::nextBytes)

    private companion object {
        const val TAG = "SendspinPairing"
    }
}
