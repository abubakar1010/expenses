package com.app.finance.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every migration, with its own frozen copy of the DDL it produces.
 *
 * **Nothing here may read [Schema].** A migration describes a historical
 * transition and has to keep describing it after the schema has moved on;
 * pointing it at the current definitions would silently rewrite the past every
 * time the present changed, and the version it claims to produce would stop
 * being the version it produces. The duplication is the point.
 *
 * `SchemaMigrationTest` is what keeps these honest: it walks a version-1
 * database through every migration and asserts the result is the schema Room
 * expects, which is the only comparison that matters.
 */
internal object Migrations {

    /**
     * Declares the primary keys `NOT NULL`, which they always were.
     *
     * No feature rides on this one, deliberately. It repairs a mismatch that
     * had been latent since M1: `Schema` wrote `id INTEGER PRIMARY KEY
     * AUTOINCREMENT`, for which `PRAGMA table_info` reports `notnull = 0`,
     * while the Room entities declare `val id: Long = 0` and therefore expect
     * `notNull = true`. Room's own generated DDL spells the `NOT NULL` out.
     *
     * It stayed invisible because **Room only compares `TableInfo` after a
     * migration**. An ordinary open verifies an identity hash out of
     * `room_master_table` and skips the column comparison entirely, so with no
     * migration in the project's history the comparison had never once run. The
     * first one to exist — whatever it was for — would have failed on the first
     * table validated, and 03 §8 leaves no destructive fallback in release, so
     * every existing install would have reached `RecoveryScreen` on update.
     *
     * SQLite cannot alter a column, so each affected table is rebuilt: create
     * the corrected shape, copy every row by explicit column list, drop the
     * old, rename into place. Only the six tables carrying an autoincrement key
     * need it; `app_meta` and the two rollups declare their keys explicitly and
     * already read back as Room expects.
     *
     * Triggers are dropped first and rebuilt last. `ALTER TABLE ... RENAME`
     * rewrites references inside triggers on SQLite 3.25 and later, which would
     * quietly point the rollup triggers at a table that is about to disappear;
     * `legacy_alter_table` suppresses that, and dropping them outright means
     * the outcome does not depend on which SQLite the device shipped.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA legacy_alter_table = ON")
            try {
                V1_TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }

                V2_REBUILT_TABLES.forEach { table ->
                    db.execSQL(table.createNew)
                    db.execSQL(
                        "INSERT INTO ${table.name}_new (${table.columns}) " +
                            "SELECT ${table.columns} FROM ${table.name}",
                    )
                    db.execSQL("DROP TABLE ${table.name}")
                    db.execSQL("ALTER TABLE ${table.name}_new RENAME TO ${table.name}")
                }

                V1_INDICES.forEach(db::execSQL)
                V1_TRIGGERS.forEach(db::execSQL)
            } finally {
                db.execSQL("PRAGMA legacy_alter_table = OFF")
            }
        }
    }

    /**
     * Shared expenses — FR-SHR-*.
     *
     * Purely additive: three tables, their indices, the guards that police
     * them, and one nullable column on `expense`. No row is rewritten, which is
     * what makes it a very different proposition from [MIGRATION_1_2] and the
     * reason the two are separate.
     *
     * Every statement is idempotent except the `ALTER`, for which SQLite has no
     * `IF NOT EXISTS` — so that one is guarded by reading `PRAGMA table_info`,
     * because a migration interrupted part-way is retried on the next launch
     * and must not fail differently the second time.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V3_TABLES.forEach(db::execSQL)
            if (!db.hasColumn("expense", "payer_person_id")) db.execSQL(V3_ALTER_EXPENSE)
            V3_INDICES.forEach(db::execSQL)
            V3_TRIGGERS.forEach(db::execSQL)
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

    // ------------------------------------------------------------------------

    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        query("PRAGMA table_info($table)").use { c ->
            val name = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(name) else null }
                .any { it == column }
        }

    /** A table being rebuilt: the corrected DDL, and the columns to carry over. */
    private class Rebuild(val name: String, val createNew: String, val columns: String)

    // ------------------------------------------------- version 2, frozen DDL

