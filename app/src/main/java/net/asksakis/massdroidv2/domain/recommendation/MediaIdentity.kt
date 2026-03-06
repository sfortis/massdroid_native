package net.asksakis.massdroidv2.domain.recommendation

object MediaIdentity {

    fun canonicalArtistKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalAlbumKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalTrackKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalMediaKey(itemId: String? = null, uri: String? = null): String? {
        val raw = uri?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val normalizedUri = normalizeUri(raw)
            if (normalizedUri.isNotEmpty()) return normalizedUri
        }

        val direct = itemId?.trim().orEmpty()
        return direct.ifEmpty { null }
    }

    private fun normalizeUri(raw: String): String {
        return raw
            .substringBefore('#')
            .substringBefore('?')
            .trim()
    }
}
