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
    private val onLinkEvent: (NoiseLinkEvent) -> Unit,
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
    // Set while a pairing the phone asked for has not reached its re-handshake; see onPairingRequested.
    @Volatile private var pairingRequested = false
    private var playerActive = false
    private var resyncPending = false
    // The PSK category last reported through onLinkEvent on this connection.
    private var reportedCategory: PskCategory? = null

    /**
     * Whether a `client/state` may carry the player object: the server's last `server/activate`
     * gave this connection a player role, and no pairing is about to take it away. aiosendspin
     * flags a player object sent while the role is inactive.
     */
    val playerRoleActive: Boolean get() = !pairingRequested && NoiseActivationStep.hasPlayerRole(activeRoles)

    /** A new connection starts from scratch: no roles, no active player. */
    fun reset() {
        activeRoles = emptyList()
        pairingRequested = false
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
        pairingRequested = false
    }

    /**
     * The phone has asked MA to pair it. aiosendspin will quiesce the connection, dropping its
     * roles without a message, and only then re-handshake. MA approves guest access on the same
     * connection at about the same time, and its server/activate with a player role can land
     * before the quiesce: the phone then reported its first state for a role the server had just
     * dropped (2026-10-12). Until the re-handshake, or [onPairingRequestEnded], no state carries
     * the player object. Safe to call off the collector.
     */
    fun onPairingRequested() {
        pairingRequested = true
    }

    /** The pairing request returned, done or failed; see [onPairingRequested]. */
    fun onPairingRequestEnded() {
        pairingRequested = false
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
        if (step !is NoiseActivationStep.FinalizePairing && step !is NoiseActivationStep.AbortPairing) {
            reportLink(psk.category)
        }
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
        onLinkEvent(event)
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
