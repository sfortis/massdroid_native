package net.asksakis.massdroidv2.widget

import android.annotation.SuppressLint
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
        // Read from the context because this is Glance, which has no LocalConfiguration of
        // its own; the check that asks for one is written for Compose UI.
        @SuppressLint("LocalContextConfigurationRead")
        val fontScale = LocalContext.current.resources.configuration.fontScale
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
                // Ordered by what the card can hold, widest layout first. The wide and
                // tall cards claim a height only when their own rows fit in it, so a
                // host between one and two cells lands on the compact family instead of
                // a layout that would overflow its bottom edge.
                when {
                    innerHeight > innerWidth * TALL_ASPECT ->
                        TallCard(snapshot, artwork, innerWidth, innerHeight, fontScale)
                    innerHeight >= fullCardMinHeight(fontScale) ->
                        FullCard(snapshot, artwork, innerWidth, innerHeight, fontScale)
                    compactTextWidth(innerWidth, innerHeight) >= COMPACT_MIN_TEXT_WIDTH ->
                        CompactRow(snapshot, artwork, innerHeight)
                    innerHeight >= stackMinHeight(fontScale) ->
                        CompactStack(snapshot, artwork, innerHeight, fontScale)
                    else -> MinimalRow(snapshot, artwork, innerHeight)
                }
            }
        }
    }

    /**
     * What is left for the title and artist once the single-row layout has taken its
     * artwork, its gaps and its three buttons. Narrow hosts leave nothing: a widget
     * three cells wide is 222dp inside, and the row wants 56 for the artwork, 20 for
     * the gaps and 152 for the buttons, so the text was squeezed to a few characters
     * and read "Nothing pl...".
     */
    private fun compactTextWidth(innerWidth: Dp, innerHeight: Dp): Dp {
        val play = innerHeight.coerceIn(COMPACT_PLAY_MIN, COMPACT_PLAY_MAX)
        val transport = MIN_TOUCH_TARGET * 2 + play + TRANSPORT_GAPS
        return innerWidth - innerHeight - COMPACT_ROW_GAPS - transport
    }

    /**
     * The height one line of text at [sizeSp] takes on the card. A widget lays its
     * children out in dp while text is measured in sp, so the viewer's font scale is
     * what decides how tall a line really is: at a scale of 1.15 the 13sp title needs
     * about 19dp, not the 16 a fixed constant assumed. Every budget below is built from
     * this rather than from a constant, because the ones that were not came up short by
     * that difference and clipped whatever sat under the text.
     */
    private fun textLineHeight(sizeSp: Float, fontScale: Float): Dp =
        (sizeSp * fontScale * LINE_HEIGHT_RATIO).dp

    /**
     * The title, the gap and the smallest buttons. A card shorter than this cannot be
     * laid out as a column at all and falls back to the minimal row.
     */
    private fun stackMinHeight(fontScale: Float): Dp =
        textLineHeight(STACK_TITLE_SP, fontScale) + STACK_TEXT_GAP + STACK_PLAY_MIN

    /**
     * The chip, the two text rows and the spacers between them in the wide layout,
     * at the viewer's font scale. What is left of the card's height goes to the
     * buttons.
     */
    private fun fullTextBlock(fontScale: Float): Dp =
        textLineHeight(CHIP_SP, fontScale) + CHIP_VERTICAL_PADDING + FULL_CHIP_GAP +
            textLineHeight(FULL_TITLE_SP, fontScale) +
            textLineHeight(FULL_ARTIST_SP, fontScale) + FULL_TEXT_GAP

    /**
     * The height the wide layout needs for its rows plus buttons at a usable size.
     * A card shorter than this is laid out by the compact family instead, which was
     * the gap a one-and-a-half cell host used to fall into.
     */
    private fun fullCardMinHeight(fontScale: Float): Dp = fullTextBlock(fontScale) + FULL_PLAY_MIN

    /** The chip, title and artist rows of the tall layout, at the viewer's font scale. */
    private fun tallTextBlock(fontScale: Float): Dp =
        textLineHeight(CHIP_SP, fontScale) + CHIP_VERTICAL_PADDING +
            textLineHeight(TALL_TITLE_SP, fontScale) +
            textLineHeight(TALL_ARTIST_SP, fontScale)

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
     * One cell high but too narrow for a single row: the artwork keeps the left, and
     * the rest is a column with the title above the buttons. Stacking them gives the
     * title the whole width instead of the sliver left beside three buttons.
     *
     * The artist is not shown at this size. A card one cell high fits one line of text
     * and a row of buttons, and a second line took its height out of the buttons until
     * they were clipped by the bottom edge. The title and the transport are what this
     * size is for; the artist returns on the taller cards.
     *
     * The buttons take whatever height the title leaves, within bounds, so the column
     * cannot outgrow the card however short the host makes it. They end up below
     * Material's 48dp target, which a row this size cannot honour and still show a
     * readable title; the system's own media widgets make the same trade here.
     */
    @Composable
    private fun CompactStack(
        snapshot: NowPlayingWidgetSnapshot,
        artwork: Bitmap?,
        innerHeight: Dp,
        fontScale: Float
    ) {
        val play = (innerHeight - textLineHeight(STACK_TITLE_SP, fontScale) - STACK_TEXT_GAP)
            .coerceIn(STACK_PLAY_MIN, STACK_PLAY_MAX)
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(artwork, innerHeight, 12.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TitleLine(snapshot, STACK_TITLE_SP.sp, centred = true)
                Spacer(GlanceModifier.height(STACK_TEXT_GAP))
                TransportButtons(snapshot, sideSize = play * STACK_SIDE_RATIO, playSize = play)
            }
        }
    }

    /**
     * Too narrow for a row and too short for a stack, which the host allows: the widget
     * may be resized down to 56dp, leaving 28dp inside. Only the title and play fit,
     * and showing those two properly beats showing five things clipped.
     */
    @Composable
    private fun MinimalRow(snapshot: NowPlayingWidgetSnapshot, artwork: Bitmap?, innerHeight: Dp) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(artwork, innerHeight, 10.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                TitleLine(snapshot, 13.sp, centred = false)
            }
            Spacer(GlanceModifier.width(8.dp))
            IconButton(
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                if (snapshot.isPlaying) "Pause" else "Play",
                TransportCommand.PLAY_PAUSE,
                innerHeight.coerceAtMost(STACK_PLAY_MAX),
                filled = true
            )
        }
    }

    /**
     * Two or more cells high: the artwork takes the card's full height on the left (capped
     * so text keeps room on narrow hosts); the rest is one column centred both ways with
     * the player chip, title, artist and the buttons, all centred on the same axis.
     */
    @Composable
    private fun FullCard(
        snapshot: NowPlayingWidgetSnapshot,
        artwork: Bitmap?,
        innerWidth: Dp,
        innerHeight: Dp,
        fontScale: Float
    ) {
        val art = minOf(innerHeight, innerWidth * 0.42f)
        // A share of the height, but never more than the text rows leave. The share
        // alone used to win on a short card and on a large font scale, and the buttons
        // were the row that fell off the bottom.
        val play = (innerHeight * FULL_PLAY_RATIO)
            .coerceAtMost(innerHeight - fullTextBlock(fontScale))
            .coerceIn(FULL_PLAY_MIN, FULL_PLAY_MAX)
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
                Spacer(GlanceModifier.height(FULL_CHIP_GAP))
                TitleLine(snapshot, FULL_TITLE_SP.sp, centred = true)
                SubtitleLine(snapshot, FULL_ARTIST_SP.sp, centred = true)
                Spacer(GlanceModifier.height(FULL_TEXT_GAP))
                TransportButtons(snapshot, sideSize = (play * 0.8f).coerceAtLeast(MIN_TOUCH_TARGET), playSize = play)
            }
        }
    }

    /**
     * Taller than wide, the way a widget stretched to three or four rows ends up: a small
     * player. The artwork takes the width (capped so the text and buttons keep their
     * rows), then the chip, the two text lines and the buttons, all centred.
     */
    @Composable
    private fun TallCard(
        snapshot: NowPlayingWidgetSnapshot,
        artwork: Bitmap?,
        innerWidth: Dp,
        innerHeight: Dp,
        fontScale: Float
    ) {
        // Everything under the artwork is a fixed stack; the artwork gets what is left.
        // The chip, title and artist rows are measured at the viewer's font scale rather
        // than assumed, so a larger scale takes its extra height out of the artwork
        // instead of pushing the buttons off the bottom of the card.
        val play = TALL_PLAY_SIZE
        val reserved = tallTextBlock(fontScale) + TALL_SPACERS + play + TALL_SAFETY
        val art = minOf(innerWidth, innerHeight - reserved).coerceAtLeast(96.dp)
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Artwork(artwork, art, 24.dp)
            Spacer(GlanceModifier.height(12.dp))
            PlayerChip(snapshot)
            Spacer(GlanceModifier.height(8.dp))
            TitleLine(snapshot, TALL_TITLE_SP.sp, centred = true)
            SubtitleLine(snapshot, TALL_ARTIST_SP.sp, centred = true)
            Spacer(GlanceModifier.height(12.dp))
            TransportButtons(snapshot, sideSize = (play * 0.8f).coerceAtLeast(MIN_TOUCH_TARGET), playSize = play)
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
                    fontSize = CHIP_SP.sp,
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
        private const val ARTWORK_PX = 320
        private val CARD_PADDING = 14.dp
        /** Material's minimum touch target; the side buttons never shrink below it. */
        private val MIN_TOUCH_TARGET = 48.dp
        /** The baked backdrop never exceeds this width; RemoteViews bitmaps count against a small budget. */
        private const val BACKDROP_MAX_PX = 640
        private const val HALF_LUMINANCE = 0.5
        /** The single-row layout needs at least this much width for the title to be worth showing. */
        private val COMPACT_MIN_TEXT_WIDTH = 96.dp

        /** Play button bounds of the single-row layout, and the gaps either layout spends. */
        private val COMPACT_PLAY_MIN = 40.dp
        private val COMPACT_PLAY_MAX = 56.dp
        private val COMPACT_ROW_GAPS = 20.dp
        private val TRANSPORT_GAPS = 16.dp

        /**
         * How much taller than its font size a line of text sits, covering the ascent,
         * the descent and the leading the platform adds. Roboto needs about 1.2; the
         * margin above that carries the taller metrics of a device font or of a script
         * with accents, which a widget cannot measure before it lays itself out.
         */
        private const val LINE_HEIGHT_RATIO = 1.3f

        /** Text sizes the layouts reserve height for, in sp, so the budget and the Text agree. */
        private const val STACK_TITLE_SP = 13f
        private const val CHIP_SP = 12f
        private const val FULL_TITLE_SP = 17f
        private const val FULL_ARTIST_SP = 14f
        private const val TALL_TITLE_SP = 18f
        private const val TALL_ARTIST_SP = 14f

        /** The chip's own padding, above and below its label. */
        private val CHIP_VERTICAL_PADDING = 10.dp

        /** Clear air between the text and the buttons, which is what was missing. */
        private val STACK_TEXT_GAP = 6.dp
        private val STACK_PLAY_MIN = 26.dp

        /**
         * The buttons take whatever the title leaves, so this cap is what stops them
         * filling a card that has room to spare once the artist is gone.
         */
        private val STACK_PLAY_MAX = 44.dp

        /** Side buttons against the play button, kept close so the row reads as one control. */
        private const val STACK_SIDE_RATIO = 0.9f

        /** Gaps of the wide layout, above and below its two text rows. */
        private val FULL_CHIP_GAP = 8.dp
        private val FULL_TEXT_GAP = 10.dp

        /**
         * Play button bounds of the wide layout. The minimum is Material's touch target,
         * which this size of card can honour, and it is also what decides the height the
         * layout asks for before it will take a card at all.
         */
        private val FULL_PLAY_MIN = 48.dp
        private val FULL_PLAY_MAX = 72.dp

        /** The share of the card height the play button takes when there is room for it. */
        private const val FULL_PLAY_RATIO = 0.3f

        /** Above this height-to-width ratio the card is laid out as a small player. */
        private const val TALL_ASPECT = 0.75f
        private val TALL_SPACERS = 32.dp
        private val TALL_PLAY_SIZE = 64.dp
        private val TALL_SAFETY = 16.dp
    }
}
