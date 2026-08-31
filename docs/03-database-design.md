# Database Design
**Engine:** SQLite (platform-bundled), accessed through Room
**Schema version:** 1

---

## 1. Design principles

Five decisions drive the whole schema. Each is a direct consequence of a stated requirement.

**Money is `INTEGER` paisa.** Never `REAL`, never `TEXT`. Floating-point accumulates rounding error across thousands of rows, and a personal finance app whose totals drift by a few paisa is worthless. ৳1,250.75 is stored as `125075`. Formatting to two decimals happens only at the presentation layer.

**Dates are `INTEGER` epoch days, with a denormalised `period_ym`.** Filtering "this month" against a string date requires `strftime()` on every row, which defeats indexing. Storing `202608` as an integer alongside the day makes every monthly query a plain indexed equality test — the single highest-leverage decision for dashboard latency.

**Rollups are maintained by triggers, not application code.** The dashboard cannot afford to scan 20,000 rows on a Cortex-A53. It reads precomputed per-period totals instead. Triggers guarantee those totals stay correct regardless of which code path performed the write — including bulk import and manual repair.

**Archive, never delete.** Foreign keys are `ON DELETE RESTRICT` for anything a transaction references. A deleted category silently rewrites history; an archived one preserves it.

**Every entity carries a UUID beside its integer key.** The integer key is fast and compact for joins. The UUID survives export and re-import, making deduplication on merge possible.

---

## 2. Entity relationship overview

```
income_source ──< income_entry
                       │
category ──< category  │        (self-reference, max depth 2)
   │  │                │
   │  └──< budget      │
   └──< expense        │
                       │
recurring_rule ────────┘  (templates targeting either side)

rollup_expense_month   ← maintained by trigger from expense
rollup_income_month    ← maintained by trigger from income_entry
app_meta               (key/value: schema version, prefs, last-used defaults)
```

Cardinalities:

| Relationship | Type | Delete rule |
|---|---|---|
| income_source → income_entry | 1 : N | RESTRICT |
| category → category (parent) | 1 : N, depth ≤ 2 | RESTRICT |
| category → budget | 1 : N (one per period) | RESTRICT |
| category → expense | 1 : N | RESTRICT |
| category → recurring_rule | 1 : N | RESTRICT |
| income_source → recurring_rule | 1 : N | RESTRICT |

---

## 3. Enumerations

Stored as `INTEGER`, not text — smaller rows, faster comparisons, no collation concerns.

| Enum | Values |
|---|---|
| `income_kind` | 0 = Stable, 1 = Variable |
| `nature` | 0 = Fixed, 1 = Variable, 2 = Unpredictable |
| `payment_method` | 0 = Cash, 1 = bKash, 2 = Nagad, 3 = Bank, 4 = Card, 5 = Other |
| `frequency` | 0 = Monthly, 1 = Weekly, 2 = Yearly |
| `rule_target` | 0 = Expense, 1 = Income |
| `entry_status` | 0 = Posted, 1 = Pending confirmation |

---

## 4. Schema definition

### 4.1 Pragmas

Applied on every connection open, before any query:

```sql
PRAGMA journal_mode = WAL;      -- concurrent reads during writes; fewer fsyncs
PRAGMA synchronous  = NORMAL;   -- safe under WAL; materially faster on eMMC
PRAGMA foreign_keys = ON;       -- off by default in SQLite
PRAGMA temp_store   = MEMORY;
PRAGMA cache_size   = -2000;    -- 2 MB page cache, deliberately modest for 2 GB devices
```

`synchronous = NORMAL` under WAL is the correct trade for this product: it survives application crashes intact, and risks only the last transaction in a full OS power loss — an acceptable exposure for a personal ledger, in exchange for a large write-latency reduction on cheap flash storage.

### 4.2 `income_source`

```sql
CREATE TABLE income_source (
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
);

CREATE UNIQUE INDEX ux_income_source_key ON income_source(name_key);
CREATE INDEX ix_income_source_active ON income_source(is_archived, sort_order);
```

`name_key` is the normalised name — trimmed, lowercased, internal whitespace collapsed. It is computed by the application on write, not by SQL, because SQLite's `LOWER()` is ASCII-only and would mishandle Bengali source names. The unique index on it is what makes FR-IS-02 structurally impossible to violate.

