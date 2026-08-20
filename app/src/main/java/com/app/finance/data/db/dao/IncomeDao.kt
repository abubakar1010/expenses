package com.app.finance.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * One (period, source) bucket — the single grain the income screen reads.
 *
 * Every figure it shows is a fold over these: the hero total, the twelve trend
 * bars, the per-source breakdown and the stable subtotal. One query for all
 * four means they cannot disagree with each other, and it means the M3 exit
 * criterion has exactly one read to reconcile against the ledger.
 */
data class IncomeCellRow(
    val periodYm: Int,
    val sourceId: Long,
    val sourceName: String,
    val kind: Int,
    val totalMinor: Long,
)

/** An entry with the source name already joined on — the list rows. */
data class IncomeEntryWithSource(
    @Embedded val entry: IncomeEntryEntity,
    val sourceName: String,
    val sourceKind: Int,
)

/** A source plus everything that would block deleting it — FR-IS-05 / FR-IS-06. */
data class SourceWithCount(
    @Embedded val source: IncomeSourceEntity,
    val entryCount: Int,
    /**
     * Recurring rules pointing at it.
     *
     * `recurring_rule.source_id` is `ON DELETE RESTRICT` too, so a rule
     * blocks the delete exactly as an entry does — and a control offered
     * against a constraint that will refuse it is the thing FR-IS-05's
     * criterion exists to prevent.
     */
    val ruleCount: Int,
)

@Dao
interface IncomeDao {

    // --- sources ------------------------------------------------------------

    @Query("SELECT * FROM income_source WHERE is_archived = 0 ORDER BY sort_order, name")
    fun observeActiveSources(): Flow<List<IncomeSourceEntity>>

    /**
     * A one-shot read of every source, for [moveSource]'s normalisation pass.
     * `observeAllSources` is the same query as a `Flow`; reordering wants the
     * list once, not a subscription.
     */
    @Query("SELECT * FROM income_source ORDER BY sort_order, name")
    suspend fun allSources(): List<IncomeSourceEntity>

    /** FR-IS-07, the mirror of [CategoryDao.setSortOrder]. */
    @Query("UPDATE income_source SET sort_order = :order, updated_at = :now WHERE id = :id")
    suspend fun setSourceSortOrder(id: Long, order: Int, now: Long)

    @Query("SELECT * FROM income_source ORDER BY sort_order, name")
    fun observeAllSources(): Flow<List<IncomeSourceEntity>>

    /**
     * Lookup by normalised key, which is what makes "type a name that does not
     * exist yet and it is created inline" safe: the same typed name always
     * resolves to the same source rather than creating a near-duplicate.
     */
    @Query("SELECT * FROM income_source WHERE name_key = :nameKey")
    suspend fun sourceByKey(nameKey: String): IncomeSourceEntity?

    @Query("SELECT * FROM income_source WHERE id = :id")
    suspend fun sourceById(id: Long): IncomeSourceEntity?

    @Insert
    suspend fun insertSource(source: IncomeSourceEntity): Long

    @Update
    suspend fun updateSource(source: IncomeSourceEntity)

    @Query("UPDATE income_source SET is_archived = :archived, updated_at = :now WHERE id = :id")
    suspend fun setSourceArchived(id: Long, archived: Boolean, now: Long)

    /**
     * What the source manager binds to. The count is what decides whether the
     * delete action is live or disabled with its reason (FR-IS-05 / FR-IS-06),
     * so it is read with the source rather than one query per row.
     */
    @Query(
        """
        SELECT s.*,
               (SELECT COUNT(*) FROM income_entry e WHERE e.source_id = s.id) AS entryCount,
               (SELECT COUNT(*) FROM recurring_rule r WHERE r.source_id = s.id) AS ruleCount
          FROM income_source s
         ORDER BY s.is_archived, s.sort_order, s.name
        """,
    )
    fun observeSourcesWithCounts(): Flow<List<SourceWithCount>>

    @Query("SELECT COUNT(*) FROM income_entry WHERE source_id = :sourceId")
    suspend fun countEntriesForSource(sourceId: Long): Int

    /** The second thing `ON DELETE RESTRICT` will refuse a delete over. */
    @Query("SELECT COUNT(*) FROM recurring_rule WHERE source_id = :sourceId")
    suspend fun countRulesForSource(sourceId: Long): Int

    /**
     * FR-IS-06 — "The system MUST permit deletion of a source with zero
     * entries." The only delete in the application, and it is guarded above by
     * [countEntriesForSource] and below by `ON DELETE RESTRICT`.
     */
    @Query("DELETE FROM income_source WHERE id = :id")
    suspend fun deleteSource(id: Long)

    // --- entries ------------------------------------------------------------

