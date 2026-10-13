package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import net.asksakis.massdroidv2.data.sendspin.noise.NoisePairingCredentials
import net.asksakis.massdroidv2.data.sendspin.noise.PskCategory
import java.io.IOException

/** The points of a Noise connection that the pairing supervisor acts on. */
sealed class NoiseLinkEvent {
    abstract val serverId: String

    /** Activated on the sentinel PSK: this server holds no pairing with the phone. */
    data class Unpaired(override val serverId: String) : NoiseLinkEvent()

    /** Activated on a long-term PSK: the phone is paired with this server. */
    data class Paired(override val serverId: String) : NoiseLinkEvent()

    /** The server dropped its pairing with the phone (`server/unpair`). */
    data class Revoked(override val serverId: String) : NoiseLinkEvent()
}

/**
 * The manager's side of the Noise transport, after the client has completed the handshake:
 * answering `server/hello`, acting on `server/activate`, finalizing a `pairing_psk` pairing,
 * `server/unpair`, and the repeated hellos after a re-handshake.
 *
 * On Noise the server speaks first. After each handshake it sends `server/hello`, the client
 * answers with its hello, and `server/activate` then says what the connection is for. The player
 * starts on the first activate that names a player role, which replaces the legacy transport's
 * start on `server/hello`.
 *
 * Every method runs on the manager's single message collector, in wire order.
 */
