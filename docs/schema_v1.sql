-- Khata — canonical SQLite schema, version 3.
--
-- Generated from app/src/main/java/com/app/finance/data/db/Schema.kt, which is
-- what actually creates the database at runtime. Regenerate both together.
--
-- Verified by SchemaAssertionsTest and SchemaValidationTest (31 instrumented
-- assertions, including the nineteen recorded in 03-database-design.md §10.1).
--
-- CHANGES FROM THE ORIGINAL DRAFT OF THIS FILE
-- The draft omitted several objects that 03-database-design.md describes in
-- prose. Each addition is marked [ADDED] below. The consequential one is the
-- income rollup trigger set: without it rollup_income_month was created and
-- then never written to, so every income figure in the app would read zero
-- while the ledger underneath stayed perfectly correct.
--   [ADDED] trg_rollup_inc_ins / _del / _upd   (§2 diagram, §4.7)
--   [ADDED] trg_expense_leaf_only / _upd       (§4.6, FR-EXP-04)
--   [ADDED] trg_category_depth_update          (§4.4)
--   [ADDED] trg_category_inherit_nature_upd    (§4.4, FR-CAT-06)
--   [ADDED] trg_budget_leaf_only_upd           (§4.5)
--   [ADDED] trg_category_child_of_used_leaf     (§22 — the tree side of §4.5)
--   [ADDED] trg_category_child_of_used_leaf_upd (§22)
--   [ADDED] the full PRAGMA block              (§4.1 — only foreign_keys was present)
--   [ADDED] seed data                          (§7)
--   [ADDED] app_meta schema_version row        (§4.9)
--
-- CONVENTIONS
--   Money      INTEGER paisa. Never REAL, never TEXT. ৳1,250.75 -> 125075.
--   Dates      INTEGER epoch days (spent_on, earned_on, next_due_day, last_run_day).
--   Timestamps INTEGER epoch milliseconds (created_at, updated_at).
--   Periods    INTEGER YYYYMM (period_ym). August 2026 -> 202608.
--   Booleans   INTEGER 0/1, policed by CHECK.
--
-- ENUMERATIONS (§3)
--   income_kind     0 Stable, 1 Variable
--   nature          0 Fixed, 1 Variable, 2 Unpredictable
--   payment_method  0 Cash, 1 bKash, 2 Nagad, 3 Bank, 4 Card, 5 Other
--   frequency       0 Monthly, 1 Weekly, 2 Yearly
--   rule_target     0 Expense, 1 Income
--   entry_status    0 Posted, 1 Pending confirmation

-- ============================================================ pragmas (§4.1)
-- Applied on every connection open, before any query. journal_mode is set by
-- the Room builder rather than here, so the two do not fight over ownership.

PRAGMA journal_mode = WAL;      -- concurrent reads during writes; fewer fsyncs
PRAGMA synchronous  = NORMAL;   -- safe under WAL; materially faster on eMMC
PRAGMA foreign_keys = ON;       -- off by default in SQLite
PRAGMA temp_store   = MEMORY;
PRAGMA cache_size   = -2000;    -- 2 MB page cache, modest for 2 GB devices

-- ============================================================= tables

CREATE TABLE income_source (
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
);

-- name_key is the normalised name: NFC, whitespace collapsed, case folded with
-- Locale.ROOT. Computed by the application on write, never by SQL, because
-- SQLite's LOWER() is ASCII-only and would mishandle Bengali source names.
CREATE UNIQUE INDEX ux_income_source_key ON income_source(name_key);
CREATE INDEX ix_income_source_active ON income_source(is_archived, sort_order);

CREATE TABLE category (
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
);

-- IFNULL(parent_id, -1) because SQL treats NULLs as distinct, which would
-- otherwise allow two root categories with the same name. Scoping uniqueness to
-- the parent is also what permits both "Fixed -> Misc" and "Variable -> Misc"
-- to coexist (FR-CAT-07).
CREATE UNIQUE INDEX ux_category_parent_key ON category(IFNULL(parent_id, -1), name_key);
CREATE INDEX ix_category_parent ON category(parent_id, is_archived, sort_order);

