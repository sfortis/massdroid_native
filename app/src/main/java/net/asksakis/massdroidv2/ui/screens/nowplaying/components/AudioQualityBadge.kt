package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.model.AudioFormatInfo
import net.asksakis.massdroidv2.ui.screens.nowplaying.SendspinStatusUi

/**
 * Quality tier classification matching the MA web UI's QualityDetailsBtn
 * component. Order from worst to best:
 *
 *  - LQ : low-bitrate lossy (< 256 kbps)
 *  - SQ : standard-quality lossy (≥ 256 kbps)
 *  - HQ : CD-quality lossless (FLAC/ALAC/WAV/AIFF/PCM at 16-bit/44.1-48kHz)
 *  - HR : Hi-Res (> 48 kHz sample rate OR > 16-bit depth)
 */
internal enum class AudioQualityTier(val label: String) {
    LQ("LQ"),
    SQ("SQ"),
    HQ("HQ"),
    HR("HR"),
}

internal fun isLosslessCodec(codec: String?): Boolean {
    if (codec.isNullOrBlank()) return false
    val c = codec.uppercase()
    return c == "FLAC" || c == "ALAC" || c == "WAV" || c == "AIFF" ||
        c == "PCM" || c.startsWith("DSF") || c == "WAVPACK" || c == "TAK" ||
        c.startsWith("DSD")
}

internal fun audioQualityTier(audioFormat: AudioFormatInfo?): AudioQualityTier? {
    val ct = audioFormat?.contentType ?: return null
    val sampleRate = audioFormat.sampleRate ?: 0
    val bitDepth = audioFormat.bitDepth ?: 0
    val bitRate = audioFormat.bitRate ?: 0
    if (sampleRate > 48_000 || bitDepth > 16) return AudioQualityTier.HR
    if (isLosslessCodec(ct)) return AudioQualityTier.HQ
    if (bitRate >= 256) return AudioQualityTier.SQ
    return AudioQualityTier.LQ
}

@Composable
internal fun qualityTierColor(tier: AudioQualityTier): Color = when (tier) {
    // Mirrors the visual hierarchy of the web UI without copying its exact
    // palette — Material 3 tokens give a consistent feel with the rest of
    // the app while still being immediately recognisable.
    AudioQualityTier.LQ -> MaterialTheme.colorScheme.error
    AudioQualityTier.SQ -> MaterialTheme.colorScheme.tertiary
    AudioQualityTier.HQ -> Color(0xFF4CAF50) // green for "lossless CD"
    AudioQualityTier.HR -> Color(0xFF9C27B0) // purple for "hi-res"
}

/**
 * Compact codec/sample-rate label used inside the Streaming Status sheet
 * (e.g. "FLAC 44.1/16"). Returns null when there isn't enough info to
 * render so callers can fall back to "Unknown".
 */
internal fun formatSampleRateCompact(sampleRate: Int): String {
    val khz = sampleRate / 1000.0
    val whole = khz.toInt()
    return if (whole.toDouble() == khz) "$whole" else "%.1f".format(java.util.Locale.US, khz)
}

internal fun formatAudioDescriptor(format: AudioFormatInfo?): String? {
    format ?: return null
    val codec = format.contentType?.replace('_', ' ')?.uppercase()?.takeIf { it.isNotBlank() && it != "?" } ?: return null
    val sampleRate = format.sampleRate ?: return codec
    val sampleStr = formatSampleRateCompact(sampleRate)
    val bitDepth = format.bitDepth
    val showBits = isLosslessCodec(codec) && bitDepth != null && bitDepth > 0
    return if (showBits) "$codec $sampleStr/$bitDepth" else "$codec $sampleStr"
}

/**
 * Sendspin transport-side output descriptor (post server re-encode). The
 * sample rate / bit depth come from the stream/start payload captured in
 * SendspinStatusUi.
 */
internal fun formatOutputDescriptor(status: SendspinStatusUi): String? {
    val codec = status.codec?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
    if (status.outputSampleRate <= 0) return codec
    val sampleStr = formatSampleRateCompact(status.outputSampleRate)
    return if (status.outputBitDepth > 0) "$codec $sampleStr/${status.outputBitDepth}"
    else "$codec $sampleStr"
}

/**
 * Now-Playing audio quality badge: shows a colored tier dot (LQ/SQ/HQ/HR)
 * alongside the tier label. When [onClick] is non-null the entire chip is
 * tappable — the caller wires this to open the Streaming Status sheet
 * for the local Sendspin player. Falls back to a plain "Sendspin" label
 * when we don't yet know the source audio format but the player is the
 * local Sendspin so the badge is never empty during track changes.
 */
@Composable
internal fun AudioQualityBadges(
    audioFormat: AudioFormatInfo?,
    isSendspinPlayer: Boolean = false,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val tier = remember(audioFormat) { audioQualityTier(audioFormat) }
    val fallbackLabel = if (tier == null && isSendspinPlayer) "Sendspin" else null
    if (tier == null && fallbackLabel == null) return

    Row(
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(999.dp)).clickable { onClick() } else Modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (tier != null) {
                    Box(
                        modifier = Modifier
                            .size(if (compact) 6.dp else 8.dp)
                            .clip(CircleShape)
                            .background(qualityTierColor(tier))
                    )
                }
                Text(
                    text = tier?.label ?: fallbackLabel!!,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
