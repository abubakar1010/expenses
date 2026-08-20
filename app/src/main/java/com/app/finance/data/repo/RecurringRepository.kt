package com.app.finance.data.repo

import androidx.room.withTransaction
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.PendingExpense
import com.app.finance.data.db.dao.PendingIncome
import com.app.finance.data.db.dao.RuleWithTarget
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.RecurringRuleEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.EntryStatus
import com.app.finance.domain.model.Frequency
import com.app.finance.domain.model.RuleTarget
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.usecase.RecurrenceSchedule
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** What one evaluation produced — what the ledger's section is about to show. */
data class GenerationResult(val pending: Int, val posted: Int) {
    val total: Int get() = pending + posted
}

/**
 * Recurring rules — FR-REC-01 … FR-REC-05.
 *
 * PRD §6.5 sets the tone for the whole feature in one sentence:
 *
 * > "Auto-posting without confirmation is available per-rule but off by default,
 * > because **silently generated transactions that didn't actually happen
 * > destroy trust in the ledger faster than any other bug**."
 *
 * Everything here is arranged so that the default path cannot do that. A
 * generated entry lands at `status = 1`, which every rollup trigger and every
 * read in the app excludes; it becomes real only when the user taps confirm.
 * The mechanism is not new — it is the same `status` column
 * `IncomeReconciliationTest.a_pending_entry_appears_in_neither` has been
 * asserting since M3.
 */
class RecurringRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.recurringDao()

    fun observeRules(): Flow<List<RuleWithTarget>> = dao.observeRules()

    fun observePendingExpenses(): Flow<List<PendingExpense>> = dao.observePendingExpenses()

    fun observePendingIncome(): Flow<List<PendingIncome>> = dao.observePendingIncome()

    suspend fun ruleById(id: Long): RecurringRuleEntity? = dao.ruleById(id)

    // --- FR-REC-01 -----------------------------------------------------------

    /**
     * @param anchorDay 1..31 for monthly and yearly. Ignored by weekly rules,
     *   which recur every seventh day from [startingFrom] — "every Friday" is
     *   not a day of the month.
     */
    suspend fun createRule(
        target: RuleTarget,
        targetId: Long,
        amount: Money,
        frequency: Frequency,
        anchorDay: Int,
        autoPost: Boolean = false,
        note: String? = null,
        startingFrom: LocalDate = LocalDate.now(clock),
    ): SaveOutcome {
        if (amount.paisa <= 0L) return SaveOutcome.Rejected(EntryError.ZERO_AMOUNT)
        if (anchorDay !in RecurrenceSchedule.MIN_ANCHOR..RecurrenceSchedule.MAX_ANCHOR) {
            return SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION)
        }
        val now = clock.millis()
        val firstDue = RecurrenceSchedule.firstDueOnOrAfter(frequency, anchorDay, startingFrom)

        return runCatching {
            dao.insertRule(
                RecurringRuleEntity(
                    uuid = UUID.randomUUID().toString(),
                    target = target.code,
                    categoryId = targetId.takeIf { target == RuleTarget.EXPENSE },
                    sourceId = targetId.takeIf { target == RuleTarget.INCOME },
                    amountMinor = amount.paisa,
                    frequency = frequency.code,
                    anchorDay = anchorDay,
                    nextDueDay = firstDue.toEpochDay(),
                    autoPost = autoPost,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION) },
        )
    }

    /** Pausing rather than deleting: a rule the user may want back next year. */
    suspend fun setActive(id: Long, active: Boolean) {
        val rule = dao.ruleById(id) ?: return
        dao.updateRule(rule.copy(isActive = active, updatedAt = clock.millis()))
    }

    suspend fun setAutoPost(id: Long, autoPost: Boolean) {
        val rule = dao.ruleById(id) ?: return
        dao.updateRule(rule.copy(autoPost = autoPost, updatedAt = clock.millis()))
    }

    /**
     * Returns the row, so the caller can offer Undo (NFR-USE-03).
     *
     * Entries it already generated stay. They are the user's transactions now,
     * not the rule's, and nothing references a rule so `ON DELETE RESTRICT`
     * guards nothing here.
     */
    suspend fun deleteRule(id: Long): RecurringRuleEntity? {
        val rule = dao.ruleById(id) ?: return null
        dao.deleteRule(rule)
        return rule
    }

    /** Re-inserts a deleted rule verbatim, uuid and schedule included. */
    suspend fun restoreRule(rule: RecurringRuleEntity): Long = dao.insertRule(rule.copy(id = 0))

    // --- FR-REC-02, -03, -04 -------------------------------------------------

    /**
     * Generates every occurrence up to and including [today].
     *
     * Called once per launch from `MainActivity`, beside the database check that
     * already runs there. **No WorkManager**: FR-REC-04 requires missed dates to
     * be generated "on next launch" regardless, 05 §12 rules out notifications,
     * and a background job would therefore produce rows nobody could see until
     * the app was opened — which is what this does, without a `ContentProvider`
     * on the startup path 04 §6 spent effort keeping clear.
     *
     * **FR-REC-03 (idempotence)** holds two ways over. The loop advances
     * `next_due_day` past [today] before it exits, so a second call the same day
     * finds nothing due; and each generation checks the ledger for a row with
     * the same target, day and amount, so even a rule whose bookkeeping was
     * corrupted cannot produce a duplicate.
     *
     * **FR-REC-04 (catch-up)** is why this is a `while` and not an `if`. An app
     * unopened since April generates April's, May's, June's and July's, each
     * individually confirmable. The loop is guarded on strict advancement, so a
     * rule whose schedule failed to move cannot spin.
     */
    suspend fun evaluate(today: LocalDate = LocalDate.now(clock)): GenerationResult {
        val todayDay = today.toEpochDay()
        var pending = 0
        var posted = 0

        db.withTransaction {
            dao.dueOnOrBefore(todayDay).forEach { rule ->
                var due = LocalDate.ofEpochDay(rule.nextDueDay)
                var lastRun = rule.lastRunDay
                val frequency = Frequency.fromCode(rule.frequency)

                while (due.toEpochDay() <= todayDay) {
                    if (generate(rule, due)) {
                        if (rule.autoPost) posted++ else pending++
                    }
                    lastRun = due.toEpochDay()

                    val nextDue = RecurrenceSchedule.next(frequency, rule.anchorDay, due)
                    // `next` is documented as strictly after; this is the guard
                    // that makes a rule with a broken schedule stop rather than
                    // fill the ledger.
                    if (!nextDue.isAfter(due)) break
                    due = nextDue
                }

                dao.updateRule(
                    rule.copy(
                        nextDueDay = due.toEpochDay(),
                        lastRunDay = lastRun,
                        updatedAt = clock.millis(),
                    ),
                )
            }
        }
        return GenerationResult(pending = pending, posted = posted)
    }

    /** @return true when a row was written; false when one already existed. */
    private suspend fun generate(rule: RecurringRuleEntity, due: LocalDate): Boolean {
        val now = clock.millis()
        val status = if (rule.autoPost) EntryStatus.POSTED.code else EntryStatus.PENDING.code

        return when (RuleTarget.fromCode(rule.target)) {
            RuleTarget.EXPENSE -> {
                val categoryId = rule.categoryId ?: return false
                if (dao.countExpenseOn(categoryId, due.toEpochDay(), rule.amountMinor) > 0) {
                    return false
                }
                db.expenseDao().insert(
                    ExpenseEntity(
                        uuid = UUID.randomUUID().toString(),
                        categoryId = categoryId,
                        amountMinor = rule.amountMinor,
                        spentOn = due.toEpochDay(),
                        // Derived here, never by SQL — 03 §4.3, the same rule
                        // every other write path follows.
                        periodYm = Period.from(due).ym,
                        note = rule.note,
                        status = status,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                true
            }

            RuleTarget.INCOME -> {
                val sourceId = rule.sourceId ?: return false
                if (dao.countIncomeOn(sourceId, due.toEpochDay(), rule.amountMinor) > 0) {
                    return false
                }
                db.incomeDao().insertEntry(
                    IncomeEntryEntity(
                        uuid = UUID.randomUUID().toString(),
                        sourceId = sourceId,
                        amountMinor = rule.amountMinor,
                        earnedOn = due.toEpochDay(),
                        periodYm = Period.from(due).ym,
                        note = rule.note,
                        status = status,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                true
            }
        }
    }

    // --- FR-REC-02's confirmation --------------------------------------------

    suspend fun confirmExpense(id: Long): Boolean = dao.confirmExpense(id, clock.millis()) > 0

    suspend fun confirmIncome(id: Long): Boolean = dao.confirmIncome(id, clock.millis()) > 0

    /**
     * The rule fired but the thing did not happen — the month the rent was
     * waived, or the subscription cancelled. Deleted rather than posted, because
     * an entry that never happened has no business in a ledger.
     *
     * Returns the row, because this is a delete and NFR-USE-03 makes **every**
     * destructive action undoable for five seconds. The rule will not generate
     * it again — its `next_due_day` has already moved past — so without an undo
     * a mis-tap would lose the entry for good.
     */
    suspend fun dismissExpense(id: Long): ExpenseEntity? {
        val row = dao.pendingExpenseById(id) ?: return null
        dao.dismissExpense(id)
        return row
    }

    suspend fun dismissIncome(id: Long): IncomeEntryEntity? {
        val row = dao.pendingIncomeById(id) ?: return null
        dao.dismissIncome(id)
        return row
    }

    /** Re-inserts a dismissed row verbatim, still pending, for Undo. */
    suspend fun restoreExpense(row: ExpenseEntity): Long =
        db.expenseDao().insert(row.copy(id = 0))

    suspend fun restoreIncome(row: IncomeEntryEntity): Long =
        db.incomeDao().insertEntry(row.copy(id = 0))
}
