package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.domain.model.Nature

/** One nature's share of the period's spend. */
data class SpendSlice(
    val nature: Nature,
    val total: Money,
    /** Whole percent, apportioned so the slices sum to exactly 100. */
    val share: Int,
)

/**
 * The fixed / variable / unpredictable split — FR-AN-07.
 *
 * PRD §6.4 gives the reason in four words: it "shows how much is actually
 * controllable". A user looking at ৳31,600 of spending cannot act on the
 * number until they know how much of it was already decided before the month
 * began.
 */
object SpendMix {

    /**
     * Ordered variable → unpredictable → fixed, the same actionability order
     * `BudgetSummary` puts the groups in and 05 §5.4 argues for: "ordering by
     * actionability rather than by amount is the whole point of separating the
     * categories in the first place".
     *
     * Natures with no spend are **absent, not zero** (05 §5.4), and the shares
     * are apportioned over what is left, so the column still totals 100.
     * [LargestRemainder] is what guarantees that — three near-equal natures
     * rounded independently read 99.
     */
    fun of(groups: List<BudgetGroup>): List<SpendSlice> = ofTotals(
        ORDER.associateWith { nature ->
            groups.filter { it.nature == nature }
                .fold(Money.ZERO) { acc, group -> acc + group.spent }
        },
    )

    /**
     * The same split from per-nature totals rather than from budget groups.
     *
     * The Reports screen (04 §7) reads an arbitrary date range straight from
     * the ledger, where there are no groups and no budgets to speak of — only
     * sums per nature. Both callers go through the one implementation so the
     * dashboard and the report cannot disagree about what "40% variable" means.
     */
    fun ofTotals(byNature: Map<Nature, Money>): List<SpendSlice> {
        val totals = ORDER
            .map { nature -> nature to (byNature[nature] ?: Money.ZERO) }
            .filter { (_, total) -> total.paisa > 0L }

        val shares = LargestRemainder.percentages(totals.map { it.second.paisa })

        return totals.mapIndexed { i, (nature, total) ->
            SpendSlice(nature = nature, total = total, share = shares[i])
        }
    }

    private val ORDER = listOf(Nature.VARIABLE, Nature.UNPREDICTABLE, Nature.FIXED)
}
