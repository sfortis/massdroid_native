package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import net.asksakis.massdroidv2.data.sendspin.noise.NoisePairingCredentials
import net.asksakis.massdroidv2.data.sendspin.noise.PskCategory

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
) {
    /** What the flow asks of the manager's player machinery. */
    interface Player {
        fun sendHello(trustLevel: String)
        fun startPlayer()
        fun stopPlayer()
        fun resendState()
    }

    private var activeRoles: List<String> = emptyList()
    private var playerActive = false
    private var resyncPending = false

    /** A new connection starts from scratch: no roles, no active player. */
    fun reset() {
        activeRoles = emptyList()
        playerActive = false
        resyncPending = false
    }

    fun onServerHello() {
        val trust = if (client.noisePsk()?.category == PskCategory.LONG_TERM) TRUST_LEVEL_USER else TRUST_LEVEL_NONE
        Log.d(TAG, "server/hello, answering with trust_level=$trust")
        player.sendHello(trust)
    }

    fun onRehandshake() {
        resyncPending = true
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
            NoiseActivationStep.FinalizePairing -> {
                val serverId = client.noiseServerId() ?: return
                client.sendPairFinalize(pairing.beginFinalize(serverId))
            }
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
        }
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
     * and reconnects, which lands on the sentinel PSK.
     */
    fun onUnpair() {
        val psk = client.noisePsk()
        if (psk?.category != PskCategory.LONG_TERM) return
        Log.i(TAG, "server/unpair: forgetting the pairing with ${psk.serverId}")
        psk.serverId?.let(pairing::forget)
        client.sendGoodbye(GOODBYE_REASON_UNPAIRED)
        client.refresh()
    }

    private companion object {
        const val TAG = "SendspinNoise"
    }
}
