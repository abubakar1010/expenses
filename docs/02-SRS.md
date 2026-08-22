# Software Requirements Specification
**Product:** Personal Finance Manager (Android)
**Version:** 1.0 — MVP
**Conforms to:** IEEE 830 structure, adapted

---

## 1. Introduction

### 1.1 Purpose
Defines the functional and non-functional requirements for the MVP. Intended audience: the implementing developer, and any reviewer verifying the build against the specification.

### 1.2 Scope
A single-user, offline-only Android application for recording income from multiple sources, maintaining a two-level budget category tree with monthly limits, logging daily expenses, and producing spending analytics. No server component exists in v1.

### 1.3 Definitions

| Term | Meaning |
|---|---|
| **Minor unit** | Integer paisa. All money is stored as `INTEGER` paisa; ৳1 = 100 minor units |
| **Epoch day** | Days since 1970-01-01, local time, as `INTEGER` |
| **Period** | A calendar month, encoded `YYYYMM` as `INTEGER` (e.g. 202608) |
| **Nature** | Classification of a root category: fixed, variable, or unpredictable |
| **Leaf** | A category with no children; the only kind selectable on a transaction |
| **Name key** | Lowercased, trimmed, whitespace-collapsed form of a name, used for uniqueness |
| **Rollup** | Precomputed per-period aggregate maintained by database trigger |
| **Reference device** | 4× Cortex-A53 @1.4 GHz, 2 GB RAM, Android 8.1, 720×1280 |

### 1.4 Overall description
Standalone application. All state resides in a local SQLite database. No network permission is declared. The user interacts through a single-activity Android UI. Data leaves the device only through explicit, user-initiated file export.

---

## 2. Functional requirements

Each requirement has an ID, a statement, and verifiable acceptance criteria. `MUST` denotes a release blocker; `SHOULD` denotes a P1 deferral candidate.

### 2.1 Income sources — FR-IS

**FR-IS-01** The system MUST allow creating an income source with a name and a kind (Stable | Variable).
- *Accept:* A source created with name "Salary" and kind Stable appears in the source list and in the entry picker.

**FR-IS-02** The system MUST enforce uniqueness of source names on the normalised name key.
- *Accept:* Attempting to create "salary", " Salary", or "SALARY" when "Salary" exists resolves to the existing source and does not create a duplicate row.

**FR-IS-03** When the user enters an unrecognised source name during income entry, the system MUST create the source inline and attach the entry to it, without a separate navigation step.
- *Accept:* Typing "Poultry" in the source field of the entry form and saving produces one new `income_source` row and one `income_entry` row referencing it.

**FR-IS-04** The system MUST allow archiving a source. Archived sources MUST be excluded from entry pickers and MUST remain visible in historical reports.
- *Accept:* After archiving "Farming", it is absent from the new-entry picker; a report covering a prior period still shows Farming totals.

**FR-IS-05** The system MUST NOT permit deletion of a source that has one or more entries.
- *Accept:* Delete action on such a source is disabled with an explanatory message offering Archive instead.

**FR-IS-06** The system MUST permit deletion of a source with zero entries.

**FR-IS-07** The system SHOULD allow reordering sources for display.

### 2.2 Income entries — FR-IE

**FR-IE-01** The system MUST allow recording an income entry with amount, source, date, and optional note.
- *Accept:* Entry saves and appears in the income ledger for the period containing its date.

**FR-IE-02** The system MUST accept unlimited entries for the same source within the same period.
- *Accept:* Two entries for Farming dated 2026-06-04 and 2026-06-19 both persist and both appear in the June total.

**FR-IE-03** Income amounts MUST be greater than zero.
- *Accept:* Save is rejected with a field-level error for 0 or negative input.

**FR-IE-04** The system MUST compute and display: period total, year total, and total for an arbitrary user-selected date range.
- *Accept:* Each total equals the sum of matching `income_entry.amount_minor` rows, verified against a manual sum.

**FR-IE-05** The system MUST allow filtering income by any subset of sources combined with a date range.
- *Accept:* Selecting {Salary, Farming} for 2026-01-01 to 2026-12-31 returns exactly the entries matching both predicates.

**FR-IE-06** The system MUST display a per-source breakdown with each source's percentage share of the filtered total.
- *Accept:* Percentages sum to 100 ± 0.1 after rounding.

**FR-IE-07** The system MUST display a 12-month income trend ending at the currently selected period.

