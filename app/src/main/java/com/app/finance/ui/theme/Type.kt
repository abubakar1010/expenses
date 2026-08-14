package com.app.finance.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.app.finance.R

/**
 * IBM Plex Mono Medium, subsetted to fourteen glyphs — 5.3 KB.
 *
 * 05-ui-ux-guide.md §4.1 turns the font budget into the type strategy: spend it
 * entirely on the characters the app sets large, and take the system faces for
 * everything else. Plex Mono over Roboto Mono because its digits are flat-sided
 * and slightly condensed, with a machine-ledger quality that suits a record of
 * transactions.
 *
 * Monospaced, so figures are tabular by construction — without that, digits
 * shift width as values change and a scrolling column of amounts visibly
 * jitters.
 *
 * The subset contains digits, comma, period, plus and U+2212 minus. It does
 * *not* contain ৳: IBM Plex Mono is a Latin/Greek/Cyrillic family with no
 * U+09F3. That costs nothing, because §4.3 sets the symbol as a separate 0.7em
 * `ink-soft` span anyway, which resolves through the system Noto Sans Bengali.
 */
val PlexMono = FontFamily(Font(R.font.plex_mono_medium, FontWeight.Medium))

/**
 * Everything that is not a figure. Resolves to Roboto for Latin and Noto Sans
 * Bengali for Bangla, both present on every Android 8+ device at zero bytes.
 */
val SystemSans = FontFamily.Default

/**
 * 05 §4.2. Sizes are `sp`, so the scale respects the system font-size setting;
 * NFR-COMP-04 requires the layouts to survive 0.85x to 1.3x at 320 dp.
 *
 * Only two sans weights ship — 400 and 600. A third adds no clarity and costs a
 * variant.
 */
@Immutable
data class KhataTypography(
    /** Safe-to-spend. The one figure the dashboard exists to show. */
    val heroFigure: TextStyle,
    /** Section totals. */
    val sectionFigure: TextStyle,
    /** Row amounts, in every list. */
    val rowFigure: TextStyle,
    val screenTitle: TextStyle,
    /** Tracked and uppercase — the printed column heading of a ledger page. */
    val sectionHeader: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    /** The ৳ glyph: 0.7em of the figure it precedes, in `ink-soft`, so it does
     *  not compete with the digits. */
    val currencySymbolScale: Float,
)

val KhataType = KhataTypography(
    heroFigure = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.02).em,
    ),
    sectionFigure = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.01).em,
    ),
    rowFigure = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    screenTitle = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    sectionHeader = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em,
    ),
    body = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        // Bengali needs roughly 1.15x the line height of Latin at the same size
        // (taller ascenders, plus the matra). 22sp against 15sp is generous
        // enough for both rather than tight for one.
        lineHeight = 22.sp,
    ),
    caption = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.01.em,
    ),
    currencySymbolScale = 0.7f,
)

/** Applied to the ৳ span so it sets at 0.7em without needing a second style. */
internal val CurrencyGeometry = TextGeometricTransform(scaleX = 1f)

val LocalKhataType = staticCompositionLocalOf { KhataType }
