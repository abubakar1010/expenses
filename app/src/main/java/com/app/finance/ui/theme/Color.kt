package com.app.finance.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The *DayBook* palette — 05-ui-ux-guide.md §3.
 *
 * Nine tokens, named for what they are in a ledger rather than for a Material
 * role, because the roles do not survive the translation: `indigo` is the pen
 * that records and `vermilion` is the pen that corrects, and that distinction
 * is the whole colour system.
 *
 * Contrast against the token's intended background, measured before commit:
 *
 * | token     | light      | dark       |
 * |-----------|------------|------------|
 * | ink       | 15.7 : 1   | 14.7 : 1   |
 * | ink-soft  |  5.31 : 1  |  6.93 : 1  |
 * | indigo    |  9.20 : 1  |  8.18 : 1  |
 * | vermilion |  5.47 : 1  |  7.24 : 1  |
 * | moss      |  5.64 : 1  |  8.04 : 1  |
 * | amber     |  5.27 : 1  |  8.84 : 1  |
 *
 * All clear the 4.5:1 body-text floor of NFR-USE-05. `amber` was darkened from
 * an earlier `#9A6B12`, which measured 4.29:1 and failed.
 */
@Immutable
data class DayBookColors(
    /** App background. Warm off-white — not pure white, which glares at the
     *  high brightness this app is read at outdoors. */
    val paper: Color,
    /** Raised surfaces: sheets, input fields, chips. */
    val card: Color,
    /** Hairlines, dividers, the ledger rule. The primary structural device. */
    val rule: Color,
    /** Primary text and figures. */
    val ink: Color,
    /** Labels, secondary text, dates, the ৳ glyph. */
    val inkSoft: Color,
    /** Primary action, selection — "the record ink". */
    val indigo: Color,
    /** Over budget, destructive — "the correction ink". Kept scarce so it
     *  retains its force. */
    val vermilion: Color,
    /** Income, positive net, a healthy budget. */
    val moss: Color,
    /** Approaching a limit (>= 80%). */
    val amber: Color,
    val isLight: Boolean,
)

/**
 * Light is the default, and it is a functional choice rather than a stylistic
 * one: the target device has an LCD panel, so a dark theme saves no battery,
 * and dark themes are markedly harder to read in the direct sunlight this app
 * is often used in.
 */
val LightDayBookColors = DayBookColors(
    paper = Color(0xFFF6F5F1),
    card = Color(0xFFFFFFFF),
    rule = Color(0xFFE4E1D8),
    ink = Color(0xFF141C28),
    inkSoft = Color(0xFF5D6675),
    indigo = Color(0xFF25407A),
    vermilion = Color(0xFFB23A22),
    moss = Color(0xFF3F6B4A),
    amber = Color(0xFF8A5D10),
    isLight = true,
)

/**
 * Not an inversion. Pure black behind pure white causes halation on cheap
 * panels, so both ends pull inward, and the accents lighten and desaturate
 * because a colour tuned against white is illegible against near-black.
 */
val DarkDayBookColors = DayBookColors(
    paper = Color(0xFF12151A),
    card = Color(0xFF1A1E25),
    rule = Color(0xFF2A2F38),
    ink = Color(0xFFE8E6E0),
    inkSoft = Color(0xFF98A0AC),
    indigo = Color(0xFF8FAEE8),
    vermilion = Color(0xFFE88A72),
    moss = Color(0xFF7FB98E),
    amber = Color(0xFFD9AE5A),
    isLight = false,
)

val LocalDayBookColors = staticCompositionLocalOf { LightDayBookColors }
