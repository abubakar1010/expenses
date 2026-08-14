package com.app.finance.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.app.finance.core.money.Money
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Sizes

/**
 * The month ribbon — 05-ui-ux-guide.md §5.5, "the one element the app should be
 * remembered by".
 *
 * A strip of 28–31 vertical bars, one per day of the period, each bar's height
 * encoding that day's spend against the month's busiest day. Days before today
 * are solid `indigo`; today carries a `vermilion` hairline; the rest are faint
 * dots on the baseline.
 *
 * It communicates four things at once — spending rhythm, whether this week is
 * heavier than last, position in the month, and how much runway is left. The
 * default choice, a donut of category shares, communicates one, and one the
 * user already knows.
 *
 * It is also nearly free: about thirty `drawRect` calls, no library, no layout
 * pass, and **no allocation inside the draw scope** — the caller passes an
 * already-built [dailyTotals] array rather than a list this rebuilds per frame.
 *
 * Never animated on load (§7).
 *
 * @param dailyTotals paisa per day, indexed from day 1. Length is the number of
 *   days in the period.
 * @param todayIndex zero-based index of today, or -1 when viewing another month.
 */
@Composable
fun MonthRibbon(
    dailyTotals: LongArray,
    todayIndex: Int,
    modifier: Modifier = Modifier,
) {
    val colors = KhataTheme.colors
    val peak = dailyTotals.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val spokenTotal = Money(dailyTotals.sum()).spokenForm()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(Sizes.ribbon)
            .clearAndSetSemantics {
                // A per-day readout would be 31 stops in the TalkBack traversal
                // for information the hero figure already states.
                contentDescription = "Daily spending for the period, $spokenTotal in total"
            },
    ) {
        val days = dailyTotals.size
        if (days == 0) return@Canvas

        val slot = size.width / days
        val barWidth = (slot - BAR_GAP.toPx()).coerceAtLeast(1f)
        val radius = CornerRadius(1.dp.toPx())
        val baseline = size.height
        val dotRadius = DOT_RADIUS.toPx()

        for (i in 0 until days) {
            val x = i * slot
            val isFuture = todayIndex >= 0 && i > todayIndex
            val isToday = i == todayIndex

            if (isFuture) {
                // Faint dots on the baseline: the days that have not happened
                // are still part of the month, and the gap is the runway.
                drawCircle(
                    color = colors.rule,
                    radius = dotRadius,
                    center = Offset(x + barWidth / 2f, baseline - dotRadius),
                )
                continue
            }

            val total = dailyTotals[i]
            if (total > 0L) {
                val h = (size.height * (total.toFloat() / peak.toFloat()))
                    .coerceIn(MIN_BAR.toPx(), size.height)
                drawRoundRect(
                    color = colors.indigo,
                    topLeft = Offset(x, baseline - h),
                    size = Size(barWidth, h),
                    cornerRadius = radius,
                )
            } else {
                drawCircle(
                    color = colors.rule,
                    radius = dotRadius,
                    center = Offset(x + barWidth / 2f, baseline - dotRadius),
                )
            }

            if (isToday) {
                drawRect(
                    color = colors.vermilion,
                    topLeft = Offset(x, baseline - TODAY_MARK.toPx()),
                    size = Size(barWidth, TODAY_MARK.toPx()),
                )
            }
        }
    }
}

private val BAR_GAP = 2.dp
private val MIN_BAR = 2.dp
private val DOT_RADIUS = 1.dp
private val TODAY_MARK = 2.dp
