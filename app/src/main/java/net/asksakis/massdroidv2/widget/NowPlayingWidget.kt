package net.asksakis.massdroidv2.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil.imageLoader
import coil.request.ImageRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.domain.player.TransportCommand
import net.asksakis.massdroidv2.domain.widget.NowPlayingWidgetSnapshot
import net.asksakis.massdroidv2.ui.MainActivity

/**
 * Home screen widget: artwork, track, the selected player and transport buttons.
 *
 * Draws from [NowPlayingWidgetStore] only, never from live state, so it renders the
 * same whether the process is warm or was just started to draw it. Two layouts: a
 * single row when the host gives one cell of height, the card otherwise.
 */
class NowPlayingWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun nowPlayingWidgetStore(): NowPlayingWidgetStore
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, FULL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = EntryPointAccessors.fromApplication(context, Dependencies::class.java).nowPlayingWidgetStore()
        val snapshot = store.load()
        val artwork = snapshot.imageUrl?.let { loadArtwork(context, it) }
        provideContent {
            GlanceTheme {
                Content(snapshot, artwork)
            }
        }
    }

    private suspend fun loadArtwork(context: Context, url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ARTWORK_PX)
            .allowHardware(false)
            .build()
        return (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
    }

    @Composable
    private fun Content(snapshot: NowPlayingWidgetSnapshot, artwork: Bitmap?) {
        val compact = LocalSize.current.height < FULL.height
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(20.dp)
                .padding(12.dp)
                .clickable(openApp(LocalContext.current, MainActivity.ACTION_OPEN_NOW_PLAYING))
        ) {
            if (compact) CompactRow(snapshot, artwork) else FullCard(snapshot, artwork)
        }
    }

    @Composable
    private fun CompactRow(snapshot: NowPlayingWidgetSnapshot, artwork: Bitmap?) {
        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork, 44.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                TitleLine(snapshot)
                SubtitleLine(snapshot)
            }
            TransportButtons(snapshot, 36.dp)
        }
    }

    @Composable
    private fun FullCard(snapshot: NowPlayingWidgetSnapshot, artwork: Bitmap?) {
        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork, 96.dp)
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                PlayerLine(snapshot)
                Spacer(GlanceModifier.height(2.dp))
                TitleLine(snapshot)
                SubtitleLine(snapshot)
                Spacer(GlanceModifier.defaultWeight())
                Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TransportButtons(snapshot, 40.dp)
                }
            }
        }
    }

    @Composable
    private fun Artwork(artwork: Bitmap?, sizeDp: androidx.compose.ui.unit.Dp) {
        val modifier = GlanceModifier.size(sizeDp).cornerRadius(12.dp)
        if (artwork != null) {
            Image(
                provider = ImageProvider(artwork),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        } else {
            Box(
                modifier = modifier.background(GlanceTheme.colors.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_music_note),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                    modifier = GlanceModifier.size(sizeDp / 2)
                )
            }
        }
    }

    @Composable
    private fun PlayerLine(snapshot: NowPlayingWidgetSnapshot) {
        val label = when {
            snapshot.playerName.isBlank() -> "No player selected"
            snapshot.connected -> snapshot.playerName
            else -> "${snapshot.playerName} (not connected)"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.clickable(openApp(LocalContext.current, MainActivity.ACTION_OPEN_PLAYERS))
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_speaker),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(14.dp)
            )
            Spacer(GlanceModifier.width(4.dp))
            Text(
                text = label,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            )
        }
    }

    @Composable
    private fun TitleLine(snapshot: NowPlayingWidgetSnapshot) {
        Text(
            text = if (snapshot.hasTrack) snapshot.title else "Nothing playing",
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        )
    }

    @Composable
    private fun SubtitleLine(snapshot: NowPlayingWidgetSnapshot) {
        val text = when {
            snapshot.hasTrack -> snapshot.artist
            snapshot.playerName.isBlank() -> "Open MassDroid to pick a player"
            snapshot.connected -> snapshot.playerName
            else -> "${snapshot.playerName} (not connected)"
        }
        Text(
            text = text,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
        )
    }

    @Composable
    private fun TransportButtons(snapshot: NowPlayingWidgetSnapshot, buttonSize: androidx.compose.ui.unit.Dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransportButton(R.drawable.ic_widget_skip_previous, "Previous", TransportCommand.PREVIOUS, buttonSize)
            TransportButton(
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                if (snapshot.isPlaying) "Pause" else "Play",
                TransportCommand.PLAY_PAUSE,
                buttonSize
            )
            TransportButton(R.drawable.ic_widget_skip_next, "Next", TransportCommand.NEXT, buttonSize)
        }
    }

    @Composable
    private fun TransportButton(
        icon: Int,
        description: String,
        command: TransportCommand,
        buttonSize: androidx.compose.ui.unit.Dp
    ) {
        Box(
            modifier = GlanceModifier
                .size(buttonSize)
                .clickable(actionRunCallback<WidgetTransportAction>(actionParametersOf(WidgetTransportAction.COMMAND to command.name))),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = description,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.size(buttonSize * 3 / 5)
            )
        }
    }

    // Built from the context so the debug build (its own application id) opens itself.
    private fun openApp(context: Context, action: String) = actionStartActivity(
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    companion object {
        private val COMPACT = DpSize(180.dp, 48.dp)
        private val FULL = DpSize(180.dp, 110.dp)
        private const val ARTWORK_PX = 256
    }
}
