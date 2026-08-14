package com.app.finance.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room's view of the schema.
 *
 * These declarations exist for DAO generation and compile-time query
 * verification. They are *not* what creates the tables — [com.app.finance.data.db.Schema]
 * is, because Room cannot express `CHECK`, `WITHOUT ROWID`, the functional
 * index on `category`, or any trigger.
 *
 * Consequently every column name, affinity, nullability and default below must
 * match `Schema` exactly, or Room's `TableInfo` validation fails on the second
 * open. When editing one, edit both.
 *
 * `is_*` columns are declared `Boolean`, which Room stores as INTEGER 0/1 —
 * the same affinity the DDL declares and the `CHECK (… IN (0,1))` constraints
 * police.
 */

@Entity(
    tableName = "income_source",
    indices = [
        Index(name = "ux_income_source_key", value = ["name_key"], unique = true),
        Index(name = "ix_income_source_active", value = ["is_archived", "sort_order"]),
    ],
)
data class IncomeSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    /** Normalised by `NameKey.of`, never by SQL's ASCII-only `lower()`. */
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(defaultValue = "1") val kind: Int = 1,
    val color: Int? = null,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "category",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    // ux_category_parent_key is deliberately absent: it indexes
    // IFNULL(parent_id, -1), which @Index cannot express. It is created in
    // Schema.INDICES instead.
    indices = [
        Index(name = "ix_category_parent", value = ["parent_id", "is_archived", "sort_order"]),
    ],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    /** Null for a root. Depth beyond two levels is rejected by trigger. */
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    /** Denormalised from the root and kept correct by trigger, to spare the
     *  hottest read path a join. */
    val nature: Int,
    val icon: String? = null,
    val color: Int? = null,
    /** The three seeded roots. Renameable, never deletable or archivable. */
    @ColumnInfo(name = "is_system", defaultValue = "0") val isSystem: Boolean = false,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "income_entry",
    foreignKeys = [
        ForeignKey(
            entity = IncomeSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            name = "ix_income_entry_period",
            value = ["period_ym", "source_id"],
        ),
        Index(
            name = "ix_income_entry_date",
            value = ["earned_on"],
            orders = [Index.Order.DESC],
        ),
        Index(
            name = "ix_income_entry_source",
            value = ["source_id", "period_ym"],
        ),
    ],
)
data class IncomeEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    /** Paisa, always positive — income has no refund case. */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    /** Epoch day. The canonical reporting date; `received_on` is deferred to v2. */
    @ColumnInfo(name = "earned_on") val earnedOn: Long,
    @ColumnInfo(name = "period_ym") val periodYm: Int,
    val note: String? = null,
    @ColumnInfo(defaultValue = "0") val status: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "budget",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        // Enforces FR-BUD-02 — one limit per (category, period) — at the
        // storage layer rather than in application code.
        Index(name = "ux_budget_cat_period", value = ["category_id", "period_ym"], unique = true),
        Index(name = "ix_budget_period", value = ["period_ym"]),
    ],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    /** Leaf categories only, enforced by trigger. Root limits are computed. */
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "period_ym") val periodYm: Int,
    @ColumnInfo(name = "limit_minor") val limitMinor: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "expense",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        // The `id DESC` tiebreaker is what gives keyset pagination a stable,
        // fully indexed sort key.
        Index(
            name = "ix_expense_date",
            value = ["spent_on", "id"],
            orders = [Index.Order.DESC, Index.Order.DESC],
        ),
        Index(name = "ix_expense_period", value = ["period_ym", "category_id"]),
        Index(
            name = "ix_expense_category",
            value = ["category_id", "spent_on"],
            orders = [Index.Order.ASC, Index.Order.DESC],
        ),
        Index(name = "ix_expense_method", value = ["payment_method", "period_ym"]),
    ],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    /** Paisa. Negative means a refund (FR-EXP-06); zero is rejected by CHECK. */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    /** Epoch day. */
    @ColumnInfo(name = "spent_on") val spentOn: Long,
    /** Derived from [spentOn] on write; the whole monthly-query strategy. */
    @ColumnInfo(name = "period_ym") val periodYm: Int,
    @ColumnInfo(name = "payment_method", defaultValue = "0") val paymentMethod: Int = 0,
    val note: String? = null,
    @ColumnInfo(defaultValue = "0") val status: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Derived data, written *only* by trigger (03 §6). Application code must never
 * update these tables directly — a repository that did would be the one code
 * path capable of drifting.
 */
@Entity(tableName = "rollup_expense_month", primaryKeys = ["period_ym", "category_id"])
data class RollupExpenseMonthEntity(
    @ColumnInfo(name = "period_ym") val periodYm: Int,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "total_minor", defaultValue = "0") val totalMinor: Long = 0,
    @ColumnInfo(name = "txn_count", defaultValue = "0") val txnCount: Int = 0,
)

@Entity(tableName = "rollup_income_month", primaryKeys = ["period_ym", "source_id"])
data class RollupIncomeMonthEntity(
    @ColumnInfo(name = "period_ym") val periodYm: Int,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "total_minor", defaultValue = "0") val totalMinor: Long = 0,
    @ColumnInfo(name = "entry_count", defaultValue = "0") val entryCount: Int = 0,
)

/** P1 — templates for rent, internet, subscriptions, salary. */
@Entity(
    tableName = "recurring_rule",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = IncomeSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(name = "ix_rule_due", value = ["is_active", "next_due_day"])],
)
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val target: Int,
    /** Exactly one of [categoryId] / [sourceId] is set — a table-level CHECK. */
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "source_id") val sourceId: Long? = null,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val frequency: Int,
    /** 1..31; clamps at generation time for short months (FR-REC-05). */
    @ColumnInfo(name = "anchor_day") val anchorDay: Int,
    @ColumnInfo(name = "next_due_day") val nextDueDay: Long,
    /** Generation proceeds only when next_due_day > last_run_day (FR-REC-03). */
    @ColumnInfo(name = "last_run_day") val lastRunDay: Long? = null,
    @ColumnInfo(name = "auto_post", defaultValue = "0") val autoPost: Boolean = false,
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Boolean = true,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Key/value store for schema version, last-used category and payment method
 * (which power the entry-form defaults in FR-EXP-02/03), last-viewed period and
 * onboarding state.
 *
 * 04 §2: a Room table rather than DataStore, which would be a whole dependency
 * and an extra file handle on the startup path for a handful of keys.
 */
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
