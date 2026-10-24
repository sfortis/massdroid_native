package net.asksakis.massdroidv2.data.sendspin.noise

/**
 * The kind of PSK that admitted a connection. The server decides from it what the client may do.
 *
 * [code] is the identifier the category travels under in handshake message 1 on the rc1 wire.
 */
enum class PskCategory(val code: String) {
    /** The published sentinel PSK: the connection is encrypted but authenticates nothing. */
    SENTINEL("sn"),

    /** A PSK the client handed to the server for one pairing attempt. */
    PAIRING("pr"),

    /** The PSK of a completed pairing, bound to one server. */
    LONG_TERM("lt");

    companion object {
        /** The category with [code], or null for a code the spec does not define. */
        fun fromCode(code: String): PskCategory? = entries.firstOrNull { it.code == code }
    }
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