    /**
     * The six tables as version 2 defines them — version 1's shape with
     * `NOT NULL` on the key. `_new` in the name because each is built beside
     * the table it replaces.
     */
    private val V2_REBUILT_TABLES = listOf(
        Rebuild(
            name = "income_source",
            columns = "id, uuid, name, name_key, kind, color, sort_order, " +
                "is_archived, created_at, updated_at",
            createNew = """
                CREATE TABLE income_source_new (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    uuid        TEXT    NOT NULL UNIQUE,
                    name        TEXT    NOT NULL,
                    name_key    TEXT    NOT NULL,
                    kind        INTEGER NOT NULL DEFAULT 1 CHECK (kind IN (0,1)),
                    color       INTEGER,
                    sort_order  INTEGER NOT NULL DEFAULT 0,
                    is_archived INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0,1)),
                    created_at  INTEGER NOT NULL,
                    updated_at  INTEGER NOT NULL
                )
            """.trimIndent(),
        ),
        Rebuild(
            name = "category",
            columns = "id, uuid, parent_id, name, name_key, nature, icon, color, " +
                "is_system, is_archived, sort_order, created_at, updated_at",
            createNew = """
                CREATE TABLE category_new (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    uuid        TEXT    NOT NULL UNIQUE,
                    parent_id   INTEGER REFERENCES category(id) ON DELETE RESTRICT,
                    name        TEXT    NOT NULL,
                    name_key    TEXT    NOT NULL,
                    nature      INTEGER NOT NULL CHECK (nature IN (0,1,2)),
                    icon        TEXT,
                    color       INTEGER,
                    is_system   INTEGER NOT NULL DEFAULT 0 CHECK (is_system IN (0,1)),
                    is_archived INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0,1)),
                    sort_order  INTEGER NOT NULL DEFAULT 0,
                    created_at  INTEGER NOT NULL,
                    updated_at  INTEGER NOT NULL
                )
            """.trimIndent(),
        ),
        Rebuild(
            name = "income_entry",
            columns = "id, uuid, source_id, amount_minor, earned_on, period_ym, " +
                "note, status, created_at, updated_at",
            createNew = """
                CREATE TABLE income_entry_new (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    uuid         TEXT    NOT NULL UNIQUE,
                    source_id    INTEGER NOT NULL REFERENCES income_source(id) ON DELETE RESTRICT,
                    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
                    earned_on    INTEGER NOT NULL,
                    period_ym    INTEGER NOT NULL,
                    note         TEXT,
                    status       INTEGER NOT NULL DEFAULT 0 CHECK (status IN (0,1)),
                    created_at   INTEGER NOT NULL,
                    updated_at   INTEGER NOT NULL
                )
            """.trimIndent(),
        ),
        Rebuild(
            name = "budget",
            columns = "id, uuid, category_id, period_ym, limit_minor, created_at, updated_at",
            createNew = """
                CREATE TABLE budget_new (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    uuid         TEXT    NOT NULL UNIQUE,
                    category_id  INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
                    period_ym    INTEGER NOT NULL,
                    limit_minor  INTEGER NOT NULL CHECK (limit_minor >= 0),
                    created_at   INTEGER NOT NULL,
                    updated_at   INTEGER NOT NULL
                )
            """.trimIndent(),
        ),
        Rebuild(
            name = "expense",
            columns = "id, uuid, category_id, amount_minor, spent_on, period_ym, " +
                "payment_method, note, status, created_at, updated_at",
            createNew = """
                CREATE TABLE expense_new (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    uuid           TEXT    NOT NULL UNIQUE,
                    category_id    INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
                    amount_minor   INTEGER NOT NULL CHECK (amount_minor <> 0),
                    spent_on       INTEGER NOT NULL,
                    period_ym      INTEGER NOT NULL,
                    payment_method INTEGER NOT NULL DEFAULT 0 CHECK (payment_method IN (0,1,2,3,4,5)),
                    note           TEXT,
                    status         INTEGER NOT NULL DEFAULT 0 CHECK (status IN (0,1)),
                    created_at     INTEGER NOT NULL,
                    updated_at     INTEGER NOT NULL
                )
            """.trimIndent(),
        ),
        Rebuild(
            name = "recurring_rule",
            columns = "id, uuid, target, category_id, source_id, amount_minor, frequency, " +
                "anchor_day, next_due_day, last_run_day, auto_post, is_active, note, " +
                "created_at, updated_at",
            createNew = """
                CREATE TABLE recurring_rule_new (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    uuid         TEXT    NOT NULL UNIQUE,
                    target       INTEGER NOT NULL CHECK (target IN (0,1)),
                    category_id  INTEGER REFERENCES category(id) ON DELETE RESTRICT,
                    source_id    INTEGER REFERENCES income_source(id) ON DELETE RESTRICT,
                    amount_minor INTEGER NOT NULL CHECK (amount_minor <> 0),
                    frequency    INTEGER NOT NULL CHECK (frequency IN (0,1,2)),
                    anchor_day   INTEGER NOT NULL,
                    next_due_day INTEGER NOT NULL,
                    last_run_day INTEGER,
                    auto_post    INTEGER NOT NULL DEFAULT 0 CHECK (auto_post IN (0,1)),
                    is_active    INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
                    note         TEXT,
                    created_at   INTEGER NOT NULL,
                    updated_at   INTEGER NOT NULL,
                    CHECK ((target = 0 AND category_id IS NOT NULL AND source_id IS NULL)
                        OR (target = 1 AND source_id IS NOT NULL AND category_id IS NULL))
                )
            """.trimIndent(),
        ),
    )

