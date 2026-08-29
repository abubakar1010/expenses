package com.app.finance.data.export

import android.util.Log
import androidx.room.withTransaction
import com.app.finance.data.db.AppDatabase
import com.app.finance.core.time.Period
import com.app.finance.data.db.Schema
import com.app.finance.data.db.dao.AppMetaDao
import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.data.repo.runCatchingWrite
import java.io.InputStream
import java.time.LocalDate

/** FR-DAT-03 — "offering **replace** or **merge**". */
enum class ImportMode { REPLACE, MERGE }

/** FR-DAT-03 — "MUST report counts of inserted, updated, and skipped rows". */
data class ImportCounts(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
) {
    operator fun plus(other: ImportCounts) = ImportCounts(
        inserted + other.inserted,
        updated + other.updated,
        skipped + other.skipped,
    )
}

sealed interface ImportOutcome {

    data class Done(val perEntity: Map<String, ImportCounts>) : ImportOutcome {
        val totals: ImportCounts
            get() = perEntity.values.fold(ImportCounts()) { acc, c -> acc + c }
    }

    /** Everything that can go wrong. In every case the database is untouched. */
    enum class Failure : ImportOutcome {
        /** FR-DAT-05 — a file from a newer schema than this build understands. */
        NEWER_SCHEMA,

        /** Not a DayBook export, or truncated. */
        UNREADABLE,

        /** A referenced category or source is in neither the file nor the database. */
        DANGLING_REFERENCE,

        /** A constraint fired. The transaction rolled back. */
        REJECTED,
    }
}

/**
 * Reads a previously exported file back in — FR-DAT-03, -04, -05.
 *
 * Four properties, in the order they matter.
 *
 * **Validate before touching anything.** FR-DAT-05 refuses a file from a newer
 * schema, and the refusal has to happen before the first `DELETE`, not after.
 *
 * **One transaction around the whole thing.** NFR-REL-04: "a failed import MUST
 * leave the existing database unmodified — the operation is transactional." A
 * half-applied import of five years of financial history is the worst outcome
 * this app has, worse than refusing outright.
 *
 * **Foreign keys resolve through UUIDs, never through the exported integer
 * ids.** This is the part that is easy to get wrong and impossible to notice
 * afterwards. An expense in the file says `category_id = 7`; on the exporting
 * phone that was Grocery, on this one it may be House Rent or nothing at all.
 * Trusting the integer would silently re-file a year of groceries under rent —
 * a corruption with no error, no crash, and nothing for the user to see. So
 * merge writes the two parent tables first, builds `uuid → local id` from the
 * result, and remaps every child through it. DR-06 exists for precisely this:
 * every entity carries a UUID "to support import deduplication across devices".
 *
 * **Rebuild the rollups last, inside the same transaction.** The triggers fire
 * correctly on each inserted row, but a replace's `DELETE` leaves zero-count
 * buckets behind (§15.3) that can point at categories the new file never had.
 * `REBUILD_ROLLUPS` is idempotent, and `assertion19` already proves it
 * reproduces the trigger-maintained state exactly.
 */
class Importer(private val db: AppDatabase) {

    private val dao = db.backupDao()

    /**
     * `period_ym`, derived here rather than trusted.
     *
     * 03 §4.3: the column "is derived from `earned_on` by the application and
     * kept consistent by an assertion in the repository layer plus a
     * debug-build integrity check". Every other write path in the app obeys
     * that; the importer is the one door into the database that does not go
     * through a repository, so it has to do the derivation itself.
     *
     * A hand-edited or third-party file could otherwise file August's rent
     * under June while the row still reads 3 August. Nothing would notice: the
     * rollups would be built from the stated period, the drift check compares
     * the rollups against that same period, and every figure would be
     * internally consistent and wrong.
     */
    private fun periodOf(epochDay: Long): Int = Period.from(LocalDate.ofEpochDay(epochDay)).ym

    private fun ExpenseDto.derived() = copy(periodYm = periodOf(spentOn))

    private fun IncomeEntryDto.derived() = copy(periodYm = periodOf(earnedOn))

    /** Parses and validates without writing a row. */
    fun parse(input: InputStream): Result<DayBookExport> = runCatching {
        val export = input.bufferedReader().use { reader ->
            DayBookExport.CODEC.decodeFromString(DayBookExport.serializer(), reader.readText())
        }
        // FR-DAT-05. Older is fine and forward-compatible; newer is not, because
        // this build cannot know what a column it has never seen means, and
        // guessing with someone's ledger is not a thing to do.
        if (export.schemaVersion > Schema.VERSION) throw NewerSchema()
        export
    }

