package com.app.finance.domain.usecase

import com.app.finance.core.money.Money
import com.app.finance.core.time.Period

/**
 * The six-period trend — FR-AN-09's first half.
 *
 * > "The system MUST render a 6-period expense trend line with a budget
 * > reference line, and a 12-period income series."
 *
 * The income series is the income screen's `YearBars`, shipped at M3. What is
 * owed here is the expense line and the reference against it.
 *
 * @property spend paisa per period, aligned to [periods].
 * @property reference the planned total for the same period, aligned the same
 *   way. Per-period rather than one flat line, because limits are set per
 *   period (FR-BUD-01) and a single line would be a fiction the moment the user
 *   changed one. It draws flat when they do not.
 */
data class ExpenseTrend(
    val periods: List<Period>,
    val spend: LongArray,
    val reference: LongArray,
) {
    val isEmpty: Boolean get() = spend.none { it > 0L }

    /** Periods where spending passed the plan — the points drawn in `vermilion`. */
    fun isOver(index: Int): Boolean =
        reference[index] > 0L && spend[index] > reference[index]

    val total: Money get() = Money(spend.sum())

    // `LongArray` is an array, so the generated data-class equals would compare
    // by identity and quietly break every assertion that compares two trends.
    // The same fix `IncomeSummary` carries, for the same reason.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExpenseTrend) return false
        return periods == other.periods &&
            spend.contentEquals(other.spend) &&
            reference.contentEquals(other.reference)
    }

    override fun hashCode(): Int {
        var result = periods.hashCode()
        result = 31 * result + spend.contentHashCode()
        result = 31 * result + reference.contentHashCode()
        return result
    }

    companion object {

        const val LENGTH = 6

        /**
         * @param spendByPeriod period → total spend, from `observeExpenseSeries`.
         * @param limitsByPeriod period → total planned, from
         *   `observeTotalLimitsInPeriods`.
         *
         * A period with no row in either map is zero rather than absent: the
         * chart is a fixed six columns, and a month the user recorded nothing
         * in is a real month with a real answer. A zero reference means no budget
         * was set, which [isOver] treats as "nothing to be over".
         */
        fun of(
            periods: List<Period>,
            spendByPeriod: Map<Int, Long>,
            limitsByPeriod: Map<Int, Long>,
        ): ExpenseTrend = ExpenseTrend(
            periods = periods,
            spend = LongArray(periods.size) { spendByPeriod[periods[it].ym] ?: 0L },
            reference = LongArray(periods.size) { limitsByPeriod[periods[it].ym] ?: 0L },
        )
    }
}
