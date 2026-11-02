package net.asksakis.massdroidv2.domain.discover

import net.asksakis.massdroidv2.domain.model.RecommendationFolder

/**
 * Which Music Assistant recommendation rows show on the home screen, and in what order.
 *
 * This is the MA user's own home row setting, which the MA frontend keeps in the user's
 * preferences as `discover.rows`, so the phone, the TV, the web UI and the official apps
 * show the same rows. Rows are named by their URI. A row in [hidden] is hidden, a row in
 * [shown] shows, and any other row follows its own `enabled_by_default`.
 */
data class HomeRowLayout(
    val hidden: Set<String> = emptySet(),
    val shown: Set<String> = emptySet(),
    val order: List<String> = emptyList()
) {
    fun isVisible(row: RecommendationFolder): Boolean {
        val uri = row.uri ?: return row.enabledByDefault
        return when (uri) {
            in hidden -> false
            in shown -> true
            else -> row.enabledByDefault
        }
    }

    /**
     * The visible rows in the user's order. A row the stored order does not name yet takes the
     * place after the row the server lists before it, as the MA frontend places it.
     */
    fun visibleRows(rows: List<RecommendationFolder>): List<RecommendationFolder> = orderedRows(rows).filter(::isVisible)

    /** Every row, hidden ones included, in the user's order: the list the settings screen sorts. */
    fun orderedRows(rows: List<RecommendationFolder>): List<RecommendationFolder> {
        if (order.isEmpty()) return rows
        val rank = mergedOrder(rows.mapNotNull { it.uri }).withIndex().associate { (index, uri) -> uri to index }
        return rows.withIndex()
            .sortedWith(compareBy({ rank[it.value.uri] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /**
     * The layout with the rows of [newSequence] in that order. Only the places those rows hold
     * in the stored order change, so entries the frontend keeps there for its own sections stay
     * where they are (the frontend reorders the same way).
     */
    fun withOrder(rows: List<RecommendationFolder>, newSequence: List<String>): HomeRowLayout {
        val moved = newSequence.toSet()
        val merged = mergedOrder(rows.mapNotNull { it.uri })
        if (moved.size != newSequence.size || !merged.containsAll(moved)) return this
        val next = newSequence.iterator()
        return copy(order = merged.map { if (it in moved) next.next() else it })
    }

    /**
     * The stored order without repeats, with every row it does not name inserted after the
     * nearest row before it in the server's list, or first when there is none.
     */
    private fun mergedOrder(rowUris: List<String>): List<String> {
        val merged = order.distinct().toMutableList()
        rowUris.forEachIndexed { index, uri ->
            if (uri in merged) return@forEachIndexed
            val predecessor = (index - 1 downTo 0).firstOrNull { rowUris[it] in merged }
            val at = predecessor?.let { merged.indexOf(rowUris[it]) + 1 } ?: 0
            merged.add(at, uri)
        }
        return merged
    }

    /**
     * The layout with [row] shown or hidden. Showing a row that shows by default only takes it
     * out of [hidden], so the row keeps following the server's default.
     */
    fun withVisibility(row: RecommendationFolder, visible: Boolean): HomeRowLayout {
        val uri = row.uri ?: return this
        return if (visible) {
            copy(hidden = hidden - uri, shown = if (row.enabledByDefault) shown - uri else shown + uri)
        } else {
            copy(hidden = hidden + uri, shown = shown - uri)
        }
    }
}
