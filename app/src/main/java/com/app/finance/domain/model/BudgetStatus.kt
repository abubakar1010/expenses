package com.app.finance.domain.model

import com.app.finance.core.money.Money

/**
 * Which of the four states a budget bar is in.
 *
 * 05-ui-ux-guide.md §3.3: the app's most important state — over budget — sits
 * on exactly the red/green axis that deuteranopia collapses, so every state
 * carries **three** signals, not one: a colour, a fill treatment, and a text
 * label. Remove all colour and the row still reads correctly. That is the test.
 */
enum class BudgetState {
    /** `moss`, solid partial fill, "৳2,400 left". */
    UNDER,

    /** `amber`, solid fill with a hatched cap, "৳900 left". At or past 80%. */
    NEAR,

    /** `vermilion`, solid fill with a full-width rule above, "৳600 over". */
    OVER,

    /** `ink-soft`, outline only, "No limit set". */
    UNBUDGETED,
}

/**
 * A leaf category's position against its limit for one period.
 *
 * Pure Kotlin over [Money], so the thresholds are unit-tested on the JVM rather
 * than eyeballed on a device — this is the calculation the user acts on.
 */
data class BudgetStatus(
    val state: BudgetState,
    val spent: Money,
    val limit: Money,
) {
    /**
     * Progress along the bar, clamped to `0f..1f` for drawing. Overspend is
     * shown by colour, the rule above, and the label — not by a bar running off
     * the end of the screen.
     */
    val fraction: Float
        get() = when {
            limit.paisa <= 0L -> 0f
            else -> (spent.paisa.toFloat() / limit.paisa.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * The percentage FR-BUD-05 requires — **not** clamped.
     *
     * [fraction] stops at 1f because a bar wider than its track is a rendering
     * bug; the label has the opposite need. `104%` is the whole point of the
     * over-budget row, and a clamped label would read `100%` next to a figure
     * saying `৳280 over`.
     */
    val percentConsumed: Int
        get() = if (limit.paisa <= 0L) 0
        else ((spent.paisa.toDouble() / limit.paisa.toDouble()) * 100).toInt().coerceAtLeast(0)

    /** Zero once the limit is passed; [overspend] carries the excess. */
    val remaining: Money
        get() = if (limit.paisa <= 0L) Money.ZERO else maxOf(limit - spent, Money.ZERO)

    val overspend: Money
        get() = if (limit.paisa <= 0L) Money.ZERO else maxOf(spent - limit, Money.ZERO)

    companion object {
        /** FR-BUD-05 — alert at 80% of the limit. */
        const val NEAR_THRESHOLD = 0.80f

        fun of(spent: Money, limit: Money): BudgetStatus {
            val state = when {
                limit.paisa <= 0L -> BudgetState.UNBUDGETED
                spent >= limit -> BudgetState.OVER
                spent.paisa.toFloat() / limit.paisa.toFloat() >= NEAR_THRESHOLD -> BudgetState.NEAR
                else -> BudgetState.UNDER
            }
            return BudgetStatus(state, spent, limit)
        }
    }
}