-- Somebody you split expenses with (FR-SHR-01). They never use this app; this
-- is your private note of a name, which is why it carries no contact details.
-- name_key and its unique index are category's, reused: one Rahim however you
-- capitalise him, so a balance cannot be split in two by a typo.
CREATE TABLE person (
    id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    uuid        TEXT    NOT NULL UNIQUE,
    name        TEXT    NOT NULL,
    name_key    TEXT    NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    is_archived INTEGER NOT NULL DEFAULT 0 CHECK (is_archived IN (0,1)),
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE UNIQUE INDEX ux_person_name_key ON person(name_key);
CREATE INDEX ix_person_active ON person(is_archived, sort_order);

CREATE TABLE income_entry (
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
);

CREATE INDEX ix_income_entry_period ON income_entry(period_ym, source_id);
CREATE INDEX ix_income_entry_date   ON income_entry(earned_on DESC);
CREATE INDEX ix_income_entry_source ON income_entry(source_id, period_ym);

CREATE TABLE budget (
    id           INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    uuid         TEXT    NOT NULL UNIQUE,
    category_id  INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    period_ym    INTEGER NOT NULL,
    limit_minor  INTEGER NOT NULL CHECK (limit_minor >= 0),
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
);

-- Enforces FR-BUD-02 at the storage layer. Root-level limits are never stored;
-- they are SUM(limit_minor) over children at query time.
CREATE UNIQUE INDEX ux_budget_cat_period ON budget(category_id, period_ym);
CREATE INDEX ix_budget_period ON budget(period_ym);

-- amount_minor <> 0 rather than > 0: negative amounts are how refunds are
-- modelled (FR-EXP-06). Zero is rejected as meaningless.
CREATE TABLE expense (
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
);

-- The `id DESC` tiebreaker gives the paged ledger a stable, fully indexed sort
-- key; without it two expenses on the same day shuffle between pages mid-scroll.
CREATE INDEX ix_expense_date     ON expense(spent_on DESC, id DESC);
CREATE INDEX ix_expense_period   ON expense(period_ym, category_id);
CREATE INDEX ix_expense_category ON expense(category_id, spent_on DESC);
CREATE INDEX ix_expense_method   ON expense(payment_method, period_ym);
CREATE INDEX ix_expense_payer    ON expense(payer_person_id);

-- One other person's portion of a shared expense (FR-SHR-02).
--
-- Rows exist ONLY where expense.payer_person_id IS NULL — only when you paid,
-- and so only when somebody owes you. If a friend paid and three of you split
-- it, the other two owe them, not you; that is not your ledger and is not
-- stored. trg_share_only_when_i_paid enforces it from both sides.
--
-- There is deliberately no total-bill column anywhere: the bill is
-- expense.amount_minor + SUM(share_minor), so the parts define the whole and a
-- rounding leak cannot be represented.
CREATE TABLE expense_share (
    id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    uuid        TEXT    NOT NULL UNIQUE,
    expense_id  INTEGER NOT NULL REFERENCES expense(id) ON DELETE RESTRICT,
    person_id   INTEGER NOT NULL REFERENCES person(id) ON DELETE RESTRICT,
    share_minor INTEGER NOT NULL CHECK (share_minor > 0),
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE UNIQUE INDEX ux_share_expense_person ON expense_share(expense_id, person_id);
CREATE INDEX ix_share_person ON expense_share(person_id);

-- Money between you and a person that is NOT consumption (FR-SHR-04): a
-- repayment, or a loan made outright.
--
-- Neither an expense nor an income entry, and read by no rollup trigger. A
-- friend settling up is your own money coming home; counting it as income would
-- lift the savings rate every time somebody paid you back.
--
-- Signed: positive means they paid you, negative means you paid them. One
-- signed column rather than a direction flag, so the balance is a single SUM
-- that cannot disagree with itself.
CREATE TABLE settlement (
    id             INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    uuid           TEXT    NOT NULL UNIQUE,
    person_id      INTEGER NOT NULL REFERENCES person(id) ON DELETE RESTRICT,
    amount_minor   INTEGER NOT NULL CHECK (amount_minor <> 0),
    settled_on     INTEGER NOT NULL,
    payment_method INTEGER NOT NULL DEFAULT 0 CHECK (payment_method IN (0,1,2,3,4,5)),
    note           TEXT,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE INDEX ix_settlement_person ON settlement(person_id, settled_on DESC);

-- Derived data, written only by trigger. WITHOUT ROWID because these are always
-- accessed by the full composite primary key, so the rowid indirection is pure
-- overhead in both storage and lookup.
CREATE TABLE rollup_expense_month (
    period_ym   INTEGER NOT NULL,
    category_id INTEGER NOT NULL,
    total_minor INTEGER NOT NULL DEFAULT 0,
    txn_count   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (period_ym, category_id)
) WITHOUT ROWID;

CREATE TABLE rollup_income_month (
    period_ym   INTEGER NOT NULL,
    source_id   INTEGER NOT NULL,
    total_minor INTEGER NOT NULL DEFAULT 0,
    entry_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (period_ym, source_id)
) WITHOUT ROWID;

-- The table-level CHECK is the exclusive-or between the two target types, so a
-- rule can never be simultaneously an income and an expense template.
CREATE TABLE recurring_rule (
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
);

CREATE INDEX ix_rule_due ON recurring_rule(is_active, next_due_day);

CREATE TABLE app_meta (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL
) WITHOUT ROWID;

-- ============================================== category shape triggers (§4.4)
-- A two-level cap cannot be expressed as a CHECK constraint, which may not
-- contain a subquery.

CREATE TRIGGER trg_category_depth_insert
BEFORE INSERT ON category
WHEN NEW.parent_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'category depth limited to two levels')
    WHERE (SELECT parent_id FROM category WHERE id = NEW.parent_id) IS NOT NULL;
END;

-- [ADDED] Two ways a move creates a third level: the new parent already has a
-- parent, or this category has children of its own.
CREATE TRIGGER trg_category_depth_update
BEFORE UPDATE OF parent_id ON category
WHEN NEW.parent_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'category depth limited to two levels')
    WHERE NEW.parent_id = NEW.id
       OR (SELECT parent_id FROM category WHERE id = NEW.parent_id) IS NOT NULL
       OR EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.id);
