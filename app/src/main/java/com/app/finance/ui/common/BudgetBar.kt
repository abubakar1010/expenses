package com.app.finance.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.app.finance.domain.model.BudgetState
import com.app.finance.domain.model.BudgetStatus
import com.app.finance.ui.theme.DayBookTheme
import com.app.finance.ui.theme.Motion
import com.app.finance.ui.theme.Sizes
import com.app.finance.ui.theme.dayBookTween

/**
 * The budget bar — 05-ui-ux-guide.md §6.
 *
 * 6 dp tall, 3 dp radius, full-width track in `rule`, fill in the state colour.
 *
 * The state is carried by three independent signals so it survives greyscale
 * and colour blindness (§3.3):
 *
 * | state      | colour      | fill                        |
 * |------------|-------------|-----------------------------|
 * | under      | `moss`      | solid, partial              |
 * | near ≥80%  | `amber`     | solid **+ hatched cap**     |
 * | over ≥100% | `vermilion` | solid **+ rule above**      |
 * | unbudgeted | `ink-soft`  | **outline only**, dashed    |
 *
 * The text half of the signal is the caller's — see `BudgetBarRow`.
 *
 * Drawn on a `Canvas` rather than assembled from Material components: it is a
 * handful of `drawRoundRect` calls with no layout pass, and the fill animates
 * at 180 ms `FastOutSlowIn` per §7.
 */
@Composable
fun BudgetBar(
    status: BudgetStatus,
    modifier: Modifier = Modifier,
    /**
     * FR-BUD-07 — a category of nature `unpredictable`.
     *
     * PRD §6.2: "Unpredictable Expenses is a buffer, not a plan. Under-spending
     * it is a win, not an unused allocation. It therefore gets a distinct
     * visual treatment and is excluded from 'under budget' nagging."
     *
     * §3.3's table has four states and none of them is this one, so the
     * treatment is invented: a **ticked track** rather than a solid fill.
     * Progress reads as marks against a scale instead of a bar filling toward
     * a target, which is the difference between spending a buffer and using up
     * a plan. It survives greyscale on pattern alone, and the caller pairs it
     * with "৳2,400 of ৳5,000" rather than "left".
     *
     * Going over is still going over: the over-budget treatment is unchanged.
     */
    unplanned: Boolean = false,
) {
    val colors = DayBookTheme.colors

    val target = if (status.state == BudgetState.OVER) 1f else status.fraction
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = dayBookTween(Motion.BAR_FILL, Motion.FastOutSlowIn),
        label = "budgetFill",
    )

    val fillColor = when (status.state) {
        BudgetState.UNDER -> colors.moss
        BudgetState.NEAR -> colors.amber
        BudgetState.OVER -> colors.vermilion
        BudgetState.UNBUDGETED -> colors.inkSoft
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (status.state == BudgetState.OVER) Sizes.bar + OVER_RULE_GAP else Sizes.bar)
            // The bar is decoration for the figures beside it; announcing it
            // separately would make TalkBack read every row twice.
            .clearAndSetSemantics {},
    ) {
        val barHeight = Sizes.bar.toPx()
        val radius = CornerRadius(BAR_RADIUS.toPx())
        val top = if (status.state == BudgetState.OVER) size.height - barHeight else 0f

        // The full-width rule above an over-budget bar — the second signal, and
        // the one that still reads in greyscale.
        if (status.state == BudgetState.OVER) {
            drawRect(
                color = fillColor,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, OVER_RULE_THICKNESS.toPx()),
            )
        }

        if (status.state == BudgetState.UNBUDGETED) {
            // Outline only: there is nothing to fill, and a zero-width solid
            // bar would be indistinguishable from a budget with no spending.
            drawRoundRect(
                color = fillColor.copy(alpha = 0.6f),
                topLeft = Offset(0f, top),
                size = Size(size.width, barHeight),
                cornerRadius = radius,
                style = Stroke(
                    width = Sizes.hairline.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                ),
            )
            return@Canvas
        }

        drawRoundRect(
            color = colors.rule,
            topLeft = Offset(0f, top),
            size = Size(size.width, barHeight),
            cornerRadius = radius,
        )

        val fillWidth = size.width * fraction
        if (fillWidth > 0f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(0f, top),
                size = Size(fillWidth, barHeight),
                cornerRadius = radius,
            )
        }

        // FR-BUD-07's distinct treatment: ticks across the whole width, so an
        // unpredictable category reads as marks against a scale rather than as
        // a bar filling toward a target. Drawn over the fill, so the amount is
        // still legible while the pattern says "this is a buffer, not a plan".
        if (unplanned) {
            drawTicks(top = top, height = barHeight, color = colors.paper)
        }

        // The hatched cap on an approaching bar — the third signal.
        if (status.state == BudgetState.NEAR) {
            drawHatchedCap(
                left = size.width * status.fraction - HATCH_WIDTH.toPx(),
                top = top,
                width = HATCH_WIDTH.toPx(),
                height = barHeight,
                color = colors.paper,
            )
        }
    }
}

/**
 * Diagonal hatching at ~115°, matching the `repeating-linear-gradient` in the
 * HTML visual spec. Clipped to the cap so the strokes never escape the bar.
 */
private fun DrawScope.drawHatchedCap(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
) {
    if (width <= 0f) return
    clipRect(left = left.coerceAtLeast(0f), top = top, right = left + width, bottom = top + height) {
        var x = left - height
        while (x < left + width + height) {
            drawLine(
                color = color.copy(alpha = 0.55f),
                start = Offset(x, top + height),
                end = Offset(x + height, top),
                strokeWidth = HATCH_STROKE,
            )
            x += HATCH_SPACING
        }
    }
}

/**
 * Vertical ticks the full width of the track — the unplanned treatment.
 *
 * Distinct from the NEAR hatch in both angle and extent: hatching is diagonal
 * and confined to the cap, these are upright and cover everything. The two are
 * never confusable, including in greyscale.
 */
private fun DrawScope.drawTicks(top: Float, height: Float, color: Color) {
    var x = TICK_SPACING
    while (x < size.width) {
        drawLine(
            color = color.copy(alpha = 0.7f),
            start = Offset(x, top),
            end = Offset(x, top + height),
            strokeWidth = TICK_STROKE,
        )
        x += TICK_SPACING
    }
}

private val BAR_RADIUS = 3.dp
private val OVER_RULE_THICKNESS = 2.dp
private val OVER_RULE_GAP = 6.dp
private val HATCH_WIDTH = 14.dp
private const val HATCH_STROKE = 2f
private const val HATCH_SPACING = 6f
private const val TICK_STROKE = 1.5f
private const val TICK_SPACING = 9f