### 4.3 `income_entry`

```sql
CREATE TABLE income_entry (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid        TEXT    NOT NULL UNIQUE,
    source_id   INTEGER NOT NULL REFERENCES income_source(id) ON DELETE RESTRICT,
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    earned_on   INTEGER NOT NULL,
    period_ym   INTEGER NOT NULL,
    note        TEXT,
    status      INTEGER NOT NULL DEFAULT 0 CHECK (status IN (0,1)),
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE INDEX ix_income_entry_period  ON income_entry(period_ym, source_id);
CREATE INDEX ix_income_entry_date    ON income_entry(earned_on DESC);
CREATE INDEX ix_income_entry_source  ON income_entry(source_id, period_ym);
```

`period_ym` is derived from `earned_on` by the application and kept consistent by an assertion in the repository layer plus a debug-build integrity check. It is not a generated column, because generated-column support varies across the SQLite versions bundled with API 26–30 devices.

### 4.4 `category`

```sql
CREATE TABLE category (
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
);

CREATE UNIQUE INDEX ux_category_parent_key
    ON category(IFNULL(parent_id, -1), name_key);
CREATE INDEX ix_category_parent ON category(parent_id, is_archived, sort_order);
```

The unique index uses `IFNULL(parent_id, -1)` because SQL treats `NULL` values as distinct, which would otherwise allow two root categories with the same name. Scoping uniqueness to the parent is what permits both "Fixed → Misc" and "Variable → Misc" to coexist, satisfying FR-CAT-07.

**Depth enforcement.** A two-level cap cannot be expressed in a `CHECK` constraint, which may not contain subqueries. It is enforced by trigger:

```sql
CREATE TRIGGER trg_category_depth_insert
BEFORE INSERT ON category
WHEN NEW.parent_id IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'category depth limited to two levels')
    WHERE (SELECT parent_id FROM category WHERE id = NEW.parent_id) IS NOT NULL;
END;
```

An equivalent `BEFORE UPDATE` trigger prevents re-parenting a category into a position that would create a third level.

**Nature inheritance.** Rather than recomputing a subcategory's nature through a join on every read, `nature` is stored denormalised on the child and kept correct by trigger when a child is inserted or re-parented:

```sql
CREATE TRIGGER trg_category_inherit_nature
AFTER INSERT ON category
WHEN NEW.parent_id IS NOT NULL
BEGIN
    UPDATE category
       SET nature = (SELECT nature FROM category WHERE id = NEW.parent_id)
     WHERE id = NEW.id;
END;
```

This trades a small write cost for removing a join from the hottest read path — the correct direction for this device class.

### 4.5 `budget`

```sql
CREATE TABLE budget (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid         TEXT    NOT NULL UNIQUE,
    category_id  INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    period_ym    INTEGER NOT NULL,
    limit_minor  INTEGER NOT NULL CHECK (limit_minor >= 0),
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
);

CREATE UNIQUE INDEX ux_budget_cat_period ON budget(category_id, period_ym);
CREATE INDEX ix_budget_period ON budget(period_ym);
```

The unique index enforces FR-BUD-02 at the storage layer. A trigger additionally rejects budgets attached to a non-leaf category, enforcing FR-BUD-03:

```sql
CREATE TRIGGER trg_budget_leaf_only
BEFORE INSERT ON budget
BEGIN
    SELECT RAISE(ABORT, 'budgets may only target leaf categories')
    WHERE EXISTS (SELECT 1 FROM category WHERE parent_id = NEW.category_id);
END;
```

Root-level budget figures are never stored. They are computed as `SUM(limit_minor)` over children at query time — a handful of rows, negligible cost, and impossible to desynchronise from their parts.

### 4.6 `expense`

