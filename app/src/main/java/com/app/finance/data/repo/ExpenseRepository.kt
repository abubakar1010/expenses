package com.app.finance.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.PaymentMethod
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The expense write path — 04-system-architecture.md §5.1, the hot path of the
 * whole application.
 *
 * Two properties fall out of this design rather than being coded:
 *
 * **Aggregates cannot drift.** The rollup update happens inside the same
 * transaction as the insert, performed by a database trigger. There is no code
 * path — including bulk import, undo, and features not yet written — that can
 * write an expense without updating its aggregate.
 *
 * **No manual refresh anywhere.** Room's invalidation tracker notifies every
 * `Flow` observing an affected table. The dashboard does not subscribe to an
 * "expense added" event; it observes a query and re-emits. That removes the
 * entire class of bug where one screen updates and another shows stale numbers.
 */
class ExpenseRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val expenseDao = db.expenseDao()
    private val categoryDao = db.categoryDao()
    private val appMetaDao = db.appMetaDao()

    /** The ledger page size. 03 §5.5 measures the keyset query at this width. */
    val pageSize: Int get() = PAGE_SIZE

    suspend fun insert(
        amount: Money,
        categoryId: Long,
        spentOn: LocalDate = LocalDate.now(clock),
        method: PaymentMethod = PaymentMethod.DEFAULT,
        note: String? = null,
    ): SaveOutcome {
        validate(amount, categoryId)?.let { return SaveOutcome.Rejected(it) }

        val now = clock.millis()
        val entity = ExpenseEntity(
            uuid = UUID.randomUUID().toString(),
            categoryId = categoryId,
            amountMinor = amount.paisa,
            spentOn = spentOn.toEpochDay(),
            // Derived here, never by the caller and never by SQL. This one
            // denormalised column is what turns every monthly filter into an
            // indexed equality test.
            periodYm = Period.from(spentOn).ym,
            paymentMethod = method.code,
            note = note?.trim()?.ifBlank { null },
            status = 0,
            createdAt = now,
            updatedAt = now,
        )

        return runCatching {
            db.withTransaction {
                val id = expenseDao.insert(entity) // trigger updates the rollup
                rememberDefaults(categoryId, method, now)
                id
            }
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toEntryError()) },
        )
    }

    /**
     * FR-EXP-07 — editing recalculates every dependent aggregate, including
     * those of prior periods. Nothing here does that recalculation: the update
     * trigger decrements the old (period, category) bucket and increments the
     * new one, so moving a June expense into July is correct by construction.
     */
    suspend fun update(
        id: Long,
        amount: Money,
        categoryId: Long,
        spentOn: LocalDate,
        method: PaymentMethod,
        note: String?,
    ): SaveOutcome {
        validate(amount, categoryId)?.let { return SaveOutcome.Rejected(it) }
        val existing = expenseDao.byId(id) ?: return SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION)

        return runCatching {
            db.withTransaction {
                expenseDao.update(
                    existing.copy(
                        categoryId = categoryId,
                        amountMinor = amount.paisa,
                        spentOn = spentOn.toEpochDay(),
                        periodYm = Period.from(spentOn).ym,
                        paymentMethod = method.code,
                        note = note?.trim()?.ifBlank { null },
                        updatedAt = clock.millis(),
                    ),
                )
                id
            }
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toEntryError()) },
        )
    }

    /**
     * Deletes and returns the row, so the caller can offer Undo.
     *
     * 05 §8: every destructive action is undoable for five seconds and there
     * are no confirmation dialogs for deletes — "a dialog interrupts before the
     * fact and is dismissed reflexively; a snackbar corrects after it and costs
     * nothing when the action was intended."
     */
    suspend fun delete(id: Long): ExpenseEntity? {
        val row = expenseDao.byId(id) ?: return null
        expenseDao.delete(row)
        return row
    }

    /** Re-inserts a deleted row verbatim, UUID included, for Undo. */
    suspend fun restore(row: ExpenseEntity): Long = expenseDao.insert(row.copy(id = 0))

    suspend fun byId(id: Long): ExpenseEntity? = expenseDao.byId(id)

    suspend fun firstPage(): List<ExpenseWithCategory> = expenseDao.firstPage(PAGE_SIZE)

    /** Keyset, not offset — see [com.app.finance.data.db.dao.ExpenseDao.pageAfter]. */
    suspend fun pageAfter(last: ExpenseWithCategory): List<ExpenseWithCategory> =
        expenseDao.pageAfter(last.expense.spentOn, last.expense.id, PAGE_SIZE)

    /** Ticks whenever the ledger changes, so screens re-read without an event bus. */
    fun observeRevision(): Flow<Int> = expenseDao.observePostedCount()

    // ------------------------------------------------------------- internals

    private suspend fun validate(amount: Money, categoryId: Long): EntryError? {
        if (amount.isZero) return EntryError.ZERO_AMOUNT
        val category = categoryDao.byId(categoryId) ?: return EntryError.CATEGORY_NOT_FOUND
        if (category.isArchived) return EntryError.CATEGORY_ARCHIVED
        if (category.parentId == null || categoryDao.hasChildren(categoryId)) {
            return EntryError.NOT_A_LEAF_CATEGORY
        }
        return null
    }

    /**
     * FR-EXP-02/03 — "defaults do the work". Written in the same transaction as
     * the expense, so the next Quick Add opens pre-filled even if the process
     * dies immediately after the save.
     *
     * The recent list is what fills the six chips on the entry sheet, turning
     * category selection from *tap, scroll, find, tap* into one tap.
     */
    private suspend fun rememberDefaults(categoryId: Long, method: PaymentMethod, now: Long) {
        appMetaDao.put(AppMetaEntity(AppMetaDao.KEY_LAST_CATEGORY, categoryId.toString(), now))
        appMetaDao.put(AppMetaEntity(AppMetaDao.KEY_LAST_METHOD, method.code.toString(), now))

        val recent = appMetaDao.get(AppMetaDao.KEY_RECENT_CATEGORIES)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            .orEmpty()
        val updated = (listOf(categoryId) + recent.filter { it != categoryId })
            .take(AppMetaDao.RECENT_CATEGORY_LIMIT)
        appMetaDao.put(
            AppMetaEntity(AppMetaDao.KEY_RECENT_CATEGORIES, updated.joinToString(","), now),
        )
    }

    private fun Throwable.toEntryError(): EntryError = when {
        this !is SQLiteConstraintException -> EntryError.CONSTRAINT_VIOLATION
        message?.contains("leaf categories") == true -> EntryError.NOT_A_LEAF_CATEGORY
        message?.contains("amount_minor") == true -> EntryError.ZERO_AMOUNT
        else -> EntryError.CONSTRAINT_VIOLATION
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
