package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetState

/**
 * One leaf projected to the end of the period — FR-AN-04.
 *
 * @property projected `(spent ÷ days elapsed) × days in period`.
 * @property isAlreadyOver true when the limit is passed *now*, not merely on
 *   this pace. Such a leaf is already in "needs attention", so the screen has
 *   the information it needs to avoid saying the same thing twice; the
 *   calculation keeps it because FR-AN-04 asks for every over-pace leaf and a
 *   leaf that is already over is trivially one.
 */
data class BurnProjection(
    val categoryId: Long,
    val name: String,
    val spent: Money,
    val limit: Money,
    val projected: Money,
    val isAlreadyOver: Boolean,
) {
    val overBy: Money get() = projected - limit
}

/**
 * The burn-rate projection — FR-AN-04, and PRD §6.4's reason for it:
 *
 * > "per category: current pace × days in month, vs limit — **warns on day 12,
 * > not day 30**."
 *
 * That sentence is the whole design. A figure that only becomes true at the end
 * of the month is a report; one that extrapolates a pace is a warning, and a
 * warning is the only kind of number that can still change the outcome.
 */
object BurnRate {

    /**
     * @param daysElapsed from `Period.daysElapsedInclusive`, counting today.
     * @param daysInPeriod the period's own length.
     *
     * Three exclusions:
     *
     * - **Zero days elapsed.** Looking at a period that has not started, there
     *   is no pace to extrapolate. Returns empty rather than dividing by zero
     *   or inventing a projection from nothing.
     * - **Unbudgeted leaves.** Nothing to compare the projection against.
     * - **Unplanned leaves** (FR-BUD-07). A projection is a statement about
     *   pace, and PRD §6.2 is explicit that "Unpredictable Expenses is a buffer,
     *   not a plan". A single medical bill on day three projects to ten times
     *   itself by month end, which would be alarming, arithmetically correct,
     *   and meaningless — the precise nagging FR-BUD-07 forbids.
     * - **Archived leaves.** A projection says what the pace will do by month
     *   end, and nothing more can be spent on a category the entry picker no
     *   longer offers. Announcing "on pace to overspend" for one the user has
     *   just retired is the same nagging by another route.
     */
    fun over(
        groups: List<BudgetGroup>,
        daysElapsed: Int,
        daysInPeriod: Int,
    ): List<BurnProjection> {
        if (daysElapsed <= 0 || daysInPeriod <= 0) return emptyList()

        return groups
            .asSequence()
            .flatMap { it.leaves.asSequence() }
            .filter { it.hasLimit && !it.isUnplanned && !it.isArchived }
            .map { leaf ->
                val projected = Money(
                    // Multiply before dividing: the other order rounds the
                    // daily rate to whole paisa first and loses up to a
                    // month's worth of the fraction.
                    (leaf.status.spent.paisa * daysInPeriod) / daysElapsed,
                )
                BurnProjection(
                    categoryId = leaf.id,
                    name = leaf.name,
                    spent = leaf.status.spent,
                    limit = leaf.status.limit,
                    projected = projected,
                    isAlreadyOver = leaf.status.state == BudgetState.OVER,
                )
            }
            .filter { it.projected > it.limit }
            // Worst overshoot first, so the row that most needs a decision is
            // the first one read.
            .sortedByDescending { it.overBy.paisa }
            .toList()
    }
}
