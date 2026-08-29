package com.app.finance.data.export

import com.app.finance.data.db.entity.AppMetaEntity
import com.app.finance.data.db.entity.BudgetEntity
import com.app.finance.data.db.entity.CategoryEntity
import com.app.finance.data.db.entity.ExpenseEntity
import com.app.finance.data.db.entity.IncomeEntryEntity
import com.app.finance.data.db.entity.IncomeSourceEntity
import com.app.finance.data.db.entity.RecurringRuleEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-disk export format — FR-DAT-01 and FR-DAT-03.
 *
 * PRD §6.6 explains why this is P0 rather than a nicety:
 *
 * > "Users do not trust an app with their financial history until they have
 * > proof they can extract it. It is also the only backup mechanism in a
 * > no-server product."
 *
 * Three properties are load-bearing.
 *
 * **Every amount is a `Long` of paisa.** DR-01 does not stop at the database —
 * "floating-point representation of money is prohibited anywhere in the system,
 * **including export files**". A JSON number that round-trips through a `Double`
 * loses taka at scale, and an export that quietly changes the figures is worse
 * than no export.
 *
 * **Every row carries its `uuid`.** DR-06 says why the column exists at all:
 * "to support import deduplication across devices". It is FR-DAT-03's "stable
 * natural key", and the only identifier that means the same thing on two
 * phones.
 *
 * **The rollups are not here.** 03 §6 makes triggers their sole writer and
 * `Schema.REBUILD_ROLLUPS` their regeneration path. Exporting derived rows would
 * hand the importer two sources of truth to reconcile, and `assertion19` already
 * proves the rebuild reproduces the trigger-maintained state exactly.
 *
 * The DTOs mirror the Room entities column for column rather than reusing them.
 * An entity is a storage detail that may be renamed or re-shaped; a file format
 * is a promise to a user who exported last year. Keeping them apart means a
 * refactor cannot silently change what is written to disk — and a compile error
 * in the mapper below is exactly the warning that should fire when one is
 * attempted.
 */
@Serializable
data class DayBookExport(
    /** FR-DAT-05 — an import refuses a file newer than the app understands. */
    @SerialName("schema_version") val schemaVersion: Int,
    /** Epoch millis. Informational; nothing keys off it. */
    @SerialName("exported_at") val exportedAt: Long,
    val categories: List<CategoryDto> = emptyList(),
    val sources: List<SourceDto> = emptyList(),
    val budgets: List<BudgetDto> = emptyList(),
    val expenses: List<ExpenseDto> = emptyList(),
    @SerialName("income_entries") val incomeEntries: List<IncomeEntryDto> = emptyList(),
    @SerialName("recurring_rules") val rules: List<RuleDto> = emptyList(),
    val meta: List<MetaDto> = emptyList(),
) {
    val rowCount: Int
        get() = categories.size + sources.size + budgets.size + expenses.size +
            incomeEntries.size + rules.size + meta.size

    companion object {
        /**
         * Lenient on the way in, strict on the way out.
         *
         * `ignoreUnknownKeys` is what lets a file written by a *newer* build
         * that added a column still import into this one, which matters because
         * FR-DAT-05 only refuses a newer **schema version** — a same-version
         * file with an extra field is a file this app can read.
         *
         * `encodeDefaults = false` keeps nulls and zeroes out of the output; on
         * nine thousand expenses that is most of the file.
         */
        val CODEC = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
            prettyPrint = false
        }
    }
}

/**
 * What every exported row has in common: a local integer key and a stable one.
 *
 * DR-06 is the reason both exist — "every entity MUST carry a stable UUID
 * alongside its integer primary key, to support import deduplication across
 * devices" — and this interface is what lets [Importer] compare and re-key any
 * of them without a `when` over six types.
 *
 * `app_meta` is deliberately not one of these: it is keyed by its own string
 * and carries no uuid, because a preference is not a row anyone syncs.
 */
interface ExportRow {
    val id: Long
    val uuid: String

    /**
     * What identifies this row on a *different* phone — FR-DAT-03's "stable
     * natural key".
     *
     * The UUID is stable per device, not across them: every seeded row's uuid
     * comes from `randomblob` at install, so two phones have different UUIDs
     * for "Grocery". Merging on the uuid alone therefore tries to insert a
     * second Grocery and is rejected by `ux_category_parent_key` — the exact
     * case DR-06 says the uuid exists to support.
     *
     * So each row also carries whatever its unique index enforces. Where the
     * schema has no unique index the answer is **null**, and that is a fact
     * about the data rather than an omission: two identical expenses on the
     * same day are two expenses, and FR-IE-02 says the same of income.
     */
    val naturalKey: String? get() = null
}

