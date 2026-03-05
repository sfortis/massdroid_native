package net.asksakis.massdroidv2.domain.recommendation

object MediaIdentity {

    fun canonicalArtistKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalAlbumKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalTrackKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalMediaKey(itemId: String? = null, uri: String? = null): String? {
        val direct = itemId?.trim().orEmpty()
        if (direct.isNotEmpty()) return direct

        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val noFragment = raw.substringBefore('#').substringBefore('?')
        if (noFragment.isEmpty()) return null

        val afterScheme = if (noFragment.contains("://")) {
            noFragment.substringAfter("://")
        } else {
            noFragment
        }
        if (afterScheme.isEmpty()) return null

        val tail = afterScheme.substringAfterLast('/')
        val fromTail = tail.substringAfterLast(':')
        return when {
            fromTail.isNotEmpty() -> fromTail
            tail.isNotEmpty() -> tail
            else -> afterScheme
        }
    }
}
