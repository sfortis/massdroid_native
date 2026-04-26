package net.asksakis.massdroidv2.ui.components

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * True when the host is Android Automotive OS (AAOS) or projected Android Auto.
 *
 * Detected from the standard `Configuration.UI_MODE_TYPE_CAR` mode bit, which
 * is set by the system on AAOS head units and during Android Auto projection.
 * Provided once at the activity root in [net.asksakis.massdroidv2.ui.MainActivity]
 * and consumed by component-level scaling helpers below.
 *
 * The composable [isCarMode] reads it directly from [LocalConfiguration] for
 * places where threading the CompositionLocal is awkward; both should agree.
 */
val LocalIsCar = compositionLocalOf { false }

@Composable
fun isCarMode(): Boolean {
    val cfg = LocalConfiguration.current
    return (cfg.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR
}

/**
 * Pick a value depending on whether the host is a car. Use at composition time
 * so the same code path renders the larger (76dp+ touch target, 22sp text, etc.)
 * variant on AAOS without forking the component. The phone branch is the
 * canonical default; the car branch should only ever be _bigger_, never smaller.
 */
@Composable
fun <T> carOr(phone: T, car: T): T = if (LocalIsCar.current) car else phone

@Composable
fun carDp(phone: Dp, car: Dp): Dp = carOr(phone, car)

@Composable
fun carSp(phone: TextUnit, car: TextUnit): TextUnit = carOr(phone, car)

@Composable
fun carInt(phone: Int, car: Int): Int = carOr(phone, car)
