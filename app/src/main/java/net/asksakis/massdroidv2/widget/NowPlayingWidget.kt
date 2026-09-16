package net.asksakis.massdroidv2.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.unit.TextUnit
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
import androidx.glance.appwidget.appWidgetBackground
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
import androidx.glance.text.TextAlign
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

    // Exact, not Responsive: with breakpoints the composition is told the breakpoint's size,
    // not the host's, so the artwork stopped short of the card's height and nothing could be
    // centred in the space actually available. One widget re-rendering per resize is cheap.
    override val sizeMode: SizeMode = SizeMode.Exact

    /**
     * The render session Glance keeps open re-composes on an update but does not call
     * this method again, so a snapshot read here once went stale: the card kept showing
     * the state from the first draw. The composition therefore observes the store, and
     * every save re-draws the placed widgets through the live session.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = EntryPointAccessors.fromApplication(context, Dependencies::class.java).nowPlayingWidgetStore()
        val initial = store.load()
        provideContent {
            val snapshot by store.snapshots.collectAsState(initial)
            val artwork by produceState<Bitmap?>(initialValue = null, key1 = snapshot.imageUrl) {
                value = snapshot.imageUrl?.let { loadArtwork(context, it) }
            }
            GlanceTheme {
                val size = LocalSize.current
                val surface = GlanceTheme.colors.surface.getColor(context).toArgb()
                val isDark = ColorUtils.calculateLuminance(surface) < HALF_LUMINANCE
                val density = context.resources.displayMetrics.density
                val backdrop by produceState<Bitmap?>(initialValue = null, snapshot.imageUrl, size, surface) {
                    val url = snapshot.imageUrl
                    value = if (url == null) null else {
                        val w = (size.width.value * density).toInt().coerceAtMost(BACKDROP_MAX_PX)
                        val h = (w * size.height.value / size.width.value).toInt()
                        WidgetBackdrop.render(context, url, w, h, surface, isDark)
                    }
                }
                Content(snapshot, artwork, backdrop)
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
    private fun Content(snapshot: NowPlayingWidgetSnapshot, artwork: Bitmap?, backdrop: Bitmap?) {
        val size = LocalSize.current
        val innerHeight = size.height - CARD_PADDING * 2
        val innerWidth = size.width - CARD_PADDING * 2
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                // appWidgetBackground marks the root the launcher clips and themes; on
                // Android 12+ the corner radius is the system's, so the card matches every
                // other widget on the same home screen instead of a value of our own.
                .appWidgetBackground()
                .background(GlanceTheme.colors.surface)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
                .clickable(openApp(LocalContext.current, MainActivity.ACTION_OPEN_NOW_PLAYING)),
            contentAlignment = Alignment.Center
        ) {
            if (backdrop != null) {
                Image(
                    provider = ImageProvider(backdrop),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize()
                )
            }
            Box(modifier = GlanceModifier.fillMaxSize().padding(CARD_PADDING), contentAlignment = Alignment.Center) {
                if (innerHeight < COMPACT_MAX_HEIGHT) {
                    CompactRow(snapshot, artwork, innerHeight)
                } else {
                    FullCard(snapshot, artwork, innerWidth, innerHeight)
                }
            }
        }
    }

    /** One cell high: artwork, two text lines and the buttons in a single centred row. */
    @Composable
    private fun CompactRow(snapshot: NowPlayingWidgetSnapshot, artwork: Bitmap?, innerHeight: Dp) {
        val play = innerHeight.coerceIn(40.dp, 56.dp)
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Artwork(artwork, innerHeight, 14.dp)
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                TitleLine(snapshot, 15.sp, centred = false)
                SubtitleLine(snapshot, 13.sp, centred = false)
            }
            Spacer(GlanceModifier.width(8.dp))
            TransportButtons(snapshot, sideSize = (play * 0.8f).coerceAtLeast(MIN_TOUCH_TARGET), playSize = play)
        }
    }

    /**
     * Two or more cells high: the artwork takes the card's full height on the left (capped
     * so text keeps room on narrow hosts); the rest is one column centred both ways with
     * the player chip, title, artist and the buttons, all centred on the same axis.
     */
    @Composable
    private fun FullCard(snapshot: NowPlayingWidgetSnapshot, artwork: Bitmap?, innerWidth: Dp, innerHeight: Dp) {
        val art = minOf(innerHeight, innerWidth * 0.42f)
        val play = (innerHeight * 0.3f).coerceIn(48.dp, 72.dp)
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Artwork(artwork, art, 24.dp)
            Spacer(GlanceModifier.width(16.dp))
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlayerChip(snapshot)
                Spacer(GlanceModifier.height(8.dp))
                TitleLine(snapshot, 17.sp, centred = true)
                SubtitleLine(snapshot, 14.sp, centred = true)
                Spacer(GlanceModifier.height(10.dp))
                TransportButtons(snapshot, sideSize = (play * 0.8f).coerceAtLeast(MIN_TOUCH_TARGET), playSize = play)
            }
        }
    }

    @Composable
    private fun Artwork(artwork: Bitmap?, sizeDp: Dp, radius: Dp) {
        val modifier = GlanceModifier.size(sizeDp).cornerRadius(radius)
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

    /** The selected player as an assist-chip: tonal pill with a speaker icon; opens the Players screen. */
    @Composable
    private fun PlayerChip(snapshot: NowPlayingWidgetSnapshot) {
        val label = when {
            snapshot.playerName.isBlank() -> "No player"
            snapshot.connected -> snapshot.playerName
            else -> "${snapshot.playerName} (offline)"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(16.dp)
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .clickable(openApp(LocalContext.current, MainActivity.ACTION_OPEN_PLAYERS))
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_speaker),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier.size(14.dp)
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = label,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSecondaryContainer,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }

    @Composable
    private fun TitleLine(snapshot: NowPlayingWidgetSnapshot, size: TextUnit, centred: Boolean) {
        Text(
            text = if (snapshot.hasTrack) snapshot.title else "Nothing playing",
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = size,
                fontWeight = FontWeight.Bold,
                textAlign = if (centred) TextAlign.Center else TextAlign.Start
            )
        )
    }

    @Composable
    private fun SubtitleLine(snapshot: NowPlayingWidgetSnapshot, size: TextUnit, centred: Boolean) {
        val text = when {
            snapshot.hasTrack -> snapshot.artist
            snapshot.playerName.isBlank() -> "Open MassDroid to pick a player"
            snapshot.connected -> snapshot.playerName
            else -> "${snapshot.playerName} (not connected)"
        }
        Text(
            text = text,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = size,
                textAlign = if (centred) TextAlign.Center else TextAlign.Start
            )
        )
    }

    /**
     * Previous and next as plain icon buttons, play/pause as a filled tonal circle in
     * between, the Material 3 arrangement every media notification uses.
     */
    @Composable
    private fun TransportButtons(
        snapshot: NowPlayingWidgetSnapshot,
        sideSize: Dp,
        playSize: Dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(R.drawable.ic_widget_skip_previous, "Previous", TransportCommand.PREVIOUS, sideSize, filled = false)
            Spacer(GlanceModifier.width(8.dp))
            IconButton(
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                if (snapshot.isPlaying) "Pause" else "Play",
                TransportCommand.PLAY_PAUSE,
                playSize,
                filled = true
            )
            Spacer(GlanceModifier.width(8.dp))
            IconButton(R.drawable.ic_widget_skip_next, "Next", TransportCommand.NEXT, sideSize, filled = false)
        }
    }

    @Composable
    private fun IconButton(
        icon: Int,
        description: String,
        command: TransportCommand,
        buttonSize: Dp,
        filled: Boolean
    ) {
        val base = GlanceModifier
            .size(buttonSize)
            .cornerRadius(buttonSize / 2)
            .clickable(actionRunCallback<WidgetTransportAction>(actionParametersOf(WidgetTransportAction.COMMAND to command.name)))
        Box(
            modifier = if (filled) base.background(GlanceTheme.colors.primary) else base,
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = description,
                colorFilter = ColorFilter.tint(
                    if (filled) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurface
                ),
                modifier = GlanceModifier.size(buttonSize * 9 / 16)
            )
        }
    }

    // Built from the context so the debug build (its own application id) opens itself.
    private fun openApp(context: Context, action: String) = actionStartActivity(
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    companion object {
        /** Below this inner height the card is one cell high and uses the single-row layout. */
        private val COMPACT_MAX_HEIGHT = 84.dp
        private const val ARTWORK_PX = 320
        private val CARD_PADDING = 14.dp
        /** Material's minimum touch target; the side buttons never shrink below it. */
        private val MIN_TOUCH_TARGET = 48.dp
        /** The baked backdrop never exceeds this width; RemoteViews bitmaps count against a small budget. */
        private const val BACKDROP_MAX_PX = 640
        private const val HALF_LUMINANCE = 0.5
    }
}
