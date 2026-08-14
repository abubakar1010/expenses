package com.app.finance.data.db

/**
 * The canonical SQLite schema — version 1.
 *
 * This object, not Room's entity annotations, is what actually creates the
 * database. Room cannot express four things this schema depends on:
 *
 *  - `CHECK` constraints (`amount_minor <> 0`, the enum ranges, the
 *    recurring-rule XOR)
 *  - `WITHOUT ROWID` on the rollup and `app_meta` tables
 *  - the functional unique index `category(IFNULL(parent_id, -1), name_key)`
 *  - triggers, which 03-database-design.md §1 makes the *sole* writer of the
 *    rollup tables
 *
 * So [AppDatabase] lets Room create its own tables, then immediately replaces
 * them from here inside the same transaction. Room's `TableInfo` validator
 * compares column names, affinities, nullability, defaults, primary keys and
 * foreign keys — it does not read `CHECK` constraints or `WITHOUT ROWID`, so
 * the two views of the schema stay compatible.
 *
 * **`docs/schema_v1.sql` is generated from this file and must be regenerated
 * whenever it changes.** It is the published reference, and a reference that
 * disagrees with the code is worse than no reference.
 *
 * Timestamps (`created_at`, `updated_at`) are epoch **milliseconds**. Dates
 * (`spent_on`, `earned_on`, `next_due_day`, `last_run_day`) are epoch **days**.
 * The two are never interchangeable and never share a column.
 */
internal object Schema {

    const val VERSION = 1

    /**
     * Applied on every connection open, before any query (03 §4.1).
     *
     * `journal_mode` is absent because Room owns it — it is set through
     * `RoomDatabase.Builder.setJournalMode`, and issuing it here would fight
     * that. The rest cannot be expressed through Room's builder.
     *
     * `synchronous = NORMAL` under WAL survives an application crash intact and
     * risks only the last transaction in a full OS power loss — the right trade
     * for a personal ledger against a large write-latency reduction on eMMC.
     */
    val PRAGMAS: List<String> = listOf(
        "PRAGMA synchronous = NORMAL",
        "PRAGMA foreign_keys = ON",
        "PRAGMA temp_store = MEMORY",
        "PRAGMA cache_size = -2000", // 2 MB page cache; modest, for 2 GB devices
    )

    // ---------------------------------------------------------------- tables