    suspend fun import(input: InputStream, mode: ImportMode): ImportOutcome {
        val parsed = parse(input).getOrElse { error ->
            return if (error is NewerSchema) ImportOutcome.Failure.NEWER_SCHEMA
            else ImportOutcome.Failure.UNREADABLE
        }
        return import(parsed, mode)
    }

    suspend fun import(export: DayBookExport, mode: ImportMode): ImportOutcome =
        // `runCatchingWrite`, not `runCatching`: this wraps a `withTransaction`,
        // and a cancellation caught here would be reported to the user as
        // `REJECTED` — "that backup was refused" about an import that was
        // simply stopped. Worse, it would be swallowed inside the transaction
        // block, which is how a transaction gets committed by a coroutine that
        // has been told to stop.
        runCatchingWrite {
            db.withTransaction {
                val counts = when (mode) {
                    ImportMode.REPLACE -> replace(export)
                    ImportMode.MERGE -> merge(export)
                }
                Schema.REBUILD_ROLLUPS.forEach { db.openHelper.writableDatabase.execSQL(it) }
                ImportOutcome.Done(counts) as ImportOutcome
            }
        }.getOrElse { error ->
            if (error is DanglingReference) {
                ImportOutcome.Failure.DANGLING_REFERENCE
            } else {
                // The database is untouched either way, but a user whose restore
                // of five years of data just failed deserves better than a
                // sentence, and so does whoever they show it to.
                Log.w(TAG, "import rolled back", error)
                ImportOutcome.Failure.REJECTED
            }
        }

    // --------------------------------------------------------------- replace

    /**
     * Wipe, then insert with the exported ids intact.
     *
     * Keeping the integer ids is what makes export → wipe → import reproduce the
     * database rather than merely an equivalent one, which is the literal
     * reading of FR-DAT-04's "lossless". There is nothing to remap because there
     * is nothing left to collide with.
     *
     * Children before parents on the way down, parents before children on the
     * way up: `PRAGMA foreign_keys` is on and every reference is
     * `ON DELETE RESTRICT`.
     */
    private suspend fun replace(export: DayBookExport): Map<String, ImportCounts> {
        val sql = db.openHelper.writableDatabase
        WIPE_ORDER.forEach(sql::execSQL)

        // Roots before children, for the same reason merge needs two passes:
        // `category.parent_id` references `category.id`.
        val (roots, children) = export.categories.partition { it.parentId == null }
        dao.insertCategories(roots.map { it.toEntity() })
        dao.insertCategories(children.map { it.toEntity() })

        dao.insertSources(export.sources.map { it.toEntity() })
        dao.insertBudgets(export.budgets.map { it.toEntity() })
        dao.insertExpenses(export.expenses.map { it.derived().toEntity() })
        dao.insertIncomeEntries(export.incomeEntries.map { it.derived().toEntity() })
        dao.insertRules(export.rules.map { it.toEntity() })
        writeMeta(export)

        return linkedMapOf(
            CATEGORIES to ImportCounts(inserted = export.categories.size),
            SOURCES to ImportCounts(inserted = export.sources.size),
            BUDGETS to ImportCounts(inserted = export.budgets.size),
            EXPENSES to ImportCounts(inserted = export.expenses.size),
            INCOME to ImportCounts(inserted = export.incomeEntries.size),
            RULES to ImportCounts(inserted = export.rules.size),
            META to ImportCounts(inserted = export.meta.size),
        )
    }

    // ----------------------------------------------------------------- merge

