package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.domain.model.AudioFormatInfo
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.screens.nowplaying.LyricsAvailability
import net.asksakis.massdroidv2.ui.screens.nowplaying.NowPlayingViewModel

/**
 * Action row that sits between the seek bar and the transport controls.
 * Left side: Add-to-playlist + Lyrics. Centre: audio quality badge (taps
 * open the Streaming Status sheet when the local Sendspin player is
 * selected). Right side: Queue + Dislike + Favorite.
 *
 * Dislike sits next to the heart on purpose: it is the only negative
 * signal the engine does not have to infer, and it has to be as cheap to
 * give as the positive one. Buried in a menu it would never be used, and
 * the alternative the app offered - blocking the whole artist - is far
 * too blunt for one bad track.
 */
@Composable
internal fun QualityActionRow(
    audioFormat: AudioFormatInfo?,
    currentTrack: Track?,
    viewModel: NowPlayingViewModel,
    onShowPlaylistDialog: () -> Unit,
    onShowLyrics: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onShowSendspinStatus: () -> Unit,
    isSendspinPlayer: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    val actionButtonSize = if (compact) 36.dp else 44.dp
    val actionIconSize = if (compact) 18.dp else 24.dp

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(if (compact) 0.88f else 0.92f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MdIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShowPlaylistDialog()
                    },
                    modifier = Modifier.size(actionButtonSize),
                    enabled = enabled
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add to playlist",
                        modifier = Modifier.size(actionIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val isAudiobook by viewModel.isAudiobook.collectAsStateWithLifecycle()
                val lyricsAvailability by viewModel.lyricsAvailability.collectAsStateWithLifecycle()
                val lyricsTint = when (lyricsAvailability) {
                    LyricsAvailability.AVAILABLE -> MaterialTheme.colorScheme.primary
                    LyricsAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    LyricsAvailability.LOADING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    LyricsAvailability.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val lyricsEnabled = lyricsAvailability != LyricsAvailability.LOADING &&
                    lyricsAvailability != LyricsAvailability.UNAVAILABLE
                // Lyrics are meaningless for audiobooks: disable the affordance (the ViewModel
                // also never resolves lyrics for an audiobook, so no API calls). Keep a same-sized
                // spacer so the row's icon alignment / centred quality badge stays put.
                if (isAudiobook) {
                    Spacer(modifier = Modifier.size(actionButtonSize))
                } else {
                    MdIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            Log.d(
                                "LyricsDbg",
                                "icon tap availability=$lyricsAvailability uri=${currentTrack?.uri} title=${currentTrack?.name} enabled=$lyricsEnabled"
                            )
                            when (lyricsAvailability) {
                                LyricsAvailability.AVAILABLE -> onShowLyrics()
                                LyricsAvailability.UNKNOWN -> viewModel.loadLyrics()
                                LyricsAvailability.LOADING,
                                LyricsAvailability.UNAVAILABLE -> Unit
                            }
                        },
                        modifier = Modifier.size(actionButtonSize),
                        enabled = lyricsEnabled
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = "Lyrics",
                            modifier = Modifier.size(actionIconSize),
                            tint = lyricsTint
                        )
                    }
                }
            }
            AudioQualityBadges(
                audioFormat = audioFormat,
                isSendspinPlayer = isSendspinPlayer,
                compact = compact,
                onClick = if (isSendspinPlayer) onShowSendspinStatus else null,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                @Suppress("DEPRECATION")
                MdIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNavigateToQueue()
                    },
                    modifier = Modifier.size(actionButtonSize),
                    enabled = enabled
                ) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        modifier = Modifier.size(actionIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MdIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.dislikeCurrentTrack()
                    },
                    modifier = Modifier.size(actionButtonSize),
                    enabled = enabled
                ) {
                    Icon(
                        Icons.Default.ThumbDown,
                        contentDescription = "Not for me",
                        modifier = Modifier.size(actionIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MdIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleFavorite()
                    },
                    modifier = Modifier.size(actionButtonSize),
                    enabled = enabled
                ) {
                    Icon(
                        if (currentTrack?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Toggle favorite",
                        modifier = Modifier.size(actionIconSize),
                        tint = if (currentTrack?.favorite == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