END;

-- nature is denormalised onto the child to keep a join off the hottest read
-- path. The WHEN guard makes the trigger idempotent, so it stays correct even
-- if recursive_triggers is ever enabled.
CREATE TRIGGER trg_category_inherit_nature
AFTER INSERT ON category
WHEN NEW.parent_id IS NOT NULL
 AND NEW.nature <> (SELECT nature FROM category WHERE id = NEW.parent_id)
BEGIN
    UPDATE category
       SET nature = (SELECT nature FROM category WHERE id = NEW.parent_id)
     WHERE id = NEW.id;
END;

-- [ADDED] FR-CAT-06 requires that moving a subcategory to a different root
-- updates its effective nature.
CREATE TRIGGER trg_category_inherit_nature_upd
AFTER UPDATE OF parent_id ON category
WHEN NEW.parent_id IS NOT NULL
 AND NEW.nature <> (SELECT nature FROM category WHERE id = NEW.parent_id)
BEGIN
    UPDATE category
       SET nature = (SELECT nature FROM category WHERE id = NEW.parent_id)
     WHERE id = NEW.id;
END;

-- ================================================ leaf-only reference triggers

CREATE TRIGGER trg_budget_leaf_only
BEFORE INSERT ON budget
BEGIN
    SELECT RAISE(ABORT, 'budgets may only target leaf categories')
    WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
END;

-- [ADDED] The insert guard alone can be walked around with an UPDATE.
CREATE TRIGGER trg_budget_leaf_only_upd
BEFORE UPDATE OF category_id ON budget
BEGIN
    SELECT RAISE(ABORT, 'budgets may only target leaf categories')
    WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
END;

-- [ADDED] §4.6 states this trigger exists, mirroring FR-EXP-04.
CREATE TRIGGER trg_expense_leaf_only
BEFORE INSERT ON expense
BEGIN
    SELECT RAISE(ABORT, 'expenses may only reference leaf categories')
    WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
