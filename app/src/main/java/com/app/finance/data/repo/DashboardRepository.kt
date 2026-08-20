package com.app.finance.data.repo

import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.BudgetBarRow
import com.app.finance.data.db.dao.CategoryCellRow
import com.app.finance.data.db.dao.DayTotal
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.data.db.dao.IncomeCellRow
import com.app.finance.data.db.dao.PeriodTotal
import com.app.finance.domain.usecase.CategoryDeltas
import com.app.finance.domain.usecase.ExpenseTrend
import kotlinx.coroutines.flow.Flow

/**
 * Everything the dashboard reads — FR-AN-01 … FR-AN-10.
 *
 * **Read-only, and every window derived from one [Period] in one place.** The
 * dashboard needs eight different spans of time — this period, its days, its
 * trailing three, its trailing six, its trailing twelve — and computing them at
 * eight call sites is how two figures on the same screen end up disagreeing
 * about which month they are describing.
 *
 * Nothing here touches the `expense` or `income_entry` tables except
 * [observeLargestExpenses], which is `LIMIT 5` on `ix_expense_period`. Every
 * other read is served by `rollup_expense_month`, `rollup_income_month`,
 * `budget` and `category`, so **row counts are bounded by the number of leaf
 * categories and sources, not by how much history exists**.
 *
 * That is 03 §5.1's claim — "row count is bounded by the number of leaf
 * categories … independent of transaction history size. This is what holds
 * NFR-PERF-04 at 300 ms as the ledger grows to five years" — generalised from
 * one query to the whole screen, and it is what `DashboardScaleTest` asserts
 * rather than assumes.
 */
class DashboardRepository(db: AppDatabase) {

    private val rollupDao = db.rollupDao()
    private val budgetDao = db.budgetDao()
    private val expenseDao = db.expenseDao()
    private val incomeDao = db.incomeDao()

    /** 03 §5.1's hot query — leaves, limits and spend for the period. */
    fun observeBars(period: Period): Flow<List<BudgetBarRow>> =
        rollupDao.observeBudgetBars(period.ym)

    /** The month ribbon — one bar per day (05 §5.5). */
    fun observeDailySpend(period: Period): Flow<List<DayTotal>> =
        rollupDao.observeDailySpend(period.firstDay().toEpochDay(), period.lastDay().toEpochDay())

    /**
     * Income for the period, per source and with its kind.
     *
     * One read answers FR-AN-02's income figure **and** FR-AN-06's stable
     * subtotal, so the net line and the coverage line cannot disagree about how
     * much came in. The same read M3's income screen folds.
     */
    fun observeIncomeCells(period: Period): Flow<List<IncomeCellRow>> =
        incomeDao.observeCellsInPeriods(period.ym, period.ym)

    /** FR-AN-05 — this period and the three behind it. */
    fun observeCategoryCells(period: Period): Flow<List<CategoryCellRow>> =
        rollupDao.observeCategoryCells(
            period.minusMonths(CategoryDeltas.BASELINE_PERIODS).ym,
            period.ym,
        )

    /** FR-AN-09 — six periods of spend. */
    fun observeExpenseSeries(period: Period): Flow<List<PeriodTotal>> =
        trendSpan(period).let { rollupDao.observeExpenseSeries(it.first, it.second) }

    /** FR-AN-09 — the budget reference over the same six. */
    fun observeTotalLimits(period: Period): Flow<List<PeriodTotal>> =
        trendSpan(period).let { budgetDao.observeTotalLimitsInPeriods(it.first, it.second) }

    /** FR-AN-08 — the five largest expenses of the period. */
    fun observeLargestExpenses(period: Period, limit: Int = TOP_EXPENSES): Flow<List<ExpenseWithCategory>> =
        expenseDao.observeLargest(period.ym, limit)

    /**
     * FR-AN-10 — income across the trailing **twelve** periods.
     *
     * > "Averages presented as 'monthly average income' MUST be computed over a
     * > trailing 12 periods, never over a single period or a partial year.
     * > *Rationale:* seasonal sources earn nothing for months and then a lump
     * > sum; a short window produces figures that are wrong in both directions."
     *
     * The window is the entire requirement, which is why it is fixed here and
     * not passed in.
     */
    fun observeTrailingIncome(period: Period): Flow<Long> =
        period.trailing(TRAILING_AVERAGE).let {
            rollupDao.observeIncomeTotalInPeriods(it.first().ym, it.last().ym)
        }

    private fun trendSpan(period: Period): Pair<Int, Int> =
        period.trailing(ExpenseTrend.LENGTH).let { it.first().ym to it.last().ym }

    companion object {
        const val TOP_EXPENSES = 5

        /** FR-AN-10's window, and the only correct one. */
        const val TRAILING_AVERAGE = 12
    }
}
