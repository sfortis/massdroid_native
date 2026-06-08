package net.asksakis.massdroidv2.playback

/**
 * The audio output route Sendspin is currently feeding, resolved from the live
 * Oboe stream's routed device (canonical) or an AudioManager heuristic fallback.
 *
 * [isExternal] means a non-speaker sink the user actively chose (Bluetooth, wired,
 * USB). Losing an external route to [SPEAKER] is the "phone-speaker fallback" that
 * must never leak audio (e.g. blasting from the phone in the car), so it is gated
 * through a settle window — see SendspinAudioController's route-loss handling.
 */
enum class OutputRoute {
    SPEAKER,
    BT,
    WIRED,
    USB,
    UNKNOWN;

    val isExternal: Boolean get() = this == BT || this == WIRED || this == USB
}
