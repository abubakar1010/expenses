package com.app.finance.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.finance.R
import com.app.finance.core.money.Money
import com.app.finance.ui.theme.KhataTheme
import java.util.Locale

/**
 * The six-period expense trend with its budget reference — FR-AN-09.
 *
 * PRD §6.4 fixes the medium: "Charts in v1: a single 6-month expense trend line
 * with a budget reference line, and a 12-month income bar series. **Both drawn
 * with the platform canvas — no charting library.**"
 *
 * Built to [MonthRibbon] and [YearBars]' discipline, because it is the same
 * kind of object: a `Canvas`, already-built [LongArray]s rather than lists
 * rebuilt per frame, **no allocation inside the draw scope**, one merged
 * content description rather than six stops in the TalkBack traversal, and
 * never animated on load (05 §7).
 *
 * **A point over the reference is drawn in `vermilion` *and* is called out in
 * the spoken description**, so the one state that matters here does not depend
 * on colour — NFR-USE-05, the same three-signal reasoning as the budget bars.
 * Its position above the dashed line is the third signal, and that one survives
 * greyscale on its own.
 *
 * @param spend paisa per period, oldest first.
 * @param reference planned spend for the same periods; zero where no budget
 *   was set, which draws no line rather than a line at the floor.
 * @param labels one short label per point, from the caller's locale.
 */
@Composable
fun TrendLine(
    spend: LongArray,
    reference: LongArray,
    labels: List<String>,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val colors = KhataTheme.colors
    val overCount = spend.indices.count { reference[it] > 0L && spend[it] > reference[it] }
    val spokenTotal = Money(spend.sum()).spokenForm(locale)
    val description = if (overCount == 0) {
        stringResource(R.string.trend_all_within_budget, spokenTotal)
    } else {
        pluralStringResource(R.plurals.trend_line_description, overCount, spokenTotal, overCount)
    }

    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .clearAndSetSemantics {},
        ) {
            val count = spend.size
            if (count == 0) return@Canvas

            // The scale covers both series, so a month under budget sits
            // visibly below the line rather than the line leaving the chart.
            var peak = 1L
            for (i in 0 until count) {
                if (spend[i] > peak) peak = spend[i]
                if (reference[i] > peak) peak = reference[i]
            }

            val slot = size.width / count
            val left = slot / 2f
            val usable = size.height - DOT_RADIUS.toPx() * 2f
            val top = DOT_RADIUS.toPx()

            fun x(i: Int) = left + i * slot
            fun y(value: Long) = top + usable - (usable * (value.toFloat() / peak.toFloat()))

            // The reference first, so the line and its points sit over it.
            val dash = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF), 0f)
            for (i in 0 until count) {
                if (reference[i] <= 0L) continue
                val yRef = y(reference[i])
                drawLine(
                    color = colors.inkSoft,
                    start = Offset(x(i) - slot / 2f, yRef),
                    end = Offset(x(i) + slot / 2f, yRef),
                    strokeWidth = REFERENCE_STROKE,
                    pathEffect = dash,
                )
            }

            for (i in 0 until count - 1) {
                drawLine(
                    color = colors.indigo,
                    start = Offset(x(i), y(spend[i])),
                    end = Offset(x(i + 1), y(spend[i + 1])),
                    strokeWidth = LINE_STROKE,
                )
            }

            for (i in 0 until count) {
                val over = reference[i] > 0L && spend[i] > reference[i]
                val centre = Offset(x(i), y(spend[i]))
                // Filled for a period over its budget, hollow otherwise — a
                // fill difference as well as a colour one, so the state reads
                // in greyscale.
                if (over) {
                    drawCircle(colors.vermilion, DOT_RADIUS.toPx(), centre)
                } else {
                    drawCircle(colors.paper, DOT_RADIUS.toPx(), centre)
                    drawCircle(
                        color = colors.indigo,
                        radius = DOT_RADIUS.toPx() - LINE_STROKE / 2f,
                        center = centre,
                        style = Stroke(width = LINE_STROKE),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(LABEL_HEIGHT)
                // The axis is a legend for the line above, which already
                // carries the whole description.
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

private val CHART_HEIGHT = 72.dp
private val LABEL_HEIGHT = 16.dp
private val DOT_RADIUS = 3.dp
private const val LINE_STROKE = 3f
private const val REFERENCE_STROKE = 2f
private const val DASH_ON = 6f
private const val DASH_OFF = 5f