    @Insert
    suspend fun insertEntry(entry: IncomeEntryEntity): Long

    @Update
    suspend fun updateEntry(entry: IncomeEntryEntity)

    @Delete
    suspend fun deleteEntry(entry: IncomeEntryEntity)

    @Query("SELECT * FROM income_entry WHERE id = :id")
    suspend fun entryById(id: Long): IncomeEntryEntity?

    @Query(
        """
        SELECT * FROM income_entry
         WHERE status = 0 AND period_ym = :period
         ORDER BY earned_on DESC, id DESC
        """,
    )
    fun observeEntriesInPeriod(period: Int): Flow<List<IncomeEntryEntity>>

    /**
     * The income screen's only aggregate read, period-aligned — 03 §5.2
     * generalised from one period to a range.
     *
     * Reads `rollup_income_month` and `income_source`; it never touches
     * `income_entry`, so its cost is bounded by (months × sources) rather than
     * by how many entries exist. A year with five sources is sixty rows.
     *
     * The join is **inner and unfiltered by `is_archived`** on purpose:
     * FR-IS-04 requires an archived source to stay "visible in historical
     * reports", and a year the user has already lived through is exactly that.
     *
     * The income screen defaults to a *year*, not a month (05 §5.7): a farming
     * month showing ৳0 is alarming and meaningless in isolation, so the year is
     * the honest unit for this user's income even though the month is the
     * honest unit for their spending.
     *
     * `entry_count > 0` is not an optimisation. `trg_rollup_inc_del` decrements
     * a bucket rather than removing it, so deleting a source's last entry in a
     * month leaves `(period, source, 0, 0)` behind forever — and without this
     * clause that residue renders as a permanent `৳0  0%` breakdown row, keeps
     * the screen out of its empty state, and makes the rollup path disagree
     * with the ledger path in [observeCellsInDays], which groups and so has no
     * such row. The bucket is left in place deliberately; see §15 of the log.
     */
    @Query(
        """
        SELECT r.period_ym   AS periodYm,
               s.id          AS sourceId,
               s.name        AS sourceName,
               s.kind        AS kind,
               r.total_minor AS totalMinor
          FROM rollup_income_month r
          JOIN income_source s ON s.id = r.source_id
         WHERE r.period_ym BETWEEN :startPeriod AND :endPeriod
           AND r.entry_count > 0
        """,
    )
    fun observeCellsInPeriods(startPeriod: Int, endPeriod: Int): Flow<List<IncomeCellRow>>

    /**
     * The same shape for an arbitrary date range — FR-IE-04's third total.
     *
     * 03 §5.3: "Ranges that do not align to month boundaries cannot use rollups
     * and fall back to the ledger." This is that fallback, served by
     * `ix_income_entry_date`, and it is invoked on explicit user action rather
     * than on every render — which is what makes the scan acceptable here and
     * nowhere else.
     */
    @Query(
        """
        SELECT e.period_ym        AS periodYm,
               s.id               AS sourceId,
               s.name             AS sourceName,
               s.kind             AS kind,
               SUM(e.amount_minor) AS totalMinor
          FROM income_entry e
          JOIN income_source s ON s.id = e.source_id
         WHERE e.status = 0 AND e.earned_on BETWEEN :fromDay AND :toDay
         GROUP BY e.period_ym, s.id
        """,
    )
    fun observeCellsInDays(fromDay: Long, toDay: Long): Flow<List<IncomeCellRow>>

    /**
     * The entry list — FR-IE-05's "any subset of sources combined with a date
     * range", in one statement.
     *
     * Each filter is `(<disabled flag> OR <predicate>)`, the same shape
     * `ExpenseDao.page` uses, so this stays a compile-time-verified `@Query`
     * rather than a `@RawQuery` assembled from strings. [sourceIds] is never
     * empty — the caller passes a non-matching sentinel instead.
     *
     * **Not paged, deliberately.** FR-EXP-10's paging requirement is expense-
     * only, and 03 §9 sizes `income_entry` at ~400 rows for five years. A
     * keyset pager here would be machinery with nothing to do.
     */
    @Query(
        """
        SELECT e.*, s.name AS sourceName, s.kind AS sourceKind
          FROM income_entry e
          JOIN income_source s ON s.id = e.source_id
         WHERE e.status = 0
           AND e.earned_on BETWEEN :fromDay AND :toDay
           AND (:anySource = 1 OR e.source_id IN (:sourceIds))
         ORDER BY e.earned_on DESC, e.id DESC
        """,
    )
    fun observeEntries(
        anySource: Int,
        sourceIds: List<Long>,
        fromDay: Long,
        toDay: Long,
    ): Flow<List<IncomeEntryWithSource>>
}
