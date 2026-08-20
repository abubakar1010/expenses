package com.app.finance.domain.usecase

import com.app.finance.core.money.Money

/**
 * What share of spending the dependable income covers — FR-AN-06.
 *
 * 05 §5.7 closes the income screen with it: *"Stable income covers 71% of your
 * spending this year"*, labelled "the insight that matters". PRD §6.1 says why
 * the Stable/Variable split exists at all:
 *
 * > "It enables the single most useful metric for this user — the percentage of
 * > monthly expenses covered by stable income alone. That number tells them how
 * > exposed they are to a bad farming season."
 *
 * Written here rather than in the income screen because the dashboard needs the
 * same figure at M4 (FR-AN-06 is a dashboard requirement; the income screen
 * shows it first only because the dashboard does not exist yet). Pure, so it is
 * tested on the JVM in milliseconds.
 */
object StableCoverage {

    /**
     * @return whole percent, or **null when there is nothing to cover**.
     *
     * Zero spending is not 100% coverage and it is not an error — it is a ratio
     * with no denominator, and the honest response is to say nothing rather
     * than to print a confident figure the user might act on. The caller omits
     * the line, which is also 05 §5.4's rule: "Sections that have nothing to
     * say are absent, not empty."
     *
     * Not clamped at 100. Earning more in stable income than you spent is real,
     * it is good news, and rounding it down to "100%" would hide the size of
     * the margin — the same argument that keeps `percentConsumed` unclamped
     * above a budget limit.
     */
    fun percent(stableIncome: Money, totalExpenses: Money): Int? {
        if (totalExpenses.paisa <= 0L) return null
        if (stableIncome.paisa <= 0L) return 0
        return ((stableIncome.paisa * 100.0) / totalExpenses.paisa).toInt()
    }
}
