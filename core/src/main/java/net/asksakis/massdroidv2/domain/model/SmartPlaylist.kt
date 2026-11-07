package net.asksakis.massdroidv2.domain.model

/** The plugin's MAX_SEEDS: more seeds across all four lists and the server refuses the rules. */
const val SMART_PLAYLIST_MAX_SEEDS = 10

/** What a seed of a Music Assistant smart playlist can be, as far as the app adds them. */
enum class SmartPlaylistSeedKind { TRACK, ARTIST }

/** A library track or artist to add as a seed; [name] is what the server shows for it. */
data class SmartPlaylistSeed(
    val kind: SmartPlaylistSeedKind,
    val uri: String,
    val name: String
)

/** A seed-based smart playlist, by the plugin's own id (a UUID). */
data class SmartPlaylistSummary(
    val playlistId: String,
    val name: String,
    val seedCount: Int
)

enum class SeedAddOutcome { ADDED, ALREADY_PRESENT, FULL }