    private suspend fun merge(export: DayBookExport): Map<String, ImportCounts> {
        val counts = LinkedHashMap<String, ImportCounts>()

        // --- categories, in two passes because the table references itself ---
        //
        // Depth is capped at two by trigger, so two passes are enough: roots
        // resolve against nothing, children resolve against the roots just
        // written. A third level would need a topological sort; the schema
        // makes one impossible.
        var localCategories = dao.allCategories().associate { it.uuid to it.toDto() }
        val (roots, children) = export.categories.partition { it.parentId == null }

        val rootCounts = writeCategories(roots, localCategories) { it }
        localCategories = dao.allCategories().associate { it.uuid to it.toDto() }

        // The file's id -> this phone's id, resolved through whichever key
        // matched. A category the file calls 7 and this phone calls 3 must end
        // up as 3 for every expense that referenced it — that remapping is the
        // entire reason the merge is written this way.
        var categoryIds = resolve(export.categories, dao.allCategories().map { it.toDto() })
        fun categoryId(fileId: Long): Long = categoryIds[fileId] ?: throw DanglingReference()

        val childCounts = writeCategories(children, localCategories) { dto ->
            dto.copy(parentId = dto.parentId?.let(::categoryId))
        }
        counts[CATEGORIES] = rootCounts + childCounts

        // Rebuilt, because the child pass added rows the first map did not have
        // — and because a child's natural key contains its *remapped* parent id,
        // so it can only be resolved once the parents are in.
        categoryIds = resolve(
            export.categories.map { dto ->
                dto.copy(parentId = dto.parentId?.let { categoryIds[it] })
            },
            dao.allCategories().map { it.toDto() },
        )
        fun category(fileId: Long): Long = categoryIds[fileId] ?: throw DanglingReference()

        // --- sources -----------------------------------------------------
        val localSources = dao.allSources().associate { it.uuid to it.toDto() }
        val sourcePlan = plan(export.sources, localSources) { d, id -> d.copy(id = id) }
        dao.insertSources(sourcePlan.inserts.map { it.toEntity() })
        dao.updateSources(sourcePlan.updates.map { it.toEntity() })
        counts[SOURCES] = sourcePlan.counts

        val sourceIds = resolve(export.sources, dao.allSources().map { it.toDto() })
        fun source(fileId: Long): Long = sourceIds[fileId] ?: throw DanglingReference()

        // --- children, every foreign key resolved through the maps above ---
        val budgets = export.budgets.map { it.copy(categoryId = category(it.categoryId)) }
        val budgetPlan = plan(budgets, dao.allBudgets().associate { it.uuid to it.toDto() }) { d, id -> d.copy(id = id) }
        dao.insertBudgets(budgetPlan.inserts.map { it.toEntity() })
        dao.updateBudgets(budgetPlan.updates.map { it.toEntity() })
        counts[BUDGETS] = budgetPlan.counts

        val expenses = export.expenses
            .map { it.derived().copy(categoryId = category(it.categoryId)) }
        val expensePlan = plan(expenses, dao.allExpenses().associate { it.uuid to it.toDto() }) { d, id -> d.copy(id = id) }
        dao.insertExpenses(expensePlan.inserts.map { it.toEntity() })
        dao.updateExpenses(expensePlan.updates.map { it.toEntity() })
        counts[EXPENSES] = expensePlan.counts

        val income = export.incomeEntries
            .map { it.derived().copy(sourceId = source(it.sourceId)) }
        val incomePlan = plan(income, dao.allIncomeEntries().associate { it.uuid to it.toDto() }) { d, id -> d.copy(id = id) }
        dao.insertIncomeEntries(incomePlan.inserts.map { it.toEntity() })
        dao.updateIncomeEntries(incomePlan.updates.map { it.toEntity() })
        counts[INCOME] = incomePlan.counts

        val rules = export.rules.map {
            it.copy(
                categoryId = it.categoryId?.let(::category),
                sourceId = it.sourceId?.let(::source),
            )
        }
        val rulePlan = plan(rules, dao.allRules().associate { it.uuid to it.toDto() }) { d, id -> d.copy(id = id) }
        dao.insertRules(rulePlan.inserts.map { it.toEntity() })
        dao.updateRules(rulePlan.updates.map { it.toEntity() })
        counts[RULES] = rulePlan.counts

        // `app_meta` is preferences, not history: the incoming value wins. The
        // user is restoring a backup, and their last-used payment method is part
        // of what they are restoring.
        writeMeta(export)
        counts[META] = ImportCounts(updated = export.meta.size)

        return counts
    }

    /**
     * Writes the file's preferences, then re-stamps the schema version.
     *
     * `app_meta` carries `schema_version`, and the file's copy is the version it
     * was *written* at. Restoring a year-old backup into a newer build would
     * otherwise leave the database claiming to be the older schema while the
     * tables are the newer one — a lie that costs nothing today and would cost
     * a migration path later.
     */
    private suspend fun writeMeta(export: DayBookExport) {
        dao.insertMeta(export.meta.map { it.toEntity() })
        dao.insertMeta(
            listOf(
                AppMetaEntity(
                    key = AppMetaDao.KEY_SCHEMA_VERSION,
                    value = Schema.VERSION.toString(),
                    updatedAt = export.exportedAt,
                ),
            ),
        )
    }