internal class SendspinNoiseFlow(
    private val client: SendspinClient,
    private val pairing: NoisePairingCredentials,
    private val player: Player,
    /** Reports a link event; false when it could not be delivered. */
    private val onLinkEvent: (NoiseLinkEvent) -> Boolean,
) {
    /** What the flow asks of the manager's player machinery. */
    interface Player {
        fun sendHello(trustLevel: String)
        fun startPlayer()
        fun stopPlayer()
        fun resendState()
    }

    // Read off the collector too, by the manager's client/state sends.
    @Volatile private var activeRoles: List<String> = emptyList()
    // The pairing hold and the connection it belongs to. Changed on the collector and by the
    // pairing supervisor, so every change goes through holdLock; see stateHeldForPairing.
    private val holdLock = Any()
    @Volatile private var pairingHold = PairingHold.NONE
    private var connectionEpoch = 0
    private var playerActive = false
    private var resyncPending = false
    // The PSK category last reported through onLinkEvent on this connection.
    private var reportedCategory: PskCategory? = null

    /** Whether the server's last `server/activate` gave this connection a player role. */
    val playerRoleActive: Boolean get() = NoiseActivationStep.hasPlayerRole(activeRoles)

    /**
     * Whether `client/state` is held back because a pairing is under way. aiosendspin quiesces a
     * connection it is about to pair, dropping its roles without a message, and only then
     * re-handshakes. MA approves guest access on the unpaired connection at about the same time,
     * and the phone's first state for that guest player role raced the quiesce: the pairing
     * request goes over the MA socket, the state over the Sendspin one, and the state arrived for
     * a role the server had just dropped (2026-10-12, on both the phone and the TV). The state
     * cannot simply leave the player out either, because the server requires it in the first state
     * of an active player role. So nothing is sent from the first unpaired activate until the
     * activate that follows the pairing's re-handshake, or until the supervisor says no pairing
     * follows.
     */
    val stateHeldForPairing: Boolean get() = pairingHold != PairingHold.NONE

    private enum class PairingHold {
        NONE,

        /** The connection is unpaired and a pairing request is expected or open. */
        PAIRING_EXPECTED,

        /** The pairing re-handshake happened; the roles come with the next activate. */
        AWAIT_ACTIVATE,
    }

    /** A new connection starts from scratch: no roles, no active player, no hold. */
    fun reset() {
        activeRoles = emptyList()
        synchronized(holdLock) {
            pairingHold = PairingHold.NONE
            connectionEpoch++
        }
        playerActive = false
        resyncPending = false
        reportedCategory = null
    }

    fun onServerHello() {
        val trust = if (client.noisePsk()?.category == PskCategory.LONG_TERM) TRUST_LEVEL_USER else TRUST_LEVEL_NONE
        Log.d(TAG, "server/hello, answering with trust_level=$trust")
        player.sendHello(trust)
    }

    fun onRehandshake() {
        resyncPending = true
        // The server drops every role before it re-handshakes and names them again in the next
        // server/activate, so from here the roles are the ones that activate gives.
        activeRoles = emptyList()
        synchronized(holdLock) {
            if (pairingHold == PairingHold.PAIRING_EXPECTED) pairingHold = PairingHold.AWAIT_ACTIVATE
        }
    }

    /**
     * The phone is about to ask MA to pair it; see [stateHeldForPairing]. Returns the connection
     * the attempt belongs to, for [onPairingAttemptEnded]. Safe to call off the collector.
     */
    fun onPairingRequested(): Int = synchronized(holdLock) {
        if (pairingHold == PairingHold.NONE) pairingHold = PairingHold.PAIRING_EXPECTED
        connectionEpoch
    }

    /**
     * A pairing attempt ended. Unless it reached the re-handshake, no pairing is under way any
     * more and the hold ends. An attempt that belonged to an earlier connection changes nothing.
     * Returns whether the hold ended. Safe to call off the collector.
     */
    fun onPairingAttemptEnded(epoch: Int): Boolean = synchronized(holdLock) {
        epoch == connectionEpoch && endHold(PairingHold.PAIRING_EXPECTED)
    }

    /**
     * The supervisor will not pair this connection (the server unpaired the phone in this MA
     * session), so the hold ends. Returns whether it did. Safe to call off the collector.
     */
    fun onPairingHeldBack(): Boolean = endHold(PairingHold.PAIRING_EXPECTED)

    private fun endHold(from: PairingHold): Boolean = synchronized(holdLock) {
        val ends = pairingHold == from
        if (ends) pairingHold = PairingHold.NONE
        ends
    }

    fun onActivate(payload: ServerActivatePayload) {
        val psk = client.noisePsk() ?: return
        payload.activeRoles?.let { activeRoles = it }
        val step = NoiseActivationStep.decide(
            payload = payload,
            pskCategory = psk.category,
            effectiveRoles = activeRoles,
            playerActive = playerActive,
            resync = resyncPending,
        )
        resyncPending = false
        Log.d(TAG, "server/activate activities=${payload.activities} roles=$activeRoles psk=${psk.category} -> $step")
        val pairingStep = step is NoiseActivationStep.FinalizePairing || step is NoiseActivationStep.AbortPairing
        // Before the step, which may start the player and send its first state.
        if (!pairingStep) {
            val firstUnpaired = psk.category == PskCategory.SENTINEL && reportedCategory != PskCategory.SENTINEL
            // Without a server id reportLink reports nothing, and nothing would end the hold.
            if (firstUnpaired && client.noiseServerId() != null) {
                // Unpaired: a pairing follows unless the supervisor holds it back, which it then
                // reports through onPairingHeldBack.
                synchronized(holdLock) { pairingHold = PairingHold.PAIRING_EXPECTED }
            } else {
                endHold(PairingHold.AWAIT_ACTIVATE)
            }
        }
        when (step) {
            NoiseActivationStep.FinalizePairing -> finalizePairing()
            is NoiseActivationStep.AbortPairing -> {
                pairing.abandon()
                client.sendPairAbort(step.reason)
            }
            NoiseActivationStep.StartPlayer -> {
                playerActive = true
                player.startPlayer()
            }
            NoiseActivationStep.ResendState -> player.resendState()
            NoiseActivationStep.StopPlayer -> {
                playerActive = false
                player.stopPlayer()
            }
            NoiseActivationStep.None -> if (!playerActive) {
                Log.i(TAG, "Connected without a player role: waiting for pairing or approval in MA")
            }
            is NoiseActivationStep.Reject -> {
                Log.w(TAG, "server/activate not admissible on the ${psk.category} PSK, saying goodbye")
                if (playerActive) {
                    playerActive = false
                    player.stopPlayer()
                }
                client.sendGoodbye(step.reason)
                return
            }
        }
        if (!pairingStep) reportLink(psk.category)
    }

    /** Reports the first activation per PSK category on this connection. */
    private fun reportLink(category: PskCategory) {
        if (category == reportedCategory) return
        val serverId = client.noiseServerId() ?: return
        val event = when (category) {
            PskCategory.SENTINEL -> NoiseLinkEvent.Unpaired(serverId)
            PskCategory.LONG_TERM -> NoiseLinkEvent.Paired(serverId)
            PskCategory.PAIRING -> return
        }
        reportedCategory = category
        if (!onLinkEvent(event) && category == PskCategory.SENTINEL) {
            // No supervisor will pair this connection or hold it back, so nothing else would end
            // the hold.
            if (endHold(PairingHold.PAIRING_EXPECTED) && playerActive) player.resendState()
        }
    }

    private fun finalizePairing() {
        val serverId = client.noiseServerId() ?: return
        val psk = try {
            pairing.beginFinalize(serverId)
        } catch (e: IOException) {
            // Unsaved, it must not be sent; the server's attempt times out and the next connect retries.
            Log.e(TAG, "Cannot save the new pairing, not finalizing", e)
            return
        }
        client.sendPairFinalize(psk)
    }

    fun onPairFinalize() {
        client.noiseServerId()?.let(pairing::completeFinalize)
    }

    fun onPairAbort(reason: String) {
        Log.i(TAG, "Pairing aborted by the server: $reason")
        pairing.abandon()
    }

    /**
     * The server dropped the pairing. Only a connection on a long-term PSK can be unpaired; on
     * any other the message is ignored, as the spec says. The client forgets the PSK, says goodbye
     * and reconnects, which lands on the sentinel PSK. It does not pair again until the user asks.
     */
    fun onUnpair() {
        val psk = client.noisePsk()
        if (psk?.category != PskCategory.LONG_TERM) return
        Log.i(TAG, "server/unpair: forgetting the pairing with ${psk.serverId}")
        psk.serverId?.let { serverId ->
            pairing.forget(serverId)
            // Reported before the reconnect, so the supervisor knows not to pair again by itself.
            onLinkEvent(NoiseLinkEvent.Revoked(serverId))
        }
        client.sendGoodbye(GOODBYE_REASON_UNPAIRED)
        client.refresh()
    }

    private companion object {
        const val TAG = "SendspinNoise"
    }
}
