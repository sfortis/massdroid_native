package net.asksakis.massdroidv2.domain.repository

import net.asksakis.massdroidv2.domain.discover.HomeRowLayout
import net.asksakis.massdroidv2.domain.model.RecommendationFolder

/** The MA user's home row setting, shared with the MA frontend and the official apps. */
interface HomeRowSettingsRepository {
    /**
     * The current setting, or null for a server without user preferences, which keeps the
     * home screen as it was before these rows existed.
     */
    suspend fun load(): HomeRowLayout?

    /** Shows or hides [row] for this MA user on every client, and returns the new setting. */
    suspend fun setVisible(row: RecommendationFolder, visible: Boolean): HomeRowLayout

    /** Puts the rows of [newSequence] (row URIs) in that order, and returns the new setting. */
    suspend fun setOrder(rows: List<RecommendationFolder>, newSequence: List<String>): HomeRowLayout
}
