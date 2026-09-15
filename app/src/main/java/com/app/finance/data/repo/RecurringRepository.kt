package com.app.finance.data.repo

import androidx.room.withTransaction
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.PendingExpense
import com.app.finance.data.db.dao.PendingIncome
import com.app.finance.data.db.dao.RuleWithTarget
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.ExpenseShareEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.RecurringRuleEntity
import com.app.finance.data.db.entity.RecurringRuleShareEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.EntryStatus
import com.app.finance.domain.model.Frequency
import com.app.finance.domain.model.RuleTarget
import com.app.finance.domain.model.SaveOutcome
import com.app.finance.domain.model.Split
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
 * A rule removed, whole, so Undo can put it back — [DeletedExpense]'s reason:
 * after the delete its shares exist nowhere else.
 */
data class DeletedRule(
    val rule: RecurringRuleEntity,
    val shares: List<RecurringRuleShareEntity> = emptyList(),
)

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
     * @param amount what each generated entry stores — for a shared spending
     *   rule, **your share**, exactly as `ExpenseRepository.insert` takes it.
     * @param anchorDay 1..31 for monthly and yearly. Ignored by weekly rules,
     *   which recur every seventh day from [startingFrom] — "every Friday" is
     *   not a day of the month.
     * @param split FR-REC-06. Copied onto every occurrence. Spending rules only.
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
        split: Split = Split.NONE,
    ): SaveOutcome {
        if (split.isShared) {
            if (target != RuleTarget.EXPENSE) return SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION)
            // Before the amount check, so an over-allocated split says what is
            // actually wrong with it rather than "amount can't be zero".
            split.validate(amount)?.let { return SaveOutcome.Rejected(it) }
        }
        if (amount.paisa <= 0L) return SaveOutcome.Rejected(EntryError.ZERO_AMOUNT)
        if (anchorDay !in RecurrenceSchedule.MIN_ANCHOR..RecurrenceSchedule.MAX_ANCHOR) {
            return SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION)
        }
        // A rule is a promise to keep writing shares against these people. An
        // archived person is out of every picker (FR-SHR-01), and evaluation
        // would skip the rule from its first due date — so refuse it here, with
        // the sentence that says to restore them, rather than save a rule that
        // never runs.
        (split.owed.map { it.personId } + listOfNotNull(split.payerPersonId)).forEach { id ->
            val person = db.personDao().byId(id)
                ?: return SaveOutcome.Rejected(EntryError.PERSON_NOT_FOUND)
            if (person.isArchived) return SaveOutcome.Rejected(EntryError.PERSON_ARCHIVED)
        }
        val now = clock.millis()
        val firstDue = RecurrenceSchedule.firstDueOnOrAfter(frequency, anchorDay, startingFrom)

        return runCatchingWrite {
            db.withTransaction {
                val id = dao.insertRule(
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
                        payerPersonId = split.payerPersonId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                if (split.owed.isNotEmpty()) {
                    dao.insertShares(
                        split.owed.map {
                            RecurringRuleShareEntity(
                                uuid = UUID.randomUUID().toString(),
                                ruleId = id,
                                personId = it.personId,
                                shareMinor = it.amount.paisa,
                                createdAt = now,
                                updatedAt = now,
                            )
                        },
                    )
                }
                id
            }
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            // There was no mapping here at all — every failure was
            // `CONSTRAINT_VIOLATION`, so a full disk and a rule that violated
            // its target CHECK produced the same sentence and neither was
            // logged. See [toWriteError].
            onFailure = { SaveOutcome.Rejected(it.toRuleError(target)) },
        )
    }

    /** See [toWriteError] — only the constraint half is this repository's. */
    /**
     * See [toWriteError] — only the constraint half is this repository's.
     *
     * [target] decides which "not found" the user reads, because a rule can
     * point at either side of the ledger and routing a missing *source* through
     * the category error prints "Pick a category" on a screen that has none.
     * The same distinction [EntryError.SOURCE_NOT_FOUND] exists for.
     */
    private fun Throwable.toRuleError(target: RuleTarget): EntryError =
        toWriteError("save a repeating entry") {
            when {
                it.message?.contains("FOREIGN KEY", ignoreCase = true) == true ->
                    if (target == RuleTarget.EXPENSE) EntryError.CATEGORY_NOT_FOUND
                    else EntryError.SOURCE_NOT_FOUND
                // The FR-REC-06 guards, matched on the text they raise.
                it.message?.contains("a share may only be recorded") == true ->
                    EntryError.SHARE_ON_FOREIGN_PAYMENT
                it.message?.contains("share_minor") == true -> EntryError.SPLIT_DOES_NOT_BALANCE
                it.message?.contains("amount_minor") == true -> EntryError.ZERO_AMOUNT
                // `CHECK (…exactly one of category_id / source_id…)` — 03 §4.5.
                // Reachable only from a caller that built a rule with neither or
                // both, which the UI cannot do; it falls through to the generic
                // branch, which now at least says so in the log.
                else -> null
            }
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
    suspend fun deleteRule(id: Long): DeletedRule? = db.withTransaction {
        val rule = dao.ruleById(id) ?: return@withTransaction null
        // `recurring_rule_share.rule_id` is `ON DELETE RESTRICT`, so the shares
        // go first — and are handed back, because Undo has to restore a shared
        // rent as a shared rent.
        val shares = dao.sharesForRule(id)
        dao.deleteSharesForRule(id)
        dao.deleteRule(rule)
        DeletedRule(rule, shares)
    }

    /**
     * Re-inserts a deleted rule verbatim, uuid and schedule included, with its
     * shares re-pointed at the new row id and their own uuids kept.
     */
    suspend fun restoreRule(deleted: DeletedRule): Long = db.withTransaction {
        val id = dao.insertRule(deleted.rule.copy(id = 0))
        if (deleted.shares.isNotEmpty()) {
            dao.insertShares(deleted.shares.map { it.copy(id = 0, ruleId = id) })
        }
        id
    }

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
     * **FR-REC-03 (idempotence)** holds three ways over. The loop advances
     * `next_due_day` past [today] before it exits, so a second call the same day
     * finds nothing due; no occurrence at or before `last_run_day` is generated
     * again, which covers a `next_due_day` recomputed *backwards* by an edit to
     * the anchor day; and each generation checks the ledger for a **pending**
     * row of the same target, day and amount, which is independent of the
     * rule's own bookkeeping.
     *
     * That third check used to look at the whole ledger, and a user's own entry
     * of the same shape is indistinguishable from a rule's — so logging the
     * rent by hand once switched the rent rule off for good. See
     * [com.app.finance.data.db.dao.RecurringDao.countExpenseOn].
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
                    // FR-REC-03's first guard, and until now it was only
                    // *written*. `Entities.lastRunDay` says "generation
                    // proceeds only when next_due_day > last_run_day"; the
                    // column was updated on every evaluation and never read, so
                    // the only thing standing between a rule and a duplicate
                    // was `next_due_day` — which editing a rule's anchor day
                    // recomputes, and can recompute *backwards* onto a date
                    // already generated.
                    if (lastRun == null || due.toEpochDay() > lastRun) {
                        if (generate(rule, due)) {
                            if (rule.autoPost) posted++ else pending++
                        }
                        lastRun = due.toEpochDay()
                    }

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
                val expenseId = db.expenseDao().insert(
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
                        payerPersonId = rule.payerPersonId,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                // FR-REC-06. Copied, not re-divided: the rule's shares were
                // allocated once against the bill, and the occurrence is the
                // same bill. A pending occurrence's shares are in no balance —
                // `SettlementDao` reads `status = 0` — until it is confirmed.
                val shares = dao.sharesForRule(rule.id)
                if (shares.isNotEmpty()) {
                    db.expenseShareDao().insert(
                        shares.map {
                            ExpenseShareEntity(
                                uuid = UUID.randomUUID().toString(),
                                expenseId = expenseId,
                                personId = it.personId,
                                shareMinor = it.shareMinor,
                                createdAt = now,
                                updatedAt = now,
                            )
                        },
                    )
                }
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
    suspend fun dismissExpense(id: Long): DeletedExpense? = db.withTransaction {
        // Read inside the transaction: if the row was confirmed a moment ago,
        // its shares belong to a posted expense now and must not be touched.
        val row = dao.pendingExpenseById(id) ?: return@withTransaction null
        // A shared rule's occurrence carries shares, and
        // `expense_share.expense_id` is `ON DELETE RESTRICT` — so they go
        // first, and travel with the row for Undo.
        val shares = db.expenseShareDao().forExpense(id)
        db.expenseShareDao().deleteForExpense(id)
        dao.dismissExpense(id)
        DeletedExpense(row, shares)
    }

    suspend fun dismissIncome(id: Long): IncomeEntryEntity? {
        val row = dao.pendingIncomeById(id) ?: return null
        dao.dismissIncome(id)
        return row
    }

    /** Re-inserts a dismissed row verbatim, still pending, with its shares, for Undo. */
    suspend fun restoreExpense(deleted: DeletedExpense): Long = db.withTransaction {
        val id = db.expenseDao().insert(deleted.expense.copy(id = 0))
        if (deleted.shares.isNotEmpty()) {
            db.expenseShareDao().insert(deleted.shares.map { it.copy(id = 0, expenseId = id) })
        }
        id
    }

    suspend fun restoreIncome(row: IncomeEntryEntity): Long =
        db.incomeDao().insertEntry(row.copy(id = 0))
}