END;

-- [ADDED]
CREATE TRIGGER trg_expense_leaf_only_upd
BEFORE UPDATE OF category_id ON expense
BEGIN
    SELECT RAISE(ABORT, 'expenses may only reference leaf categories')
    WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
END;

-- [ADDED] The leaf-only rule, defended from the tree side as well as the
-- reference side: nothing stopped a category that already carries expenses or
-- budgets from being given a child, which turns those rows into references to a
-- non-leaf after the fact. Importer reaches this path.

CREATE TRIGGER trg_category_child_of_used_leaf
BEFORE INSERT ON category
WHEN NEW.parent_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'a category with expenses or budgets may not gain a child')
    WHERE EXISTS (SELECT 1 FROM expense WHERE category_id = NEW.parent_id)
       OR EXISTS (SELECT 1 FROM budget  WHERE category_id = NEW.parent_id);
END;

CREATE TRIGGER trg_category_child_of_used_leaf_upd
BEFORE UPDATE OF parent_id ON category
WHEN NEW.parent_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'a category with expenses or budgets may not gain a child')
    WHERE EXISTS (SELECT 1 FROM expense WHERE category_id = NEW.parent_id)
       OR EXISTS (SELECT 1 FROM budget  WHERE category_id = NEW.parent_id);
END;

-- ================================================== expense rollups (§4.7)
-- Pending rows (status = 1) stay out of the rollups until confirmed, so an
-- unconfirmed recurring entry never distorts a budget bar.

CREATE TRIGGER trg_rollup_exp_ins
AFTER INSERT ON expense
WHEN NEW.status = 0
BEGIN
    INSERT INTO rollup_expense_month(period_ym, category_id, total_minor, txn_count)
    VALUES (NEW.period_ym, NEW.category_id, NEW.amount_minor, 1)
    ON CONFLICT(period_ym, category_id) DO UPDATE SET
        total_minor = total_minor + NEW.amount_minor,
        txn_count   = txn_count + 1;
END;

CREATE TRIGGER trg_rollup_exp_del
AFTER DELETE ON expense
WHEN OLD.status = 0
BEGIN
    UPDATE rollup_expense_month
       SET total_minor = total_minor - OLD.amount_minor,
           txn_count   = txn_count - 1
     WHERE period_ym = OLD.period_ym AND category_id = OLD.category_id;
END;

-- Decrement the old bucket, then increment the new one. This is what makes
-- FR-EXP-07 -- recalculating prior periods when an old expense is re-categorised
-- -- correct by construction rather than by remembering to handle it in code.
CREATE TRIGGER trg_rollup_exp_upd
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
END;

-- =================================================== income rollups [ALL ADDED]
-- §2's diagram shows "rollup_income_month <- maintained by trigger from
-- income_entry" and §4.7 says maintenance is by trigger on all three mutations,
-- but the draft of this file defined none of them.

CREATE TRIGGER trg_rollup_inc_ins
AFTER INSERT ON income_entry
WHEN NEW.status = 0
BEGIN
    INSERT INTO rollup_income_month(period_ym, source_id, total_minor, entry_count)
    VALUES (NEW.period_ym, NEW.source_id, NEW.amount_minor, 1)
    ON CONFLICT(period_ym, source_id) DO UPDATE SET
        total_minor = total_minor + NEW.amount_minor,
        entry_count = entry_count + 1;
END;

CREATE TRIGGER trg_rollup_inc_del
AFTER DELETE ON income_entry
WHEN OLD.status = 0
BEGIN
    UPDATE rollup_income_month
       SET total_minor = total_minor - OLD.amount_minor,
           entry_count = entry_count - 1
     WHERE period_ym = OLD.period_ym AND source_id = OLD.source_id;
END;

CREATE TRIGGER trg_rollup_inc_upd
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
END;