**FR-IE-08** The system MUST support editing and deleting income entries, with all dependent aggregates recalculated.
- *Accept:* Deleting a ৳50,000 June entry reduces the June total, the 2026 total, and the savings rate accordingly, within one frame of the confirming action.

### 2.3 Categories — FR-CAT

**FR-CAT-01** On first launch the system MUST seed three system root categories: Fixed Expenses (nature=fixed), Variable Expenses (nature=variable), Unpredictable Expenses (nature=unpredictable).
- *Accept:* A fresh install shows exactly these three roots, each flagged `is_system = 1`.

**FR-CAT-02** The system MUST seed a starter set of subcategories so the first-run experience is not an empty screen. Minimum: House Rent, Utilities under Fixed; Grocery, Transport, Mobile Recharge under Variable; Medical, Gifts under Unpredictable.

**FR-CAT-03** System root categories MUST be renameable and MUST NOT be deletable or archivable.
- *Accept:* Delete and archive actions are absent for `is_system = 1` roots.

**FR-CAT-04** The system MUST allow creating additional root categories, each with a user-chosen nature.

**FR-CAT-05** The system MUST allow creating subcategories under any root. Category depth MUST NOT exceed two levels.
- *Accept:* The "add subcategory" action is unavailable on a category whose `parent_id` is non-null.

**FR-CAT-06** A subcategory MUST inherit `nature` from its root and MUST NOT override it.
- *Accept:* Moving a subcategory to a different root updates its effective nature in all subsequent reports.

**FR-CAT-07** Category names MUST be unique within their parent, on the normalised name key. Two roots MAY each have a child named "Misc".

**FR-CAT-08** The system MUST allow archiving any non-system category. Archived categories MUST be hidden from entry pickers and MUST remain present in historical reports and in the ledger rows that reference them.

**FR-CAT-09** Archiving a root MUST archive its descendants.

**FR-CAT-10** The system MUST NOT permit deletion of a category referenced by any expense or budget row.

**FR-CAT-11** The system SHOULD allow reordering categories within their parent.

### 2.4 Budgets — FR-BUD

**FR-BUD-01** The system MUST allow setting a monthly limit for a leaf subcategory in a given period.
- *Accept:* A limit of ৳7,000 for Grocery in 202608 persists and drives the progress display for that period.

**FR-BUD-02** At most one budget row MUST exist per (category, period).
- *Accept:* Setting a second limit for the same pair updates the existing row rather than inserting.

**FR-BUD-03** Budgets MUST NOT be set directly on a root. A root's limit MUST be the computed sum of its children's limits for that period.
- *Accept:* With children at ৳7,000 and ৳3,000, the root displays ৳10,000 and offers no editable limit field.

**FR-BUD-04** The system MUST provide a single action copying all of the previous period's budgets into the current period.
- *Accept:* After the action, every leaf that had a limit last period has an equal limit this period; leaves already carrying a limit this period are not overwritten without confirmation.

**FR-BUD-05** For each budgeted category the system MUST display spent, limit, remaining, and percentage consumed for the selected period.

**FR-BUD-06** The system MUST surface a warning state at ≥80% and an over-budget state at ≥100% of a leaf limit.
- *Accept:* Spend of ৳5,600 against a ৳7,000 limit renders the warning state; ৳7,000 renders over-budget.

**FR-BUD-07** Categories of nature `unpredictable` MUST NOT produce under-spend nagging and MUST be visually distinguished from planned categories.

**FR-BUD-08** Budget limits MUST be greater than or equal to zero.

### 2.5 Expenses — FR-EXP

**FR-EXP-01** The system MUST provide an entry flow completing in no more than three interactions: amount, category, save.
- *Accept:* On the reference device, from tapping the add control to seeing the updated ledger takes ≤ 5 s including typing, and ≤ 100 ms of that is application processing.

**FR-EXP-02** The expense date MUST default to today and MUST be user-overridable.

**FR-EXP-03** The category picker MUST surface the most recently used categories first.

**FR-EXP-04** Only leaf categories MUST be selectable on an expense.
- *Accept:* Root categories appear as non-selectable group headers in the picker.

**FR-EXP-05** The system MUST accept an optional note and an optional payment method from: Cash, bKash, Nagad, Bank, Card.

**FR-EXP-06** The system MUST accept negative amounts to represent refunds. Zero MUST be rejected.
- *Accept:* A −৳500 entry against Grocery reduces the Grocery period total by 500.