```sql
CREATE TABLE expense (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid           TEXT    NOT NULL UNIQUE,
    category_id    INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    amount_minor   INTEGER NOT NULL CHECK (amount_minor <> 0),
    spent_on       INTEGER NOT NULL,
    period_ym      INTEGER NOT NULL,
    payment_method INTEGER NOT NULL DEFAULT 0,
    note           TEXT,
    status         INTEGER NOT NULL DEFAULT 0 CHECK (status IN (0,1)),
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE INDEX ix_expense_date     ON expense(spent_on DESC, id DESC);
CREATE INDEX ix_expense_period   ON expense(period_ym, category_id);
CREATE INDEX ix_expense_category ON expense(category_id, spent_on DESC);
CREATE INDEX ix_expense_method   ON expense(payment_method, period_ym);
```

`CHECK (amount_minor <> 0)` rather than `> 0`, because negative amounts are how refunds are modelled (FR-EXP-06). Zero is rejected as meaningless.

`ix_expense_date` is a composite ending in `id DESC` so that the paged ledger has a stable, fully-indexed sort key — without the tiebreaker, two expenses on the same day can shuffle between pages during scrolling.

A trigger rejects expenses attached to non-leaf categories, mirroring FR-EXP-04.

### 4.7 Rollup tables

These exist purely for read speed. They are derived data and can be rebuilt from the ledger at any time.

```sql
CREATE TABLE rollup_expense_month (
    period_ym    INTEGER NOT NULL,
    category_id  INTEGER NOT NULL,
    total_minor  INTEGER NOT NULL DEFAULT 0,
    txn_count    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (period_ym, category_id)
) WITHOUT ROWID;

CREATE TABLE rollup_income_month (
    period_ym    INTEGER NOT NULL,
    source_id    INTEGER NOT NULL,
    total_minor  INTEGER NOT NULL DEFAULT 0,
    entry_count  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (period_ym, source_id)
) WITHOUT ROWID;
```

`WITHOUT ROWID` is chosen deliberately: these tables are always accessed by their full composite primary key, so eliminating the extra rowid indirection reduces both storage and lookup cost.

Maintenance is by trigger on all three mutations. Insert:

```sql
CREATE TRIGGER trg_rollup_exp_ins AFTER INSERT ON expense
WHEN NEW.status = 0
BEGIN
    INSERT INTO rollup_expense_month(period_ym, category_id, total_minor, txn_count)
    VALUES (NEW.period_ym, NEW.category_id, NEW.amount_minor, 1)
    ON CONFLICT(period_ym, category_id) DO UPDATE SET
        total_minor = total_minor + NEW.amount_minor,
        txn_count   = txn_count + 1;
END;
```

Delete decrements symmetrically. Update is implemented as a decrement of the old (period, category) followed by an increment of the new, which is what makes FR-EXP-07 — recalculating prior periods when an old expense is re-categorised — correct by construction rather than by remembering to handle it in application code.

Pending rows (`status = 1`) are excluded from rollups until confirmed, so an unconfirmed recurring entry never distorts a budget bar.

### 4.8 `recurring_rule`

```sql
CREATE TABLE recurring_rule (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid          TEXT    NOT NULL UNIQUE,
    target        INTEGER NOT NULL CHECK (target IN (0,1)),
    category_id   INTEGER REFERENCES category(id) ON DELETE RESTRICT,
    source_id     INTEGER REFERENCES income_source(id) ON DELETE RESTRICT,
    amount_minor  INTEGER NOT NULL CHECK (amount_minor <> 0),
    frequency     INTEGER NOT NULL CHECK (frequency IN (0,1,2)),
    anchor_day    INTEGER NOT NULL,
    next_due_day  INTEGER NOT NULL,
    last_run_day  INTEGER,
    auto_post     INTEGER NOT NULL DEFAULT 0 CHECK (auto_post IN (0,1)),
    is_active     INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
    note          TEXT,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,
    CHECK ((target = 0 AND category_id IS NOT NULL AND source_id IS NULL)
        OR (target = 1 AND source_id IS NOT NULL AND category_id IS NULL))
);

CREATE INDEX ix_rule_due ON recurring_rule(is_active, next_due_day);
```

The table-level `CHECK` enforces the exclusive-or between the two target types, so a rule can never be simultaneously an income and an expense template. `last_run_day` provides the idempotency guarantee required by FR-REC-03: generation for a due date only proceeds when `next_due_day > last_run_day`.

Anchor day 31 in a 30-day month clamps at generation time, satisfying FR-REC-05.

