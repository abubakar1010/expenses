package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One leaf category's budget bar for the period being viewed. */
data class BudgetBarRow(
    val id: Long,
    val name: String,
    val nature: Int,
    val limitMinor: Long,
    val spentMinor: Long,
)

data class PeriodTotal(val periodYm: Int, val total: Long)

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
               c.name                    AS name,
               c.nature                  AS nature,
               IFNULL(b.limit_minor, 0)  AS limitMinor,
               IFNULL(r.total_minor, 0)  AS spentMinor
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

    /** 03 §5.2 — period income by source, for the income screen breakdown. */
    @Query(
        """
        SELECT s.id           AS id,
               s.name         AS name,
               s.kind         AS kind,
               r.total_minor  AS totalMinor
          FROM rollup_income_month r
          JOIN income_source s ON s.id = r.source_id
         WHERE r.period_ym = :period
         ORDER BY r.total_minor DESC
        """,
    )
    fun observeIncomeBySource(period: Int): Flow<List<SourceTotal>>

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
