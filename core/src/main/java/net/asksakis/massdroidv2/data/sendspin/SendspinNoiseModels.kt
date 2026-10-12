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
const val GOODBYE_REASON_UNPAIRED = "unpaired"
const val GOODBYE_REASON_UNAUTHORIZED = "unauthorized"
