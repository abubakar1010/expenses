package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import kotlin.math.floor

/**
 * The net strip — FR-AN-02 and FR-AN-03.
 *
 * 05 §5.4 draws it as two lines under the ribbon:
 *
 * ```
 * Earned ৳48,000   Spent ৳31,600
 * Net +৳16,400 · saving 34%
 * ```
 *
 * PRD §6.4 calls net position "the headline health number" and the savings rate
 * "the best single long-term indicator", which is why they share a strip rather
 * than each taking a section.
 */
data class NetPosition(
    val income: Money,
    val expenses: Money,
) {
    /** FR-AN-02 — period income minus period expenses. May be negative. */
    val net: Money get() = income - expenses

    /**
     * FR-AN-03 — `(income − expenses) ÷ income`, **suppressed when income is
     * zero**.
     *
     * Null rather than 0%, for the reason [StableCoverage] returns null: a
     * ratio with no denominator is not a figure with a value, and printing one
     * invites the user to act on it. A month with no income yet is ordinary for
     * this user, not an error.
     *
     * Negative rates are real and are not suppressed — spending more than was
     * earned is exactly the situation the number exists to report. Not clamped
     * above 100 either, for the same reason `percentConsumed` is not.
     *
     * **Floored, not truncated.** `toInt()` rounds toward zero, which on a
     * negative rate rounds the flattering way: −60.5% would report as −60%.
     * That is the direction `Money.divideBy` documents as the wrong one —
     * "telling the user they may spend one paisa more than they actually may" —
     * and a month that went badly should not read better than it was. Positive
     * rates are unchanged: 34.9% is still 34%, which is already conservative.
     */
    val savingsRate: Int?
        get() = if (income.paisa <= 0L) null
        else floor((net.paisa * 100.0) / income.paisa).toInt()
}
