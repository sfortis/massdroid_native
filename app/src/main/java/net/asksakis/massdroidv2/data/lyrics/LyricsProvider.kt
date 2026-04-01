package net.asksakis.massdroidv2.data.lyrics

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LyricsProvider"
private const val DEBUG_TAG = "LyricsDbg"
private const val LYRICS_REQUEST_TIMEOUT_MS = 2_500L

@Singleton
class LyricsProvider @Inject constructor(
    private val wsClient: MaWebSocketClient
) {
    data class LyricsResult(val plainText: String?, val syncedLrc: String?)
    data class LrcLine(val timeMs: Long, val text: String)
    sealed interface FetchResult {
        data class Found(val lyrics: LyricsResult) : FetchResult
        data object NotFound : FetchResult
        data object Failed : FetchResult
    }

    private var cachedTrackUri: String? = null
    private var cachedResult: LyricsResult? = null

    suspend fun getLyrics(itemId: String, provider: String, trackUri: String): LyricsResult {
        cachedResult?.let { if (trackUri == cachedTrackUri) return it }

        val fallbackResult = fallbackLyricsRequest(
            itemId = itemId,
            provider = provider
        )
        return (parseLyricsResult(fallbackResult) ?: LyricsResult(null, null)).also { cache(trackUri, it) }
    }

    suspend fun fetchLyrics(itemId: String, provider: String, trackUri: String): FetchResult {
        cachedResult?.let {
            if (trackUri == cachedTrackUri) {
                Log.d(DEBUG_TAG, "fetch cache uri=$trackUri plain=${it.plainText != null} synced=${it.syncedLrc != null}")
                return if (it.plainText != null || it.syncedLrc != null) {
                    FetchResult.Found(it)
                } else {
                    FetchResult.NotFound
                }
            }
        }

        val fallbackResult = try {
            fallbackLyricsRequest(itemId = itemId, provider = provider)
        } catch (e: Exception) {
            Log.w(TAG, "fetchLyrics failed: ${e.message}")
            Log.w(DEBUG_TAG, "fetch failed uri=$trackUri error=${e.message}")
            return FetchResult.Failed
        }

        val parsed = parseLyricsResult(fallbackResult) ?: return FetchResult.Failed
        cache(trackUri, parsed)
        Log.d(DEBUG_TAG, "fetch result uri=$trackUri plain=${parsed.plainText != null} synced=${parsed.syncedLrc != null}")
        return if (parsed.plainText != null || parsed.syncedLrc != null) {
            FetchResult.Found(parsed)
        } else {
            FetchResult.NotFound
        }
    }

    fun clearCache() {
        cachedTrackUri = null
        cachedResult = null
    }

    private fun cache(uri: String, result: LyricsResult) {
        cachedTrackUri = uri
        cachedResult = result
    }

    private suspend fun fallbackLyricsRequest(
        itemId: String,
        provider: String
    ): JsonElement? {
        Log.d(DEBUG_TAG, "request track item=$itemId provider=$provider")
        val trackJson = wsClient.sendCommand(
            MaCommands.Music.TRACKS_GET,
            buildJsonObject {
                put("item_id", itemId)
                put("provider_instance_id_or_domain", provider)
            },
            timeoutMs = LYRICS_REQUEST_TIMEOUT_MS
        ) ?: return null

        Log.d(DEBUG_TAG, "request lyrics item=$itemId provider=$provider")
        return try {
            wsClient.sendCommand(
                MaCommands.Metadata.GET_TRACK_LYRICS,
                buildJsonObject { put("track", trackJson) },
                timeoutMs = LYRICS_REQUEST_TIMEOUT_MS
            )
        } catch (e: Exception) {
            Log.w(DEBUG_TAG, "request lyrics failed item=$itemId error=${e.message}")
            null
        }
    }

    private fun parseLyricsResult(result: JsonElement?): LyricsResult? {
        val arr = try {
            result?.jsonArray
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected lyrics response format: ${e.message}")
            null
        } ?: return null

        val plain = arr.getOrNull(0)?.takeIf { it.jsonPrimitive.isString }?.jsonPrimitive?.content
        var lrc = arr.getOrNull(1)?.takeIf { it.jsonPrimitive.isString }?.jsonPrimitive?.content
        if (lrc == null && plain != null && looksLikeLrc(plain)) {
            lrc = plain
        }
        return LyricsResult(plain, lrc)
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
