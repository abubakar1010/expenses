package com.app.finance.data.repo

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.withTransaction
import com.app.finance.core.money.Money
import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.BudgetBarRow
import com.app.finance.data.db.entity.BudgetEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.util.UUID

/**
 * Monthly spending limits — FR-BUD-01 … FR-BUD-08.
 *
 * Three of the rules this enforces are enforced *below* it as well, and
 * deliberately so:
 *
 * - one row per (category, period) is the `ux_budget_cat_period` unique index
 * - leaf-only is the `trg_budget_leaf_only` / `_upd` trigger pair
 * - `ON DELETE RESTRICT` keeps a budgeted category from being deleted
 *
 * What lives here is the part SQL cannot express: turning those failures into
 * typed errors (04 §8), and the two multi-row operations — upsert and
 * copy-from-last-period — that must each be a single transaction.
 *
 * **Root limits are never written.** 03 §4.5: "Root-level budget figures are
 * never stored. They are computed as `SUM(limit_minor)` over children at query
 * time — a handful of rows, negligible cost, and impossible to desynchronise
 * from their parts." That is FR-BUD-03, and the reason there is no `setLimit`
 * overload taking a root.
 */
class BudgetRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val budgetDao = db.budgetDao()
    private val rollupDao = db.rollupDao()
    private val categoryDao = db.categoryDao()

    /**
     * The budget screen's only read — 03 §5.1, the hot query.
     *
     * Reads `category`, `budget` and `rollup_expense_month` and never touches
     * `expense`, so its cost is bounded by the leaf count rather than by how
     * much history exists. That is what holds NFR-PERF-04 at 300 ms as the
     * ledger grows to five years.
     */
    fun observeBars(period: Period): Flow<List<BudgetBarRow>> =
        rollupDao.observeBudgetBars(period.ym)

    suspend fun limitFor(categoryId: Long, period: Period): Money? =
        budgetDao.forCategory(categoryId, period.ym)?.let { Money(it.limitMinor) }

    /**
     * Sets or replaces a leaf's limit for one period (FR-BUD-01, FR-BUD-02).
     *
     * An upsert rather than an insert: `ux_budget_cat_period` makes a second
     * insert for the same pair an error, and FR-BUD-02's acceptance criterion
     * is explicit that "setting a second limit for the same pair updates the
     * existing row rather than inserting".
     */
    suspend fun setLimit(categoryId: Long, period: Period, limit: Money): SaveOutcome {
        // FR-BUD-08 says limits are >= 0, and the column's CHECK agrees. Zero is
        // refused here anyway: the dashboard query reads a missing row as
        // IFNULL(limit_minor, 0), so a stored zero and no budget at all are
        // indistinguishable downstream — and a zero limit would make the
        // percentage a division by zero. Clearing the limit is how a leaf
        // returns to the unbudgeted state.
        if (limit.paisa <= 0L) return SaveOutcome.Rejected(EntryError.ZERO_LIMIT)

        val category = categoryDao.byId(categoryId)
            ?: return SaveOutcome.Rejected(EntryError.CATEGORY_NOT_FOUND)
        if (category.parentId == null || categoryDao.hasChildren(categoryId)) {
            return SaveOutcome.Rejected(EntryError.BUDGET_ON_NON_LEAF)
        }

        val now = clock.millis()
        return runCatchingWrite {
            db.withTransaction {
                val existing = budgetDao.forCategory(categoryId, period.ym)
                if (existing == null) {
                    budgetDao.insert(
                        BudgetEntity(
                            uuid = UUID.randomUUID().toString(),
                            categoryId = categoryId,
                            periodYm = period.ym,
                            limitMinor = limit.paisa,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } else {
                    // Keeps the original uuid and created_at: this is the same
                    // budget revised, not a new one. Export dedup depends on
                    // the uuid surviving an edit (03 §1).
                    budgetDao.update(
                        existing.copy(limitMinor = limit.paisa, updatedAt = now),
                    )
                    existing.id
                }
            }
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toBudgetError()) },
        )
    }

    /**
     * Returns a leaf to the unbudgeted state. The only way to have no limit.
     *
     * Returns what it removed, because NFR-USE-03 makes **every** destructive
     * action undoable for five seconds and this destroys a figure the user
     * typed. Archiving a category — which is less destructive than this — has
     * had an undo since M2.
     */
    suspend fun clearLimit(categoryId: Long, period: Period): Money? {
        val existing = limitFor(categoryId, period)
        budgetDao.clear(categoryId, period.ym)
        return existing
    }

    /**
     * FR-BUD-04 — "a single action copying all of the previous period's budgets
     * into the current period".
     *
     * Copies only leaves that have no limit this period, so the acceptance
     * criterion's "leaves already carrying a limit this period are not
     * overwritten without confirmation" is satisfied by never overwriting at
     * all — which also means no confirmation dialog, consistent with 05 §8's
     * argument against them. The caller reports the count and offers Undo.
     *
     * One transaction: a half-copied month is a worse state than an uncopied
     * one.
     *
     * Archived leaves are skipped. Carrying a limit forward onto a category the
     * user has retired sets a target they cannot spend against, and the budget
     * screen would not show the row (an archived leaf surfaces only when it has
     * spend), so there would be no way left to clear it.
     */
    suspend fun copyFromPreviousPeriod(period: Period): Int {
        val source = budgetDao.forPeriodActive(period.prev().ym)
        if (source.isEmpty()) return 0

        val now = clock.millis()
        // Wrapped, where [setLimit] above it has always been. Nothing here is
        // known to be able to throw — a leaf that gained a child since last
        // month would violate `trg_budget_leaf_only`, and
        // `trg_category_child_of_used_leaf` now makes that structurally
        // impossible — but this writes as many rows as the user has budgets and
        // a full disk part-way through would take the whole screen down rather
        // than report a copy that did not happen.
        return runCatchingWrite {
            copyInto(period, source, now)
        }.getOrElse { error ->
            Log.w("Khata", "could not copy last month's limits", error)
            0
        }
    }

    private suspend fun copyInto(
        period: Period,
        source: List<BudgetEntity>,
        now: Long,
    ): Int =
        db.withTransaction {
            val alreadySet = budgetDao.forPeriod(period.ym).map { it.categoryId }.toSet()
            val toCopy = source.filterNot { it.categoryId in alreadySet }

            toCopy.forEach { previous ->
                budgetDao.insert(
                    BudgetEntity(
                        // A new uuid: this is a distinct budget for a distinct
                        // period, not the same row moved.
                        uuid = UUID.randomUUID().toString(),
                        categoryId = previous.categoryId,
                        periodYm = period.ym,
                        limitMinor = previous.limitMinor,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            toCopy.size
        }

    /** Undoes a copy by removing exactly what it added. */
    suspend fun removeLimits(categoryIds: List<Long>, period: Period) {
        db.withTransaction {
            categoryIds.forEach { budgetDao.clear(it, period.ym) }
        }
    }

    /** Which leaves a copy *would* add — drives the disabled state and Undo. */
    suspend fun copyableFromPreviousPeriod(period: Period): List<Long> {
        val source = budgetDao.forPeriodActive(period.prev().ym)
        if (source.isEmpty()) return emptyList()
        val alreadySet = budgetDao.forPeriod(period.ym).map { it.categoryId }.toSet()
        return source.map { it.categoryId }.filterNot { it in alreadySet }
    }

    /** See [toWriteError] — only the constraint half is this repository's. */
    private fun Throwable.toBudgetError(): EntryError = toWriteError("set a budget") {
        when {
            it.message?.contains("leaf categories") == true -> EntryError.BUDGET_ON_NON_LEAF
            // A duplicate `(category, period)` is the upsert doing its job
            // somewhere it should not have been reached; it has no copy of its
            // own, so it falls through to the generic branch — and now logs.
            else -> null
        }
    }
}
