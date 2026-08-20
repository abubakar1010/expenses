package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.data.db.entity.BudgetEntity
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.data.db.entity.RecurringRuleEntity

/**
 * Whole-table reads and writes — export, import, and delete-all.
 *
 * Separate from the feature DAOs on purpose. Every query in this file is a
 * `SELECT *` or a bulk write with no `WHERE`, which is exactly the shape the
 * rest of the app must never have: `ExpenseDao` pages because FR-EXP-10 forbids
 * loading the history into memory, and a `selectAll` sitting beside `firstPage`
 * is an invitation to call the wrong one. Here it is the point.
 *
 * Ordering matters on the way out as much as on the way in. Parents precede
 * children in [com.app.finance.data.export.Importer]'s insert order, because
 * `PRAGMA foreign_keys` is on and every child carries `ON DELETE RESTRICT`.
 */
@Dao
interface BackupDao {

    // --- reads ---------------------------------------------------------------

    @Query("SELECT * FROM category ORDER BY id")
    suspend fun allCategories(): List<CategoryEntity>

    @Query("SELECT * FROM income_source ORDER BY id")
    suspend fun allSources(): List<IncomeSourceEntity>

    @Query("SELECT * FROM budget ORDER BY id")
    suspend fun allBudgets(): List<BudgetEntity>

    /**
     * Every expense, pending rows included.
     *
     * An export that dropped `status = 1` would lose the recurring entries the
     * user has not confirmed yet — data they can see on the ledger and would
     * expect back. The rollups exclude them; the file does not.
     */
    @Query("SELECT * FROM expense ORDER BY id")
    suspend fun allExpenses(): List<ExpenseEntity>

    @Query("SELECT * FROM income_entry ORDER BY id")
    suspend fun allIncomeEntries(): List<IncomeEntryEntity>

    @Query("SELECT * FROM recurring_rule ORDER BY id")
    suspend fun allRules(): List<RecurringRuleEntity>

    @Query("SELECT * FROM app_meta ORDER BY key")
    suspend fun allMeta(): List<AppMetaEntity>

    // --- writes --------------------------------------------------------------
    //
    // ABORT rather than REPLACE: a collision during import is a bug in the
    // importer's own de-duplication, and `REPLACE` would hide it by silently
    // overwriting a row the merge had decided to keep. The transaction rolls
    // back instead (NFR-REL-04).

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategories(rows: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSources(rows: List<IncomeSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBudgets(rows: List<BudgetEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExpenses(rows: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIncomeEntries(rows: List<IncomeEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRules(rows: List<RecurringRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeta(rows: List<AppMetaEntity>)

    @Update
    suspend fun updateCategories(rows: List<CategoryEntity>)

    @Update
    suspend fun updateSources(rows: List<IncomeSourceEntity>)

    @Update
    suspend fun updateBudgets(rows: List<BudgetEntity>)

    @Update
    suspend fun updateExpenses(rows: List<ExpenseEntity>)

    @Update
    suspend fun updateIncomeEntries(rows: List<IncomeEntryEntity>)

    @Update
    suspend fun updateRules(rows: List<RecurringRuleEntity>)

    // --- lookups the merge needs ---------------------------------------------
    //
    // DR-06: "Every entity MUST carry a stable UUID alongside its integer
    // primary key, **to support import deduplication across devices**." These
    // are that support.

    @Query("SELECT uuid, id FROM category")
    suspend fun categoryUuids(): List<UuidId>

    @Query("SELECT uuid, id FROM income_source")
    suspend fun sourceUuids(): List<UuidId>

    @Query("SELECT uuid, id FROM budget")
    suspend fun budgetUuids(): List<UuidId>

    @Query("SELECT uuid, id FROM expense")
    suspend fun expenseUuids(): List<UuidId>

    @Query("SELECT uuid, id FROM income_entry")
    suspend fun incomeEntryUuids(): List<UuidId>

    @Query("SELECT uuid, id FROM recurring_rule")
    suspend fun ruleUuids(): List<UuidId>
}

/** One row's stable key beside its local integer key. */
data class UuidId(val uuid: String, val id: Long)
