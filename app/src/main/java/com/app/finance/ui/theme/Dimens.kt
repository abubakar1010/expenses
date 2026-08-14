package com.app.finance.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 05-ui-ux-guide.md §5.1 — 4 dp base unit, 8 dp working rhythm.
 *
 * These are `object` constants rather than theme-provided values because they
 * do not vary between light and dark, and a CompositionLocal read costs more
 * than a static field for something that never changes.
 *
 * **Every layout is designed against 288 dp of content on a 320 dp phone** —
 * not 360, not 411. The gutter is what makes that arithmetic work.
 */
object Space {
    /** Between a label and its figure. */
    val s1 = 4.dp

    /** Within a component. */
    val s2 = 8.dp

    /** Screen gutters, and between rows. */
    val s3 = 16.dp

    /** Between sections. */
    val s4 = 24.dp

    /** Above the hero figure. */
    val s5 = 32.dp

    /** The screen gutter, named for the thing it is rather than its size. */
    val gutter = s3
}

/**
 * 05 §6. Deliberately tighter than the Material default — a ledger is made of
 * rectangles, and heavy rounding reads as playful in a context where precision
 * is the message.
 */
object Radius {
    /** Sheets and dialogs. */
    val sheet = 8.dp

    /** Inputs and chips. */
    val input = 4.dp

    /** Budget bars and the month ribbon. */
    val bar = 3.dp

    /** The FAB and a selected chip. The only fully rounded things in the app. */
    val pill = 16.dp

    val sheetTop = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}

/**
 * Component measurements from §6, kept together so a change to the ledger row
 * height is a one-line edit rather than a search.
 */
object Sizes {
    /** Plain ledger row. Clears the 48 dp touch minimum. */
    val rowPlain = 56.dp

    /** Ledger row carrying a budget bar. */
    val rowWithBar = 72.dp

    /** Budget bar thickness. */
    val bar = 6.dp

    /** The month ribbon — one bar per day of the period. */
    val ribbon = 32.dp

    val chip = 32.dp
    val fab = 56.dp
    val navBar = 56.dp
    val navIcon = 24.dp

    /** NFR-USE-04 / accessibility §10: nothing tappable may be smaller. */
    val minTouchTarget = 48.dp

    /** Hairline. The ledger rule is the grid, replacing the Material card. */
    val hairline = 1.dp
}
