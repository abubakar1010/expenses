package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.app.finance.data.db.entity.ExpenseEntity
import kotlinx.coroutines.flow.Flow

/** An expense joined to the name of the category it was filed under. */
data class ExpenseWithCategory(
    @Embedded val expense: ExpenseEntity,
    val categoryName: String,
    val categoryNature: Int,
)

@Dao
interface ExpenseDao {

    @Insert
    suspend fun insert(expense: ExpenseEntity): Long

    @Update
    suspend fun update(expense: ExpenseEntity)

    @Delete
    suspend fun delete(expense: ExpenseEntity)

    @Query("SELECT * FROM expense WHERE id = :id")
    suspend fun byId(id: Long): ExpenseEntity?

    /**
     * First page of the ledger.
     *
     * `LIMIT` with no offset, ordered exactly as `ix_expense_date` is stored,
     * so the read is a straight index walk with no temp B-tree.
     */
    @Query(
        """
        SELECT e.*, c.name AS categoryName, c.nature AS categoryNature
          FROM expense e JOIN category c ON c.id = e.category_id
         WHERE e.status = 0
         ORDER BY e.spent_on DESC, e.id DESC
         LIMIT :limit
        """,
    )
    suspend fun firstPage(limit: Int): List<ExpenseWithCategory>


    /**
     * Emits on every change to `expense`, so the ledger re-reads its first page
     * without any event bus. Room's invalidation tracker is the whole
     * refresh mechanism (04 §5.1).
     */
    @Query("SELECT COUNT(*) FROM expense WHERE status = 0")
    fun observePostedCount(): Flow<Int>

    /** Per-day subtotals for the ledger's day headers (FR-EXP-09). */
    @Query(
        """
        SELECT spent_on AS day, SUM(amount_minor) AS total
          FROM expense
         WHERE status = 0 AND spent_on BETWEEN :fromDay AND :toDay
         GROUP BY spent_on
         ORDER BY spent_on DESC
        """,
    )
    suspend fun dailyTotals(fromDay: Long, toDay: Long): List<DayTotal>

    /**
     * Arbitrary date range — 03 §5.3. A deliberate exception to the rollup
     * strategy: ranges that do not align to month boundaries cannot use them,
     * so this falls back to a bounded index scan on `ix_expense_date`. It is
     * invoked from Reports on explicit user action, never on a dashboard
     * render.
     */
    @Query("SELECT IFNULL(SUM(amount_minor), 0) FROM expense WHERE status = 0 AND spent_on BETWEEN :fromDay AND :toDay")
    suspend fun totalInRange(fromDay: Long, toDay: Long): Long

    /**
     * The same figure as a `Flow`, for the income screen's stable-coverage line
     * over a custom range (FR-AN-06 with FR-IE-04's third total).
     *
     * A separate declaration rather than a `Flow`-returning replacement:
     * `totalInRange` is a one-shot read used by report-style code that does not
     * want an invalidation subscription, and Room's tracker is not free.
     */
    @Query("SELECT IFNULL(SUM(amount_minor), 0) FROM expense WHERE status = 0 AND spent_on BETWEEN :fromDay AND :toDay")
    fun observeTotalInRange(fromDay: Long, toDay: Long): Flow<Long>

    /**
     * The filtered, keyset-paged ledger — FR-EXP-08 and FR-EXP-10 in one query.
     *
     * One statement serves both the first page and every page after it:
     * [firstPage] passes `noKeyset = 1`, later pages pass the last row's
     * `(spent_on, id)`. That keeps a single query plan to document for
     * NFR-MAIN-03 rather than a family of near-identical ones.
     *
     * Every filter is expressed as `(<disabled flag> OR <predicate>)`, which
     * lets the whole thing stay a compile-time-verified `@Query` instead of a
     * `@RawQuery` assembled from strings. [categoryIds] is never empty — the
     * caller passes a non-matching sentinel when no category filter is active.
     *
     * The `ORDER BY` matches `ix_expense_date` exactly, so even with a `LIKE`
     * over notes the read is an index walk with no temp B-tree.
     */
    /**
     * Keyset pagination — 03 §5.5. Deliberately not `OFFSET`, which degrades
     * linearly as the user scrolls into history and is exactly the case
     * NFR-PERF-05 measures.
     *
     * The row-value comparison `(spent_on, id) < (:lastDay, :lastId)` matches
     * the composite index directly. SQLite has supported row values since
     * 3.15; API 26 ships 3.18, so the floor is safe.
     *
     * This paragraph used to sit on a second, unfiltered `pageAfter` — the
     * ledger's first paging query, superseded when FR-EXP-08's filters arrived
     * and this one grew an `:noKeyset` flag to serve both the first page and
     * every page after it. Nothing had called `pageAfter` since, in either the
     * DAO or the repository, and it went on being maintained and explained: a
     * second implementation of the app's most performance-sensitive read, kept
     * warm by nothing. Removed rather than given the test §22 was going to
     * write for it.
     */
    @Query(
        """
        SELECT e.*, c.name AS categoryName, c.nature AS categoryNature
          FROM expense e JOIN category c ON c.id = e.category_id
         WHERE e.status = 0
           AND (:noKeyset = 1 OR (e.spent_on, e.id) < (:lastDay, :lastId))
           AND e.spent_on BETWEEN :fromDay AND :toDay
           AND (:anyCategory = 1 OR e.category_id IN (:categoryIds))
           AND (:anyMethod = 1 OR e.payment_method = :method)
           AND (
                :noQuery = 1
                OR e.note LIKE '%' || :query || '%' ESCAPE '\'
                OR (:hasAmount = 1 AND e.amount_minor = :exactAmount)
               )
         ORDER BY e.spent_on DESC, e.id DESC
         LIMIT :limit
        """,
    )
    suspend fun page(
        noKeyset: Int,
        lastDay: Long,
        lastId: Long,
        fromDay: Long,
        toDay: Long,
        anyCategory: Int,
        categoryIds: List<Long>,
        anyMethod: Int,
        method: Int,
        noQuery: Int,
        query: String,
        hasAmount: Int,
        exactAmount: Long,
        limit: Int,
    ): List<ExpenseWithCategory>

