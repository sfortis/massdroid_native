package net.asksakis.massdroidv2.domain.nfc

import java.io.ByteArrayOutputStream

/**
 * What a tag asks for when it is tapped.
 *
 * A tag can name the music, or it can name only a speaker and let whatever is already
 * happening decide. The second kind is what makes a tag by the door useful: it moves the
 * listening into the room rather than starting something new.
 */
sealed interface NfcTagAction {
    /** Replace the player's queue with this container and start it. */
    data class PlayMedia(val uri: String) : NfcTagAction

    /** Bring whatever is playing elsewhere onto this player, from where it had got to. */
    data object TransferQueue : NfcTagAction

    /** Start whatever this player already holds in its queue. */
    data object Resume : NfcTagAction
}

/**
 * What a MassDroid NFC tag says: do this, on this player.
 *
 * The tag carries the whole instruction rather than a key into a local table, so a tag
 * written on one phone still works on another that has the app. The app keeps its own
 * record of the tags it wrote, but only to name them on screen, never to resolve them.
 *
 * @param action what the tap should do.
 * @param playerId the player to do it on, or null to use whichever player is selected.
 * A transfer or a resume without a player names nothing at all and is rejected on reading.
 * @param label what to call this on screen while the tag is being acted on. It is written
 * so that a phone without a record of the tag can still say what it started.
 * @param volume what to set the player's volume to first, or null to leave it alone. A tag
 * by a bed and a tag in a kitchen want very different levels from the same speaker, which
 * is the whole reason this is on the tag rather than a setting.
 */
data class NfcTagPayload(
    val action: NfcTagAction,
    val playerId: String?,
    val label: String?,
    val volume: Int? = null
) {

    /** The media uri when this tag names one, for the callers that only care about that. */
    val mediaUri: String? get() = (action as? NfcTagAction.PlayMedia)?.uri

    /**
     * The uri to write into the tag's NDEF record.
     *
     * Only the characters that would break the query are escaped, which keeps a typical
     * payload near 60 bytes and so within an NTAG213. Escaping everything reserved pushed
     * `library://playlist/12` from 21 characters to 29 for no gain, because a colon and a
     * slash are both legal inside a query value.
     */
    fun toTagUri(): String = buildString {
        append(SCHEME).append("://").append(HOST).append('?')
        // A media tag keeps writing `media`, exactly as the first tags did, so nothing
        // written before this existed has to be written again. The other two name an
        // action instead, and a reader that finds `media` never looks at it.
        when (val current = action) {
            is NfcTagAction.PlayMedia -> append(PARAM_MEDIA).append('=').append(encode(current.uri))
            NfcTagAction.TransferQueue -> append(PARAM_ACTION).append('=').append(ACTION_TRANSFER)
            NfcTagAction.Resume -> append(PARAM_ACTION).append('=').append(ACTION_RESUME)
        }
        if (!playerId.isNullOrBlank()) {
            append('&').append(PARAM_PLAYER).append('=').append(encode(playerId))
        }
        if (!label.isNullOrBlank()) {
            append('&').append(PARAM_LABEL).append('=').append(encode(label.take(MAX_LABEL_LENGTH)))
        }
        volume?.coerceIn(MIN_VOLUME, MAX_VOLUME)?.let {
            append('&').append(PARAM_VOLUME).append('=').append(it)
        }
    }

    companion object {
        const val SCHEME = "massdroid"
        const val HOST = "play"

        private const val PARAM_MEDIA = "media"
        private const val PARAM_ACTION = "action"
        private const val PARAM_VOLUME = "vol"

        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 100
        private const val ACTION_TRANSFER = "transfer"
        private const val ACTION_RESUME = "resume"
        private const val PARAM_PLAYER = "player"
        private const val PARAM_LABEL = "label"

        /** Bounded so a long album title cannot be what makes a tag too big to write. */
        private const val MAX_LABEL_LENGTH = 64

        /**
         * Read a tag uri, or null when it is not one of ours.
         *
         * Unknown parameters are ignored and a tag without a media uri is rejected, so a
         * later version may add parameters without the tags written today going stale.
         */
        fun parse(raw: String?): NfcTagPayload? {
            val uri = raw?.trim().orEmpty()
            val prefix = "$SCHEME://"
            if (!uri.startsWith(prefix, ignoreCase = true)) return null
            val rest = uri.substring(prefix.length)
            val split = rest.indexOf('?')
            val host = if (split < 0) rest else rest.substring(0, split)
            if (!host.equals(HOST, ignoreCase = true)) return null
            if (split < 0) return null

            val params = parseQuery(rest.substring(split + 1))
            val player = params[PARAM_PLAYER]?.takeIf { it.isNotBlank() }
            val media = params[PARAM_MEDIA]?.takeIf { it.isNotBlank() }
            // Media first, so a tag written before actions existed reads the same as it
            // always did and an action alongside it is ignored rather than fought over.
            val action = when {
                media != null -> NfcTagAction.PlayMedia(media)
                // Both of these are about a speaker, so without one there is nothing to
                // act on and the tag is not ours to use.
                player == null -> return null
                params[PARAM_ACTION] == ACTION_TRANSFER -> NfcTagAction.TransferQueue
                params[PARAM_ACTION] == ACTION_RESUME -> NfcTagAction.Resume
                else -> return null
            }
            return NfcTagPayload(
                action = action,
                playerId = player,
                label = params[PARAM_LABEL]?.takeIf { it.isNotBlank() },
                // A level outside the scale, or one that is not a number at all, is read as
                // no instruction rather than clamped: a tag saying something impossible
                // should leave the volume where the listener left it.
                volume = params[PARAM_VOLUME]?.toIntOrNull()?.takeIf { it in MIN_VOLUME..MAX_VOLUME }
            )
        }

        private fun parseQuery(query: String): Map<String, String> =
            query.split('&')
                .mapNotNull { pair ->
                    val eq = pair.indexOf('=')
                    if (eq <= 0) return@mapNotNull null
                    decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
                }
                .toMap()

        /**
         * Characters left as they are. They are the unreserved set plus the sub-delimiters
         * a query value may hold, minus the three that would end the value or the parameter.
         */
        private const val SAFE_PUNCTUATION = "-._~:/@!$'()*,;"

        private fun encode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = byte.toInt().toChar()
                // A byte outside ASCII is negative here, which is also why it is never
                // taken as plain: it is part of a multi-byte character and each of its
                // bytes is escaped on its own.
                if (byte > 0 && (char.isAsciiLetterOrDigit() || char in SAFE_PUNCTUATION)) {
                    append(char)
                } else {
                    append('%')
                    append(HEX[(byte.toInt() shr 4) and 0xF])
                    append(HEX[byte.toInt() and 0xF])
                }
            }
        }

        /**
         * Percent-decoding that leaves a plus sign alone.
         *
         * A form encoder would read it as a space, and a provider uri may hold one for
         * real, so a tag written elsewhere must not have its media uri quietly altered.
         */
        private fun decode(value: String): String {
            if ('%' !in value) return value
            val out = ByteArrayOutputStream(value.length)
            var i = 0
            while (i < value.length) {
                val char = value[i]
                val hex = if (char == '%' && i + 2 < value.length) {
                    value.substring(i + 1, i + 3).toIntOrNull(16)
                } else {
                    null
                }
                if (hex != null) {
                    out.write(hex)
                    i += 3
                } else {
                    out.write(char.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            // Built from the bytes rather than ByteArrayOutputStream.toString(Charset),
            // which Android only gained at API 33 and would throw on anything older.
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private const val HEX = "0123456789ABCDEF"

        private fun Char.isAsciiLetterOrDigit(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