-- ====================================== shared-expense guards [v2] (FR-SHR-02)
-- A share row says "this person owes me their part of this", which is only ever
-- true when YOU paid. So a share may not sit on an expense somebody else
-- settled at the counter.
--
-- Guarded from both sides, because either half alone leaves the rule reachable:
-- adding a share to a friend-paid expense, and marking an already-shared
-- expense as friend-paid, produce the same impossible row. Left unguarded the
-- balance would double-count -- the share saying they owe you while
-- payer_person_id says you owe them.
CREATE TRIGGER trg_share_only_when_i_paid
BEFORE INSERT ON expense_share
BEGIN
    SELECT RAISE(ABORT, 'a share may only be recorded on an expense you paid')
    WHERE (SELECT payer_person_id FROM expense WHERE id = NEW.expense_id) IS NOT NULL;
END;

CREATE TRIGGER trg_share_only_when_i_paid_upd
BEFORE UPDATE OF expense_id ON expense_share
BEGIN
    SELECT RAISE(ABORT, 'a share may only be recorded on an expense you paid')
    WHERE (SELECT payer_person_id FROM expense WHERE id = NEW.expense_id) IS NOT NULL;
END;

CREATE TRIGGER trg_payer_excludes_shares
BEFORE UPDATE OF payer_person_id ON expense
WHEN NEW.payer_person_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'an expense with shares was not paid by someone else')
    WHERE EXISTS (SELECT 1 FROM expense_share WHERE expense_id = NEW.id);
END;

-- ================================================== seed data [ADDED] (§7)
-- Inserted in the same transaction as schema creation, so there is no
-- observable state in which the app has a schema but nothing to spend against.
-- FR-CAT-01, FR-CAT-02.
--
-- At runtime the uuid columns are filled with a randomblob-derived UUIDv4 and
-- the timestamps with the current epoch millisecond; literals are shown here
-- for readability.

INSERT INTO category (uuid, parent_id, name, name_key, nature, is_system, sort_order, created_at, updated_at) VALUES
    ('<uuid>', NULL, 'Fixed Expenses',         'fixed expenses',         0, 1, 0, <now>, <now>),
    ('<uuid>', NULL, 'Variable Expenses',      'variable expenses',      1, 1, 1, <now>, <now>),
    ('<uuid>', NULL, 'Unpredictable Expenses', 'unpredictable expenses', 2, 1, 2, <now>, <now>);

-- Children are inserted with their parent resolved by name_key. nature is
-- copied from the parent by trg_category_inherit_nature regardless of the value
-- supplied, since FR-CAT-06 forbids a subcategory overriding it.
--   Fixed Expenses         -> House Rent, Utilities, Internet, Education Fees
--   Variable Expenses      -> Grocery, Transport, Mobile Recharge, Dining Out, Household
--   Unpredictable Expenses -> Medical, Gifts, Repairs, New Clothes

-- One income source, because the first thing the user will do is record a
-- salary and an empty picker on first run is a needless obstacle.
INSERT INTO income_source (uuid, name, name_key, kind, sort_order, created_at, updated_at) VALUES
    ('<uuid>', 'Salary', 'salary', 0, 0, <now>, <now>);

INSERT INTO app_meta (key, value, updated_at) VALUES ('schema_version', '3', <now>);

-- ============================================ integrity and repair (§6)
-- The user-invocable "rebuild aggregates" action in Settings, and the recovery
-- path if a future migration bug corrupts derived data. Rollup tables are
-- derived and may be dropped and regenerated by any migration.
--
-- This is also the oracle for the most important assertion in §10.1: a rebuild
-- must reproduce the trigger-maintained state exactly, which is the invariant
-- the whole aggregate strategy rests on.

-- DELETE FROM rollup_expense_month;
-- INSERT INTO rollup_expense_month(period_ym, category_id, total_minor, txn_count)
-- SELECT period_ym, category_id, SUM(amount_minor), COUNT(*)
--   FROM expense WHERE status = 0
--  GROUP BY period_ym, category_id;
--
-- DELETE FROM rollup_income_month;
-- INSERT INTO rollup_income_month(period_ym, source_id, total_minor, entry_count)
-- SELECT period_ym, source_id, SUM(amount_minor), COUNT(*)
--   FROM income_entry WHERE status = 0
--  GROUP BY period_ym, source_id;
