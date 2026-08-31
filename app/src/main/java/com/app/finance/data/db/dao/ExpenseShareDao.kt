package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.app.finance.data.db.entity.ExpenseShareEntity

/**
 * What other people owe on an expense you paid — FR-SHR-02.
 *
 * Small by construction: rows exist only for shared bills, so this table grows
 * with how often you split rather than with the twenty thousand rows of the
 * ledger. Nothing here pages.
 */
@Dao
interface ExpenseShareDao {

    @Insert
    suspend fun insert(shares: List<ExpenseShareEntity>)

    @Query("SELECT * FROM expense_share WHERE expense_id = :expenseId ORDER BY id")
    suspend fun forExpense(expenseId: Long): List<ExpenseShareEntity>

    /**
     * Every share on a set of expenses, for the ledger's third line.
     *
     * One query per page rather than one per row: the ledger renders fifty rows
     * at a time and a per-row lookup would put fifty round trips on a scroll
     * that NFR-PERF-05 holds to 55 fps.
     */
    @Query("SELECT * FROM expense_share WHERE expense_id IN (:expenseIds)")
    suspend fun forExpenses(expenseIds: List<Long>): List<ExpenseShareEntity>

    /**
     * Clears an expense's shares.
     *
     * Needed before the expense itself can go: `expense_share.expense_id` is
     * `ON DELETE RESTRICT`, so deleting a shared expense without this throws
     * rather than cascading. Both happen in one transaction — see
     * [com.app.finance.data.repo.ExpenseRepository.delete].
     */
    @Query("DELETE FROM expense_share WHERE expense_id = :expenseId")
    suspend fun deleteForExpense(expenseId: Long)

    @Query("SELECT COUNT(*) FROM expense_share WHERE expense_id = :expenseId")
    suspend fun countForExpense(expenseId: Long): Int
}
