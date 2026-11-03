package net.asksakis.massdroidv2.domain.model

/**
 * The album types the library can be filtered by, spelled as Music Assistant's `album_types`
 * filter expects them. The server's "unknown" type is left out, because nobody filters by it;
 * the official app leaves it out too.
 */
enum class AlbumType(val serverValue: String, val label: String) {
    ALBUM("album", "Album"),
    SINGLE("single", "Single"),
    EP("ep", "EP"),
    COMPILATION("compilation", "Compilation"),
    LIVE("live", "Live"),
    SOUNDTRACK("soundtrack", "Soundtrack");

    companion object {
        fun fromServer(value: String): AlbumType? = entries.firstOrNull { it.serverValue == value }
    }
}