### 4.9 `app_meta`

```sql
CREATE TABLE app_meta (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL
) WITHOUT ROWID;
```

Holds schema version, last-used category and payment method (powering the entry-form defaults in FR-EXP-02 and FR-EXP-03), last-viewed period, and onboarding state.

---

## 5. Read paths

### 5.1 Dashboard budget bars

The hot query. Reads only rollups and budgets — never the expense table:

```sql
SELECT c.id, c.name, c.nature,
       IFNULL(b.limit_minor, 0)   AS limit_minor,
       IFNULL(r.total_minor, 0)   AS spent_minor
  FROM category c
  LEFT JOIN budget b
         ON b.category_id = c.id AND b.period_ym = :period
  LEFT JOIN rollup_expense_month r
         ON r.category_id = c.id AND r.period_ym = :period
 WHERE c.parent_id IS NOT NULL
   AND (c.is_archived = 0 OR r.total_minor IS NOT NULL)
 ORDER BY c.sort_order;
```

Row count is bounded by the number of leaf categories — dozens, not thousands — independent of transaction history size. This is what holds NFR-PERF-04 at 300 ms as the ledger grows to five years.

The archived-category clause is worth noting: archived categories are hidden *unless* they carry spend in the period being viewed, which is precisely the behaviour FR-CAT-08 requires.

### 5.2 Period income by source

```sql
SELECT s.id, s.name, s.kind, r.total_minor
  FROM rollup_income_month r
  JOIN income_source s ON s.id = r.source_id
 WHERE r.period_ym = :period
 ORDER BY r.total_minor DESC;
```

### 5.3 Arbitrary date range totals

Ranges that do not align to month boundaries cannot use rollups and fall back to the ledger, served by `ix_expense_date`:

```sql
SELECT SUM(amount_minor) FROM expense
 WHERE spent_on BETWEEN :from_day AND :to_day AND status = 0;
```

Range queries are a deliberate exception to the rollup strategy: they are invoked from the reports screen on explicit user action, not on every dashboard render, so a bounded index scan is acceptable there.

### 5.4 Trailing 12-period series

```sql
SELECT period_ym, SUM(total_minor) AS total
  FROM rollup_expense_month
 WHERE period_ym BETWEEN :start AND :end
 GROUP BY period_ym ORDER BY period_ym;
```

At most 12 × (leaf count) rows scanned. Feeds both the trend chart and the trailing-average requirement of FR-AN-10.

### 5.5 Paged ledger

Keyset pagination, not `OFFSET`. Offset pagination degrades linearly as the user scrolls deep into history, which is exactly the case NFR-PERF-05 measures:

```sql
SELECT * FROM expense
 WHERE (spent_on, id) < (:last_day, :last_id)
 ORDER BY spent_on DESC, id DESC
 LIMIT 50;
```

---

## 6. Integrity and repair

Derived data invites drift. Three defences:

1. **Triggers as the sole writer** of rollup tables. Application code never updates them directly.
2. **A debug-build consistency assertion** comparing every rollup row against a live aggregate over the ledger, run on app start in debug variants and failing loudly on mismatch.
3. **A user-invocable "rebuild aggregates" action** in settings that truncates and regenerates both rollup tables inside one transaction. This is the recovery path if a future migration bug corrupts them, and it costs a few hundred milliseconds even at five years of data.

```sql
DELETE FROM rollup_expense_month;
INSERT INTO rollup_expense_month(period_ym, category_id, total_minor, txn_count)
SELECT period_ym, category_id, SUM(amount_minor), COUNT(*)
  FROM expense WHERE status = 0
 GROUP BY period_ym, category_id;
```

---

## 7. Seed data

Inserted in the same transaction as schema creation, satisfying FR-CAT-01 and FR-CAT-02:

| Root (nature) | Subcategories |
|---|---|
| Fixed Expenses (0) | House Rent, Utilities, Internet, Education Fees |
| Variable Expenses (1) | Grocery, Transport, Mobile Recharge, Dining Out, Household |
| Unpredictable Expenses (2) | Medical, Gifts, Repairs, New Clothes |

One income source is seeded — Salary, kind Stable — because the first thing the user will do is record a salary, and an empty picker on first run is a needless obstacle.