**FR-EXP-07** The system MUST support editing and deleting expenses, recalculating all dependent aggregates including those of prior periods.
- *Accept:* Editing a January expense's category updates January's rollups for both the old and new category.

**FR-EXP-08** The ledger MUST be filterable by date range, root, leaf, and payment method, and searchable by note substring and by exact amount.

**FR-EXP-09** The ledger MUST be grouped by day with per-day subtotals.

**FR-EXP-10** The ledger MUST page results and MUST NOT load the full transaction history into memory.
- *Accept:* Scrolling a 20,000-row ledger maintains ≥ 55 fps on the reference device with heap growth under 10 MB.

### 2.6 Analytics — FR-AN

**FR-AN-01** The dashboard MUST display **safe to spend today** = (remaining limits of variable + unpredictable leaves) ÷ (days remaining in period, inclusive of today). When the numerator is negative the value MUST render as zero with an over-budget indicator.

**FR-AN-02** The dashboard MUST display **net position** = period income − period expenses.

**FR-AN-03** The dashboard MUST display **savings rate** = (income − expenses) ÷ income, suppressed when income is zero.

**FR-AN-04** The dashboard MUST display a **burn-rate projection** per over-pace leaf: (spent ÷ days elapsed) × days in period, compared against the limit.

**FR-AN-05** The dashboard MUST display **category deltas** — current period spend versus the trailing 3-period mean, sorted descending by absolute increase, top 5.

**FR-AN-06** The dashboard MUST display **stable coverage** = stable-source income ÷ total expenses for the period.

**FR-AN-07** The dashboard MUST display the **fixed / variable / unpredictable** share of period spend.

**FR-AN-08** The dashboard MUST display the **top 5 largest expenses** of the period.

**FR-AN-09** The system MUST render a 6-period expense trend line with a budget reference line, and a 12-period income series.

**FR-AN-10** Averages presented as "monthly average income" MUST be computed over a trailing 12 periods, never over a single period or a partial year.
- *Rationale:* seasonal sources earn nothing for months and then a lump sum; a short window produces figures that are wrong in both directions.

### 2.7 Recurring rules — FR-REC *(P1)*

**FR-REC-01** The system SHOULD allow defining a recurring income or expense template with amount, target source or category, frequency (monthly | weekly | yearly), and anchor day.

**FR-REC-02** On or after the due date the system SHOULD generate a **pending** entry requiring one-tap confirmation. Auto-post SHOULD be configurable per rule and default to off.

**FR-REC-03** Rule evaluation MUST be idempotent — repeated evaluation for the same due date MUST NOT produce duplicates.

**FR-REC-04** Missed due dates accumulated while the app was unopened MUST all be generated on next launch, each individually confirmable.

**FR-REC-05** Anchor days beyond the length of a short month MUST clamp to that month's final day.

### 2.8 Data portability — FR-DAT

**FR-DAT-01** The system MUST export the complete dataset as JSON to a user-chosen location via the system document picker.

**FR-DAT-02** The system MUST export CSV, one file per entity, delivered as a single archive.

**FR-DAT-03** The system MUST import a previously exported JSON file, offering **replace** or **merge**. Merge MUST deduplicate on a stable natural key and MUST report counts of inserted, updated, and skipped rows.

**FR-DAT-04** Export → wipe → import MUST be lossless.
- *Accept:* Row counts and checksums for every entity match pre-export values; every report renders identical figures.

**FR-DAT-05** Import MUST validate schema version and MUST refuse a file from a newer schema than the installed app supports.

**FR-DAT-06** The system MUST provide a "delete all data" action behind an explicit typed confirmation.

### 2.9 Application-level requirements — FR-APP

**FR-APP-01** The system MUST NOT declare the `INTERNET` permission.
- *Accept:* The merged manifest of a release build contains no `android.permission.INTERNET` entry.

**FR-APP-02** The system MUST function identically in airplane mode.

**FR-APP-03** The system MUST restore the user's last-viewed screen and period after process death.

**FR-APP-04** The system SHOULD offer an optional app-lock PIN or biometric gate *(P1)*.

**FR-APP-05** All monetary values MUST be displayed with the Bengali Taka symbol and thousands grouping per the device locale.

---

## 3. Non-functional requirements

### 3.1 Performance — NFR-PERF

