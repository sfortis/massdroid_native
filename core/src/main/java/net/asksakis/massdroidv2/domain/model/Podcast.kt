package net.asksakis.massdroidv2.domain.model

import kotlinx.serialization.Serializable

/**
 * A podcast is a CONTAINER of episodes (like an [Album] of tracks), not a single playable item
 * with chapters (that is an audiobook [Track]). Episodes are fetched separately via
 * `music/podcasts/podcast_episodes` and played like tracks.
 */
@Serializable
data class Podcast(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    val imageUrl: String? = null,
    val publisher: String? = null,
    val totalEpisodes: Int? = null,
    val favorite: Boolean = false,
    val description: String? = null,
    val providerDomains: List<String> = emptyList()
)

/**
 * A single episode of a [Podcast]. Played like a [Track] (MA resumes from the server-side position
 * automatically). [fullyPlayed] / [resumePositionMs] are server-maintained and drive a read-only
 * played / in-progress indicator (mark-played/unplayed is not available over the WS API).
 */
@Serializable
data class PodcastEpisode(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    val imageUrl: String? = null,
    val duration: Double = 0.0,
    val position: Int? = null,
    val description: String? = null,
    val fullyPlayed: Boolean = false,
    val resumePositionMs: Long = 0L,
    val favorite: Boolean = false,
    val podcastName: String? = null
)