    /**
     * Ordered parents-before-children so that creation and destruction are both
     * safe with `foreign_keys = ON`.
     */
    val TABLES: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS income_source (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
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
        """,
        """
        CREATE TABLE IF NOT EXISTS category (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
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
        """,
        """
        CREATE TABLE IF NOT EXISTS income_entry (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
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
        """,
        """
        CREATE TABLE IF NOT EXISTS budget (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            uuid         TEXT    NOT NULL UNIQUE,
            category_id  INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
            period_ym    INTEGER NOT NULL,
            limit_minor  INTEGER NOT NULL CHECK (limit_minor >= 0),
            created_at   INTEGER NOT NULL,
            updated_at   INTEGER NOT NULL
        )
        """,
        // amount_minor <> 0 rather than > 0: negative amounts are how refunds
        // are modelled (FR-EXP-06). Zero is rejected as meaningless.
        """
        CREATE TABLE IF NOT EXISTS expense (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
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
        """,
        // WITHOUT ROWID: always accessed by the full composite primary key, so
        // eliminating the rowid indirection cuts both storage and lookup cost.
        """
        CREATE TABLE IF NOT EXISTS rollup_expense_month (
            period_ym   INTEGER NOT NULL,
            category_id INTEGER NOT NULL,
            total_minor INTEGER NOT NULL DEFAULT 0,
            txn_count   INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (period_ym, category_id)
        ) WITHOUT ROWID
        """,
        """
        CREATE TABLE IF NOT EXISTS rollup_income_month (
            period_ym   INTEGER NOT NULL,
            source_id   INTEGER NOT NULL,
            total_minor INTEGER NOT NULL DEFAULT 0,
            entry_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (period_ym, source_id)
        ) WITHOUT ROWID
        """,
        // The table-level CHECK is the exclusive-or: a rule can never be both an
        // income and an expense template.
        """
        CREATE TABLE IF NOT EXISTS recurring_rule (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
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
        """,
        """
        CREATE TABLE IF NOT EXISTS app_meta (
            key        TEXT PRIMARY KEY,
            value      TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        ) WITHOUT ROWID
        """,
    ).map { it.trimIndent() }

    /** Reverse of [TABLES] — children before parents, so drops never violate a FK. */
    val DROP_TABLES: List<String> = listOf(
        "app_meta", "recurring_rule", "rollup_income_month", "rollup_expense_month",
        "expense", "budget", "income_entry", "category", "income_source",
    ).map { "DROP TABLE IF EXISTS $it" }

    // --------------------------------------------------------------- indices

    val INDICES: List<String> = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_income_source_key ON income_source(name_key)",
        "CREATE INDEX IF NOT EXISTS ix_income_source_active ON income_source(is_archived, sort_order)",

        "CREATE INDEX IF NOT EXISTS ix_income_entry_period ON income_entry(period_ym, source_id)",
        "CREATE INDEX IF NOT EXISTS ix_income_entry_date ON income_entry(earned_on DESC)",
        "CREATE INDEX IF NOT EXISTS ix_income_entry_source ON income_entry(source_id, period_ym)",

        // IFNULL(parent_id, -1) because SQL treats NULLs as distinct, which
        // would otherwise permit two root categories with the same name.
        // Scoping uniqueness to the parent is also what lets both
        // "Fixed → Misc" and "Variable → Misc" exist (FR-CAT-07).
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_category_parent_key ON category(IFNULL(parent_id, -1), name_key)",
        "CREATE INDEX IF NOT EXISTS ix_category_parent ON category(parent_id, is_archived, sort_order)",

        "CREATE UNIQUE INDEX IF NOT EXISTS ux_budget_cat_period ON budget(category_id, period_ym)",
        "CREATE INDEX IF NOT EXISTS ix_budget_period ON budget(period_ym)",

        // The `id DESC` tiebreaker gives the paged ledger a stable, fully
        // indexed sort key. Without it two expenses on the same day can shuffle
        // between pages while the user scrolls.
        "CREATE INDEX IF NOT EXISTS ix_expense_date ON expense(spent_on DESC, id DESC)",
        "CREATE INDEX IF NOT EXISTS ix_expense_period ON expense(period_ym, category_id)",
        "CREATE INDEX IF NOT EXISTS ix_expense_category ON expense(category_id, spent_on DESC)",
        "CREATE INDEX IF NOT EXISTS ix_expense_method ON expense(payment_method, period_ym)",

        "CREATE INDEX IF NOT EXISTS ix_rule_due ON recurring_rule(is_active, next_due_day)",
    )

    // -------------------------------------------------------------- triggers

    /**
     * Six of these are additions to what `docs/schema_v1.sql` originally
     * shipped; each is required by prose in 03-database-design.md that the SQL
     * file did not implement. The three income rollup triggers are the
     * consequential ones — without them `rollup_income_month` is created and
     * then never written to, so every income figure in the app reads ৳0 forever
     * while the ledger underneath is perfectly correct.
     */
    val TRIGGERS: List<String> = listOf(
        // --- category shape (03 §4.4) ---------------------------------------
        // A two-level cap cannot be a CHECK constraint, which may not contain
        // a subquery.
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_depth_insert
        BEFORE INSERT ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'category depth limited to two levels')
            WHERE (SELECT parent_id FROM category WHERE id = NEW.parent_id) IS NOT NULL;
        END
        """,
        // ADDED. 03 §4.4 states this trigger exists ("An equivalent BEFORE
        // UPDATE trigger prevents re-parenting…") but the SQL file omitted it.
        // Two ways a move creates a third level: the new parent already has a
        // parent, or this category has children of its own.
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_depth_update
        BEFORE UPDATE OF parent_id ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'category depth limited to two levels')
            WHERE (SELECT parent_id FROM category WHERE id = NEW.parent_id) IS NOT NULL
               OR EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.id);
        END
        """,
        // nature is denormalised onto the child so the hottest read path does
        // not need a join. The WHEN guard makes this idempotent, so it stays
        // correct even if recursive_triggers is ever turned on.
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_inherit_nature
        AFTER INSERT ON category
        WHEN NEW.parent_id IS NOT NULL
         AND NEW.nature <> (SELECT nature FROM category WHERE id = NEW.parent_id)
        BEGIN
            UPDATE category
               SET nature = (SELECT nature FROM category WHERE id = NEW.parent_id)
             WHERE id = NEW.id;
        END
        """,
        // ADDED. FR-CAT-06 requires that moving a subcategory to a different
        // root updates its effective nature; nothing in the SQL file did.
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_inherit_nature_upd
        AFTER UPDATE OF parent_id ON category
        WHEN NEW.parent_id IS NOT NULL
         AND NEW.nature <> (SELECT nature FROM category WHERE id = NEW.parent_id)
        BEGIN
            UPDATE category
               SET nature = (SELECT nature FROM category WHERE id = NEW.parent_id)
             WHERE id = NEW.id;
        END
        """,

        // --- leaf-only references (FR-BUD-03, FR-EXP-04) ---------------------
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_leaf_only
        BEFORE INSERT ON budget
        BEGIN
            SELECT RAISE(ABORT, 'budgets may only target leaf categories')
            WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
        END
        """,
        // ADDED — the insert guard alone can be walked around with an UPDATE.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_leaf_only_upd
        BEFORE UPDATE OF category_id ON budget
        BEGIN
            SELECT RAISE(ABORT, 'budgets may only target leaf categories')
            WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
        END
        """,
        // ADDED. 03 §4.6: "A trigger rejects expenses attached to non-leaf
        // categories, mirroring FR-EXP-04." The SQL file never defined it.
        """
        CREATE TRIGGER IF NOT EXISTS trg_expense_leaf_only
        BEFORE INSERT ON expense
        BEGIN
            SELECT RAISE(ABORT, 'expenses may only reference leaf categories')
            WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
        END
        """,
        // ADDED.
        """
        CREATE TRIGGER IF NOT EXISTS trg_expense_leaf_only_upd
        BEFORE UPDATE OF category_id ON expense
        BEGIN
            SELECT RAISE(ABORT, 'expenses may only reference leaf categories')
            WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
        END
        """,

        // --- expense rollups (03 §4.7) ---------------------------------------
        // Pending rows (status = 1) stay out of the rollups until confirmed, so
        // an unconfirmed recurring entry never distorts a budget bar.
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
        // Decrement the old bucket, then increment the new one. This is what
        // makes FR-EXP-07 — recalculating prior periods when an old expense is
        // re-categorised — correct by construction rather than by remembering
        // to handle it in application code.
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

        // --- income rollups --------------------------------------------------
        // ALL THREE ADDED. 03 §2 shows "rollup_income_month ← maintained by
        // trigger from income_entry" and §4.7 says maintenance is by trigger on
        // all three mutations, but schema_v1.sql defined none of them.
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

    val DROP_TRIGGERS: List<String> = listOf(
        "trg_category_depth_insert", "trg_category_depth_update",
        "trg_category_inherit_nature", "trg_category_inherit_nature_upd",
        "trg_budget_leaf_only", "trg_budget_leaf_only_upd",
        "trg_expense_leaf_only", "trg_expense_leaf_only_upd",
        "trg_rollup_exp_ins", "trg_rollup_exp_del", "trg_rollup_exp_upd",
        "trg_rollup_inc_ins", "trg_rollup_inc_del", "trg_rollup_inc_upd",
    ).map { "DROP TRIGGER IF EXISTS $it" }

    // ------------------------------------------------------------------ seed

    /**
     * A UUIDv4 built from `randomblob`, so seeding stays inside SQL and inside
     * the creation transaction (03 §7). Every entity carries a UUID beside its
     * integer key: the integer is fast and compact for joins, the UUID survives
     * export and re-import and makes deduplication on merge possible.
     */
    private const val UUID4 =
        "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || " +
            "substr(hex(randomblob(2)), 2) || '-' || " +
            "substr('89ab', abs(random()) % 4 + 1, 1) || " +
            "substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6)))"

    private const val NOW = "CAST(strftime('%s','now') AS INTEGER) * 1000"

    private fun root(name: String, key: String, nature: Int, sort: Int) = """
        INSERT INTO category (uuid, parent_id, name, name_key, nature, is_system, sort_order, created_at, updated_at)
        VALUES ($UUID4, NULL, '$name', '$key', $nature, 1, $sort, $NOW, $NOW)
    """.trimIndent()

    private fun child(parentKey: String, name: String, key: String, nature: Int, sort: Int) = """
        INSERT INTO category (uuid, parent_id, name, name_key, nature, is_system, sort_order, created_at, updated_at)
        VALUES ($UUID4,
                (SELECT id FROM category WHERE parent_id IS NULL AND name_key = '$parentKey'),
                '$name', '$key', $nature, 0, $sort, $NOW, $NOW)
    """.trimIndent()

    /**
     * FR-CAT-01 and FR-CAT-02. The subcategory set is the superset from
     * 03 §7, which satisfies the smaller minimum the SRS states.
     *
     * `name_key` values are written literally rather than computed with SQL's
     * `lower()`, for the same reason the application computes them on every
     * write: `lower()` is ASCII-only. These seeds happen to be ASCII, but
     * having two different normalisation rules in the system is how they drift.
     */
    val SEED: List<String> = buildList {
        add(root("Fixed Expenses", "fixed expenses", nature = 0, sort = 0))
        add(root("Variable Expenses", "variable expenses", nature = 1, sort = 1))
        add(root("Unpredictable Expenses", "unpredictable expenses", nature = 2, sort = 2))

        listOf("House Rent", "Utilities", "Internet", "Education Fees")
            .forEachIndexed { i, n -> add(child("fixed expenses", n, n.lowercase(), 0, i)) }

        listOf("Grocery", "Transport", "Mobile Recharge", "Dining Out", "Household")
            .forEachIndexed { i, n -> add(child("variable expenses", n, n.lowercase(), 1, i)) }

        listOf("Medical", "Gifts", "Repairs", "New Clothes")
            .forEachIndexed { i, n -> add(child("unpredictable expenses", n, n.lowercase(), 2, i)) }

        // One income source, because the first thing the user will do is record
        // a salary and an empty picker on first run is a needless obstacle.
        add(
            """
            INSERT INTO income_source (uuid, name, name_key, kind, sort_order, created_at, updated_at)
            VALUES ($UUID4, 'Salary', 'salary', 0, 0, $NOW, $NOW)
            """.trimIndent(),
        )

        add(
            """
            INSERT INTO app_meta (key, value, updated_at)
            VALUES ('schema_version', '$VERSION', $NOW)
            """.trimIndent(),
        )
    }

    // -------------------------------------------------------------- integrity

    /**
     * Truncate-and-regenerate for both rollup tables (03 §6).
     *
     * This is the user-invocable "rebuild aggregates" action in Settings and
     * the recovery path if a future migration bug corrupts derived data. It is
     * also the oracle for the most important of the nineteen assertions: a
     * rebuild must reproduce the trigger-maintained state exactly, which is the
     * invariant the whole aggregate strategy rests on.
     */
    val REBUILD_ROLLUPS: List<String> = listOf(
        "DELETE FROM rollup_expense_month",
        """
        INSERT INTO rollup_expense_month(period_ym, category_id, total_minor, txn_count)
        SELECT period_ym, category_id, SUM(amount_minor), COUNT(*)
          FROM expense WHERE status = 0
         GROUP BY period_ym, category_id
        """.trimIndent(),
        "DELETE FROM rollup_income_month",
        """
        INSERT INTO rollup_income_month(period_ym, source_id, total_minor, entry_count)
        SELECT period_ym, source_id, SUM(amount_minor), COUNT(*)
          FROM income_entry WHERE status = 0
         GROUP BY period_ym, source_id
        """.trimIndent(),
    )
}
