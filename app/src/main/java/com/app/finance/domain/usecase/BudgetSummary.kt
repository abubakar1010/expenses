package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.BudgetStatus
import com.app.finance.domain.model.CategoryNode
import com.app.finance.domain.model.Nature

/**
 * One leaf, ready to render.
 *
 * [status] carries the three-signal state; [isUnplanned] is the fourth
 * treatment FR-BUD-07 demands for `unpredictable` categories, which §3.3's
 * four-state table does not cover.
 */
data class BudgetLeaf(
    val id: Long,
    val name: String,
    val nature: Nature,
    val status: BudgetStatus,
    /**
     * FR-CAT-08 — an archived leaf stays visible while it carries spend in the
     * period, "so history never silently loses rows".
     *
     * It is still shown, still counted in its group's total and still in the
     * spend mix, because the money really was spent. What it is **not** is
     * money the user may still spend: the entry picker no longer offers it, so
     * [SafeToSpend] and [BurnRate] both leave it out.
     */
    val isArchived: Boolean = false,
) {
    /**
     * FR-BUD-07: unpredictable spending "MUST NOT produce under-spend nagging
     * and MUST be visually distinguished from planned categories". PRD §6.2 is
     * blunter — "Unpredictable Expenses is a buffer, not a plan. Under-spending
     * it is a win, not an unused allocation."
     *
     * So these leaves never say "left", never appear in the alert list for
     * being under, and draw a ticked track rather than a filled bar.
     */
    val isUnplanned: Boolean get() = nature == Nature.UNPREDICTABLE

    val hasLimit: Boolean get() = status.limit.paisa > 0L
}

/**
 * A root and its leaves. The root's limit is computed here and stored nowhere.
 */
data class BudgetGroup(
    val id: Long,
    val name: String,
    val nature: Nature,
    val leaves: List<BudgetLeaf>,
) {
    val spent: Money get() = leaves.fold(Money.ZERO) { acc, leaf -> acc + leaf.status.spent }

    /**
     * FR-BUD-03 — "A root's limit MUST be the computed sum of its children's
     * limits for that period." Summed on every read rather than stored, which
     * is what makes it "impossible to desynchronise from their parts"
     * (03 §4.5).
     */
    val limit: Money get() = leaves.fold(Money.ZERO) { acc, leaf -> acc + leaf.status.limit }

    val isUnplanned: Boolean get() = nature == Nature.UNPREDICTABLE

    /** True when no child carries a limit — the group has nothing to total. */
    val isUnbudgeted: Boolean get() = limit.paisa <= 0L
}

/**
 * Folds the budget-bar query and the category tree into the screen's model.
 *
 * Pure Kotlin with no Android and no Room types, per NFR-MAIN-01: "Business
 * rules (budget states, projections, safe-to-spend) live in pure functions with
 * no Android dependencies, unit-testable on the JVM." The query supplies leaves
 * only; the tree supplies the root names and the ordering.
 */
object BudgetSummary {

    /**
     * @param bars one row per leaf, already scoped to a period — the projection
     *   of `RollupDao.observeBudgetBars`, reduced to the four fields this needs
     *   so the domain layer does not depend on a DAO type.
     */
    fun build(bars: List<LeafSpend>, tree: List<CategoryNode>): List<BudgetGroup> {
        val byParent = bars.groupBy { it.parentId }

        return tree
            .map { root ->
                BudgetGroup(
                    id = root.id,
                    name = root.name,
                    nature = root.nature,
                    leaves = byParent[root.id]
                        .orEmpty()
                        .map { bar ->
                            BudgetLeaf(
                                id = bar.id,
                                name = bar.name,
                                nature = Nature.fromCode(bar.nature),
                                status = BudgetStatus.of(
                                    spent = Money(bar.spentMinor),
                                    limit = Money(bar.limitMinor),
                                ),
                                isArchived = bar.isArchived,
                            )
                        },
                )
            }
            .filter { it.leaves.isNotEmpty() }
            .sortedWith(GROUP_ORDER)
    }

    /**
     * 05 §5.4: "**Fixed expenses sit below variable ones**, despite being
     * larger, because rent is not a decision. Ordering by actionability rather
     * than by amount is the whole point of separating the categories in the
     * first place."
     *
     * So: variable first — the money still in play — then unpredictable, then
     * fixed, which is already committed.
     */
    private val GROUP_ORDER = compareBy<BudgetGroup> {
        when (it.nature) {
            Nature.VARIABLE -> 0
            Nature.UNPREDICTABLE -> 1
            Nature.FIXED -> 2
        }
    }

    /** The DAO row, restated without a Room dependency. */
    data class LeafSpend(
        val id: Long,
        val parentId: Long,
        val name: String,
        val nature: Int,
        val limitMinor: Long,
        val spentMinor: Long,
        val isArchived: Boolean = false,
    )
}
