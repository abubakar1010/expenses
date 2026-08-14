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
     * Keyset pagination — 03 §5.5. Deliberately not `OFFSET`, which degrades
     * linearly as the user scrolls into history and is exactly the case
     * NFR-PERF-05 measures.
     *
     * The row-value comparison `(spent_on, id) < (:lastDay, :lastId)` matches
     * the composite index directly. SQLite has supported row values since
     * 3.15; API 26 ships 3.18, so the floor is safe.
     */
    @Query(
        """
        SELECT e.*, c.name AS categoryName, c.nature AS categoryNature
          FROM expense e JOIN category c ON c.id = e.category_id
         WHERE e.status = 0
           AND (e.spent_on, e.id) < (:lastDay, :lastId)
         ORDER BY e.spent_on DESC, e.id DESC
         LIMIT :limit
        """,
    )
    suspend fun pageAfter(lastDay: Long, lastId: Long, limit: Int): List<ExpenseWithCategory>

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

    /** FR-EXP-08 — search by note substring or by exact amount. */
    @Query(
        """
        SELECT e.*, c.name AS categoryName, c.nature AS categoryNature
          FROM expense e JOIN category c ON c.id = e.category_id
         WHERE e.status = 0
           AND (:query = '' OR e.note LIKE '%' || :query || '%' OR e.amount_minor = :exactAmount)
           AND (:categoryId IS NULL OR e.category_id = :categoryId)
           AND (:method IS NULL OR e.payment_method = :method)
           AND e.spent_on BETWEEN :fromDay AND :toDay
         ORDER BY e.spent_on DESC, e.id DESC
         LIMIT :limit
        """,
    )
    suspend fun search(
        query: String,
        exactAmount: Long,
        categoryId: Long?,
        method: Int?,
        fromDay: Long,
        toDay: Long,
        limit: Int,
    ): List<ExpenseWithCategory>

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
}

data class DayTotal(val day: Long, val total: Long)
