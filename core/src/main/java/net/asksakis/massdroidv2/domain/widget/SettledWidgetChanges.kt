package net.asksakis.massdroidv2.domain.widget

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * How long what the widget shows has to stay the same before it is drawn. Every draw opens a
 * Glance session, which runs for at least 45 s under a WorkManager wake lock whatever the widget
 * does. A group that flips between playing and paused every few seconds, or a title that changes
 * and changes back, then costs one session instead of one per flip. The widget lags a real change
 * by this much.
 */
const val WIDGET_SETTLE_MS = 2_000L

/**
 * The inputs whose change the widget should draw. [shown] gives what the widget would show for an
 * input. An input that shows the same as the one before is dropped, and a change is passed on once
 * nothing the widget shows has changed for [settleMs].
 *
 * Equal inputs are dropped before the wait, not after it. The selected player updates far more
 * often than every 2 s (elapsed time, volume, every server event), and those updates must not
 * restart the wait, or a change would never be drawn while a player plays.
 */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.settledWidgetChanges(
    shown: (T) -> Any?,
    settleMs: Long = WIDGET_SETTLE_MS,
): Flow<T> = distinctUntilChanged { old, new -> shown(old) == shown(new) }.debounce(settleMs)
