package net.asksakis.massdroidv2.domain.model

/**
 * A genre entry for Discover/Genre Radio surfaces: display name, track count,
 * and an optional representative image. Lives in the domain layer so both the
 * recommendation builders and the UI can reference it without a UI dependency.
 */
data class GenreItem(
    val name: String,
    val count: Int,
    val imageUrl: String?
)
