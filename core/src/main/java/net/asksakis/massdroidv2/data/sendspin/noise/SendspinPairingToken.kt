package net.asksakis.massdroidv2.data.sendspin.noise

/**
 * The version 0 pairing token of the Sendspin `pairing_psk` method (aiosendspin `pairing_token.py`).
 *
 * The token is "SP:0" followed by the unpadded RFC 4648 base32 of the client's public key and the
 * pairing PSK, with every '2' replaced by '9' so it survives being read aloud or typed. The server
 * decodes the client id and the PSK from it, so the token alone names the player to pair.
 */
object SendspinPairingToken {
    private const val PREFIX = "SP:0"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val CHAR_MASK = 0x1F

    fun encode(clientPublicKey: ByteArray, pairingPsk: ByteArray): String {
        require(clientPublicKey.size == SendspinNoise.KEY_SIZE) { "public key must be ${SendspinNoise.KEY_SIZE} bytes" }
        require(pairingPsk.size == SendspinNoise.PSK_SIZE) { "pairing PSK must be ${SendspinNoise.PSK_SIZE} bytes" }
        return PREFIX + base32(clientPublicKey + pairingPsk).replace('2', '9')
    }

    /** Unpadded base32, as Python's `b32encode(...).rstrip("=")` produces it. */
    private fun base32(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + BITS_PER_CHAR - 1) / BITS_PER_CHAR)
        var buffer = 0
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= BITS_PER_CHAR) {
                bits -= BITS_PER_CHAR
                out.append(ALPHABET[(buffer shr bits) and CHAR_MASK])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and CHAR_MASK])
        return out.toString()
    }
}
