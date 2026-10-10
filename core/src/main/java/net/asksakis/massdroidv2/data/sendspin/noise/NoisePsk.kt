package net.asksakis.massdroidv2.data.sendspin.noise

/** The kind of PSK that admitted a connection. The server decides from it what the client may do. */
enum class PskCategory {
    /** The published sentinel PSK: the connection is encrypted but authenticates nothing. */
    SENTINEL,

    /** A PSK the client handed to the server for one pairing attempt. */
    PAIRING,

    /** The PSK of a completed pairing, bound to one server. */
    LONG_TERM,
}

class ResolvedPsk(
    val pskId: String,
    val psk: ByteArray,
    val category: PskCategory,
    /** The `server_id` the PSK is bound to, or null when it is not bound to one server. */
    val serverId: String?,
)

/**
 * Finds the PSK the server named in handshake message 1. The sentinel PSK is handled by the
 * session itself, so a resolver only knows the PSKs the client holds.
 */
fun interface PskResolver {
    fun resolve(pskId: String): ResolvedPsk?
}
