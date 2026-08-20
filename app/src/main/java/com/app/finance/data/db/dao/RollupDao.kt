package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One leaf category's budget bar for the period being viewed. */
data class BudgetBarRow(
    val id: Long,
    /**
     * The root this leaf sits under. `sort_order` is assigned per parent, so a
     * flat render of these rows interleaves children from different roots —
     * the budget screen groups by this before ordering.
     */
    val parentId: Long,
    val name: String,
    val nature: Int,
    val limitMinor: Long,
    val spentMinor: Long,
    /**
     * FR-CAT-08 keeps an archived leaf visible while it carries spend, so the
     * row is here on purpose — but a figure about what the user *may still
     * spend* has to be able to tell the difference.
     */
    val isArchived: Boolean,
)

data class PeriodTotal(val periodYm: Int, val total: Long)

/** One (period, category) bucket — the dashboard's category-delta grain. */
data class CategoryCellRow(
    val periodYm: Int,
    val categoryId: Long,
    val name: String,
    val totalMinor: Long,
)

data class SourceTotal(
    val id: Long,
    val name: String,
    val kind: Int,
    val totalMinor: Long,
)

@Dao
interface RollupDao {

    /**
     * The hot query — 03 §5.1. Reads only `category`, `budget` and the rollup;
     * it never touches the `expense` table.
     *
     * Row count is bounded by the number of leaf categories — dozens, not
     * thousands — independent of how much history exists. That is what holds
     * NFR-PERF-04 at 300 ms as the ledger grows to five years.
     *
     * The archived clause is exact: archived categories are hidden *unless*
     * they carry spend in the period being viewed, which is what FR-CAT-08
     * requires so history never silently loses rows.
     */
    @Query(
        """
        SELECT c.id                      AS id,
               c.parent_id               AS parentId,
               c.name                    AS name,
               c.nature                  AS nature,
               IFNULL(b.limit_minor, 0)  AS limitMinor,
               IFNULL(r.total_minor, 0)  AS spentMinor,
               c.is_archived             AS isArchived
          FROM category c
          LEFT JOIN budget b
                 ON b.category_id = c.id AND b.period_ym = :period
          LEFT JOIN rollup_expense_month r
                 ON r.category_id = c.id AND r.period_ym = :period
         WHERE c.parent_id IS NOT NULL
           AND (c.is_archived = 0 OR r.total_minor IS NOT NULL)
         ORDER BY c.sort_order
        """,
    )
    fun observeBudgetBars(period: Int): Flow<List<BudgetBarRow>>

    @Query("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month WHERE period_ym = :period")
    fun observeExpenseTotal(period: Int): Flow<Long>

    @Query("SELECT IFNULL(SUM(total_minor), 0) FROM rollup_income_month WHERE period_ym = :period")
    fun observeIncomeTotal(period: Int): Flow<Long>

    /**
     * Spending across a span of whole months — the denominator of
     * [com.app.finance.domain.usecase.StableCoverage], FR-AN-06.
     *
     * A scalar rather than a fold over [observeExpenseSeries], because coverage
     * needs one number and summing twelve rows in Kotlin on every emission is
     * work the database has already done.
     */
    @Query(
        """
        SELECT IFNULL(SUM(total_minor), 0) FROM rollup_expense_month
         WHERE period_ym BETWEEN :startPeriod AND :endPeriod
        """,
    )
    fun observeExpenseTotalInPeriods(startPeriod: Int, endPeriod: Int): Flow<Long>

    /**
     * 03 §5.2 — period income by source, for the income screen breakdown.
     *
     * `entry_count > 0` for the reason [com.app.finance.data.db.dao.IncomeDao
     * .observeCellsInPeriods] carries the same clause: the delete trigger zeroes
     * a bucket rather than removing it, and a source with nothing left in the
     * period is not a row of the breakdown.
     */
    @Query(
        """
        SELECT s.id           AS id,
               s.name         AS name,
               s.kind         AS kind,
               r.total_minor  AS totalMinor
          FROM rollup_income_month r
          JOIN income_source s ON s.id = r.source_id
         WHERE r.period_ym = :period AND r.entry_count > 0
         ORDER BY r.total_minor DESC
        """,
    )
    fun observeIncomeBySource(period: Int): Flow<List<SourceTotal>>

    /**
     * Income across a span of whole months — the year figure the income
     * screen's zero-month line reframes to (05 §9), and a scalar for the same
     * reason [observeExpenseTotalInPeriods] is one.
     */
    @Query(
        """
        SELECT IFNULL(SUM(total_minor), 0) FROM rollup_income_month
         WHERE period_ym BETWEEN :startPeriod AND :endPeriod
        """,
    )
    fun observeIncomeTotalInPeriods(startPeriod: Int, endPeriod: Int): Flow<Long>

    /**
     * 03 §5.4 — at most 12 × (leaf count) rows scanned. Feeds the trend chart
     * and the trailing-twelve average FR-AN-10 requires (never one period,
     * never a partial year).
     */
    @Query(
        """
        SELECT period_ym AS periodYm, SUM(total_minor) AS total
          FROM rollup_expense_month
         WHERE period_ym BETWEEN :startPeriod AND :endPeriod
         GROUP BY period_ym ORDER BY period_ym
        """,
    )
    fun observeExpenseSeries(startPeriod: Int, endPeriod: Int): Flow<List<PeriodTotal>>

    @Query(
        """
        SELECT period_ym AS periodYm, SUM(total_minor) AS total
          FROM rollup_income_month
         WHERE period_ym BETWEEN :startPeriod AND :endPeriod
         GROUP BY period_ym ORDER BY period_ym
        """,
    )
    fun observeIncomeSeries(startPeriod: Int, endPeriod: Int): Flow<List<PeriodTotal>>

    /**
     * Spend per (period, category) across a span — FR-AN-05's four periods.
     *
     * Reads only the rollup and `category`, so its cost is bounded by
     * 4 x (leaf count) — dozens of rows — rather than by how many expenses
     * exist. That bound is 03 §5.1's argument for NFR-PERF-04 applied to a
     * second query, and it is what the dashboard's scale test asserts.
     *
     * `txn_count > 0` for the reason §15.3 records: the delete trigger zeroes a
     * bucket rather than removing it, and a category with nothing left in a
     * period is not a row of anything. Without the clause a category the user
     * emptied would read as a delta of exactly its old baseline, for ever.
     */
    @Query(
        """
        SELECT r.period_ym    AS periodYm,
               c.id           AS categoryId,
               c.name         AS name,
               r.total_minor  AS totalMinor
          FROM rollup_expense_month r
          JOIN category c ON c.id = r.category_id
         WHERE r.period_ym BETWEEN :startPeriod AND :endPeriod
           AND r.txn_count > 0
        """,
    )
    fun observeCategoryCells(startPeriod: Int, endPeriod: Int): Flow<List<CategoryCellRow>>

    /** Daily spend for the month ribbon — one bar per day of the period. */
    @Query(
        """
        SELECT spent_on AS day, SUM(amount_minor) AS total
          FROM expense
         WHERE status = 0 AND spent_on BETWEEN :fromDay AND :toDay
         GROUP BY spent_on ORDER BY spent_on
        """,
    )
    fun observeDailySpend(fromDay: Long, toDay: Long): Flow<List<DayTotal>>

    /**
     * Escape hatch for the integrity checks and the rebuild action, which need
     * to run statements Room has no typed binding for.
     */
    @RawQuery
    suspend fun raw(query: SupportSQLiteQuery): Int
}
