package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.domain.model.IncomeKind

/**
 * One (period, source) bucket of income.
 *
 * The DAO row restated without a Room dependency, the way
 * [BudgetSummary.LeafSpend] is. It is deliberately the *finest* grain the
 * screen needs — every figure on the income screen is a fold over these, so
 * one query answers the hero total, the twelve bars, the per-source
 * breakdown and the stable subtotal, and they cannot disagree with one
 * another because they are not read separately.
 *
 * For a year with five sources that is sixty rows.
 */
data class IncomeCell(
    val periodYm: Int,
    val sourceId: Long,
    val sourceName: String,
    val kind: Int,
    val totalMinor: Long,
)

/**
 * One line of the breakdown — 05 §5.7's `Salary  ৳3,60,000  62%  ●`.
 *
 * [share] is a whole percent, apportioned so the column sums to exactly 100.
 * [kind] drives a filled or hollow dot: a **shape** difference rather than a
 * colour one, so it survives greyscale and colour blindness.
 */
data class IncomeSourceShare(
    val sourceId: Long,
    val name: String,
    val kind: IncomeKind,
    val total: Money,
    val share: Int,
) {
    val isStable: Boolean get() = kind == IncomeKind.STABLE
}

/** A source the user can filter by — id and name, nothing the chip does not need. */
data class SourceOption(val id: Long, val name: String)

/** Everything the income screen renders, folded from one read. */
data class IncomeSummary(
    val total: Money,
    val stableTotal: Money,
    val shares: List<IncomeSourceShare>,
    /** Paisa per bar, aligned to [com.app.finance.domain.model.IncomeScope.trendPeriods]. */
    val trend: LongArray,
    /**
     * Every source with income in this window, **before** the subset filter.
     *
     * The filter sheet needs these: an archived source keeps its history
     * (FR-IS-04) and so keeps its breakdown row, but it is not in
     * `observeActiveSources`, so without this it is a row the user can see and
     * tap and then cannot un-tap from the sheet that shows the filter. Taken
     * before the filter on purpose — narrowing to one source must not delete
     * the others from the control that widens it again.
     */
    val presentSources: List<SourceOption> = emptyList(),
) {
    val isEmpty: Boolean get() = shares.isEmpty()

    // `LongArray` is an array, so the generated data-class equals would compare
    // by identity and quietly break every assertion that compares two
    // summaries. Kotlin warns about exactly this; these are the fix.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomeSummary) return false
        return total == other.total &&
            stableTotal == other.stableTotal &&
            shares == other.shares &&
            trend.contentEquals(other.trend) &&
            presentSources == other.presentSources
    }

    override fun hashCode(): Int {
        var result = total.hashCode()
        result = 31 * result + stableTotal.hashCode()
        result = 31 * result + shares.hashCode()
        result = 31 * result + trend.contentHashCode()
        result = 31 * result + presentSources.hashCode()
        return result
    }
}

/**
 * Folds the income cells into the screen's model — FR-IE-04, -06 and -07.
 *
 * Pure Kotlin with no Android and no Room types, per NFR-MAIN-01.
 */
object IncomeBreakdown {

    /**
     * @param cells one row per (period, source) inside the window — the hero
     *   total, the breakdown and the stable subtotal.
     * @param trendCells the same shape over
     *   [com.app.finance.domain.model.IncomeScope.trendWindow], which is a
     *   different span in every scope but [Year][com.app.finance.domain.model
     *   .IncomeScope.Year]. Passing [cells] here — or folding [cells] into
     *   [trendPeriods], which is the same mistake — draws eleven
     *   guaranteed-zero bars in Month scope.
     * @param sourceIds FR-IE-05's source subset; empty means "all of them".
     *   Applied here rather than in SQL so the total, the shares and the bars
     *   are all filtered by the same predicate at the same instant.
     * @param trendPeriods the twelve bars, in order — from
     *   [com.app.finance.domain.model.IncomeScope.trendPeriods].
     */
    fun build(
        cells: List<IncomeCell>,
        trendCells: List<IncomeCell>,
        sourceIds: Set<Long>,
        trendPeriods: List<Period>,
    ): IncomeSummary {
        val visible = if (sourceIds.isEmpty()) cells else cells.filter { it.sourceId in sourceIds }
        val visibleTrend =
            if (sourceIds.isEmpty()) trendCells else trendCells.filter { it.sourceId in sourceIds }

        val trend = LongArray(trendPeriods.size)
        val index = HashMap<Int, Int>(trendPeriods.size)
        trendPeriods.forEachIndexed { i, period -> index[period.ym] = i }
        visibleTrend.forEach { cell ->
            index[cell.periodYm]?.let { trend[it] += cell.totalMinor }
        }

        val bySource = visible
            .groupBy { it.sourceId }
            .map { (id, rows) ->
                Row(
                    sourceId = id,
                    // Every row for one source carries the same name and kind;
                    // the first is as good as any.
                    name = rows.first().sourceName,
                    kind = IncomeKind.fromCode(rows.first().kind),
                    amount = rows.sumOf { it.totalMinor },
                )
            }
            // 03 §5.2's `ORDER BY r.total_minor DESC` — largest first, because
            // the question the breakdown answers is "where does it come from".
            .sortedWith(compareByDescending<Row> { it.amount }.thenBy { it.sourceId })

        val total = bySource.sumOf { it.amount }
        val shares = apportion(bySource)

        return IncomeSummary(
            total = Money(total),
            stableTotal = Money(bySource.filter { it.kind == IncomeKind.STABLE }.sumOf { it.amount }),
            shares = shares,
            trend = trend,
            // From `cells`, not `visible` — see [IncomeSummary.presentSources].
            presentSources = cells
                .distinctBy { it.sourceId }
                .map { SourceOption(it.sourceId, it.sourceName) }
                .sortedBy { it.name },
        )
    }

    /**
     * FR-IE-06's percentages, by largest remainder.
     *
     * The acceptance criterion is "percentages sum to 100 ± 0.1 after
     * rounding", and rounding each share on its own does not meet it: three
     * equal sources each round to 33 and the column reads 99. That is not a
     * corner case — equal-ish sources are the ordinary shape of this user's
     * income, and a breakdown whose percentages visibly fail to add up
     * undermines every other figure on the screen.
     *
     * [LargestRemainder] does the arithmetic; [rows] arrives already sorted by
     * amount and then by id, and that order is what its positional tie-break
     * uses, so the same data always produces the same column.
     */
    private fun apportion(rows: List<Row>): List<IncomeSourceShare> {
        val shares = LargestRemainder.percentages(rows.map { it.amount })
        return rows.mapIndexed { i, row -> row.toShare(share = shares[i]) }
    }

    private data class Row(
        val sourceId: Long,
        val name: String,
        val kind: IncomeKind,
        val amount: Long,
    ) {
        fun toShare(share: Int) = IncomeSourceShare(
            sourceId = sourceId,
            name = name,
            kind = kind,
            total = Money(amount),
            share = share,
        )
    }
}
