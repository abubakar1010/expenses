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
}
