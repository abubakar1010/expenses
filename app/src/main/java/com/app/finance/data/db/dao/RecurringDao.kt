package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.RecurringRuleEntity
import kotlinx.coroutines.flow.Flow

/** A rule with the name of whatever it posts to — the manager's rows. */
data class RuleWithTarget(
    @Embedded val rule: RecurringRuleEntity,
    /** The category's or the source's name, whichever the rule targets. */
    val targetName: String?,
    /**
     * Whether that target has since been archived.
     *
     * A rule can only be created against an active target, but nothing stops
     * the user archiving it afterwards — and FR-CAT-08 and FR-IS-04 both say an
     * archived one is out of the entry pickers. A rule that kept generating
     * into it would be producing entries the user could not create by hand, so
     * evaluation skips it and the row says so rather than looking live.
     */
    val targetArchived: Boolean,
)

/** A pending expense with its category name — the ledger's confirm rows. */
data class PendingExpense(
    @Embedded val expense: ExpenseEntity,
    val categoryName: String,
)

/** A pending income entry with its source name. */
data class PendingIncome(
    @Embedded val entry: IncomeEntryEntity,
    val sourceName: String,
)

@Dao
interface RecurringDao {

    // --- rules ---------------------------------------------------------------

    /**
     * Every rule, active first, with its target's name resolved.
     *
     * A `LEFT JOIN` to each side and `COALESCE` between them, because the table
     * CHECK makes exactly one of `category_id` / `source_id` non-null — an inner
     * join to either would drop half the rules.
     */
    @Query(
        """
        SELECT r.*,
               COALESCE(c.name, s.name) AS targetName,
               COALESCE(c.is_archived, s.is_archived, 0) AS targetArchived
          FROM recurring_rule r
          LEFT JOIN category c      ON c.id = r.category_id
          LEFT JOIN income_source s ON s.id = r.source_id
         ORDER BY r.is_active DESC, r.next_due_day
        """,
    )
    fun observeRules(): Flow<List<RuleWithTarget>>

    /**
     * The rules an evaluation has to consider — `ix_rule_due` covers the
     * `is_active` / `next_due_day` half, which is why that index exists.
     *
     * The joins add the half no index can: **a rule whose target has been
     * archived generates nothing.** FR-CAT-08 and FR-IS-04 put archived
     * categories and sources out of the entry pickers, and a rule quietly
     * posting into one would create entries the user could not have created
     * themselves. Un-archiving resumes it, because the rule was never altered.
     */
    @Query(
        """
        SELECT r.* FROM recurring_rule r
          LEFT JOIN category c      ON c.id = r.category_id
          LEFT JOIN income_source s ON s.id = r.source_id
         WHERE r.is_active = 1
           AND r.next_due_day <= :today
           AND COALESCE(c.is_archived, s.is_archived, 0) = 0
        """,
    )
    suspend fun dueOnOrBefore(today: Long): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rule WHERE id = :id")
    suspend fun ruleById(id: Long): RecurringRuleEntity?

    @Insert
    suspend fun insertRule(rule: RecurringRuleEntity): Long

    @Update
    suspend fun updateRule(rule: RecurringRuleEntity)

    @Delete
    suspend fun deleteRule(rule: RecurringRuleEntity)

    // --- what the rules generate --------------------------------------------
    //
    // `status = 1`. Every other query in the app filters `status = 0`, and none
    // of them changes: a pending row is invisible to the ledger, the rollups and
    // every figure derived from them until it is confirmed. These two are the
    // only reads that can see it, which is what makes the mechanism safe.

    @Query(
        """
        SELECT e.*, c.name AS categoryName
          FROM expense e JOIN category c ON c.id = e.category_id
         WHERE e.status = 1
         ORDER BY e.spent_on, e.id
        """,
    )
    fun observePendingExpenses(): Flow<List<PendingExpense>>

    @Query(
        """
        SELECT i.*, s.name AS sourceName
          FROM income_entry i JOIN income_source s ON s.id = i.source_id
         WHERE i.status = 1
         ORDER BY i.earned_on, i.id
        """,
    )
    fun observePendingIncome(): Flow<List<PendingIncome>>

    @Query("SELECT COUNT(*) FROM expense WHERE status = 1")
    suspend fun pendingExpenseCount(): Int

    /**
     * Confirming is a status change, not an insert.
     *
     * `trg_rollup_exp_upd` sees `OLD.status = 1` and `NEW.status = 0`, so it
     * skips the decrement and performs the increment — the entry joins every
     * aggregate at the moment of confirmation and not before, without a line of
     * application code touching a rollup.
     */
    @Query("UPDATE expense SET status = 0, updated_at = :now WHERE id = :id AND status = 1")
    suspend fun confirmExpense(id: Long, now: Long): Int

    @Query("UPDATE income_entry SET status = 0, updated_at = :now WHERE id = :id AND status = 1")
    suspend fun confirmIncome(id: Long, now: Long): Int

    @Query("SELECT * FROM expense WHERE id = :id AND status = 1")
    suspend fun pendingExpenseById(id: Long): ExpenseEntity?

    @Query("SELECT * FROM income_entry WHERE id = :id AND status = 1")
    suspend fun pendingIncomeById(id: Long): IncomeEntryEntity?

    @Query("DELETE FROM expense WHERE id = :id AND status = 1")
    suspend fun dismissExpense(id: Long): Int

    @Query("DELETE FROM income_entry WHERE id = :id AND status = 1")
    suspend fun dismissIncome(id: Long): Int

    /** FR-REC-03's guard, asked of the database rather than assumed. */
    @Query(
        """
        SELECT COUNT(*) FROM expense
         WHERE category_id = :categoryId AND spent_on = :day AND amount_minor = :amount
        """,
    )
    suspend fun countExpenseOn(categoryId: Long, day: Long, amount: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM income_entry
         WHERE source_id = :sourceId AND earned_on = :day AND amount_minor = :amount
        """,
    )
    suspend fun countIncomeOn(sourceId: Long, day: Long, amount: Long): Int
}
