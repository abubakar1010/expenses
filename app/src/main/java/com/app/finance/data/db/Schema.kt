package com.app.finance.data.db

import com.app.finance.core.text.NameKey

/**
 * The canonical SQLite schema — version 3.
 *
 * This object always describes the **current** schema. What an upgrading
 * database had before it lives in [Migrations], frozen, and must stay there:
 * a migration that read from here would rewrite its own history every time
 * this file changed.
 *
 *  - **Version 2** declared the primary keys `NOT NULL`, which they always
 *    were. `id INTEGER PRIMARY KEY AUTOINCREMENT` reads back from
 *    `PRAGMA table_info` as `notnull = 0`, while the Room entities expect
 *    `notNull = true` — a mismatch that had been latent since M1 because Room
 *    only compares `TableInfo` *after a migration*, and there had never been
 *    one. See [Migrations.MIGRATION_1_2]; it carries no feature, on purpose.
 *  - **Version 3** added shared expenses (FR-SHR-*): `person`,
 *    `expense_share`, `settlement`, and `expense.payer_person_id`. Nothing
 *    existing changed meaning — `expense.amount_minor` is still *your* share
 *    and still the only thing the rollup triggers read, which is why not one
 *    of them was touched.
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

    const val VERSION = 3

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

    // ------------------------------------------------- version 2 (FR-SHR-*)

    /*
     * The v2 additions are named constants rather than literals inside
     * [TABLES], because both a fresh install and a migrated one have to end up
     * with byte-identical tables. Room's `TableInfo` validator compares the two
     * on the next open, so a difference between the creation path and the
     * migration path surfaces as a crash on somebody's phone rather than here.
     * One string, used twice, cannot drift.
     */

    /**
     * FR-SHR-01. Somebody you split a bill with.
     *
     * They never use this app — this is your record of a name, not an account,
     * which is why it carries no contact details and nothing that could leave
     * the device. `name_key` and its unique index are `category`'s exactly: one
     * Rahim however you capitalise him, so a balance cannot be split in two by
     * a typo.
     */
    private const val PERSON_TABLE = """
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
        """

    /**
     * FR-SHR-02. One other person's portion of a shared expense.
     *
     * Rows exist **only where `expense.payer_person_id IS NULL`** — only when
     * you paid, and so only when somebody owes you. If Rahim paid and three of
     * you split it, the other two owe *Rahim*; that is not your ledger and is
     * not stored. `trg_share_only_when_i_paid` enforces it from both sides.
     *
     * There is deliberately no total-bill column: the bill is
     * `expense.amount_minor + SUM(share_minor)`, so the parts *define* the
     * whole and a rounding leak cannot be represented.
     */
    private const val EXPENSE_SHARE_TABLE = """
        CREATE TABLE IF NOT EXISTS expense_share (
            id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            uuid        TEXT    NOT NULL UNIQUE,
            expense_id  INTEGER NOT NULL REFERENCES expense(id) ON DELETE RESTRICT,
            person_id   INTEGER NOT NULL REFERENCES person(id) ON DELETE RESTRICT,
            share_minor INTEGER NOT NULL CHECK (share_minor > 0),
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """

    /**
     * FR-SHR-04. Money between you and a person that is **not consumption** —
     * a repayment, or a loan made outright.
     *
     * Neither an expense nor an income entry, and read by no rollup trigger. A
     * friend settling up is your own money coming home; counting it as income
     * would lift the savings rate every time somebody paid you back.
     *
     * Signed: positive means they paid you, negative means you paid them. One
     * signed column rather than a direction flag, so the balance is a single
     * `SUM` that cannot disagree with itself.
     */
    private const val SETTLEMENT_TABLE = """
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
        """

    /**
     * The three tables version 3 introduced, in parents-before-children order.
     *
     * `trimIndent()` because [TABLES] applies it to every element, and these
     * have to be *equal* to their copies in there — anything comparing the two
     * lists otherwise finds no overlap and silently does nothing. That is not
     * hypothetical: `SchemaMigrationTest` builds its version-1 fixture as
     * `TABLES - SHARED_TABLES`, and without this it created the very tables it
     * was supposed to leave out, so the migration found them already present
     * and skipped past the shape it was meant to produce.
     */
    val SHARED_TABLES: List<String> =
        listOf(PERSON_TABLE, EXPENSE_SHARE_TABLE, SETTLEMENT_TABLE).map { it.trimIndent() }

    val SHARED_INDICES: List<String> = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_person_name_key ON person(name_key)",
        "CREATE INDEX IF NOT EXISTS ix_person_active ON person(is_archived, sort_order)",

        // One row per person per expense: splitting the same bill with the same
        // person twice is a mistake, not a second debt.
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_share_expense_person " +
            "ON expense_share(expense_id, person_id)",
        // The balance query's access path — every share owed by one person.
        "CREATE INDEX IF NOT EXISTS ix_share_person ON expense_share(person_id)",
        // The other half of the balance, and the ledger's person filter.
        "CREATE INDEX IF NOT EXISTS ix_expense_payer ON expense(payer_person_id)",

        "CREATE INDEX IF NOT EXISTS ix_settlement_person ON settlement(person_id, settled_on DESC)",
    )

    /**
     * A share row says "this person owes me their part of this", which is only
     * ever true when *you* paid. So a share may not sit on an expense somebody
     * else settled at the counter.
     *
     * Guarded from both sides, because either half alone leaves the rule
     * reachable: adding a share to a friend-paid expense, and marking an
     * already-shared expense as friend-paid, produce the same impossible row.
     * Left unguarded the balance would double-count — the share saying they owe
     * you while `payer_person_id` says you owe them.
     */
    val SHARED_TRIGGERS: List<String> = listOf(
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

    // ---------------------------------------------------------------- tables

    /**
     * Ordered parents-before-children so that creation and destruction are both
     * safe with `foreign_keys = ON`.
     */
    val TABLES: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS income_source (
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
        """,
        """
        CREATE TABLE IF NOT EXISTS category (
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
        """,
        PERSON_TABLE,
        """
        CREATE TABLE IF NOT EXISTS income_entry (
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
        """,
        """
        CREATE TABLE IF NOT EXISTS budget (
            id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
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
            id             INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            uuid           TEXT    NOT NULL UNIQUE,
            category_id    INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
            amount_minor   INTEGER NOT NULL CHECK (amount_minor <> 0),
            spent_on       INTEGER NOT NULL,
            period_ym      INTEGER NOT NULL,
            payment_method INTEGER NOT NULL DEFAULT 0 CHECK (payment_method IN (0,1,2,3,4,5)),
            note           TEXT,
            status         INTEGER NOT NULL DEFAULT 0 CHECK (status IN (0,1)),
            payer_person_id INTEGER REFERENCES person(id) ON DELETE RESTRICT,
            created_at     INTEGER NOT NULL,
            updated_at     INTEGER NOT NULL
        )
        """,
        EXPENSE_SHARE_TABLE,
        SETTLEMENT_TABLE,
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
        """,
        """
        CREATE TABLE IF NOT EXISTS app_meta (
            key        TEXT PRIMARY KEY,
            value      TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        ) WITHOUT ROWID
        """,
    ).map { it.trimIndent() }

    /**
     * Every table's contents, children before parents — what a replace-import
     * and "delete all data" both clear.
     *
     * The same ordering [DROP_TABLES] uses and for the same reason: every
     * reference is `ON DELETE RESTRICT`, so a parent deleted before its children
     * fails. Declared once because two copies of a nine-element ordering is how
     * a table added later gets cleared by one caller and left behind by the
     * other.
     *
     * **Complete statements rather than table names**, which is what §20.1 cost
     * to learn. The rule above is about ordering *between* tables and this list
     * got that right; `category` fails it *within* one table, because it is the
     * only table whose foreign key points at itself. Nothing that reads as a
     * list of table names can express "children first" for that case, so the
     * list stopped being one.
     *
     * The rollups lead: they are derived, both callers regenerate or re-seed
     * them afterwards, and emptying them first means the delete triggers have
     * nothing left to decrement.
     */
    val WIPE_ORDER: List<String> = listOf(
        "DELETE FROM rollup_expense_month",
        "DELETE FROM rollup_income_month",
        "DELETE FROM recurring_rule",
        // Both reference `person`, and `expense_share` also references
        // `expense`, so both must go before either parent.
        "DELETE FROM settlement",
        "DELETE FROM expense_share",
        "DELETE FROM expense",
        "DELETE FROM budget",
        "DELETE FROM income_entry",
        // `category` takes two statements, not one, because it is the only
        // table that references *itself*: `parent_id REFERENCES category(id)
        // ON DELETE RESTRICT`. A single `DELETE FROM category` walks the table
        // in rowid order and reaches a root while its children still point at
        // it, and RESTRICT fires. Two levels is all that is needed, and the
        // depth trigger is what guarantees it: no category may sit under a
        // category that already has a parent, so clearing the rows that have a
        // parent leaves only roots behind.
        "DELETE FROM category WHERE parent_id IS NOT NULL",
        "DELETE FROM category",
        "DELETE FROM income_source",
        // After `expense`, `expense_share` and `settlement`, all of which
        // reference it.
        "DELETE FROM person",
        "DELETE FROM app_meta",
    )

    /** Reverse of [TABLES] — children before parents, so drops never violate a FK. */
    val DROP_TABLES: List<String> = listOf(
        "app_meta", "recurring_rule", "rollup_income_month", "rollup_expense_month",
        "settlement", "expense_share",
        "expense", "budget", "income_entry", "category", "income_source",
        "person",
    ).map { "DROP TABLE IF EXISTS $it" }

    // --------------------------------------------------------------- indices

    val INDICES: List<String> = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_income_source_key ON income_source(name_key)",
        "CREATE INDEX IF NOT EXISTS ix_income_source_active ON income_source(is_archived, sort_order)",

        "CREATE INDEX IF NOT EXISTS ix_income_entry_period ON income_entry(period_ym, source_id)",
        "CREATE INDEX IF NOT EXISTS ix_income_entry_date ON income_entry(earned_on DESC)",
        "CREATE INDEX IF NOT EXISTS ix_income_entry_source ON income_entry(source_id, period_ym)",

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
    ) + SHARED_INDICES

    /**
     * The one index Room must never see — and the reason it is separate.
     *
     * `IFNULL(parent_id, -1)` is what makes uniqueness scope to the parent
     * while still catching two roots of the same name, because SQL treats NULLs
     * as distinct (FR-CAT-07). `@Index` cannot express a functional index, so
     * [com.app.finance.data.db.entity.CategoryEntity] deliberately does not
     * declare it.
     *
     * That was harmless while nothing compared the two views. **Room compares
     * every index after a migration** — and only after a migration — and it
     * rejects an index it did not expect just as firmly as a missing one. So an
     * index the entities cannot describe makes *every* migration fail on
     * `category`, whatever the migration was for.
     *
     * The resolution keeps it genuinely invisible: created by [CanonicalSchema]
     * on first creation and re-created on every open, never by a migration. At
     * the moment Room validates, `category` carries only the indices its entity
     * declares; a few statements later the constraint is back. The gap is
     * inside one open, before any write can reach the table.
     *
     * `IF NOT EXISTS` makes the per-open cost a `sqlite_master` lookup, which
     * is the price of the one thing this schema does that Room cannot see.
     */
    val ROOM_INVISIBLE_INDICES: List<String> = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_category_parent_key " +
            "ON category(IFNULL(parent_id, -1), name_key)",
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
        // Three ways a move breaks the tree: the new parent already has a
        // parent, this category has children of its own, or it is made its own
        // parent.
        //
        // The third was reachable. At `BEFORE UPDATE` the row still holds its
        // OLD values, so setting `parent_id = id` on a childless root read that
        // root's own `parent_id` — NULL — and passed the first clause; the
        // second found no children and passed; and the foreign key is satisfied
        // by a self-reference. The row became neither root nor leaf: absent
        // from `roots()`, excluded from `observeSelectableLeaves` because it is
        // its own child, and enough to make `WIPE_ORDER`'s two-statement
        // category delete fail with RESTRICT. Nothing re-parents today, but
        // this trigger is the stated defence and it did not hold.
        """
        CREATE TRIGGER IF NOT EXISTS trg_category_depth_update
        BEFORE UPDATE OF parent_id ON category
        WHEN NEW.parent_id IS NOT NULL
        BEGIN
            SELECT RAISE(ABORT, 'category depth limited to two levels')
            WHERE NEW.parent_id = NEW.id
               OR (SELECT parent_id FROM category WHERE id = NEW.parent_id) IS NOT NULL
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

        // ADDED. The leaf-only rule, defended from the *tree* side.
        //
        // Everything above guards the reference: an expense or a budget may not
        // point at a category that has children. Nothing guarded the reverse —
        // giving a child to a category that already carries expenses or
        // budgets, which turns those existing rows into references to a
        // non-leaf after the fact.
        //
        // `CategoryRepository.createSubcategory` closes the in-app path, but
        // `Importer` inserts categories through `BackupDao` with no such check,
        // and a merge from another phone can legitimately add a child under a
        // category that is a spent-into leaf here. The rows then vanish from
        // `observeBudgetBars` while staying in the rollup total — the two
        // become computable to different answers — and any recurring rule
        // targeting the category aborts generation for every rule in that
        // transaction.
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
        // ADDED — the same rule, for a move that re-parents onto a used leaf.
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
    ).map { it.trimIndent() } + SHARED_TRIGGERS

    val DROP_TRIGGERS: List<String> = listOf(
        "trg_category_depth_insert", "trg_category_depth_update",
        "trg_category_inherit_nature", "trg_category_inherit_nature_upd",
        "trg_budget_leaf_only", "trg_budget_leaf_only_upd",
        "trg_expense_leaf_only", "trg_expense_leaf_only_upd",
        "trg_category_child_of_used_leaf", "trg_category_child_of_used_leaf_upd",
        "trg_rollup_exp_ins", "trg_rollup_exp_del", "trg_rollup_exp_upd",
        "trg_rollup_inc_ins", "trg_rollup_inc_del", "trg_rollup_inc_upd",
        "trg_share_only_when_i_paid", "trg_share_only_when_i_paid_upd",
        "trg_payer_excludes_shares",
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

    /**
     * Seed timestamps must be on the same basis as everything the application
     * later writes, which is `Clock.millis()` — epoch milliseconds, UTC-based.
     * `strftime('%s','now')` is also UTC, so the two agree; the multiplication
     * is what converts SQLite's seconds to the milliseconds `created_at`
     * carries everywhere else. Sub-second precision is not meaningful for a
     * seed, and is not claimed.
     */
    private const val NOW = "CAST(strftime('%s','now') AS INTEGER) * 1000"

    private fun root(name: String, nature: Int, sort: Int) = """
        INSERT INTO category (uuid, parent_id, name, name_key, nature, is_system, sort_order, created_at, updated_at)
        VALUES ($UUID4, NULL, '$name', '${NameKey.of(name)}', $nature, 1, $sort, $NOW, $NOW)
    """.trimIndent()

    private fun child(parentName: String, name: String, nature: Int, sort: Int) = """
        INSERT INTO category (uuid, parent_id, name, name_key, nature, is_system, sort_order, created_at, updated_at)
        VALUES ($UUID4,
                (SELECT id FROM category WHERE parent_id IS NULL AND name_key = '${NameKey.of(parentName)}'),
                '$name', '${NameKey.of(name)}', $nature, 0, $sort, $NOW, $NOW)
    """.trimIndent()

    /**
     * FR-CAT-01 and FR-CAT-02. The subcategory set is the superset from
     * 03 §7, which satisfies the smaller minimum the SRS states.
     *
     * `name_key` comes from [NameKey.of] — the same function every write path
     * uses — rather than from SQL's `lower()` or from Kotlin's.
     *
     * SQL's `lower()` is ASCII-only, which is the reason originally recorded
     * here. But the keys were then folded with Kotlin's default-locale
     * `lowercase()`, which is a *third* rule and a locale-sensitive one: on a
     * Turkish or Azerbaijani device `"Internet".lowercase()` is `ınternet`, so
     * the seeded leaf carried a key no write path would ever produce.
     * `ux_category_parent_key` then stopped guarding it, a second "Internet"
     * was accepted, and merge-import's natural-key match (§18.1) missed it and
     * rolled the whole import back.
     *
     * `NameKeyTest` proves `NameKey.of` does not depend on the device locale.
     * This is the other half: one rule, used everywhere, including here.
     * Having two different normalisation rules in the system is how they drift,
     * and this file said so while holding the third.
     */
    val SEED: List<String> = buildList {
        add(root("Fixed Expenses", nature = 0, sort = 0))
        add(root("Variable Expenses", nature = 1, sort = 1))
        add(root("Unpredictable Expenses", nature = 2, sort = 2))

        listOf("House Rent", "Utilities", "Internet", "Education Fees")
            .forEachIndexed { i, n -> add(child("Fixed Expenses", n, 0, i)) }

        listOf("Grocery", "Transport", "Mobile Recharge", "Dining Out", "Household")
            .forEachIndexed { i, n -> add(child("Variable Expenses", n, 1, i)) }

        listOf("Medical", "Gifts", "Repairs", "New Clothes")
            .forEachIndexed { i, n -> add(child("Unpredictable Expenses", n, 2, i)) }

        // One income source, because the first thing the user will do is record
        // a salary and an empty picker on first run is a needless obstacle.
        add(
            """
            INSERT INTO income_source (uuid, name, name_key, kind, sort_order, created_at, updated_at)
            VALUES ($UUID4, 'Salary', '${NameKey.of("Salary")}', 0, 0, $NOW, $NOW)
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