---

## 8. Migration policy

- Room's `Migration` classes only; destructive fallback is disabled in release builds, because losing a user's financial history to a schema change is unrecoverable in a product with no server backup.
- **Corruption is a separate path from migration, and it deleted the ledger.** SQLite's `SQLITE_NOTADB` never reaches Room's migration handling; androidx.sqlite's default `onCorruption` deletes the file, Room opens an empty one in its place, and the launch probe then reports success. `AppDatabase` installs an open helper whose corruption callback does nothing, so the open keeps failing and `RecoveryScreen` gets its file to copy (06 §26.3).
- Each migration ships with a test that opens a populated database exported from the previous released version and asserts both schema and data integrity afterwards (NFR-REL-03).
- Rollup tables may be dropped and rebuilt by any migration; they are derived and therefore safe to regenerate.
- The exported JSON carries `schema_version`; import refuses files from a newer schema (FR-DAT-05).
- **Migrations hold frozen DDL and may never read `Schema`.** A migration describes a historical transition and has to keep describing it after the schema has moved on; pointing it at the current definitions would rewrite the past every time the present changed, and the version a migration claims to produce would stop being the version it produces. `Migrations.kt` carries the duplication deliberately.
- **Room compares `TableInfo` only *after* a migration.** An ordinary open verifies an identity hash out of `room_master_table` and skips the column comparison entirely. Version 1 therefore shipped with two mismatches nothing could see — `id INTEGER PRIMARY KEY AUTOINCREMENT` reads back as `notnull = 0` while the entities expect non-null, and `ux_category_parent_key` is a functional index the entities cannot declare — and the first migration to exist, whatever it was for, would have failed on them. Version 2 exists to repair the first; `Schema.ROOM_INVISIBLE_INDICES` handles the second by creating that index in `onOpen` rather than in any migration, so at the moment Room validates, `category` carries only the indices its entity declares.

---

## 8a. Shared expenses (FR-SHR)

`person`, `expense_share`, `settlement`, and `expense.payer_person_id`, added in version 3.

**`expense.amount_minor` remains the user's own share.** That is the decision the whole design rests on, and it is why **no rollup trigger changed**: your share *is* your spend, which is what they already sum. Recording the whole bill and correcting it on repayment cannot work here — budgets and both rollups are keyed by calendar month, so a dinner on 30 August repaid on 2 September would falsify both months permanently.

**There is no total-bill column anywhere.** A bill is `expense.amount_minor + SUM(share_minor)`, so the parts define the whole and a rounding leak cannot be represented. `SplitAllocator` places every paisa; the user's share absorbs the remainder.

**A share and a payer are mutually exclusive**, enforced by trigger from both sides:

| Trigger | Refuses |
|---|---|
| `trg_share_only_when_i_paid` | a share inserted on an expense somebody else paid |
| `trg_share_only_when_i_paid_upd` | the same, by moving a share onto one |
| `trg_payer_excludes_shares` | naming a payer on an expense that still has shares |

A share means "they owe me", which is only true when you paid. If a friend paid and three of you split it, the other two owe *them* — not your ledger, and not stored.

**A settlement is neither an expense nor income**, and no rollup reads it. A repayment is the user's own money coming home; counting it would lift the savings rate every time somebody paid them back. The same table records a loan made outright, since that is the same row with the sign reversed.

**No rollup table backs the balances.** Share rows scale with how often the user splits rather than with the 20,000-row ledger, and people number in the tens, so `SettlementDao.observeBalances` is an indexed sum over a small table. It uses correlated subqueries rather than three `LEFT JOIN`s: joining two one-to-many tables against `person` multiplies their rows together and silently inflates both sums.

---

## 9. Sizing estimate

| Entity | Rows at 5 years | Avg row | Total |
|---|---|---|---|
| expense | 20,000 | ~90 B | ~1.8 MB |
| income_entry | 400 | ~90 B | ~36 KB |
| category | 60 | ~120 B | ~7 KB |
| budget | 3,600 | ~60 B | ~216 KB |
| rollups | ~3,800 | ~40 B | ~152 KB |
| Indexes | — | — | ~1.5 MB |
| **Total** | | | **≈ 3.8 MB** (estimate) |
| **Measured** | 22,200 expenses | — | **5.41 MB** — 5,000 KB main, 512 KB WAL, 32 KB shm |

