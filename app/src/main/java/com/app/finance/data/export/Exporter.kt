package com.app.finance.data.export

import com.app.finance.data.db.AppDatabase
import com.app.finance.data.db.Schema
import com.app.finance.data.db.dao.AppMetaDao
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the whole dataset out — FR-DAT-01 and FR-DAT-02.
 *
 * Takes an already-opened [OutputStream] rather than a `Uri` and a `Context`,
 * so it has no Android dependency beyond Room and is drivable from a test
 * without a document picker. The caller opens the stream; 04 §8's rule that
 * "no data leaves the device except by explicit user-initiated export" is
 * enforced there, by the fact that the only stream this ever receives comes
 * from `ACTION_CREATE_DOCUMENT`.
 *
 * **Both formats stream.** Every row is encoded and written as it is produced
 * rather than assembled into one string first. NFR-PERF-07 allows three seconds
 * and NFR-PERF-08 caps resident memory at 80 MB; holding a five-year document
 * and its serialised form at once is the one way to miss both at the same time.
 */
class Exporter(private val db: AppDatabase) {

    private val dao = db.backupDao()

    /**
     * FR-DAT-01 — "the complete dataset as JSON".
     *
     * Complete means every persisted table. It does **not** mean the rollups:
     * they are derived, 03 §6 makes triggers their only writer, and
     * `Schema.REBUILD_ROLLUPS` regenerates them on import. Writing them would
     * give a future importer two sources of truth and no rule for which wins.
     */
    suspend fun writeJson(out: OutputStream, exportedAt: Long): ExportSummary {
        val counts = LinkedHashMap<String, Int>()
        // Explicit rather than the platform default. It is UTF-8 on Android
        // either way, but a file format is a promise to a user who exported
        // last year, and a promise should not rest on a default.
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("{\"schema_version\":${Schema.VERSION},\"exported_at\":$exportedAt")

            counts[CATEGORIES] = w.array(CATEGORIES, CategoryDto.serializer(), dao.allCategories()) { it.toDto() }
            counts[SOURCES] = w.array(SOURCES, SourceDto.serializer(), dao.allSources()) { it.toDto() }
            counts[BUDGETS] = w.array(BUDGETS, BudgetDto.serializer(), dao.allBudgets()) { it.toDto() }
            counts[EXPENSES] = w.array(EXPENSES, ExpenseDto.serializer(), dao.allExpenses()) { it.toDto() }
            counts[INCOME] = w.array(INCOME, IncomeEntryDto.serializer(), dao.allIncomeEntries()) { it.toDto() }
            counts[RULES] = w.array(RULES, RuleDto.serializer(), dao.allRules()) { it.toDto() }
            counts[META] = w.array(META, MetaDto.serializer(), exportableMeta()) { it.toDto() }

            w.write("}")
        }
        return ExportSummary(counts)
    }

    /**
     * FR-DAT-02 — "CSV, one file per entity, delivered as a single archive".
     *
     * The same `ZipOutputStream` shape `RecoveryScreen` already uses for the raw
     * database, and for the same reason: one file the user can put anywhere,
     * with the pieces intact inside it.
     *
     * A header row per file. Spreadsheets are the reason anyone asks for CSV,
     * and a spreadsheet with `1,4,1200000,20678` in it is not an export, it is
     * a puzzle.
     */
    suspend fun writeCsvArchive(out: OutputStream, exportedAt: Long): ExportSummary {
        val counts = LinkedHashMap<String, Int>()
        ZipOutputStream(out.buffered()).use { zip ->
            counts[CATEGORIES] = zip.csv(CATEGORIES, CATEGORY_HEADER, dao.allCategories()) {
                listOf(
                    it.id.s(), it.uuid, it.parentId?.s(), it.name, it.nameKey, it.nature.s(),
                    it.icon, it.color?.s(), it.isSystem.s(), it.isArchived.s(),
                    it.sortOrder.s(), it.createdAt.s(), it.updatedAt.s(),
                )
            }
            counts[SOURCES] = zip.csv(SOURCES, SOURCE_HEADER, dao.allSources()) {
                listOf(
                    it.id.s(), it.uuid, it.name, it.nameKey, it.kind.s(), it.color?.s(),
                    it.sortOrder.s(), it.isArchived.s(), it.createdAt.s(), it.updatedAt.s(),
                )
            }
            counts[BUDGETS] = zip.csv(BUDGETS, BUDGET_HEADER, dao.allBudgets()) {
                listOf(
                    it.id.s(), it.uuid, it.categoryId.s(), it.periodYm.s(),
                    it.limitMinor.s(), it.createdAt.s(), it.updatedAt.s(),
                )
            }
            counts[EXPENSES] = zip.csv(EXPENSES, EXPENSE_HEADER, dao.allExpenses()) {
                listOf(
                    it.id.s(), it.uuid, it.categoryId.s(), it.amountMinor.s(), it.spentOn.s(),
                    it.periodYm.s(), it.paymentMethod.s(), it.note, it.status.s(),
                    it.createdAt.s(), it.updatedAt.s(),
                )
            }
            counts[INCOME] = zip.csv(INCOME, INCOME_HEADER, dao.allIncomeEntries()) {
                listOf(
                    it.id.s(), it.uuid, it.sourceId.s(), it.amountMinor.s(), it.earnedOn.s(),
                    it.periodYm.s(), it.note, it.status.s(), it.createdAt.s(), it.updatedAt.s(),
                )
            }
            counts[RULES] = zip.csv(RULES, RULE_HEADER, dao.allRules()) {
                listOf(
                    it.id.s(), it.uuid, it.target.s(), it.categoryId?.s(), it.sourceId?.s(),
                    it.amountMinor.s(), it.frequency.s(), it.anchorDay.s(), it.nextDueDay.s(),
                    it.lastRunDay?.s(), it.autoPost.s(), it.isActive.s(), it.note,
                    it.createdAt.s(), it.updatedAt.s(),
                )
            }
            counts[META] = zip.csv(META, META_HEADER, exportableMeta()) {
                listOf(it.key, it.value, it.updatedAt.s())
            }
        }
        return ExportSummary(counts)
    }

    /**
     * `app_meta` minus the rows that describe this phone rather than this
     * ledger — `AppMetaDao.TRANSIENT_KEYS`, and the reasoning is there.
     *
     * Filtered in Kotlin rather than by a `WHERE key NOT IN (...)`, so the set
     * has exactly one definition. A SQL literal listing the same keys is a
     * second one, and the two would part company the first time a key was
     * added.
     */
    private suspend fun exportableMeta() =
        dao.allMeta().filterNot { it.key in AppMetaDao.TRANSIENT_KEYS }

    // ------------------------------------------------------------- internals

    /**
     * `"name":[{…},{…}]` — one element encoded at a time.
     *
     * `encodeToString` on the whole list would build the entire array in memory
     * before a byte reached the stream, which for nine thousand expenses is the
     * allocation this method exists to avoid.
     */
    private fun <E, D> Writer.array(
        name: String,
        serializer: KSerializer<D>,
        rows: List<E>,
        toDto: (E) -> D,
    ): Int {
        write(",\"$name\":[")
        rows.forEachIndexed { i, row ->
            if (i > 0) write(",")
            write(CODEC.encodeToString(serializer, toDto(row)))
        }
        write("]")
        return rows.size
    }

    private fun <E> ZipOutputStream.csv(
        name: String,
        header: String,
        rows: List<E>,
        fields: (E) -> List<String?>,
    ): Int {
        putNextEntry(ZipEntry("$name.csv"))
        val w = writer(Charsets.UTF_8)
        w.write(header)
        w.write(CsvWriter.EOL)
        rows.forEach { w.write(CsvWriter.row(fields(it))) }
        // Flushed rather than closed: closing the writer would close the whole
        // archive under the next entry.
        w.flush()
        closeEntry()
        return rows.size
    }

    private fun Long.s() = toString()
    private fun Int.s() = toString()

    /** `1` / `0`, matching the column's own storage and its `CHECK (… IN (0,1))`. */
    private fun Boolean.s() = if (this) "1" else "0"

    private companion object {
        val CODEC: Json = DayBookExport.CODEC

        const val CATEGORIES = "categories"
        const val SOURCES = "sources"
        const val BUDGETS = "budgets"
        const val EXPENSES = "expenses"
        const val INCOME = "income_entries"
        const val RULES = "recurring_rules"
        const val META = "meta"

        const val CATEGORY_HEADER =
            "id,uuid,parent_id,name,name_key,nature,icon,color,is_system,is_archived," +
                "sort_order,created_at,updated_at"
        const val SOURCE_HEADER =
            "id,uuid,name,name_key,kind,color,sort_order,is_archived,created_at,updated_at"
        const val BUDGET_HEADER =
            "id,uuid,category_id,period_ym,limit_minor,created_at,updated_at"
        const val EXPENSE_HEADER =
            "id,uuid,category_id,amount_minor,spent_on,period_ym,payment_method,note,status," +
                "created_at,updated_at"
        const val INCOME_HEADER =
            "id,uuid,source_id,amount_minor,earned_on,period_ym,note,status,created_at,updated_at"
        const val RULE_HEADER =
            "id,uuid,target,category_id,source_id,amount_minor,frequency,anchor_day," +
                "next_due_day,last_run_day,auto_post,is_active,note,created_at,updated_at"
        const val META_HEADER = "key,value,updated_at"
    }
}

/** Row counts per entity — what the snackbar reports, and what a test compares. */
data class ExportSummary(val counts: Map<String, Int>) {
    val total: Int get() = counts.values.sum()
}
