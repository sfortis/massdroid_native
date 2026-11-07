package net.asksakis.massdroidv2.domain.model

/** How a queue item came from a guest of Music Assistant's Party plugin. */
enum class PartyMark {
    /** A guest added it. */
    GUEST,

    /** A guest added it and boosted it to play sooner. */
    BOOSTED
}

/**
 * Where the guests' songs play. VENUE is a real player; REMOTE is the plugin's silent disco,
 * where every guest listens on their own device. UNKNOWN is a mode this app does not know yet.
 */
enum class PartyMode { VENUE, REMOTE, UNKNOWN }

/** What the Party plugin's settings say about the party itself. */
data class PartyInfo(
    /** The name the server gives the party, or null when none is set. */
    val name: String?,
    /** The text the server shows with the QR code, or null when none is set. */
    val qrText: String?,
    val mode: PartyMode
)

/** The link guests open to join the party. */
data class PartyJoinLink(
    val url: String,
    /**
     * True when the link names a private address. It then opens only on the server's own
     * network, so a guest on mobile data cannot join.
     */
    val homeNetworkOnly: Boolean
)
