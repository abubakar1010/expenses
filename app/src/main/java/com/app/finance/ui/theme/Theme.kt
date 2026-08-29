package com.app.finance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Convenience accessors, so components read `DayBookTheme.colors.vermilion`
 * rather than `LocalDayBookColors.current.vermilion`.
 */
object DayBookTheme {
    val colors: DayBookColors
        @Composable @ReadOnlyComposable get() = LocalDayBookColors.current

    val type: DayBookTypography
        @Composable @ReadOnlyComposable get() = LocalDayBookType.current
}

/**
 * The app theme.
 *
 * Material 3 components are used where they are genuinely useful — the modal
 * bottom sheet, the snackbar host — so an M3 `ColorScheme` is derived from the
 * DayBook tokens to keep those consistent. Everything else is drawn directly:
 * 05-ui-ux-guide.md §2 replaces the card-with-shadow with the ruled line, and
 * §6 restricts elevation to exactly three places.
 *
 * **Dynamic colour is off** (§3.4). It requires API 31+ against an API 26
 * floor, so it would ship an inconsistent experience across the target range —
 * and it would hand the semantic budget colours to an algorithm, which is
 * unacceptable when red carries a specific meaning.
 */
@Composable
fun DayBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkDayBookColors else LightDayBookColors

    CompositionLocalProvider(
        LocalDayBookColors provides colors,
        LocalDayBookType provides DayBookType,
        LocalAnimationsEnabled provides rememberAnimationsEnabled(),
        LocalTextStyle provides DayBookType.body.copy(color = colors.ink),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(darkTheme),
            typography = colors.toMaterialTypography(),
            content = content,
        )
    }
}

/**
 * Maps the nine DayBook tokens onto the M3 roles the handful of borrowed
 * components read. Only the roles actually reachable are meaningful; the rest
 * are filled with the nearest sensible token so that nothing renders in
 * Material's default purple if a component is added later without review.
 */
private fun DayBookColors.toMaterialScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = indigo,
        onPrimary = paper,
        secondary = moss,
        onSecondary = paper,
        error = vermilion,
        onError = paper,
        background = paper,
        onBackground = ink,
        surface = card,
        onSurface = ink,
        surfaceVariant = card,
        onSurfaceVariant = inkSoft,
        outline = rule,
        outlineVariant = rule,
        // The snackbar is an `ink` surface with `paper` text (§6).
        inverseSurface = ink,
        inverseOnSurface = paper,
        scrim = ink,
    )
} else {
    lightColorScheme(
        primary = indigo,
        onPrimary = card,
        secondary = moss,
        onSecondary = card,
        error = vermilion,
        onError = card,
        background = paper,
        onBackground = ink,
        surface = card,
        onSurface = ink,
        surfaceVariant = card,
        onSurfaceVariant = inkSoft,
        outline = rule,
        outlineVariant = rule,
        inverseSurface = ink,
        inverseOnSurface = paper,
        scrim = ink,
    )
}

private fun DayBookColors.toMaterialTypography() = Typography(
    titleLarge = DayBookType.screenTitle,
    titleMedium = DayBookType.screenTitle,
    bodyLarge = DayBookType.body,
    bodyMedium = DayBookType.body,
    bodySmall = DayBookType.caption,
    labelLarge = DayBookType.body,
    labelMedium = DayBookType.caption,
    labelSmall = DayBookType.caption,
)
