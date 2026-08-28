package com.app.finance.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.dao.ExpenseWithCategory
import com.app.finance.data.db.dao.FilteredTotal
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.LedgerFilters
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
        validate(amount, categoryId, spentOn)?.let { return SaveOutcome.Rejected(it) }

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

        return runCatchingWrite {
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
        validate(amount, categoryId, spentOn)?.let { return SaveOutcome.Rejected(it) }
        val existing = expenseDao.byId(id) ?: return SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION)

        return runCatchingWrite {
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

    /**
     * One page of the filtered ledger — FR-EXP-08.
     *
     * A root filter is resolved to its leaves here rather than in SQL: the tree
     * is two levels and a few dozen rows, so one extra indexed read is cheaper
     * and far clearer than a self-join inside the hot paging query.
     *
     * @param after the last row of the previous page, or null for the first.
     */
    suspend fun filteredPage(
        filters: LedgerFilters,
        after: ExpenseWithCategory? = null,
    ): List<ExpenseWithCategory> {
        val p = resolvePredicate(filters)

        return expenseDao.page(
            noKeyset = if (after == null) 1 else 0,
            lastDay = after?.expense?.spentOn ?: 0L,
            lastId = after?.expense?.id ?: 0L,
            fromDay = p.fromDay,
            toDay = p.toDay,
            anyCategory = p.anyCategory,
            categoryIds = p.categoryIds,
            anyMethod = p.anyMethod,
            method = p.method,
            noQuery = p.noQuery,
            query = p.query,
            hasAmount = p.hasAmount,
            exactAmount = p.exactAmount,
            limit = PAGE_SIZE,
        )
    }

    /**
     * What the filter matches in total, across every page — FR-EXP-11.
     *
     * Deliberately not derived from [filteredPage]'s results. FR-EXP-10 keeps
     * only the scrolled pages in memory, so a total summed from those would
     * climb as the user scrolls and settle on the truth only at the very
     * bottom. One aggregate over the same predicate is the only figure that
     * reconciles with the ledger the way NFR-REL-02 demands.
     */
    suspend fun filteredTotal(filters: LedgerFilters): FilteredTotal {
        val p = resolvePredicate(filters)

        return expenseDao.filteredTotal(
            fromDay = p.fromDay,
            toDay = p.toDay,
            anyCategory = p.anyCategory,
            categoryIds = p.categoryIds,
            anyMethod = p.anyMethod,
            method = p.method,
            noQuery = p.noQuery,
            query = p.query,
            hasAmount = p.hasAmount,
            exactAmount = p.exactAmount,
        )
    }

    /**
     * The filter, resolved to the bound values both queries take.
     *
     * Extracted when [filteredTotal] arrived and became a second reader of the
     * same predicate. The SQL is still written out twice — Room needs a literal
     * `@Query` — but the *values* are computed once, so only the text can
     * drift, and a test watches for that.
     */
    private suspend fun resolvePredicate(filters: LedgerFilters): Predicate {
        val categoryIds = resolveCategoryIds(filters)
        return Predicate(
            fromDay = filters.from?.toEpochDay() ?: EPOCH_DAY_MIN,
            toDay = filters.to?.toEpochDay() ?: EPOCH_DAY_MAX,
            anyCategory = if (categoryIds == null) 1 else 0,
            // Never empty: SQLite tolerates `IN ()` but most engines do not,
            // and a sentinel that matches nothing is clearer than relying on it.
            categoryIds = categoryIds ?: NO_CATEGORY_SENTINEL,
            anyMethod = if (filters.method == null) 1 else 0,
            method = filters.method?.code ?: -1,
            noQuery = if (filters.hasQuery) 0 else 1,
            query = filters.query.trim().escapeForLike(),
            hasAmount = if (filters.exactAmount != null) 1 else 0,
            exactAmount = filters.exactAmount?.paisa ?: Long.MIN_VALUE,
        )
    }

    /** [LedgerFilters] flattened into the arguments the DAO binds. */
    private data class Predicate(
        val fromDay: Long,
        val toDay: Long,
        val anyCategory: Int,
        val categoryIds: List<Long>,
        val anyMethod: Int,
        val method: Int,
        val noQuery: Int,
        val query: String,
        val hasAmount: Int,
        val exactAmount: Long,
    )

    /** Null means "no category filter"; a list means "these leaves only". */
    private suspend fun resolveCategoryIds(filters: LedgerFilters): List<Long>? = when {
        filters.leafId != null -> listOf(filters.leafId)
        filters.rootId != null -> categoryDao.children(filters.rootId).map { it.id }
            .ifEmpty { NO_CATEGORY_SENTINEL }
        else -> null
    }

    /** Ticks whenever the ledger changes, so screens re-read without an event bus. */
    fun observeRevision(): Flow<Int> = expenseDao.observePostedCount()

    // ------------------------------------------------------------- internals

    /**
     * Makes the user's search text a literal for SQL's `LIKE`.
     *
     * `%` and `_` are wildcards, and the query went straight into the pattern —
     * so a note search for `50%` matched every note containing `50` followed by
     * anything, and `_` matched any single character. Nobody would report that
     * as a bug; they would decide the search is unreliable and stop using it.
     *
     * The backslash is escaped **first**, or escaping the wildcards would then
     * escape the escapes. `ESCAPE '\'` on the query side is the other half.
     */
    private fun String.escapeForLike(): String = this
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private suspend fun validate(amount: Money, categoryId: Long, spentOn: LocalDate): EntryError? {
        if (amount.isZero) return EntryError.ZERO_AMOUNT
        // [EntryError.FUTURE_DATE] calls this a data-integrity rule — "a future
        // one would post straight into the period rollup and inflate spending
        // that has not happened" — and it was enforced in
        // [com.app.finance.ui.feature.entry.QuickAddViewModel] alone. A rule
        // whose only enforcement is in a ViewModel is a rule the next caller
        // does not have; `saveEntry` on the Income side had its own copy, and
        // the importer had none.
        //
        // Recurring generation does not come through here — it writes through
        // the DAO — so FR-REC-04's catch-up is unaffected, and a future
        // occurrence is `status = 1` anyway, which every rollup excludes.
        if (spentOn.isAfter(LocalDate.now(clock))) return EntryError.FUTURE_DATE
        val category = categoryDao.byId(categoryId) ?: return EntryError.CATEGORY_NOT_FOUND
        if (category.isArchived) return EntryError.CATEGORY_ARCHIVED
        val parentId = category.parentId
        if (parentId == null || categoryDao.hasChildren(categoryId)) {
            return EntryError.NOT_A_LEAF_CATEGORY
        }
        // A live leaf under an archived root. `CategoryRepository.archive`
        // archives a root's active children in the same transaction, so nothing
        // in the app can *produce* this — an imported tree can, because a
        // restore reproduces whatever the file holds. It costs one primary-key
        // lookup and makes the check total rather than true-by-convention.
        if (categoryDao.byId(parentId)?.isArchived == true) return EntryError.CATEGORY_ARCHIVED
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

    /** See [toWriteError] — only the constraint half is this repository's. */
    private fun Throwable.toEntryError(): EntryError = toWriteError("save an expense") {
        when {
            it.message?.contains("leaf categories") == true -> EntryError.NOT_A_LEAF_CATEGORY
            it.message?.contains("amount_minor") == true -> EntryError.ZERO_AMOUNT
            else -> null
        }
    }

    private companion object {
        const val PAGE_SIZE = 50

        /**
         * Wide enough to mean "no date filter" without a nullable column
         * comparison: roughly 1970 ± 2700 years in epoch days.
         */
        const val EPOCH_DAY_MIN = -1_000_000L
        const val EPOCH_DAY_MAX = 1_000_000L

        /** No category has a negative id, so this matches nothing. */
        val NO_CATEGORY_SENTINEL = listOf(-1L)
    }
}
