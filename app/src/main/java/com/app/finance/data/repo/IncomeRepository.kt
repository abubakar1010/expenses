package com.app.finance.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.app.finance.core.money.Money
import com.app.finance.core.text.NameKey
import com.app.finance.core.time.Period
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.dao.IncomeCellRow
import com.app.finance.data.db.dao.IncomeEntryWithSource
import com.app.finance.data.db.dao.SourceWithCount
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.domain.model.EntryError
import com.app.finance.domain.model.IncomeKind
import com.app.finance.domain.model.IncomeWindow
import com.app.finance.domain.model.SaveOutcome
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Income sources and entries — FR-IS-01 … FR-IS-06, FR-IE-01 … FR-IE-08.
 *
 * The same shape as [ExpenseRepository], and for the same reasons: `period_ym`
 * is derived here from `earned_on` and never by SQL (03 §4.3), the rollup is
 * maintained by trigger inside the same transaction so aggregates cannot drift,
 * and `SQLiteConstraintException` becomes a typed domain error rather than
 * reaching the user (04 §8).
 *
 * Two rules are enforced below this class as well, deliberately:
 *
 * - source-name uniqueness is `ux_income_source_key` over `NameKey.of(name)`,
 *   which is what makes FR-IS-02 structurally impossible to violate
 * - `ON DELETE RESTRICT` on `income_entry.source_id` is what makes FR-IS-05
 *   true even if the check here were forgotten
 *
 * What lives here is the part SQL cannot express: resolving a typed name to a
 * source *or creating it*, and doing that in the same transaction as the entry
 * it belongs to.
 */
