package net.asksakis.massdroidv2.domain.model

enum class SortOption(val apiValue: String, val label: String) {
    NAME("name", "Name"),
    RECENTLY_ADDED("timestamp_added", "Recently Added"),
    LAST_PLAYED("last_played", "Last Played"),
    MOST_PLAYED("play_count", "Most Played"),
    RANDOM("random", "Random")
}

enum class LibraryDisplayMode {
    LIST, GRID
}

enum class LibraryTabKey(
    val index: Int,
    val defaultDisplayMode: LibraryDisplayMode
) {
    ARTISTS(index = 0, defaultDisplayMode = LibraryDisplayMode.GRID),
    ALBUMS(index = 1, defaultDisplayMode = LibraryDisplayMode.GRID),
    TRACKS(index = 2, defaultDisplayMode = LibraryDisplayMode.GRID),
    PLAYLISTS(index = 3, defaultDisplayMode = LibraryDisplayMode.GRID),
    RADIOS(index = 4, defaultDisplayMode = LibraryDisplayMode.GRID),
    BROWSE(index = 5, defaultDisplayMode = LibraryDisplayMode.LIST);

    companion object {
        fun fromIndex(index: Int): LibraryTabKey? = entries.firstOrNull { it.index == index }

        fun fromStoredKey(key: String): LibraryTabKey? =
            entries.firstOrNull { it.name == key }
                ?: key.toIntOrNull()?.let(::fromIndex)
    }
}
