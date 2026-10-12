package net.asksakis.massdroidv2.data.sendspin

import net.asksakis.massdroidv2.data.sendspin.noise.PskCategory

/** How the current Sendspin connection is protected, for the settings and status screens. */
enum class SendspinLinkSecurity {
    /** The legacy protocol of MA before 2.10.0: plain JSON over the MA proxy. */
    UNENCRYPTED,

    /** Noise with the sentinel or the pairing PSK: encrypted, but the server has not paired the phone. */
    ENCRYPTED_UNPAIRED,

    /** Noise with this server's long-term PSK. */
    ENCRYPTED_PAIRED;

    companion object {
        fun of(category: PskCategory): SendspinLinkSecurity =
            if (category == PskCategory.LONG_TERM) ENCRYPTED_PAIRED else ENCRYPTED_UNPAIRED
    }
}
