package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.core.time.Period

/**
 * One (period, category) bucket of spend — the DAO row without a Room type.
 *
 * The same shape [IncomeCell] takes, and for the same reason: `domain/` stays
 * free of platform imports so every calculation over these is a JVM test.
 */
data class CategoryCell(
    val periodYm: Int,
    val categoryId: Long,
    val name: String,
    val totalMinor: Long,
)

/** One line of the "biggest changes" block. */
data class CategoryDelta(
    val categoryId: Long,
    val name: String,
    val current: Money,
    /** The trailing three-period mean this is measured against. */
    val baseline: Money,
) {
    val increase: Money get() = current - baseline
}

/**
 * Category deltas — FR-AN-05, and the one dashboard metric that reports a
 * *change* rather than a level.
 *
 * PRD §6.4: "this month vs trailing 3-month average, sorted by largest increase
 * — **surfaces the change, which is the only actionable part**." Every other
 * figure on the screen says where the money is; this one says what moved, and
 * a level the user has lived with for a year is not news.
 */
object CategoryDeltas {

    const val BASELINE_PERIODS = 3
    const val TOP_N = 5

    /**
     * @param cells every (period, category) bucket over [current] and the three
     *   periods before it.
     *
     * Two decisions worth stating:
     *
     * - **The mean always divides by three**, never by "however many periods
     *   had spending". A category first bought last month would otherwise show
     *   a baseline equal to that one month and read as flat, when the honest
     *   comparison is against a quarter in which it mostly did not exist.
     * - **Increases only.** FR-AN-05 says "sorted descending by absolute
     *   increase", which could be read as ranking by magnitude in either
     *   direction; PRD §6.4 settles it — "sorted by largest increase" — and a
     *   category that fell is not something to act on.
     */
    fun top(cells: List<CategoryCell>, current: Period, limit: Int = TOP_N): List<CategoryDelta> {
        val baselinePeriods = (1..BASELINE_PERIODS).map { current.minusMonths(it).ym }.toSet()
        val names = HashMap<Long, String>()
        val now = HashMap<Long, Long>()
        val before = HashMap<Long, Long>()

        cells.forEach { cell ->
            names[cell.categoryId] = cell.name
            when (cell.periodYm) {
                current.ym -> now[cell.categoryId] = (now[cell.categoryId] ?: 0L) + cell.totalMinor
                in baselinePeriods ->
                    before[cell.categoryId] = (before[cell.categoryId] ?: 0L) + cell.totalMinor
            }
        }

        return names.keys
            .map { id ->
                CategoryDelta(
                    categoryId = id,
                    name = names.getValue(id),
                    current = Money(now[id] ?: 0L),
                    baseline = Money((before[id] ?: 0L) / BASELINE_PERIODS),
                )
            }
            .filter { it.increase.paisa > 0L }
            .sortedWith(
                compareByDescending<CategoryDelta> { it.increase.paisa }
                    // Deterministic order for equal increases, so the same data
                    // always produces the same five rows.
                    .thenBy { it.name },
            )
            .take(limit)
    }
}
