package com.app.finance.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 05-ui-ux-guide.md §7.
 *
 * Material 3 Expressive moves to spring-based motion with visible overshoot.
 * This app adopts M3's component and accessibility standards and **declines the
 * expressive motion scheme** — for a measurable reason rather than an aesthetic
 * one. Springs run until they settle, producing a variable-length tail of
 * frames; on a Cortex-A53 with Compose recomposition already on the critical
 * path, that tail is exactly where dropped frames appear. A fixed 180 ms tween
 * has a known cost.
 *
 * **Never animated:** list item appearance, numbers counting up, the month
 * ribbon on load, screen entry content, anything decorative. A figure that
 * counts up from zero to ৳1,240 delays the answer to the user's question by
 * 400 ms in order to look impressive.
 */
object Motion {
    const val SHEET_IN = 200
    const val SHEET_OUT = 160
    const val BAR_FILL = 180
    const val CHIP = 80

    /** Fade through. No slide, no shared element. */
    const val SCREEN = 150
    const val SNACKBAR = 150

    /** Material standard decelerate / accelerate. */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val FastOutSlowIn: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Linear: Easing = LinearEasing
}

/**
 * False when the system animator scale is zero.
 *
 * §7 is specific that at zero, animation is **skipped entirely, not shortened**
 * — a 1 ms animation still schedules frames and still arrives late, which is
 * the opposite of what someone who turned animations off asked for.
 */
val LocalAnimationsEnabled: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { true }

@Composable
internal fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/**
 * A tween that collapses to zero duration when the user has turned animation
 * off. Use this instead of [tween] everywhere in the app.
 */
@Composable
fun <T> khataTween(durationMillis: Int, easing: Easing = Motion.FastOutSlowIn): TweenSpec<T> =
    tween(
        durationMillis = if (LocalAnimationsEnabled.current) durationMillis else 0,
        easing = easing,
    )
