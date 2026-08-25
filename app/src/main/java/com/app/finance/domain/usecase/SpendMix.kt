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
    fun of(groups: List<BudgetGroup>): List<SpendSlice> = ofTotals(totalsOf(groups))

    /**
     * The same split from per-nature totals rather than from budget groups.
     *
     * The Reports screen (04 §7) reads an arbitrary date range straight from
     * the ledger, where there are no groups and no budgets to speak of — only
     * sums per nature. Both callers go through the one implementation so the
     * dashboard and the report cannot disagree about what "40% variable" means.
     */
    fun ofTotals(byNature: Map<Nature, Money>): List<SpendSlice> {
        // A nature whose net is zero or negative is dropped, not shown at 0%.
        //
        // The shares therefore sum to 100% of what is *displayed*, which is the
        // honest reading of a mix — a slice cannot have negative width, and
        // "Unpredictable −8%" is not a thing a pie can mean. What it does not
        // sum to is the hero total printed beside it, which includes the
        // negative: a month with ৳5,000 variable and a ৳1,000 net refund on
        // unpredictable shows one slice at 100% above a total of ৳4,000.
        //
        // That is a framing question and not an arithmetic one, and the mix is
        // the wrong place to answer it. [excludedFrom] is how a screen asks
        // what was left out, and 05 §5.3 is the rule for saying so.
        val totals = ORDER
            .map { nature -> nature to (byNature[nature] ?: Money.ZERO) }
            .filter { (_, total) -> total.paisa > 0L }

        val shares = LargestRemainder.percentages(totals.map { it.second.paisa })

        return totals.mapIndexed { i, (nature, total) ->
            SpendSlice(nature = nature, total = total, share = shares[i])
        }
    }

    /**
     * What the slices leave out — the magnitude of every nature whose net is at
     * or below zero.
     *
     * Zero whenever the mix and the total beside it agree, which is almost
     * always. It is non-zero exactly when FR-EXP-06's refunds outweigh a
     * nature's spending for the period, and then the percentages are of a
     * smaller number than the figure printed above them.
     *
     * Returned rather than folded into the slices because a refund is not a
     * share of spending; it is money coming back, and a pie cannot draw it.
     * 05 §5.3 says what a screen does with this: a caption, and only when it is
     * non-zero — a line that is always there is a line nobody reads on the day
     * it matters.
     */
    fun excludedFrom(byNature: Map<Nature, Money>): Money =
        ORDER.fold(Money.ZERO) { acc, nature ->
            val total = byNature[nature] ?: Money.ZERO
            if (total.paisa > 0L) acc else acc + total.absoluteValue
        }

    /** The same question asked of budget groups — see [of]. */
    fun excludedFrom(groups: List<BudgetGroup>): Money = excludedFrom(totalsOf(groups))

    /** One fold, so [of] and [excludedFrom] cannot disagree about a nature's total. */
    private fun totalsOf(groups: List<BudgetGroup>): Map<Nature, Money> =
        ORDER.associateWith { nature ->
            groups.filter { it.nature == nature }
                .fold(Money.ZERO) { acc, group -> acc + group.spent }
        }

    private val ORDER = listOf(Nature.VARIABLE, Nature.UNPREDICTABLE, Nature.FIXED)
}
