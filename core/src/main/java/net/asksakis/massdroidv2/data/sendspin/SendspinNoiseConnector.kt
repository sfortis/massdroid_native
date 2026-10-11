package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.data.sendspin.noise.NoiseClientSession
import net.asksakis.massdroidv2.data.sendspin.noise.NoisePairingCredentials
import net.asksakis.massdroidv2.data.sendspin.noise.SendspinIdentityStore
import net.asksakis.massdroidv2.data.sendspin.noise.SendspinPairingToken
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.PairWebPlayerArgs
import net.asksakis.massdroidv2.data.websocket.sendCommand
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import java.io.IOException
import java.util.UUID
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
 * migrated, [SendspinPlayerMigration] moves its settings onto the new player. The old player is
 * removed once MA has unregistered it, which can take a few minutes after an app update.
 *
 * It also decides, before every start, whether the connection uses Noise at all ([planStart]).
 */
@Singleton
class SendspinNoiseConnector @Inject constructor(
    private val sendspinManager: SendspinManager,
    private val identityStore: SendspinIdentityStore,
    private val credentials: NoisePairingCredentials,
    private val migration: SendspinPlayerMigration,
    private val wsClient: MaWebSocketClient,
    private val settingsRepository: SettingsRepository,
    private val playerRepository: PlayerRepository,
) {
    /** The player id to start Sendspin with and, on MA 2.10.0 and later, the Noise session factory. */
    class StartPlan(val clientId: String, val noiseSessionFactory: NoiseSessionFactory?)

    /**
     * Waits for the MA connection, then picks the protocol by its `server_version`.
     *
     * On Noise the player id is the public key id. The first time that happens, the id the phone
     * used before is kept as the migration source and the public key id replaces it in
     * `sendspinClientId`, so every consumer and every later server, a legacy one included, sees one
     * player. The source is written first, so a crash in between only repeats the switch.
     *
     * @throws IOException when a new identity cannot be saved. Falling back to the legacy protocol
     *   is not an option: a server that already paired this phone refuses it.
     */
    suspend fun planStart(): StartPlan {
        val connected = wsClient.connectionState.first { it is ConnectionState.Connected } as ConnectionState.Connected
        val version = connected.serverInfo.serverVersion
        val stored = settingsRepository.sendspinClientId.first()
        if (!supportsNoise(version)) {
            Log.i(TAG, "MA $version: legacy Sendspin")
            val id = stored ?: UUID.randomUUID().toString().also { settingsRepository.setSendspinClientId(it) }
            return StartPlan(id, null)
        }
        val identity = withContext(Dispatchers.IO) { identityStore.identity() }
        if (stored != identity.peerId) {
            if (stored != null && settingsRepository.sendspinMigrationSourceId.first() == null) {
                settingsRepository.setSendspinMigrationSourceId(stored)
            }
            settingsRepository.setSendspinClientId(identity.peerId)
            Log.i(TAG, "Player id is now ${identity.peerId} (was $stored)")
        }
        Log.i(TAG, "MA $version: Sendspin over Noise")
        return StartPlan(identity.peerId, NoiseSessionFactory { NoiseClientSession(identity, credentials) })
    }

    /** Acts on Noise connection events until cancelled; runs for as long as Sendspin does. */
    suspend fun superviseLink(): Unit = coroutineScope {
        var removal: Job? = null
        sendspinManager.noiseLinkEvents.collect { event ->
            when (event) {
                is NoiseLinkEvent.Unpaired -> pair(event.serverId)
                is NoiseLinkEvent.Paired -> {
                    migrateIfPending()
                    // Waits for minutes at most, so it must not hold up the next link event.
                    if (removal?.isActive != true) removal = launch { removeOldPlayerIfPending() }
                }
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
            settingsRepository.setSendspinRemovalPendingId(source)
            settingsRepository.setSendspinMigrationSourceId(null)
        }
    }

    /**
     * Removes the old player once MA has let go of it. MA refuses while the player is registered,
     * and after an app update it stays registered until aiosendspin cleans up its client, 180
     * seconds after the old connection ended. The wait follows MA's player list instead of a
     * timer. When it runs out, or MA still refuses, the next connection tries again.
     */
    private suspend fun removeOldPlayerIfPending() {
        val playerId = settingsRepository.sendspinRemovalPendingId.first() ?: return
        if (!awaitMaConnected()) return
        // An empty list means the players have not arrived yet, not that the old one is gone.
        val released = withTimeoutOrNull(OLD_PLAYER_RELEASE_WAIT_MS) {
            playerRepository.players.first { list -> list.isNotEmpty() && list.none { it.playerId == playerId } }
        } != null
        if (!released) {
            Log.i(TAG, "Old player $playerId is still registered; removing it on the next connection")
            return
        }
        val outcome = try {
            migration.removeOldPlayer(playerId)
        } catch (e: MaApiException) {
            Log.w(TAG, "Removing old player $playerId failed, retrying on the next connection: ${e.message}")
            return
        }
        Log.i(TAG, "Old player $playerId: $outcome")
        if (outcome != SendspinPlayerMigration.RemoveOutcome.STILL_REGISTERED) {
            settingsRepository.setSendspinRemovalPendingId(null)
        }
    }

    private suspend fun awaitMaConnected(): Boolean = withTimeoutOrNull(MA_CONNECT_WAIT_MS) {
        wsClient.connectionState.first { it is ConnectionState.Connected }
    } != null

    internal companion object {
        private const val TAG = "SendspinPairing"
        private val VERSION = Regex("""^(\d+)\.(\d+)\.""")
        private const val NOISE_MIN_MINOR = 10

        /**
         * True from MA 2.10.0, whose aiosendspin speaks Noise. A version that does not read as
         * X.Y.Z, and the 0.0.0 of a dev build, count as older.
         */
        internal fun supportsNoise(serverVersion: String): Boolean {
            val match = VERSION.find(serverVersion) ?: return false
            val major = match.groupValues[1].toIntOrNull() ?: return false
            val minor = match.groupValues[2].toIntOrNull() ?: return false
            return major > 2 || (major == 2 && minor >= NOISE_MIN_MINOR)
        }

        // The command answers after the whole exchange: MA waits up to 10 s for the
        // client to register, then runs a re-handshake, the finalize and another one.
        private const val PAIR_TIMEOUT_MS = 45_000L
        private const val MA_CONNECT_WAIT_MS = 30_000L

        // aiosendspin's CLIENT_CLEANUP_DELAY is 180 s; the rest covers MA's unregister and a slow start.
        private const val OLD_PLAYER_RELEASE_WAIT_MS = 300_000L
    }
}