Inside the 6 MB ceiling of NFR-SIZE-05, but with about a quarter of the budget
left rather than the third this estimate implied.

The estimate was low by 42%, at a corpus 11% *larger* than the 20,000 rows it
assumed, and the gap is worth keeping in front of anyone adding a column. Three
schema decisions spend it, all deliberate: `period_ym` denormalised onto every
row (§4.3), a UUID on every entity for FR-DAT-04's merge, and two rollup tables
that duplicate the ledger on purpose (§1). Index overhead is also the line an
estimate is most likely to get wrong — SQLite stores the indexed columns *and*
the rowid in every entry, so a composite index on two integers is not cheaper
than a third of the table it indexes.

`PerformanceProbeTest.five_years_of_data_fits_the_database_budget` is where the
measured figure comes from, and it asserts the ceiling rather than reporting it;
`06-implementation-log.md` §20.6 has the full reading.

---

## 10. Validation

This schema was executed and exercised against SQLite 3.45 before publication. The results below are measured, not estimated.

### 10.1 Constraint behaviour

Nineteen behavioural assertions, all passing:

| Assertion | Result |
|---|---|
| Child inserted with a conflicting `nature` is corrected to inherit its parent's | pass |
| Third-level category insert aborts | pass |
| Same leaf name under two different roots accepted | pass |
| Duplicate root name rejected (the `IFNULL` index working as intended) | pass |
| Budget on a root category aborts | pass |
| Budget on a leaf accepted | pass |
| Second budget for the same (category, period) rejected | pass |
| Two inserts accumulate correctly in the rollup | pass |
| Negative refund reduces the rollup total | pass |
| Zero amount rejected | pass |
| Pending entry excluded from rollups | pass |
| Confirming a pending entry adds it to rollups | pass |
| Delete decrements the rollup symmetrically | pass |
| Re-categorising an expense into a different period moves the total out of the old bucket | pass |
| …and into the new one | pass |
| Deleting a referenced category rejected | pass |
| Recurring rule targeting both a category and a source rejected | pass |
| Duplicate income source name key rejected | pass |
| Rollups rebuilt from the ledger match the trigger-maintained state exactly | pass |

The last assertion is the important one — it confirms the trigger set and the rebuild query agree, which is the invariant the whole aggregate strategy rests on.

### 10.2 Query performance

Seeded with 20,000 expenses over 60 periods and 60 leaf categories, database 4.5 MB:

| Query | Time | Plan |
|---|---|---|
| Dashboard budget bars | 0.24 ms | index seek on all three tables |
| Ledger page, keyset | 0.84 ms | `ix_expense_date`, no temp sort |
| 12-period trend | 0.38 ms | rollup primary key range |

These are desktop figures. Budget a factor of 10–20× on the reference device, which still leaves the dashboard an order of magnitude inside the 300 ms target — the remaining budget is consumed by view inflation and layout, not by the database.

### 10.3 Why the rollup tables earn their complexity

The rollup design costs trigger maintenance on every write and a rebuild path for safety. That expense is justified only if it buys asymptotic behaviour rather than a constant factor. Measured, aggregating one period's spend:

| Ledger size | Via rollup | Direct aggregate | Ratio |
|---|---|---|---|
| 20,000 rows | 0.027 ms | 0.139 ms | 5× |
| 100,000 rows | 0.028 ms | 2.15 ms | 77× |

The rollup path is flat — its cost depends on the number of leaf categories, which is fixed, not on history length. The direct aggregate degrades linearly. At MVP scale the difference is invisible; by year five on a Cortex-A53 it is the difference between an instant dashboard and a visible stall. This is the one piece of premature-looking optimisation in the design that is not premature, because retrofitting it later means rewriting every read path.

### 10.4 Known plan note

The dashboard query ends with `USE TEMP B-TREE FOR ORDER BY` on `sort_order`. With a bounded row count in the dozens this is immaterial and no index is warranted. If custom category ordering is ever removed in favour of alphabetical display, the sort can be dropped entirely.