class IncomeRepository(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    private val dao = db.incomeDao()
    private val rollupDao = db.rollupDao()
    private val expenseDao = db.expenseDao()

    // --- reads ---------------------------------------------------------------

    /**
     * The screen's one aggregate read, dispatched on the window's shape.
     *
     * 03 §5.3 is the reason there are two: whole months come from
     * `rollup_income_month`, and a range that does not align to month
     * boundaries cannot, so it falls back to the ledger. Everything the screen
     * renders — total, breakdown, trend, stable subtotal — is folded from these
     * rows by [com.app.finance.domain.usecase.IncomeBreakdown], so no two
     * figures on the screen can disagree.
     */
    fun observeCells(window: IncomeWindow): Flow<List<IncomeCellRow>> = when (window) {
        is IncomeWindow.Periods -> dao.observeCellsInPeriods(window.start.ym, window.end.ym)
        is IncomeWindow.Days -> dao.observeCellsInDays(window.from, window.to)
    }

    /**
     * The calendar year containing [period] — 05 §9's zero-income month line.
     *
     * > "Nothing recorded in August. Your year is at ৳5,84,000"
     *
     * The guide singles that sentence out: it "refuses to render an empty month
     * as a failure, and immediately reframes to the unit that is meaningful for
     * this user". It is the same accommodation as the year-first default, and
     * it needs a figure the month's own window cannot supply.
     */
    fun observeYearTotal(period: Period): Flow<Long> =
        rollupDao.observeIncomeTotalInPeriods(
            Period.of(period.year, 1).ym,
            Period.of(period.year, 12).ym,
        )

    /** Spending over the same window — the coverage denominator (FR-AN-06). */
    fun observeExpenseTotal(window: IncomeWindow): Flow<Long> = when (window) {
        is IncomeWindow.Periods ->
            rollupDao.observeExpenseTotalInPeriods(window.start.ym, window.end.ym)
        is IncomeWindow.Days ->
            expenseDao.observeTotalInRange(window.from, window.to)
    }

    /** FR-IE-05 — a source subset combined with the window's dates. */
    fun observeEntries(window: IncomeWindow, sourceIds: Set<Long>): Flow<List<IncomeEntryWithSource>> {
        val days = window.dayRange()
        return dao.observeEntries(
            anySource = if (sourceIds.isEmpty()) 1 else 0,
            // Never empty: SQLite tolerates `IN ()` but most engines do not, and
            // a sentinel that matches nothing is clearer than relying on it.
            sourceIds = sourceIds.toList().ifEmpty { NO_SOURCE_SENTINEL },
            fromDay = days.first,
            toDay = days.second,
        )
    }

    fun observeActiveSources(): Flow<List<IncomeSourceEntity>> = dao.observeActiveSources()

    fun observeSourcesWithCounts(): Flow<List<SourceWithCount>> = dao.observeSourcesWithCounts()

    suspend fun entryById(id: Long): IncomeEntryEntity? = dao.entryById(id)

    suspend fun sourceById(id: Long): IncomeSourceEntity? = dao.sourceById(id)

    // --- entries -------------------------------------------------------------

    /**
     * FR-IE-01 and FR-IS-03 in one call.
     *
     * [sourceName] is whatever the user typed. If it normalises to a source
     * that already exists the entry attaches to it (FR-IS-02: "salary",
     * " Salary" and "SALARY" all resolve to the same row); if it does not, the
     * source is created here — "without a separate navigation step", which is
     * FR-IS-03's whole point.
     *
     * Both writes are one transaction. A rejected entry must not leave a source
     * behind that the user never asked for and would then have to go and
     * delete.
     */
    suspend fun saveEntry(
        amount: Money,
        sourceName: String,
        earnedOn: LocalDate = LocalDate.now(clock),
        note: String? = null,
        kind: IncomeKind = IncomeKind.VARIABLE,
    ): SaveOutcome {
        // FR-IE-03 — "Income amounts MUST be greater than zero", and the
        // acceptance criterion names "0 or negative input". Income has no
        // refund case, which is why this is not the expense rule.
        if (amount.paisa <= 0L) return SaveOutcome.Rejected(EntryError.NON_POSITIVE_INCOME)
        if (NameKey.isBlank(sourceName)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)

        val now = clock.millis()
        return runCatching {
            db.withTransaction {
                val sourceId = resolveOrCreateSource(sourceName, kind, now)
                dao.insertEntry(newEntry(sourceId, amount, earnedOn, note, now))
            }
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toIncomeError()) },
        )
    }

    /** The same, when the source is already known — the edit and picker paths. */
    suspend fun saveEntryToSource(
        amount: Money,
        sourceId: Long,
        earnedOn: LocalDate = LocalDate.now(clock),
        note: String? = null,
    ): SaveOutcome {
        if (amount.paisa <= 0L) return SaveOutcome.Rejected(EntryError.NON_POSITIVE_INCOME)
        dao.sourceById(sourceId) ?: return SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND)

        val now = clock.millis()
        return runCatching { dao.insertEntry(newEntry(sourceId, amount, earnedOn, note, now)) }
            .fold(
                onSuccess = { SaveOutcome.Saved(it) },
                onFailure = { SaveOutcome.Rejected(it.toIncomeError()) },
            )
    }

    /**
     * FR-IE-08 — editing recalculates every dependent aggregate.
     *
     * Nothing here does that recalculation: `trg_rollup_inc_upd` decrements the
     * old (period, source) bucket and increments the new one, so moving a
     * December entry into January is correct by construction and both years
     * stay right. The same property the expense path relies on.
     */
    suspend fun updateEntry(
        id: Long,
        amount: Money,
        sourceName: String,
        earnedOn: LocalDate,
        note: String?,
    ): SaveOutcome {
        if (amount.paisa <= 0L) return SaveOutcome.Rejected(EntryError.NON_POSITIVE_INCOME)
        if (NameKey.isBlank(sourceName)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val existing = dao.entryById(id)
            ?: return SaveOutcome.Rejected(EntryError.CONSTRAINT_VIOLATION)

        val now = clock.millis()
        return runCatching {
            db.withTransaction {
                val sourceId = resolveOrCreateSource(sourceName, IncomeKind.VARIABLE, now)
                dao.updateEntry(
                    existing.copy(
                        sourceId = sourceId,
                        amountMinor = amount.paisa,
                        earnedOn = earnedOn.toEpochDay(),
                        periodYm = Period.from(earnedOn).ym,
                        note = note?.trim()?.ifBlank { null },
                        updatedAt = now,
                    ),
                )
                id
            }
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toIncomeError()) },
        )
    }

    /** Deletes and returns the row, so the caller can offer Undo (NFR-USE-03). */
    suspend fun deleteEntry(id: Long): IncomeEntryEntity? {
        val row = dao.entryById(id) ?: return null
        dao.deleteEntry(row)
        return row
    }

    /** Re-inserts a deleted row verbatim, UUID included, for Undo. */
    suspend fun restoreEntry(row: IncomeEntryEntity): Long = dao.insertEntry(row.copy(id = 0))

    // --- sources -------------------------------------------------------------

    /** FR-IS-01 — a source with a name and a kind. */
    suspend fun createSource(name: String, kind: IncomeKind): SaveOutcome {
        if (NameKey.isBlank(name)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val now = clock.millis()
        return runCatching { dao.insertSource(newSource(name, kind, now)) }
            .fold(
                onSuccess = { SaveOutcome.Saved(it) },
                onFailure = { SaveOutcome.Rejected(it.toIncomeError()) },
            )
    }

    suspend fun renameSource(id: Long, name: String): SaveOutcome {
        if (NameKey.isBlank(name)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val existing = dao.sourceById(id) ?: return SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND)
        return runCatching {
            dao.updateSource(
                existing.copy(
                    name = name.trim(),
                    nameKey = NameKey.of(name),
                    updatedAt = clock.millis(),
                ),
            )
            id
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toIncomeError()) },
        )
    }

    suspend fun setSourceKind(id: Long, kind: IncomeKind): SaveOutcome {
        val existing = dao.sourceById(id) ?: return SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND)
        dao.updateSource(existing.copy(kind = kind.code, updatedAt = clock.millis()))
        return SaveOutcome.Saved(id)
    }

    /**
     * Name and kind together, in one transaction — what the manager's editor
     * actually submits.
     *
     * Two calls would let the rename land and the kind fail behind it, leaving
     * a source renamed but still classified wrongly, which is the one field the
     * coverage figure depends on.
     */
    suspend fun updateSource(id: Long, name: String, kind: IncomeKind): SaveOutcome {
        if (NameKey.isBlank(name)) return SaveOutcome.Rejected(EntryError.BLANK_NAME)
        val existing = dao.sourceById(id) ?: return SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND)
        return runCatching {
            db.withTransaction {
                dao.updateSource(
                    existing.copy(
                        name = name.trim(),
                        nameKey = NameKey.of(name),
                        kind = kind.code,
                        updatedAt = clock.millis(),
                    ),
                )
            }
            id
        }.fold(
            onSuccess = { SaveOutcome.Saved(it) },
            onFailure = { SaveOutcome.Rejected(it.toIncomeError()) },
        )
    }

    /**
     * FR-IS-04 — "Archived sources MUST be excluded from entry pickers and MUST
     * remain visible in historical reports."
     *
     * Both halves are elsewhere: `observeActiveSources` is what the picker binds
     * to, and the breakdown query joins `income_source` without filtering on
     * this flag precisely so a year the user already lived through still shows
     * where its money came from.
     */
    suspend fun setSourceArchived(id: Long, archived: Boolean): SaveOutcome {
        dao.sourceById(id) ?: return SaveOutcome.Rejected(EntryError.SOURCE_NOT_FOUND)
        dao.setSourceArchived(id, archived, clock.millis())
        return SaveOutcome.Saved(id)
    }

    /**
     * FR-IS-06 — "The system MUST permit deletion of a source with zero
     * entries." **The only delete in the application.**
     *
     * Every other removal in Khata is an archive, because deleting a category
     * silently rewrites history. A source with no entries has no history to
     * rewrite, and the SRS says so explicitly, so this one exists — guarded
     * here by the count and below by `ON DELETE RESTRICT`, which is what makes
     * FR-IS-05 true regardless of what this method remembers to check.
     *
     * Returns the deleted row so the snackbar can undo it (NFR-USE-03): with no
     * entries pointing at it, restoring is a single re-insert.
     */
    suspend fun deleteSource(id: Long): DeleteSourceOutcome {
        val source = dao.sourceById(id)
            ?: return DeleteSourceOutcome.Rejected(EntryError.SOURCE_NOT_FOUND)
        if (dao.countEntriesForSource(id) > 0) {
            return DeleteSourceOutcome.Rejected(EntryError.SOURCE_HAS_ENTRIES)
        }
        // The second `ON DELETE RESTRICT`. Without this the delete reaches
        // SQLite, the foreign key refuses it, and the user reads "that
        // source no longer exists" about a row in front of them.
        if (dao.countRulesForSource(id) > 0) {
            return DeleteSourceOutcome.Rejected(EntryError.SOURCE_HAS_RULES)
        }
        return runCatching { dao.deleteSource(id) }.fold(
            onSuccess = { DeleteSourceOutcome.Deleted(source) },
            onFailure = { DeleteSourceOutcome.Rejected(it.toIncomeError()) },
        )
    }

    /** Undo for [deleteSource] — the row verbatim, uuid included. */
    suspend fun restoreSource(source: IncomeSourceEntity): Long =
        dao.insertSource(source.copy(id = 0))

    /**
     * FR-IS-07 — "reordering sources for display".
     *
     * The same shape as [CategoryRepository.move] and for the same reasons:
     * the whole run is normalised rather than two rows swapped, because
     * nothing has written `sort_order` since the seed assigned it; and
     * archived sources sort after the active ones, since FR-IS-04 lists them
     * apart and their order among themselves is not something anyone is
     * arranging.
     *
     * @return true if the source moved; false at the end of its range.
     */
    suspend fun moveSource(id: Long, up: Boolean): Boolean {
        val sources = dao.allSources()
            .sortedWith(compareBy({ it.isArchived }, { it.sortOrder }, { it.name }))

        val movable = sources.filterNot { it.isArchived }.toMutableList()
        val from = movable.indexOfFirst { it.id == id }
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in movable.indices) return false

        movable.add(to, movable.removeAt(from))

        val now = clock.millis()
        val ordered = movable + sources.filter { it.isArchived }
        db.withTransaction {
            ordered.forEachIndexed { index, source ->
                if (source.sortOrder != index) dao.setSourceSortOrder(source.id, index, now)
            }
        }
        return true
    }

    suspend fun countEntriesForSource(id: Long): Int = dao.countEntriesForSource(id)

    suspend fun countRulesForSource(id: Long): Int = dao.countRulesForSource(id)

    // ------------------------------------------------------------- internals

    /**
     * FR-IS-03 + FR-IS-02. Must be called inside a transaction.
     *
     * The lookup is on `NameKey.of`, never on the raw text: `LOWER()` in SQLite
     * is ASCII-only and would let two visually identical Bengali names both
     * through the unique index. This is the only route by which typing a name
     * creates a source.
     */
    private suspend fun resolveOrCreateSource(name: String, kind: IncomeKind, now: Long): Long {
        val key = NameKey.of(name)
        dao.sourceByKey(key)?.let { return it.id }
        return dao.insertSource(newSource(name, kind, now))
    }

    private fun newSource(name: String, kind: IncomeKind, now: Long) = IncomeSourceEntity(
        uuid = UUID.randomUUID().toString(),
        name = name.trim(),
        nameKey = NameKey.of(name),
        kind = kind.code,
        createdAt = now,
        updatedAt = now,
    )

    private fun newEntry(
        sourceId: Long,
        amount: Money,
        earnedOn: LocalDate,
        note: String?,
        now: Long,
    ) = IncomeEntryEntity(
        uuid = UUID.randomUUID().toString(),
        sourceId = sourceId,
        amountMinor = amount.paisa,
        earnedOn = earnedOn.toEpochDay(),
        // Derived here, never by the caller and never by SQL — the one
        // denormalised column that turns every monthly filter into an indexed
        // equality test (03 §1).
        periodYm = Period.from(earnedOn).ym,
        note = note?.trim()?.ifBlank { null },
        status = 0,
        createdAt = now,
        updatedAt = now,
    )

    private fun Throwable.toIncomeError(): EntryError = when {
        this !is SQLiteConstraintException -> EntryError.CONSTRAINT_VIOLATION
        message?.contains("ux_income_source_key") == true -> EntryError.DUPLICATE_NAME
        message?.contains("UNIQUE", ignoreCase = true) == true -> EntryError.DUPLICATE_NAME
        message?.contains("amount_minor") == true -> EntryError.NON_POSITIVE_INCOME
        // Only reachable from an entry insert against a source that has since
        // gone. `deleteSource` never gets here: it checks both counts first —
        // entries and rules — and returns its own error for each.
        message?.contains("FOREIGN KEY", ignoreCase = true) == true -> EntryError.SOURCE_NOT_FOUND
        else -> EntryError.CONSTRAINT_VIOLATION
    }

    private companion object {
        /** No source has a negative id, so this matches nothing. */
        val NO_SOURCE_SENTINEL = listOf(-1L)
    }
}

/** [IncomeRepository.deleteSource]'s result — the row is carried back for Undo. */
sealed interface DeleteSourceOutcome {
    data class Deleted(val source: IncomeSourceEntity) : DeleteSourceOutcome

    @JvmInline
    value class Rejected(val error: EntryError) : DeleteSourceOutcome
}

/**
 * The window's bounds as epoch days, whichever shape it is.
 *
 * The entry *list* is always ledger-backed — it shows rows, not aggregates — so
 * it needs days even when the aggregates are being served by whole periods.
 */
private fun IncomeWindow.dayRange(): Pair<Long, Long> = when (this) {
    is IncomeWindow.Periods -> start.firstDay().toEpochDay() to end.lastDay().toEpochDay()
    is IncomeWindow.Days -> from to to
}
