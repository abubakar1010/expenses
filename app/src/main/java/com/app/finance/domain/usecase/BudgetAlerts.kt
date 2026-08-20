package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetState

/**
 * One line in the "needs attention" block.
 *
 * [daysRemaining] is carried so the approaching-limit copy can read
 * `৳900 left in Grocery — 6 days to go`. A remaining amount without the time
 * left to spend it is not actionable: ৳900 with six days to go and ৳900 with
 * one are different situations.
 */
data class BudgetAlert(
    val categoryId: Long,
    val name: String,
    val state: BudgetState,
    val remaining: Money,
    val overspend: Money,
    val daysRemaining: Int,
) {
    val isOver: Boolean get() = state == BudgetState.OVER
}

/**
 * The alerts PRD §6.2 asks for — "Alerts at 80% and 100% of a subcategory
 * limit".
 *
 * They are a *list on a screen*, never a notification. 05 §8: "The app never
 * nags. Budget warnings appear on the dashboard when the user looks. No push
 * notifications in v1 — there is no background service, and an app that scolds
 * you about spending gets uninstalled." §12 rejects "Budget alerts / reminders"
 * outright and 04 §6 keeps WorkManager out of the M1–M4 builds.
 *
 * Until the dashboard exists at M4 the block lives at the top of the Budget
 * screen; this function is what both surfaces call.
 */
object BudgetAlerts {

    /**
     * Over-budget first, then approaching — within each, the largest figure
     * first, so the worst problem is the first thing read.
     *
     * Two exclusions:
     * - **Unplanned leaves** (FR-BUD-07). An unpredictable category cannot be
     *   "approaching" anything, because it was never a plan. It *can* still be
     *   over a limit the user set, and that is worth saying, so only the NEAR
     *   case is suppressed.
     * - **Unbudgeted leaves.** With no limit there is no threshold to cross,
     *   and a row that says "no limit set" is information for the list below,
     *   not an alert.
     */
    fun from(groups: List<BudgetGroup>, daysRemaining: Int): List<BudgetAlert> =
        groups
            .asSequence()
            .flatMap { it.leaves.asSequence() }
            .filter { it.hasLimit }
            .filter { leaf ->
                when (leaf.status.state) {
                    BudgetState.OVER -> true
                    BudgetState.NEAR -> !leaf.isUnplanned
                    BudgetState.UNDER, BudgetState.UNBUDGETED -> false
                }
            }
            .map { leaf ->
                BudgetAlert(
                    categoryId = leaf.id,
                    name = leaf.name,
                    state = leaf.status.state,
                    remaining = leaf.status.remaining,
                    overspend = leaf.status.overspend,
                    daysRemaining = daysRemaining,
                )
            }
            .sortedWith(
                compareByDescending<BudgetAlert> { it.isOver }
                    .thenByDescending { if (it.isOver) it.overspend.paisa else -it.remaining.paisa },
            )
            .toList()
}
