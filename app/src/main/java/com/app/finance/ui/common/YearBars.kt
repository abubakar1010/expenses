package com.app.finance.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.ui.theme.KhataTheme
import com.app.finance.ui.theme.Space
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * The twelve income bars — 05-ui-ux-guide.md §5.7.
 *
 * > `▁▁█▁▁▁▁▇▁▁█▁`
 * > `J F M A M J J A S O N D`
 * > "12-month bars — the lumpiness is the information"
 *
 * That caption is the whole design brief. PRD §1: standard apps assume a fixed
 * monthly salary, "so their 'monthly average' figures are meaningless when five
 * months earn nothing and the sixth earns a year's worth". Smoothing this into
 * a line, or scaling the bars against anything but the window's own peak, would
 * hide the one thing the user opened the screen to see.
 *
 * Built to [MonthRibbon]'s discipline, because it is the same kind of object: a
 * `Canvas`, an already-built [LongArray] rather than a list rebuilt per frame,
 * **no allocation inside the draw scope**, no charting library (PRD §6.4 —
 * "drawn with the platform canvas"), and one merged content description rather
 * than twelve stops in the TalkBack traversal. Never animated on load (05 §7).
 *
 * A month that earned nothing draws a baseline dot rather than nothing at all:
 * the empty months are part of the shape, and a blank would read as missing
 * data instead of as a season without income.
 *
 * @param monthlyTotals paisa per bar, in the same order as [labels].
 * @param labels one initial per bar, from the locale's own month names.
 * @param locale the composition's locale, not `Locale.getDefault()`. The
 *   spoken total picks its numbering scale from it — lakh and crore rather than
 *   thousands — and reading the system locale here would announce this one
 *   figure on a different scale from every other figure on the screen.
 */
@Composable
fun YearBars(
    monthlyTotals: LongArray,
    labels: List<String>,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val colors = KhataTheme.colors
    val peak = monthlyTotals.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val description = stringResource(
        R.string.trend_description,
        Money(monthlyTotals.sum()).spokenForm(locale),
    )

    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(BAR_AREA)
                .clearAndSetSemantics {},
        ) {
            val count = monthlyTotals.size
            if (count == 0) return@Canvas

            val slot = size.width / count
            val barWidth = (slot - BAR_GAP.toPx()).coerceAtLeast(1f)
            val radius = CornerRadius(BAR_RADIUS.toPx())
            val baseline = size.height
            val dotRadius = DOT_RADIUS.toPx()

            for (i in 0 until count) {
                val x = i * slot + (slot - barWidth) / 2f
                val total = monthlyTotals[i]

                if (total <= 0L) {
                    drawCircle(
                        color = colors.rule,
                        radius = dotRadius,
                        center = Offset(x + barWidth / 2f, baseline - dotRadius),
                    )
                    continue
                }

                val height = (size.height * (total.toFloat() / peak.toFloat()))
                    .coerceIn(MIN_BAR.toPx(), size.height)
                drawRoundRect(
                    // `moss` — 05 §3.1 assigns it to "income, positive net".
                    color = colors.moss,
                    topLeft = Offset(x, baseline - height),
                    size = Size(barWidth, height),
                    cornerRadius = radius,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(LABEL_HEIGHT)
                // The axis is a legend for the bars above, which already carry
                // the whole description. Twelve single letters in the traversal
                // would be noise.
                .clearAndSetSemantics {},
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    style = KhataTheme.type.caption,
                    color = colors.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One initial per month, in the device locale — so a Bengali build gets Bengali
 * initials rather than `J F M` transliterated by hand.
 *
 * `TextStyle.NARROW` is already the one-or-two character form; the extra
 * `take(1)` is for the locales whose narrow form is still two characters wide,
 * which would break the twelve-column grid at 320 dp.
 */
fun monthInitials(locale: Locale, months: List<Int>): List<String> =
    months.map { Month.of(it).getDisplayName(TextStyle.NARROW, locale).take(1) }

private val BAR_AREA = 56.dp
private val LABEL_HEIGHT = 16.dp
private val BAR_GAP = Space.s1
private val BAR_RADIUS = 1.dp
private val MIN_BAR = 2.dp
private val DOT_RADIUS = 1.dp
