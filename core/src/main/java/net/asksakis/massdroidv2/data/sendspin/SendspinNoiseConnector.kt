package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.security.MessageDigest
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
 * When the phone is registered with an MA server that has not had the legacy player's settings
 * moved yet, [SendspinPlayerMigration] moves them onto the new player. This is tracked per MA
 * server, so every server the phone connects to gets it. The legacy player's config stays on the
 * server, where MA keeps the configs of every player that ever connected and the UI lists only the
 * registered ones. Deleting it would gain nothing visible, and a legacy id restored onto another
 * phone from a backup would delete that phone's player.
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
    // One move of the legacy player at a time: a Noise registration and a legacy one can overlap.
    private val legacyLock = Mutex()

    /** The player id to start Sendspin with and, on MA 2.10.0 and later, the Noise session factory. */
    class StartPlan(val clientId: String, val noiseSessionFactory: NoiseSessionFactory?)

    /**
     * Waits for the MA connection, then picks the protocol by its `server_version`.
     *
     * On Noise the player id is the public key id. The first time that happens, the id the phone
     * used before is kept as the legacy id and the public key id replaces it in
     * `sendspinClientId`, so every consumer and every later server, a legacy one included, sees one
     * player. The legacy id is written first, so a crash in between only repeats the switch.
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
            if (stored != null && settingsRepository.sendspinLegacyClientId.first() == null) {
                settingsRepository.setSendspinLegacyClientId(stored)
            }
            settingsRepository.setSendspinClientId(identity.peerId)
            Log.i(TAG, "Player id is now ${identity.peerId} (was $stored)")
        }
        Log.i(TAG, "MA $version: Sendspin over Noise")
        return StartPlan(identity.peerId, NoiseSessionFactory { NoiseClientSession(identity, credentials) })
    }

    /**
     * Acts on connection events until cancelled; runs for as long as Sendspin does.
     *
     * On Noise it pairs an unpaired connection and keeps retrying while that connection waits,
     * unless the server unpaired the phone during the current MA session, in which case it waits
     * for [pairAgain]. MA unpairs an app player only when the user's access is revoked, and a new
     * MA login means the access was granted again, so the hold ends with the session. Once the phone
     * is registered with a server, on Noise or on the legacy transport, that server gets the
     * legacy player's settings.
     */
    suspend fun superviseLink(): Unit = coroutineScope {
        var pairing: Job? = null
        // One collector for every source, so the job above is only touched in order.
        merge(
            sendspinManager.noiseLinkEvents.map { LinkSignal.Noise(it) },
            // The legacy transport has no pairing; its hello is the registration.
            sendspinManager.linkSecurity.filter { it == SendspinLinkSecurity.UNENCRYPTED }.map { LinkSignal.LegacyRegistered },
            pairAgainRequests.map { LinkSignal.PairAgain },
        ).collect { signal ->
            val registered = when (signal) {
                is LinkSignal.Noise -> when (val event = signal.event) {
                    is NoiseLinkEvent.Unpaired -> {
                        pairing?.cancel()
                        val held = unpairedKey(event.serverId)
                        if (held != null && held in settingsRepository.sendspinUnpairedServers.first()) {
                            Log.i(TAG, "Server ${event.serverId} unpaired this phone; not pairing until asked")
                            _pairingHeld.value = event.serverId
                        } else {
                            _pairingHeld.value = null
                            pairing = launch { pairWhileUnpaired(event.serverId) }
                        }
                        false
                    }
                    is NoiseLinkEvent.Revoked -> {
                        pairing?.cancel()
                        unpairedKey(event.serverId)?.let { settingsRepository.addSendspinUnpairedServer(it) }
                        false
                    }
                    is NoiseLinkEvent.Paired -> {
                        pairing?.cancel()
                        _pairingHeld.value = null
                        true
                    }
                }
                LinkSignal.LegacyRegistered -> true
                LinkSignal.PairAgain -> {
                    _pairingHeld.value?.let { serverId ->
                        unpairedKey(serverId)?.let { settingsRepository.removeSendspinUnpairedServer(it) }
                        _pairingHeld.value = null
                        pairing?.cancel()
                        pairing = launch { pairWhileUnpaired(serverId) }
                    }
                    false
                }
            }
            if (registered) moveLegacyPlayer()
        }
    }

    private sealed interface LinkSignal {
        data class Noise(val event: NoiseLinkEvent) : LinkSignal
        data object LegacyRegistered : LinkSignal
        data object PairAgain : LinkSignal
    }

    /**
     * The Sendspin server id whose pairing is held back because that server unpaired the phone,
     * or null. Settings offers [pairAgain] while it is set.
     */
    val pairingHeld: StateFlow<String?> get() = _pairingHeld.asStateFlow()
    private val _pairingHeld = MutableStateFlow<String?>(null)
    private val pairAgainRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Pairs again with the server that unpaired the phone; the user asked for it. */
    fun pairAgain() {
        pairAgainRequests.tryEmit(Unit)
    }

    /**
     * Pairs, and tries again with a growing pause while the connection still waits for a
     * pairing: a non-admin MA account or a slow server fails one attempt, not the connection.
     */
    private suspend fun pairWhileUnpaired(serverId: String) {
        var pause = PAIR_RETRY_FIRST_MS
        while (!pair(serverId)) {
            delay(pause)
            pause = (pause * 2).coerceAtMost(PAIR_RETRY_MAX_MS)
            if (sendspinManager.linkSecurity.value != SendspinLinkSecurity.ENCRYPTED_UNPAIRED) return
        }
    }

    /** One pairing attempt; true when MA accepted the token. */
    private suspend fun pair(serverId: String): Boolean {
        if (!awaitMaConnected()) {
            Log.w(TAG, "MA is not connected; pairing with $serverId waits")
            return false
        }
        val token = try {
            withContext(Dispatchers.IO) {
                SendspinPairingToken.encode(identityStore.identity().publicKey, credentials.newPairingPsk())
            }
        } catch (e: IOException) {
            Log.e(TAG, "Sendspin identity unavailable, cannot pair", e)
            return false
        }
        Log.i(TAG, "Pairing with Sendspin server $serverId")
        sendspinManager.onNoisePairingRequested()
        return try {
            wsClient.sendCommand(MaCommands.Sendspin.PAIR_WEB_PLAYER, PairWebPlayerArgs(token), timeoutMs = PAIR_TIMEOUT_MS)
            true
        } catch (e: MaApiException) {
            credentials.abandon()
            Log.w(TAG, "Pairing with $serverId failed: ${e.message}")
            false
        } finally {
            sendspinManager.onNoisePairingRequestEnded()
        }
    }

    /**
     * Moves the legacy player's settings onto this phone's player on the MA server it is
     * connected to, once per server. A user with several servers gets the move on each.
     */
    private suspend fun moveLegacyPlayer(): Unit = legacyLock.withLock {
        val legacyId = settingsRepository.sendspinLegacyClientId.first() ?: return@withLock
        val ourId = settingsRepository.sendspinClientId.first() ?: return@withLock
        if (legacyId == ourId || !awaitMaConnected()) return@withLock
        val maServerId = currentMaServerId() ?: return@withLock
        if (maServerId !in settingsRepository.sendspinMigratedServers.first()) {
            val outcome = try {
                migration.migrate(fromId = legacyId, toId = ourId)
            } catch (e: MaApiException) {
                Log.w(TAG, "Player migration from $legacyId failed, retrying on the next connection: ${e.message}")
                return@withLock
            }
            Log.i(TAG, "Player migration from $legacyId on $maServerId: $outcome")
            when (outcome) {
                SendspinPlayerMigration.Outcome.MOVED,
                SendspinPlayerMigration.Outcome.NO_OLD_PLAYER -> settingsRepository.addSendspinMigratedServer(maServerId)
                SendspinPlayerMigration.Outcome.NEW_PLAYER_NOT_READY -> return@withLock
            }
        }
        followSelection(legacyId, ourId)
    }

    /**
     * The saved selection still naming the legacy player follows the phone to its new id, or
     * the app would come up with the old player selected and lose it once MA drops it.
     */
    private suspend fun followSelection(legacyId: String, ourId: String) {
        if (settingsRepository.selectedPlayerId.first() != legacyId) return
        val appeared = withTimeoutOrNull(NEW_PLAYER_WAIT_MS) {
            playerRepository.players.first { list -> list.any { it.playerId == ourId } }
        } != null
        if (appeared && playerRepository.selectPlayer(ourId)) {
            Log.i(TAG, "Selection moved from the legacy player $legacyId to $ourId")
        }
    }

    /** The stored hold for an unpair by [serverId] in the current MA session, or null when signed out. */
    private fun unpairedKey(serverId: String): String? = wsClient.authToken?.let { unpairedSessionKey(serverId, it) }

    private fun currentMaServerId(): String? =
        (wsClient.connectionState.value as? ConnectionState.Connected)?.serverInfo?.serverId

    private suspend fun awaitMaConnected(): Boolean = withTimeoutOrNull(MA_CONNECT_WAIT_MS) {
        wsClient.connectionState.first { it is ConnectionState.Connected }
    } != null

    internal companion object {
        private const val TAG = "SendspinPairing"
        private val VERSION = Regex("""^(\d+)\.(\d+)\.""")
        private const val NOISE_MIN_MINOR = 10
        private const val SESSION_DIGEST_BYTES = 8

        /**
         * True from MA 2.10.0, whose aiosendspin speaks Noise. A version that does not read as
         * X.Y.Z, and the 0.0.0 of a dev build, count as older.
         */
        /**
         * The key of an unpair hold: the Sendspin server id and a digest of the MA auth token the
         * unpair arrived under. The token itself is not stored a second time.
         */
        internal fun unpairedSessionKey(serverId: String, authToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(authToken.toByteArray(Charsets.UTF_8))
            val session = digest.take(SESSION_DIGEST_BYTES).joinToString("") { "%02x".format(it) }
            return "$serverId@$session"
        }

        internal fun supportsNoise(serverVersion: String): Boolean {
            val match = VERSION.find(serverVersion) ?: return false
            val major = match.groupValues[1].toIntOrNull() ?: return false
            val minor = match.groupValues[2].toIntOrNull() ?: return false
            return major > 2 || (major == 2 && minor >= NOISE_MIN_MINOR)
        }

        // The command answers after the whole exchange: MA waits up to 10 s for the
        // client to register, then runs a re-handshake, the finalize and another one.
        private const val PAIR_TIMEOUT_MS = 45_000L
        private const val PAIR_RETRY_FIRST_MS = 30_000L
        private const val PAIR_RETRY_MAX_MS = 300_000L

        // How long the new player may take to show up in MA's list after the registration.
        private const val NEW_PLAYER_WAIT_MS = 30_000L
        private const val MA_CONNECT_WAIT_MS = 30_000L
    }
}
