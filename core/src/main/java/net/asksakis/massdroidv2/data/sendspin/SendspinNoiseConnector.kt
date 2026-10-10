package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.sendspin.noise.NoisePairingCredentials
import net.asksakis.massdroidv2.data.sendspin.noise.SendspinIdentityStore
import net.asksakis.massdroidv2.data.sendspin.noise.SendspinPairingToken
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.PairWebPlayerArgs
import net.asksakis.massdroidv2.data.websocket.sendCommand
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pairs the phone with MA's Sendspin server and moves the old player's settings, the way MA's own
 * web player does: no PIN and no user step.
 *
 * When a Noise connection comes up on the sentinel PSK, the server holds no pairing with the
 * phone. The phone then makes a pairing PSK, puts it with its public key into a pairing token,
 * and hands the token to MA with `sendspin/pair_web_player` over the authenticated MA API. MA
 * stages the PSK and runs the pairing on the Sendspin connection, where [SendspinNoiseFlow]
 * finalizes it; the command answers once that is done.
 *
 * When a connection comes up on a long-term PSK and an old player id is still waiting to be
 * migrated, [SendspinPlayerMigration] moves its settings onto the new player.
 */
@Singleton
class SendspinNoiseConnector @Inject constructor(
    private val sendspinManager: SendspinManager,
    private val identityStore: SendspinIdentityStore,
    private val credentials: NoisePairingCredentials,
    private val migration: SendspinPlayerMigration,
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
) {
    /** Acts on Noise connection events until cancelled; runs for as long as Sendspin does. */
    suspend fun superviseLink() {
        sendspinManager.noiseLinkEvents.collect { event ->
            when (event) {
                is NoiseLinkEvent.Unpaired -> pair(event.serverId)
                is NoiseLinkEvent.Paired -> migrateIfPending()
            }
        }
    }

    private suspend fun pair(serverId: String) {
        if (!awaitMaConnected()) {
            Log.w(TAG, "MA is not connected; pairing with $serverId waits for the next connection")
            return
        }
        val token = try {
            withContext(Dispatchers.IO) {
                SendspinPairingToken.encode(identityStore.identity().publicKey, credentials.newPairingPsk())
            }
        } catch (e: IOException) {
            Log.e(TAG, "Sendspin identity unavailable, cannot pair", e)
            return
        }
        Log.i(TAG, "Pairing with Sendspin server $serverId")
        try {
            wsClient.sendCommand(MaCommands.Sendspin.PAIR_WEB_PLAYER, PairWebPlayerArgs(token), timeoutMs = PAIR_TIMEOUT_MS)
        } catch (e: MaApiException) {
            // The connection stays up without a player; the next connection tries again.
            credentials.abandon()
            Log.w(TAG, "Pairing with $serverId failed: ${e.message}")
        }
    }

    private suspend fun migrateIfPending() {
        val source = settingsRepository.sendspinMigrationSourceId.first() ?: return
        val target = withContext(Dispatchers.IO) { identityStore.identity().peerId }
        if (source == target) {
            settingsRepository.setSendspinMigrationSourceId(null)
            return
        }
        if (!awaitMaConnected()) return
        val outcome = try {
            migration.migrate(fromId = source, toId = target)
        } catch (e: MaApiException) {
            Log.w(TAG, "Player migration from $source failed, retrying on the next connection: ${e.message}")
            return
        }
        Log.i(TAG, "Player migration from $source: $outcome")
        // Kept when this server never knew the old player: another server may still have it.
        if (outcome == SendspinPlayerMigration.Outcome.MOVED) {
            settingsRepository.setSendspinMigrationSourceId(null)
        }
    }

    private suspend fun awaitMaConnected(): Boolean = withTimeoutOrNull(MA_CONNECT_WAIT_MS) {
        wsClient.connectionState.first { it is ConnectionState.Connected }
    } != null

    private companion object {
        const val TAG = "SendspinPairing"

        // The command answers after the whole exchange: MA waits up to 10 s for the
        // client to register, then runs a re-handshake, the finalize and another one.
        const val PAIR_TIMEOUT_MS = 45_000L
        const val MA_CONNECT_WAIT_MS = 30_000L
    }
}