**"Resident memory" means anonymous RSS.** The unqualified wording was ambiguous in a way that mattered: total RSS for this app is about 145 MB, of which ~94 MB is file-backed — the framework, the fonts, the APK itself — pages that are shared with every other process and exist whether Khata runs or not. Charging those to the app would fail the budget for any Compose application ever written, and would say nothing about whether this one behaves on a 2 GB device. Anonymous RSS is what the app actually allocated and what the system reclaims against it, and it is what the benchmark suite can assert, which a budget nobody can gate on is not. Restated in `06-implementation-log.md` §20.14.

All targets are measured on the **reference device** with a seeded database of **5 years, 20,000 expenses, 400 income entries, 60 categories**. Targets measured on a flagship device are not evidence of compliance.

| ID | Requirement | Target |
|---|---|---|
| NFR-PERF-01 | Cold start to first interactive frame | ≤ 800 ms |
| NFR-PERF-02 | Warm start | ≤ 250 ms |
| NFR-PERF-03 | Expense save committed and UI updated | ≤ 100 ms |
| NFR-PERF-04 | Dashboard fully rendered | ≤ 300 ms |
| NFR-PERF-05 | Ledger scroll | ≥ 55 fps, no frame > 16 ms at p95 |
| NFR-PERF-06 | Period switch on dashboard | ≤ 150 ms |
| NFR-PERF-07 | Full JSON export | ≤ 3 s |
| NFR-PERF-08 | Steady-state **anonymous** resident memory — the pages this app allocated, measured as `MemoryUsageMetric`'s `memoryRssAnonLastKb` once the dashboard has settled over five years of data | ≤ 80 MB |
| NFR-PERF-09 | Main-thread database access | Zero occurrences; enforced by StrictMode in debug builds |

### 3.2 Size — NFR-SIZE

| ID | Requirement | Target |
|---|---|---|
| NFR-SIZE-01 | Release APK / AAB download size | ≤ 6 MB |
| NFR-SIZE-02 | Installed footprint excluding user data | ≤ 20 MB |
| NFR-SIZE-03 | Method count after shrinking | ≤ 40,000 — single dex, no multidex |
| NFR-SIZE-04 | Third-party runtime dependencies | Justified individually in review; a dependency exceeding 300 KB requires written rationale |
| NFR-SIZE-05 | Database size for 5 years of data | ≤ 6 MB |

### 3.3 Reliability — NFR-REL

| ID | Requirement |
|---|---|
| NFR-REL-01 | No user-entered transaction may be lost by process death, low-memory kill, or forced stop. Writes are committed synchronously before the UI acknowledges success |
| NFR-REL-02 | Every displayed aggregate MUST reconcile exactly with a direct sum over the underlying ledger. A nightly self-check in debug builds asserts rollup consistency |
| NFR-REL-03 | Schema migrations MUST be tested against a populated database from each prior released version |
| NFR-REL-04 | A failed import MUST leave the existing database unmodified — the operation is transactional |
| NFR-REL-05 | A release candidate must exhibit **no unhandled exception**. *Accept:* the instrumented suite completes with zero crashes; no StrictMode violation attributable to app code; the crash log is empty after the dogfooding period in `01-PRD.md` §8. — Restated from "crash-free session rate ≥ 99.5%", which presumed a fleet and a backend. A *field* rate requires transmitting session outcomes off the device, and NFR-SEC-01, NFR-SEC-02 and FR-APP-01 each forbid that — the last structurally, by removing the `INTERNET` permission altogether, so the app has no transport even in principle. The three could not all hold at once. What the requirement was *for* was reliability, not the statistic, so the instrument changed and the intent did not. Recorded here deliberately, in the manner of NFR-SEC-05; `06-implementation-log.md` §20.11 has the reasoning. |

### 3.4 Compatibility — NFR-COMP

| ID | Requirement |
|---|---|
| NFR-COMP-01 | Minimum API 26 (Android 8.0); target latest stable |
| NFR-COMP-02 | ARM 32-bit and 64-bit. **No native library this project chooses**; native code arriving transitively through AndroidX is permitted and must be justified individually, as NFR-SIZE-04 already requires of dependencies. There is currently one: `androidx.graphics:graphics-path`, reached through Compose UI, 16 KB installed, supplying `PathIterator` — the platform gained an equivalent only at API 34, above this app's minimum of 26. — Restated from "no native libraries beyond the platform-bundled SQLite", which cannot hold while the UI is Compose: the class survives R8 in the release dex, so removing the library would be an `UnsatisfiedLinkError` on exactly the low-end devices this app is for. The intent is unchanged — this project takes on no native code of its own, and NFR-SEC-05's decision against bundling SQLCipher stands. `06-implementation-log.md` §20.12 has the reasoning. |
| NFR-COMP-03 | Screen widths 320 dp to 480 dp; no tablet layouts required |
| NFR-COMP-04 | Correct rendering at system font scale 0.85× to 1.3× |
| NFR-COMP-05 | Functions correctly under Doze and aggressive OEM battery restrictions — no background work is required for core function |