    /**
     * The file's integer ids mapped onto this phone's, through whichever key
     * matched — see [plan].
     *
     * A file id that resolves to nothing is left out rather than defaulted, so
     * the caller's `?: throw DanglingReference()` still fires and the import
     * rolls back instead of filing an expense against an arbitrary row.
     */
    private fun <D : ExportRow> resolve(incoming: List<D>, local: List<D>): Map<Long, Long> {
        val byUuid = local.associate { it.uuid to it.id }
        val byNaturalKey = local.mapNotNull { row -> row.naturalKey?.let { it to row.id } }.toMap()
        return incoming.mapNotNull { dto ->
            val localId = byUuid[dto.uuid] ?: dto.naturalKey?.let { byNaturalKey[it] }
            localId?.let { dto.id to it }
        }.toMap()
    }

    private suspend fun writeCategories(
        incoming: List<CategoryDto>,
        local: Map<String, CategoryDto>,
        prepare: (CategoryDto) -> CategoryDto,
    ): ImportCounts {
        val p = plan(incoming.map(prepare), local) { d, id -> d.copy(id = id) }
        dao.insertCategories(p.inserts.map { it.toEntity() })
        dao.updateCategories(p.updates.map { it.toEntity() })
        return p.counts
    }

    /**
     * One entity's three-way split — FR-DAT-03's three counts.
     *
     * Two lookups, in this order, and the order is the whole design:
     *
     * 1. **By UUID** — the same row, from a backup of this phone. Identical
     *    apart from its integer key means **skip**, which is what makes merging
     *    a file into the database it came from a no-op rather than a rewrite of
     *    every row. Anything else means **update**: the file is newer.
     * 2. **By natural key** — the same *thing*, from a different phone. Two
     *    installs seed "Grocery" with different UUIDs, so this is the ordinary
     *    cross-device case, not an edge one, and without it the insert below
     *    would hit `ux_category_parent_key` and take the whole import down with
     *    it. Counted as **skipped**, and the local row is left alone: its
     *    `sort_order`, `icon` and `is_system` are facts about this phone, and
     *    `is_system` in particular is not something a file should be able to
     *    set on a seeded root.
     * 3. **Neither** — **insert**, with `id = 0` so SQLite assigns a local key.
     *    The file's integer belongs to another device, and reusing it is how
     *    two rows end up fighting over one id.
     *
     * What matters after a natural-key match is not the row — it is that
     * [merge] then maps that UUID to the **local** id, so every child follows
     * this phone's Grocery rather than the other phone's integer.
     */
    private fun <D : ExportRow> plan(
        incoming: List<D>,
        local: Map<String, D>,
        withId: (D, Long) -> D,
    ): Plan<D> {
        val byNaturalKey = local.values
            .mapNotNull { row -> row.naturalKey?.let { it to row } }
            .toMap()

        val inserts = ArrayList<D>()
        val updates = ArrayList<D>()
        var skipped = 0

        incoming.forEach { dto ->
            val sameRow = local[dto.uuid]
            val sameThing = dto.naturalKey?.let { byNaturalKey[it] }
            when {
                sameRow != null ->
                    // Compared with the local id substituted in, so a differing
                    // integer key alone never counts as a change.
                    if (withId(dto, sameRow.id) == sameRow) skipped++
                    else updates += withId(dto, sameRow.id)

                sameThing != null -> skipped++

                else -> inserts += withId(dto, 0L)
            }
        }
        return Plan(inserts, updates, skipped)
    }

    private class Plan<D>(
        val inserts: List<D>,
        val updates: List<D>,
        val skipped: Int,
    ) {
        val counts get() = ImportCounts(inserts.size, updates.size, skipped)
    }

    private class NewerSchema : IllegalStateException("newer schema")

    /** Thrown inside the transaction, so the whole import rolls back. */
    private class DanglingReference : IllegalStateException("unresolved foreign key")

    private companion object {
        const val TAG = "DayBookImport"

        const val CATEGORIES = "categories"
        const val SOURCES = "sources"
        const val BUDGETS = "budgets"
        const val EXPENSES = "expenses"
        const val INCOME = "income_entries"
        const val RULES = "recurring_rules"
        const val META = "meta"

        /**
         * [Schema.WIPE_ORDER] — children before parents, rollups first.
         *
         * Clearing the rollups up front does **not** stop the delete triggers
         * firing; nine thousand `DELETE FROM expense` rows still fire
         * `trg_rollup_exp_del` nine thousand times. What it does is leave each
         * of those firings nothing to find, which is cheaper than decrementing
         * a row that is about to be discarded — and it means the rebuild at the
         * end starts from an empty table rather than from residue.
         */
        val WIPE_ORDER = Schema.WIPE_ORDER
    }
}