@Serializable
data class CategoryDto(
    override val id: Long,
    override val uuid: String,
    @SerialName("parent_id") val parentId: Long? = null,
    val name: String,
    @SerialName("name_key") val nameKey: String,
    val nature: Int,
    val icon: String? = null,
    val color: Int? = null,
    @SerialName("is_system") val isSystem: Boolean = false,
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : ExportRow {
    /** `ux_category_parent_key` — `IFNULL(parent_id, -1)` and the name key. */
    override val naturalKey: String get() = "${parentId ?: -1}/$nameKey"
}

@Serializable
data class SourceDto(
    override val id: Long,
    override val uuid: String,
    val name: String,
    @SerialName("name_key") val nameKey: String,
    val kind: Int,
    val color: Int? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : ExportRow {
    /** `ux_income_source_key`, which is what makes FR-IS-02 structural. */
    override val naturalKey: String get() = nameKey
}

@Serializable
data class BudgetDto(
    override val id: Long,
    override val uuid: String,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("period_ym") val periodYm: Int,
    @SerialName("limit_minor") val limitMinor: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : ExportRow {
    /**
     * `ux_budget_cat_period`, which is FR-BUD-02 — "one limit per (category,
     * period)" — enforced at the storage layer.
     *
     * Read **after** the category id has been remapped to this phone's, so it
     * is a local key by the time the merge compares it.
     */
    override val naturalKey: String get() = "$categoryId/$periodYm"
}

@Serializable
data class ExpenseDto(
    override val id: Long,
    override val uuid: String,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("spent_on") val spentOn: Long,
    @SerialName("period_ym") val periodYm: Int,
    @SerialName("payment_method") val paymentMethod: Int = 0,
    val note: String? = null,
    val status: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : ExportRow

@Serializable
data class IncomeEntryDto(
    override val id: Long,
    override val uuid: String,
    @SerialName("source_id") val sourceId: Long,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("earned_on") val earnedOn: Long,
    @SerialName("period_ym") val periodYm: Int,
    val note: String? = null,
    val status: Int = 0,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : ExportRow

@Serializable
data class RuleDto(
    override val id: Long,
    override val uuid: String,
    val target: Int,
    @SerialName("category_id") val categoryId: Long? = null,
    @SerialName("source_id") val sourceId: Long? = null,
    @SerialName("amount_minor") val amountMinor: Long,
    val frequency: Int,
    @SerialName("anchor_day") val anchorDay: Int,
    @SerialName("next_due_day") val nextDueDay: Long,
    @SerialName("last_run_day") val lastRunDay: Long? = null,
    @SerialName("auto_post") val autoPost: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = true,
    val note: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : ExportRow

@Serializable
data class MetaDto(
    val key: String,
    val value: String,
    @SerialName("updated_at") val updatedAt: Long,
)

// ---------------------------------------------------------------- entity → dto

fun CategoryEntity.toDto() = CategoryDto(
    id, uuid, parentId, name, nameKey, nature, icon, color,
    isSystem, isArchived, sortOrder, createdAt, updatedAt,
)

fun IncomeSourceEntity.toDto() = SourceDto(
    id, uuid, name, nameKey, kind, color, sortOrder, isArchived, createdAt, updatedAt,
)

fun BudgetEntity.toDto() =
    BudgetDto(id, uuid, categoryId, periodYm, limitMinor, createdAt, updatedAt)

fun ExpenseEntity.toDto() = ExpenseDto(
    id, uuid, categoryId, amountMinor, spentOn, periodYm,
    paymentMethod, note, status, createdAt, updatedAt,
)

fun IncomeEntryEntity.toDto() = IncomeEntryDto(
    id, uuid, sourceId, amountMinor, earnedOn, periodYm, note, status, createdAt, updatedAt,
)

fun RecurringRuleEntity.toDto() = RuleDto(
    id, uuid, target, categoryId, sourceId, amountMinor, frequency, anchorDay,
    nextDueDay, lastRunDay, autoPost, isActive, note, createdAt, updatedAt,
)

fun AppMetaEntity.toDto() = MetaDto(key, value, updatedAt)

// ---------------------------------------------------------------- dto → entity

fun CategoryDto.toEntity() = CategoryEntity(
    id, uuid, parentId, name, nameKey, nature, icon, color,
    isSystem, isArchived, sortOrder, createdAt, updatedAt,
)

fun SourceDto.toEntity() = IncomeSourceEntity(
    id, uuid, name, nameKey, kind, color, sortOrder, isArchived, createdAt, updatedAt,
)

fun BudgetDto.toEntity() =
    BudgetEntity(id, uuid, categoryId, periodYm, limitMinor, createdAt, updatedAt)

// Named rather than positional from here on: `expense` gained
// `payer_person_id` between `status` and `created_at` in v2, and a positional
// call silently re-binds every argument after an insertion rather than failing.
// It failed here only because the arity changed too.
//
// `payerPersonId` is not carried yet — the shared-expense arrays arrive with
// the rest of FR-SHR in the export format, and until then a restored expense is
// one you paid, which is what every v1 expense was.
fun ExpenseDto.toEntity() = ExpenseEntity(
    id = id,
    uuid = uuid,
    categoryId = categoryId,
    amountMinor = amountMinor,
    spentOn = spentOn,
    periodYm = periodYm,
    paymentMethod = paymentMethod,
    note = note,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun IncomeEntryDto.toEntity() = IncomeEntryEntity(
    id, uuid, sourceId, amountMinor, earnedOn, periodYm, note, status, createdAt, updatedAt,
)

fun RuleDto.toEntity() = RecurringRuleEntity(
    id, uuid, target, categoryId, sourceId, amountMinor, frequency, anchorDay,
    nextDueDay, lastRunDay, autoPost, isActive, note, createdAt, updatedAt,
)

fun MetaDto.toEntity() = AppMetaEntity(key, value, updatedAt)