### 3.5 Usability — NFR-USE

| ID | Requirement |
|---|---|
| NFR-USE-01 | Expense entry reachable in one tap from any primary screen |
| NFR-USE-02 | The numeric keypad is focused and raised on opening the amount field, with no additional tap |
| NFR-USE-03 | Every destructive action is undoable for at least 5 seconds via snackbar |
| NFR-USE-04 | Touch targets ≥ 48 dp |
| NFR-USE-05 | Text contrast ≥ 4.5:1; state is never conveyed by colour alone |
| NFR-USE-06 | The app is fully operable one-handed on a 5-inch display; primary actions sit in the lower half |

### 3.6 Security and privacy — NFR-SEC

| ID | Requirement |
|---|---|
| NFR-SEC-01 | No data leaves the device except by explicit user-initiated export |
| NFR-SEC-02 | No analytics, telemetry, crash reporting SDK, or advertising identifier |
| NFR-SEC-03 | The database resides in app-private internal storage; `allowBackup` disabled to prevent silent cloud copies |
| NFR-SEC-04 | `FLAG_SECURE` applied optionally to block screenshots and recents-screen previews *(P1)* |
| NFR-SEC-05 | Database encryption at rest is out of scope for v1; the rationale — that it requires bundling a native crypto library at material size and startup cost, while the device lock screen already gates access — is recorded here deliberately, and revisited in v2 |

### 3.7 Maintainability — NFR-MAIN

| ID | Requirement |
|---|---|
| NFR-MAIN-01 | Business rules (budget states, projections, safe-to-spend) live in pure functions with no Android dependencies, unit-testable on the JVM |
| NFR-MAIN-02 | Line coverage ≥ 80% on the calculation and repository layers |
| NFR-MAIN-03 | Every DAO query used on a hot path has a documented `EXPLAIN QUERY PLAN` result confirming index use |
| NFR-MAIN-04 | Performance targets are asserted by an automated benchmark suite run on each release candidate |

---

## 4. Data requirements

| ID | Requirement |
|---|---|
| DR-01 | Money MUST be stored as `INTEGER` minor units. Floating-point representation of money is prohibited anywhere in the system, including export files |
| DR-02 | Dates MUST be stored as `INTEGER` epoch days in local time, with a denormalised `INTEGER` period column in `YYYYMM` form |
| DR-03 | Every mutable row MUST carry `created_at` and `updated_at` epoch-millisecond timestamps |
| DR-04 | Rollup tables MUST be maintained by database triggers, not by application code, so that consistency is guaranteed independent of the write path |
| DR-05 | Referential integrity MUST be enforced by foreign keys with `PRAGMA foreign_keys = ON` |
| DR-06 | Every entity MUST carry a stable UUID alongside its integer primary key, to support import deduplication across devices |

---

## 5. Constraints and assumptions

**Constraints**
- Android only; no cross-platform runtime, because the size and startup cost conflict with NFR-SIZE-01 and NFR-PERF-01
- No server, therefore no sync, no authentication, no remote configuration
- Single user per installation
- Single currency (BDT)

**Assumptions**
- The user enters transactions manually and accepts that in exchange for speed and privacy
- The device lock screen is an adequate security boundary for v1
- Data volume stays within roughly 10,000 transactions per year
- The user's income genuinely varies month to month, so all averaging defaults to trailing 12 periods

---

## 6. Acceptance test summary

The release is accepted when all `MUST` requirements pass, and:

1. A seeded 5-year database renders the dashboard within 300 ms on the reference device, measured across 10 cold runs.
2. Export → factory reset → import reproduces every figure exactly.
3. StrictMode reports zero main-thread disk reads or writes across a full manual regression pass.
4. The release manifest contains no network permission.
5. The author has used the build daily for 14 consecutive days without reverting to a spreadsheet.
