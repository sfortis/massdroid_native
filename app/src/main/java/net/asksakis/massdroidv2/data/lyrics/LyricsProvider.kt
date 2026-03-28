package net.asksakis.massdroidv2.data.lyrics

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LyricsProvider"

@Singleton
class LyricsProvider @Inject constructor(
    private val wsClient: MaWebSocketClient
) {
    data class LyricsResult(val plainText: String?, val syncedLrc: String?)
    data class LrcLine(val timeMs: Long, val text: String)

    private var cachedTrackUri: String? = null
    private var cachedResult: LyricsResult? = null

    suspend fun getLyrics(itemId: String, provider: String, trackUri: String): LyricsResult {
        cachedResult?.let { if (trackUri == cachedTrackUri) return it }

        val trackJson = wsClient.sendCommand(
            MaCommands.Music.TRACKS_GET,
            buildJsonObject {
                put("item_id", itemId)
                put("provider_instance_id_or_domain", provider)
            }
        ) ?: return LyricsResult(null, null).also { cache(trackUri, it) }

        val result = wsClient.sendCommand(
            MaCommands.Metadata.GET_TRACK_LYRICS,
            buildJsonObject { put("track", trackJson) }
        )

        val arr = try {
            result?.jsonArray
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected lyrics response format: ${e.message}")
            null
        } ?: return LyricsResult(null, null).also { cache(trackUri, it) }

        val plain = arr.getOrNull(0)?.takeIf { it.jsonPrimitive.isString }?.jsonPrimitive?.content
        var lrc = arr.getOrNull(1)?.takeIf { it.jsonPrimitive.isString }?.jsonPrimitive?.content
        // Server may put LRC-formatted text in the plain field (embedded tags)
        if (lrc == null && plain != null && looksLikeLrc(plain)) {
            lrc = plain
        }
        return LyricsResult(plain, lrc).also { cache(trackUri, it) }
    }

    fun clearCache() {
        cachedTrackUri = null
        cachedResult = null
    }

    private fun cache(uri: String, result: LyricsResult) {
        cachedTrackUri = uri
        cachedResult = result
    }

    companion object {
        private val LRC_LINE_PATTERN = Regex("""\[\d+:\d+[.:]\d+]""")

        fun looksLikeLrc(text: String): Boolean {
            val firstLines = text.lineSequence().take(5).toList()
            return firstLines.count { LRC_LINE_PATTERN.containsMatchIn(it) } >= 2
        }

        fun parseLrc(lrc: String): List<LrcLine> {
            val regex = Regex("""\[(\d+):(\d+)[.:](\d+)](.*)""")
            return lrc.lines().mapNotNull { line ->
                regex.find(line)?.let { match ->
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val ms = match.groupValues[3].padEnd(3, '0').take(3).toLong()
                    LrcLine((min * 60 + sec) * 1000 + ms, match.groupValues[4].trim())
                }
            }.sortedBy { it.timeMs }
        }
    }
}