    /** Version 1's indices, recreated after the rebuild drops them with their tables. */
    private val V1_INDICES = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_income_source_key ON income_source(name_key)",
        "CREATE INDEX IF NOT EXISTS ix_income_source_active ON income_source(is_archived, sort_order)",
        "CREATE INDEX IF NOT EXISTS ix_income_entry_period ON income_entry(period_ym, source_id)",
        "CREATE INDEX IF NOT EXISTS ix_income_entry_date ON income_entry(earned_on DESC)",
        "CREATE INDEX IF NOT EXISTS ix_income_entry_source ON income_entry(source_id, period_ym)",
        "CREATE INDEX IF NOT EXISTS ix_category_parent ON category(parent_id, is_archived, sort_order)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_budget_cat_period ON budget(category_id, period_ym)",
        "CREATE INDEX IF NOT EXISTS ix_budget_period ON budget(period_ym)",
        "CREATE INDEX IF NOT EXISTS ix_expense_date ON expense(spent_on DESC, id DESC)",
        "CREATE INDEX IF NOT EXISTS ix_expense_period ON expense(period_ym, category_id)",
        "CREATE INDEX IF NOT EXISTS ix_expense_category ON expense(category_id, spent_on DESC)",
        "CREATE INDEX IF NOT EXISTS ix_expense_method ON expense(payment_method, period_ym)",
        "CREATE INDEX IF NOT EXISTS ix_rule_due ON recurring_rule(is_active, next_due_day)",
    )

    private val V1_TRIGGER_NAMES = listOf(
        "trg_category_depth_insert", "trg_category_depth_update",
        "trg_category_inherit_nature", "trg_category_inherit_nature_upd",
        "trg_budget_leaf_only", "trg_budget_leaf_only_upd",
        "trg_expense_leaf_only", "trg_expense_leaf_only_upd",
        "trg_category_child_of_used_leaf", "trg_category_child_of_used_leaf_upd",
        "trg_rollup_exp_ins", "trg_rollup_exp_del", "trg_rollup_exp_upd",
        "trg_rollup_inc_ins", "trg_rollup_inc_del", "trg_rollup_inc_upd",
    )

    /**
     * Version 1's triggers, verbatim.
     *
     * A frozen copy, and it must stay one even though it duplicates [Schema]
     * today. The moment a trigger legitimately changes, this list is what says
     * what an upgrading database had before it.
     */
    private val V1_TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_depth_insert
        BEFORE INSERT ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'category nesting is limited to two levels')
            WHERE (SELECT parent_id FROM category WHERE id = NEW.parent_id) IS NOT NULL;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_depth_update
        BEFORE UPDATE OF parent_id ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'category nesting is limited to two levels')
            WHERE (SELECT parent_id FROM category WHERE id = NEW.parent_id) IS NOT NULL
               OR EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.id);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_inherit_nature
        AFTER INSERT ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            UPDATE category
               SET nature = (SELECT nature FROM category WHERE id = NEW.parent_id)
             WHERE id = NEW.id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_inherit_nature_upd
        AFTER UPDATE OF parent_id ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            UPDATE category
               SET nature = (SELECT nature FROM category WHERE id = NEW.parent_id)
             WHERE id = NEW.id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_leaf_only
        BEFORE INSERT ON budget
        BEGIN
            SELECT RAISE(ABORT, 'budgets may only target leaf categories')
            WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_leaf_only_upd
        BEFORE UPDATE OF category_id ON budget
        BEGIN
            SELECT RAISE(ABORT, 'budgets may only target leaf categories')
            WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_expense_leaf_only
        BEFORE INSERT ON expense
        BEGIN
            SELECT RAISE(ABORT, 'expenses may only reference leaf categories')
            WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_expense_leaf_only_upd
        BEFORE UPDATE OF category_id ON expense
        BEGIN
            SELECT RAISE(ABORT, 'expenses may only reference leaf categories')
            WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_child_of_used_leaf
        BEFORE INSERT ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'a category with expenses or budgets may not gain a child')
            WHERE EXISTS (SELECT 1 FROM expense WHERE category_id = NEW.parent_id)
               OR EXISTS (SELECT 1 FROM budget  WHERE category_id = NEW.parent_id);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_child_of_used_leaf_upd
        BEFORE UPDATE OF parent_id ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'a category with expenses or budgets may not gain a child')
            WHERE EXISTS (SELECT 1 FROM expense WHERE category_id = NEW.parent_id)
               OR EXISTS (SELECT 1 FROM budget  WHERE category_id = NEW.parent_id);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_rollup_exp_ins
        AFTER INSERT ON expense
        WHEN NEW.status = 0
        BEGIN
            INSERT INTO rollup_expense_month(period_ym, category_id, total_minor, txn_count)
            VALUES (NEW.period_ym, NEW.category_id, NEW.amount_minor, 1)
            ON CONFLICT(period_ym, category_id) DO UPDATE SET
                total_minor = total_minor + NEW.amount_minor,
                txn_count   = txn_count + 1;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_rollup_exp_del
        AFTER DELETE ON expense
        WHEN OLD.status = 0
        BEGIN
            UPDATE rollup_expense_month
               SET total_minor = total_minor - OLD.amount_minor,
                   txn_count   = txn_count - 1
             WHERE period_ym = OLD.period_ym AND category_id = OLD.category_id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_rollup_exp_upd
        AFTER UPDATE ON expense
        BEGIN
            UPDATE rollup_expense_month
               SET total_minor = total_minor - OLD.amount_minor,
                   txn_count   = txn_count - 1
             WHERE OLD.status = 0
               AND period_ym = OLD.period_ym
               AND category_id = OLD.category_id;

            INSERT INTO rollup_expense_month(period_ym, category_id, total_minor, txn_count)
            SELECT NEW.period_ym, NEW.category_id, NEW.amount_minor, 1
             WHERE NEW.status = 0
            ON CONFLICT(period_ym, category_id) DO UPDATE SET
                total_minor = total_minor + NEW.amount_minor,
                txn_count   = txn_count + 1;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_rollup_inc_ins
        AFTER INSERT ON income_entry
        WHEN NEW.status = 0
        BEGIN
            INSERT INTO rollup_income_month(period_ym, source_id, total_minor, entry_count)
            VALUES (NEW.period_ym, NEW.source_id, NEW.amount_minor, 1)
            ON CONFLICT(period_ym, source_id) DO UPDATE SET
                total_minor = total_minor + NEW.amount_minor,
                entry_count = entry_count + 1;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_rollup_inc_del
        AFTER DELETE ON income_entry
        WHEN OLD.status = 0
        BEGIN
            UPDATE rollup_income_month
               SET total_minor = total_minor - OLD.amount_minor,
                   entry_count = entry_count - 1
             WHERE period_ym = OLD.period_ym AND source_id = OLD.source_id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_rollup_inc_upd
        AFTER UPDATE ON income_entry
        BEGIN
            UPDATE rollup_income_month
               SET total_minor = total_minor - OLD.amount_minor,
                   entry_count = entry_count - 1
             WHERE OLD.status = 0
               AND period_ym = OLD.period_ym
               AND source_id = OLD.source_id;

            INSERT INTO rollup_income_month(period_ym, source_id, total_minor, entry_count)
            SELECT NEW.period_ym, NEW.source_id, NEW.amount_minor, 1
             WHERE NEW.status = 0
            ON CONFLICT(period_ym, source_id) DO UPDATE SET
                total_minor = total_minor + NEW.amount_minor,
                entry_count = entry_count + 1;
        END
        """,
    ).map { it.trimIndent() }

    // ------------------------------------------------- version 3, frozen DDL

    private val V3_TABLES = listOf(
        """
        CREATE TABLE IF NOT EXISTS person (
            id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            uuid        TEXT    NOT NULL UNIQUE,
            name        TEXT    NOT NULL,
            name_key    TEXT    NOT NULL,
            sort_order  INTEGER NOT NULL DEFAULT 0,
            is_archived INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0,1)),
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS expense_share (
            id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            uuid        TEXT    NOT NULL UNIQUE,
            expense_id  INTEGER NOT NULL REFERENCES expense(id) ON DELETE RESTRICT,
            person_id   INTEGER NOT NULL REFERENCES person(id) ON DELETE RESTRICT,
            share_minor INTEGER NOT NULL CHECK (share_minor > 0),
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS settlement (
            id             INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            uuid           TEXT    NOT NULL UNIQUE,
            person_id      INTEGER NOT NULL REFERENCES person(id) ON DELETE RESTRICT,
            amount_minor   INTEGER NOT NULL CHECK (amount_minor <> 0),
            settled_on     INTEGER NOT NULL,
            payment_method INTEGER NOT NULL DEFAULT 0 CHECK (payment_method IN (0,1,2,3,4,5)),
            note           TEXT,
            created_at     INTEGER NOT NULL,
            updated_at     INTEGER NOT NULL
        )
        """,
    ).map { it.trimIndent() }

    private const val V3_ALTER_EXPENSE =
        "ALTER TABLE expense ADD COLUMN payer_person_id INTEGER " +
            "REFERENCES person(id) ON DELETE RESTRICT"

    private val V3_INDICES = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_person_name_key ON person(name_key)",
        "CREATE INDEX IF NOT EXISTS ix_person_active ON person(is_archived, sort_order)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_share_expense_person " +
            "ON expense_share(expense_id, person_id)",
        "CREATE INDEX IF NOT EXISTS ix_share_person ON expense_share(person_id)",
        "CREATE INDEX IF NOT EXISTS ix_expense_payer ON expense(payer_person_id)",
        "CREATE INDEX IF NOT EXISTS ix_settlement_person ON settlement(person_id, settled_on DESC)",
    )

    private val V3_TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS trg_share_only_when_i_paid
        BEFORE INSERT ON expense_share
        BEGIN
            SELECT RAISE(ABORT, 'a share may only be recorded on an expense you paid')
            WHERE (SELECT payer_person_id FROM expense WHERE id = NEW.expense_id) IS NOT NULL;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_share_only_when_i_paid_upd
        BEFORE UPDATE OF expense_id ON expense_share
        BEGIN
            SELECT RAISE(ABORT, 'a share may only be recorded on an expense you paid')
            WHERE (SELECT payer_person_id FROM expense WHERE id = NEW.expense_id) IS NOT NULL;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_payer_excludes_shares
        BEFORE UPDATE OF payer_person_id ON expense
        WHEN NEW.payer_person_id IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'an expense with shares was not paid by someone else')
            WHERE EXISTS (SELECT 1 FROM expense_share WHERE expense_id = NEW.id);
        END
        """,
    ).map { it.trimIndent() }
}
