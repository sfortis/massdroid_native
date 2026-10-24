package net.asksakis.massdroidv2.data.sendspin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Messages that exist only on the Noise transport (aiosendspin 9, MA 2.10.0 and later).

/** A pairing method offered in `client/hello`. Only `pairing_psk`, the one MA's web player route uses. */
@Serializable
data class PairMethodDescriptor(val method: String = PAIR_METHOD_PAIRING_PSK)

@Serializable
data class UnpairedAccess(val enabled: Boolean)

/**
 * `client/hello` on the Noise transport. Unlike the legacy hello it carries no `client_id` and no
 * `version`, which the server takes from `client/init`, and it adds the trust the client extends
 * to this server and the pairing it offers.
 */
@Serializable
data class NoiseClientHelloPayload(
    val name: String,
    @SerialName("supported_roles") val supportedRoles: List<String> = listOf("player@v1", "metadata@v1"),
    @SerialName("device_info") val deviceInfo: DeviceInfo = DeviceInfo(),
    @SerialName("player@v1_support") val playerV1Support: PlayerV1Support = PlayerV1Support(),
    /** `user` once the connection runs on a long-term PSK, `none` otherwise. */
    @SerialName("trust_level") val trustLevel: String,
    @SerialName("supported_pair_methods") val supportedPairMethods: List<PairMethodDescriptor> =
        listOf(PairMethodDescriptor()),
    /**
     * Lets an operator approve the phone in MA as a trusted unpaired player, the fallback when
     * the automatic pairing cannot run.
     */
    @SerialName("unpaired_access") val unpairedAccess: UnpairedAccess = UnpairedAccess(enabled = true),
)

@Serializable
data class SendspinNoiseClientHello(
    val type: String = "client/hello",
    val payload: NoiseClientHelloPayload,
)

/**
 * The pairing methods offered in an rc1 `client/hello`, keyed by method identifier. Only
 * `pairing_psk`, with no `locations` hint, which is the method MA's web player route uses.
 */
@Serializable
data class Rc1PairMethods(@SerialName("pairing_psk") val pairingPsk: Rc1PairingPskMethod = Rc1PairingPskMethod)

/** The `pairing_psk` descriptor: an empty object, since the app gives no `locations` hint. */
@Serializable
data object Rc1PairingPskMethod

/** `player@v1_support` on the rc1 wire: the commands moved to `client/state`. */
@Serializable
data class Rc1PlayerV1Support(
    @SerialName("supported_formats") val supportedFormats: List<AudioFormatSpec>,
    @SerialName("buffer_capacity") val bufferCapacity: Int = SENDSPIN_BUFFER_CAPACITY_BYTES,
)

/**
 * `client/hello` on the rc1 wire (Sendspin 1.0.0-rc1, aiosendspin 10). Compared with
 * [NoiseClientHelloPayload] it has no `trust_level`, which the server now reads from the PSK
 * category it matched, the pairing methods are an object keyed by method, and the player
 * support carries no `supported_commands`. Those three keys are what tell aiosendspin 10 that a
 * client speaks the rc1 wire.
 */
@Serializable
data class Rc1ClientHelloPayload(
    val name: String,
    @SerialName("supported_roles") val supportedRoles: List<String> = listOf("player@v1", "metadata@v1"),
    @SerialName("device_info") val deviceInfo: DeviceInfo,
    @SerialName("player@v1_support") val playerV1Support: Rc1PlayerV1Support,
    @SerialName("supported_pair_methods") val supportedPairMethods: Rc1PairMethods = Rc1PairMethods(),
    @SerialName("unpaired_access") val unpairedAccess: UnpairedAccess = UnpairedAccess(enabled = true),
)

@Serializable
data class SendspinRc1ClientHello(
    val type: String = "client/hello",
    val payload: Rc1ClientHelloPayload,
)

/**
 * The player object of an rc1 `client/state`. Every field is required in every state: the
 * renamed `output_delay_ms`, the timing fields, `supported_commands` (moved here from the hello)
 * with the `volume` and `muted` they promise, and `format`, the format the player prefers. A
 * player object without `format` would clear the preference on the server.
 */
@Serializable
data class Rc1PlayerState(
    val volume: Int,
    val muted: Boolean,
    @SerialName("output_delay_ms") val outputDelayMs: Int,
    @SerialName("required_lead_time_ms") val requiredLeadTimeMs: Int = SENDSPIN_REQUIRED_LEAD_TIME_MS,
    @SerialName("min_buffer_ms") val minBufferMs: Int,
    @SerialName("supported_commands") val supportedCommands: List<String> = SENDSPIN_PLAYER_COMMANDS,
    val format: AudioFormatSpec,
)

@Serializable
data class Rc1ClientStatePayload(val available: Boolean, val player: Rc1PlayerState)

@Serializable
data class SendspinRc1ClientState(
    val type: String = "client/state",
    val payload: Rc1ClientStatePayload,
)

/**
 * An rc1 `client/state` while the server has no player role active. The player object is left
 * out rather than sent as `null`, which aiosendspin 10 records as a wrong type.
 */
@Serializable
data class Rc1AvailabilityPayload(val available: Boolean)

@Serializable
data class SendspinRc1AvailabilityState(
    val type: String = "client/state",
    val payload: Rc1AvailabilityPayload,
)

@Serializable
data class ActivatePairing(val method: String)

/**
 * `server/activate`: what the connection is for right now. `active_roles` is sticky, so a message
 * without it keeps the previous set.
 */
@Serializable
data class ServerActivatePayload(
    val activities: List<String> = emptyList(),
    @SerialName("active_roles") val activeRoles: List<String>? = null,
    val pairing: ActivatePairing? = null,
)

@Serializable
data class ClientPairFinalizePayload(@SerialName("long_term_psk") val longTermPsk: String)

/**
 * `client/pair-init` on the rc1 wire, sent right before `client/pair-finalize`. [pairingIndex]
 * counts the pairing `server/activate` messages since the last Noise handshake, this one included.
 */
@Serializable
data class ClientPairInitPayload(@SerialName("pairing_index") val pairingIndex: Int)

@Serializable
data class SendspinClientPairInit(
    val type: String = "client/pair-init",
    val payload: ClientPairInitPayload,
)

@Serializable
data class SendspinClientPairFinalize(
    val type: String = "client/pair-finalize",
    val payload: ClientPairFinalizePayload,
)

@Serializable
data class PairAbortPayload(val reason: String)

@Serializable
data class SendspinPairAbort(
    val type: String = "pair/abort",
    val payload: PairAbortPayload,
)

const val PAIR_METHOD_PAIRING_PSK = "pairing_psk"
const val ACTIVITY_PAIRING = "pairing"
const val ACTIVITY_PLAYBACK = "playback"
const val ACTIVITY_MANAGEMENT = "management"
const val TRUST_LEVEL_USER = "user"
const val TRUST_LEVEL_NONE = "none"
const val PAIR_ABORT_METHOD_NOT_SUPPORTED = "method_not_supported"
const val GOODBYE_REASON_USER_REQUEST = "user_request"
const val GOODBYE_REASON_UNPAIRED = "unpaired"
const val GOODBYE_REASON_UNAUTHORIZED = "unauthorized"