    /**
     * What the whole filtered set comes to — FR-EXP-11.
     *
     * The predicate is [page]'s, minus the keyset clause and the limit, and
     * that difference is the point. [page] holds only the pages scrolled so far
     * because FR-EXP-10 forbids loading the history into memory, so summing the
     * rows in hand would produce a figure that *grows as the user scrolls* —
     * worse than showing nothing, because it looks like an answer.
     *
     * No join to `category`. [page] joins only to select the category's name
     * and nature for display; `expense.category_id` is `NOT NULL` behind an
     * enforced foreign key (`PRAGMA foreign_keys = ON`, 03 §1), so an inner
     * join cannot drop a row and its absence here cannot change the result. It
     * only saves a lookup per row on a scan that may cover 20,000 of them.
     *
     * NFR-REL-02 requires this to reconcile exactly with a direct sum, and the
     * one thing that could break that is this predicate and [page]'s drifting
     * apart — two hand-copied queries, which is how the EXPLAIN test in §22.5
     * went wrong. `the_total_is_every_matching_row_not_the_loaded_ones` and
     * `the_total_agrees_with_the_sum_of_every_page` are the guards.
     */
    @Query(
        """
        SELECT IFNULL(SUM(e.amount_minor), 0) AS totalMinor, COUNT(*) AS txnCount
          FROM expense e
         WHERE e.status = 0
           AND e.spent_on BETWEEN :fromDay AND :toDay
           AND (:anyCategory = 1 OR e.category_id IN (:categoryIds))
           AND (:anyMethod = 1 OR e.payment_method = :method)
           AND (
                :noQuery = 1
                OR e.note LIKE '%' || :query || '%' ESCAPE '\'
                OR (:hasAmount = 1 AND e.amount_minor = :exactAmount)
               )
        """,
    )
    suspend fun filteredTotal(
        fromDay: Long,
        toDay: Long,
        anyCategory: Int,
        categoryIds: List<Long>,
        anyMethod: Int,
        method: Int,
        noQuery: Int,
        query: String,
        hasAmount: Int,
        exactAmount: Long,
    ): FilteredTotal

    /** Top N of the period, descending — one of the eight dashboard metrics. */
    @Query(
        """
        SELECT e.*, c.name AS categoryName, c.nature AS categoryNature
          FROM expense e JOIN category c ON c.id = e.category_id
         WHERE e.status = 0 AND e.period_ym = :period
         ORDER BY e.amount_minor DESC
         LIMIT :limit
        """,
    )
    fun observeLargest(period: Int, limit: Int): Flow<List<ExpenseWithCategory>>

    /**
     * The same top-N over an arbitrary range, for Reports.
     *
     * A separate declaration rather than a generalisation of [observeLargest]:
     * that one is keyed on `period_ym` and rides `ix_expense_period`, this one
     * is a bounded scan of `ix_expense_date`, and collapsing them would cost
     * the dashboard its index for the sake of one shared signature.
     */
    @Query(
        """
        SELECT e.*, c.name AS categoryName, c.nature AS categoryNature
          FROM expense e JOIN category c ON c.id = e.category_id
         WHERE e.status = 0 AND e.spent_on BETWEEN :fromDay AND :toDay
         ORDER BY e.amount_minor DESC
         LIMIT :limit
        """,
    )
    fun observeLargestInRange(fromDay: Long, toDay: Long, limit: Int): Flow<List<ExpenseWithCategory>>

    /**
     * Spend per nature over an arbitrary range — FR-AN-07's split, off the
     * ledger rather than off the rollups.
     *
     * 03 §5.3 licenses exactly this: "Range queries are a deliberate exception
     * to the rollup strategy: they are invoked from the reports screen on
     * explicit user action, not on every dashboard render, so a bounded index
     * scan is acceptable there."
     */
    @Query(
        """
        SELECT c.nature AS nature,
               SUM(e.amount_minor) AS totalMinor,
               COUNT(*) AS txnCount
          FROM expense e JOIN category c ON c.id = e.category_id
         WHERE e.status = 0 AND e.spent_on BETWEEN :fromDay AND :toDay
         GROUP BY c.nature
        """,
    )
    fun observeNatureTotalsInRange(fromDay: Long, toDay: Long): Flow<List<NatureTotal>>
}

/** One nature's spend over a range. */
data class NatureTotal(val nature: Int, val totalMinor: Long, val txnCount: Int)

data class DayTotal(val day: Long, val total: Long)

/** What the current ledger filter matches, in full — FR-EXP-11. */
data class FilteredTotal(val totalMinor: Long, val txnCount: Int)
