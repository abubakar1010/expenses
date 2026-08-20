package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.app.finance.data.db.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Insert
    suspend fun insert(budget: BudgetEntity): Long

    @Update
    suspend fun update(budget: BudgetEntity)

    @Query("SELECT * FROM budget WHERE period_ym = :period")
    fun observeForPeriod(period: Int): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budget WHERE category_id = :categoryId AND period_ym = :period")
    suspend fun forCategory(categoryId: Long, period: Int): BudgetEntity?

    @Query("SELECT * FROM budget WHERE period_ym = :period")
    suspend fun forPeriod(period: Int): List<BudgetEntity>

    /**
     * The rows FR-BUD-04's copy is allowed to read from.
     *
     * `forPeriod` alone reads the `budget` table and nothing else, so it
     * happily carries a limit forward onto a leaf that has been archived since
     * — a limit on a category the user can no longer spend into, and one the
     * budget screen will not render (an archived leaf appears only when it
     * carries spend), so nothing on any screen could ever clear it again.
     * Joining the category is what keeps the copy to categories that still
     * exist for the user.
     */
    @Query(
        """
        SELECT b.* FROM budget b
          JOIN category c ON c.id = b.category_id
         WHERE b.period_ym = :period
           AND c.is_archived = 0
           AND NOT EXISTS (
                 SELECT 1 FROM category p
                  WHERE p.id = c.parent_id AND p.is_archived = 1
               )
        """,
    )
    suspend fun forPeriodActive(period: Int): List<BudgetEntity>

    @Query("DELETE FROM budget WHERE category_id = :categoryId AND period_ym = :period")
    suspend fun clear(categoryId: Long, period: Int)

    /**
     * Root-level budget figures are never stored — 03 §4.5. They are the sum of
     * their children, computed at query time over a handful of rows, and
     * therefore impossible to desynchronise from their parts.
     */
    @Query(
        """
        SELECT IFNULL(SUM(b.limit_minor), 0)
          FROM budget b JOIN category c ON c.id = b.category_id
         WHERE c.parent_id = :rootId AND b.period_ym = :period
        """,
    )
    fun observeRootLimit(rootId: Long, period: Int): Flow<Long>

    @Query("SELECT COUNT(*) FROM budget WHERE period_ym = :period")
    suspend fun countForPeriod(period: Int): Int

    /**
     * Total planned spend per period — FR-AN-09's budget reference line.
     *
     * Archived leaves excluded, for the reason §13.5 established when
     * copy-from-last-month was reading limits it could never clear: a limit on
     * an archived category is not a plan, and a reference line that includes
     * one is drawn above where the user actually intends to be.
     *
     * Reads `budget` and `category` only. Bounded by (periods x leaves).
     */
    @Query(
        """
        SELECT b.period_ym AS periodYm, SUM(b.limit_minor) AS total
          FROM budget b
          JOIN category c ON c.id = b.category_id
         WHERE b.period_ym BETWEEN :startPeriod AND :endPeriod
           AND c.is_archived = 0
         GROUP BY b.period_ym
        """,
    )
    fun observeTotalLimitsInPeriods(startPeriod: Int, endPeriod: Int): Flow<List<PeriodTotal>>
}
