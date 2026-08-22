

# Implementation Log — M1 and M2
**Product:** Khata — Personal Finance Manager (Android)
**Covers:** initial scaffold through milestone M2
**Date:** 14 August 2026 (§1–§11, M1) · 15 August 2026 (§12, M2)

This document records what was built from `01`–`05`, every place the
implementation departs from those documents and why, and the evidence behind
each claim. It is the handover note for whoever picks this up next — including
the version of you that has forgotten the details.

Documents `01`–`05` remain normative. Where this log and a specification
disagree, the specification wins and this log is the bug.

---

## 1. Starting point

The repository contained five specification documents, a SQL schema, an HTML
visual spec, an empty IntelliJ Java module, and a `files.zip` holding duplicates
of two of the docs. **No source code of any kind.**

The first substantive finding was a framing one: every document specifies a
**native Kotlin + Jetpack Compose Android application**. There is no backend, no
network layer, no authentication, no API and no monorepo. That absence is the
architectural thesis rather than an omission — `04` §1 lists offline-only as one
of four drivers, and `04` §12 makes the missing `INTERNET` permission a
structural privacy guarantee rather than a promise.

---

## 2. Scope delivered

Milestone **M1** as `01` §8 defines it — *"Ship M1 to yourself before building
M2"* — plus the complete persistence layer and design system that M2–M4 rest on.

| Area | State | Requirement |
|---|---|---|
| Build configuration, R8 full mode, signed release | complete | NFR-SIZE-* |
| Schema: 9 tables, 14 indices, 14 triggers, seed, PRAGMAs | complete | DR-01…06 |
| `Money`, `Period`, `NameKey` | complete | NFR-MAIN-01 |
| *Khata* design system, light and dark | complete | `05` §3–§7 |
| Navigation shell, bottom bar, centre FAB | complete | NFR-USE-01/06 |
| Quick Add — keypad, chips, date, method, note, full picker | complete | FR-EXP-01…06 |
| Ledger — paging, day groups, edit, delete+undo, filter, search | complete | FR-EXP-07…10 |
| Crash log, recovery screen, rollup drift check | complete | `04` §8, NFR-REL-02 |
| Dashboard, Income, Budget | placeholder routes (M2–M4) | |
| Category manager, Reports, Settings | not started | |
| Export/import, recurring rules | not started (M5, P1) | |

**Size:** 51 main source files (7,104 lines), 13 test files (2,519 lines).

---

## 3. Verified results

Every number below was measured, not estimated. Reproduce with the commands in
§9.

Figures are after the completion pass (§11); the scaffold's are in brackets.

| Constraint | Target | Measured |
|---|---|---|
| NFR-SIZE-01 APK download size | ≤ 6 MB | **1.41 MB** (1,478,481 bytes) [1.13 MB] |
| NFR-SIZE-03 dex methods, single dex | ≤ 40,000 | **16,628** in 3,122 classes [13,687] |
| FR-APP-01 no `INTERNET` permission | none declared | **confirmed on the merged release manifest** |
| Font budget (`05` §4.1) | ~12–18 KB | **5,316 bytes** |
| Android lint, release | no errors | **0 errors, 7 warnings** [3 errors, 27 warnings] |
| JVM unit tests | ≥ 90% on `core/` | **53 passing** |
| Instrumented tests | — | **94 passing** [31] |

Closing all of FR-EXP cost 280 KB and 2,941 methods — the date and filter
pickers, the note field, `SwipeToDismissBox`, and the M3 `DatePicker` that comes
with them. That leaves 4.6 MB of the ceiling for M2–M5.

The only permission in the merged manifest is
`com.app.finance.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level
self-permission AndroidX injects for the app's own dynamic receivers. It grants
no capability and reaches nothing outside the app.

**Not measured:** every NFR-PERF target. `02` is explicit that "targets measured
on a flagship device are not evidence of compliance", and an x86_64 emulator on
a desktop is further from the reference device than a flagship phone is. These
need the 1.4 GHz Cortex-A53 with eMMC storage and five years of seeded data. See
§8.

---

## 4. Decisions that departed from the specifications

Five. Each is a deliberate call with a stated reason, not an oversight.

### 4.1 AGP 9.3.1, not the AGP 8.13.2 originally chosen

The toolchain was picked for one reason — the highest chance of building first
try. That premise turned out to be false. Every current AndroidX artifact
(`compose-ui` 1.12.0, `core-ktx` 1.19.0, `lifecycle` 2.11.0) refuses to build
under AGP 8 and demands compileSdk 37:

```
Dependency 'androidx.compose.ui:ui-android:1.12.0' requires
Android Gradle plugin 9.1.0 or higher.
```

Holding AGP 8 would have meant pinning the entire AndroidX surface roughly a
year back. API 37 is a stable base SDK on this machine (`PreviewSdkInt=0`, no
codename), so **compileSdk/targetSdk 37 also satisfies NFR-COMP-01's "latest
stable"** — which AGP 8 could not have done. minSdk stays at 26.

Two consequences follow, both recorded in the README:

- AGP 9 has built-in Kotlin and registers the `kotlin` extension itself.
  Applying `org.jetbrains.kotlin.android` alongside it fails outright. The
  Compose and serialization *compiler* plugins are separate and still apply.
- `android.disallowKotlinSourceSets=false` is required in `gradle.properties`.
  AGP 9's built-in Kotlin rejects contributions to `kotlin.sourceSets`, but the
  KSP plugin still registers generated sources that way
  ([google/ksp#2729](https://github.com/google/ksp/issues/2729)). This is AGP's
  own documented suppression for the overlap; remove it once KSP registers
  through `android.sourceSets`.

### 4.2 `navigation-compose` is not in the dependency budget

`04` §2.5 lists every runtime dependency, and navigation is not among them —
but §7 specifies "a single `NavHost`", which is Compose Navigation's own
terminology. It is used, confined to `ui/AppNav.kt`.

If an APK measurement later shows it costing more than the 300 KB that §2.5
requires justification for, the replacement is a hand-rolled host: the specified
transition is a 150 ms fade-through with no slide and no shared element, which
is a `Crossfade` and a saved back stack. Only `AppNav.kt` changes.

Current headroom makes this a non-issue today — 1.41 MB against a 6 MB ceiling.

### 4.3 Architecture rules as a Gradle task, not a lint module

`04` §3.1 and §4.1 both ask for lint rules: no `android.*` imports inside
`domain/`, and `Double` for money prohibited rather than discouraged. Both are
implemented as `:app:architectureCheck`, wired into `preBuild` so a violation
fails the build.

A real `com.android.lint` module would add a Gradle project and a published
artifact in order to *also* show squiggles in the IDE. The guarantee the
documents ask for is that the build fails, and that is delivered. The lint
module is worth building when there is a second developer to see the squiggles.

The check is verified to actually fire — a probe file with both violations was
injected and rejected, and `fraction: Float` in `BudgetStatus` was correctly
**not** flagged, confirming the money heuristic discriminates on name rather
than on type alone.

### 4.4 `NameKey` folds case with `Locale.ROOT`, not the device locale

`04` §3.1 describes `NameKey.kt` as "locale-aware normalisation" and `03` §4.2
gives the reason: SQLite's `LOWER()` is ASCII-only and would mishandle Bengali.

The requirement is therefore **Unicode-correct** folding, and locale-*sensitive*
folding is precisely what must be avoided. Turkish lowercases `I` to a dotless
`ı`, so folding with the device locale would key the same name differently on a
Turkish phone and silently break `ux_income_source_key` after a locale change or
an export/import round trip. `Locale.ROOT` gives Unicode correctness with a
stable result. NFC normalisation was added ahead of the fold, because Bengali
conjuncts have composed and decomposed encodings that render identically — two
visually identical names would otherwise produce two different keys. Both are
covered by tests.

### 4.5 The keypad is 48 dp per key, not larger

Not a spec departure so much as a measurement. The sheet — amount, chips,
sentence, Save, four key rows — must fit above the navigation bar on a
320 × 569 dp screen without scrolling, because a keypad you scroll to reach is
not "always up". 56 dp keys overflowed by one row on device; 48 dp fits and is
exactly the accessibility floor that `05` §10 requires.

---

## 5. Corrections to `docs/schema_v1.sql`

The file as delivered was incomplete relative to `03`, which describes several
objects in prose that the SQL never defined. It has been regenerated from
`Schema.kt` with every addition marked `[ADDED]` against the section requiring
it.

| Addition | Authority | Consequence had it stayed missing |
|---|---|---|
| `trg_rollup_inc_ins` / `_del` / `_upd` | §2 diagram, §4.7 | **`rollup_income_month` created and never written to.** Every income figure in the app reads ৳0 forever while the ledger underneath is perfectly correct — a silent, total failure of the income module, invisible until someone reconciles by hand |
| `trg_expense_leaf_only` | §4.6, FR-EXP-04 | Expenses attachable to group categories, corrupting the fixed/variable/unpredictable split |
| `trg_expense_leaf_only_upd` | §4.6 | The insert guard walked around by an `UPDATE` |
| `trg_budget_leaf_only_upd` | §4.5 | As above, for budgets |
| `trg_category_depth_update` | §4.4 | Re-parenting creates a third level the reads cannot represent |
| `trg_category_inherit_nature_upd` | §4.4, FR-CAT-06 | Moving a subcategory between roots leaves its `nature` stale |
| Full PRAGMA block | §4.1 | Only `foreign_keys` was present; WAL, `synchronous`, `temp_store` and `cache_size` absent |
| Seed data | §7 | Empty pickers on first run |
| `app_meta` `schema_version` row | §4.9 | Import cannot refuse newer-schema files (FR-DAT-05) |

The income rollup triggers are the consequential one. The failure mode is the
worst kind: no crash, no error, correct underlying data, and every derived
figure wrong.

One further note — `03` §4.4's depth-update trigger needed a second condition
the prose does not state. A move creates a third level in **two** ways: the new
parent already has a parent, *or* the category being moved has children of its
own. Both are checked.

---

## 6. The one genuinely risky decision, and how it was retired

Room cannot express four things this schema depends on: `CHECK` constraints,
`WITHOUT ROWID` storage, the functional index
`category(IFNULL(parent_id, -1), name_key)`, and triggers — and `03` §1 makes
triggers the *sole* writer of the rollup tables.

**Approach taken.** Entities are declared for Room's DAO generation and
compile-time query verification, but `Schema.kt` owns the real DDL.
`AppDatabase.CanonicalSchema.onCreate` drops the tables Room just generated and
rebuilds them from `Schema.kt` inside the same transaction, then creates the
indices, the triggers and the seed.

**Why it might not have worked.** Room validates the schema it finds against the
schema its entities describe on *every* open. A mismatch in any property Room
inspects — column affinity, nullability, default, primary key, foreign key,
index — would not have failed in a test. It would have failed on the user's
**second launch**, with their financial history already in the file.

**How it was retired.** `SchemaValidationTest` is file-backed and deliberately
opens the database twice, because an in-memory database is discarded on close
and can never catch this. It passes, and additionally asserts that after the
round trip the functional `IFNULL` index survives, all fourteen triggers survive,
and the PRAGMAs are applied on the reopened connection.

The residual-risk fallback documented in the plan — replacing the functional
index with two partial indices — was **not needed**. Room accepts the canonical
schema as written.

---

## 7. Test inventory

### JVM unit tests — 53, all passing

| Suite | Count | Covers |
|---|---|---|
| `MoneyTest` | 27 | arithmetic, South Asian vs Western grouping, decimals hidden when whole, true minus U+2212, spoken form for TalkBack, keypad parsing of partial input |
| `PeriodTest` | 18 | December→January rollover, leap-year February, 30-day months, safe-to-spend divisor inclusive of today, trailing-12 series across a year boundary |
| `NameKeyTest` | 8 | case and whitespace collapse, Bengali NFC folding, locale independence under a Turkish default |

### Instrumented tests — 31, all passing

| Suite | Count | Covers |
|---|---|---|
| `SchemaAssertionsTest` | 29 | the 19 assertions of `03` §10.1 ported one-to-one, plus the six added triggers, `WITHOUT ROWID` storage, `CHECK` survival, and two `EXPLAIN QUERY PLAN` assertions |
| `SchemaValidationTest` | 2 | Room's acceptance of the canonical schema across a close/reopen; PRAGMAs on every connection |

Two of these deserve highlighting.

**Assertion 19** — a rebuild of the rollups from the ledger must reproduce the
trigger-maintained state exactly. `03` §10.1 calls this the important one, and
it is: it confirms the trigger set and the rebuild query agree, which is the
invariant the entire aggregate strategy rests on.

**The query-plan assertions** — NFR-MAIN-03 requires a documented
`EXPLAIN QUERY PLAN` for every hot-path query. Two are asserted rather than
documented: the paged ledger uses `ix_expense_date` with no temp B-tree (a temp
sort would mean keyset pagination had quietly stopped working), and the
dashboard query never touches the `expense` table at all — which is the property
that keeps dashboard cost flat as history grows.

### Manual verification on device

Installed on an emulator forced to **320 dp width** (`wm density 360` on a
720 × 1280 panel), which is the width `05` §5.1 says every layout is designed
against. Logged an expense end to end: FAB → three keypad taps → Save. The
snackbar read "Expense saved" — the same verb as the button, per `05` §9 — and
the row appeared in the Ledger under a `TODAY · FRIDAY 14 AUGUST` header with a
day subtotal, leader dots carrying the eye to the figure, and no manual refresh
anywhere, confirming the Room-invalidation path of `04` §5.1.

One crash was found and fixed this way: Compose rejects negative `padding`, so
the centre-docked FAB's overlap of the nav bar needed `offset`.

---

## 8. What is not done

**All NFR-PERF targets are unmeasured.** `:benchmark` is scaffolded and
assembles — `StartupBenchmark` covers cold start with and without a Baseline
Profile plus ledger scroll frame timings, and `BaselineProfileGenerator` covers
the journeys `04` §6 names. Two obstacles stand between that and real numbers:

1. Baseline Profile generation **requires root**, so it needs an AOSP or
   `google_apis` emulator image. Every AVD on this machine is
   `google_apis_playstore`, which cannot be rooted.
2. Emulator numbers would not be evidence anyway. `04` §2.2 defines the fallback
   if the reference device misses 800 ms after Baseline Profiles, single-Activity
   and R8 full mode: XML views for the entry and ledger screens only. **That
   decision point belongs at M1, with numbers** — it has not been reached.

Also outstanding: the debug-build rollup drift assertion (`03` §6 item 2, the
user-invocable rebuild is implemented as `Schema.REBUILD_ROLLUPS` but not yet
wired to a Settings action); Bengali `values-bn` strings, currently `en` only;
and everything in M2–M5.

`04` §11 records soft deletes as the one acknowledged design debt. Nothing here
changes that assessment — `deleted_at` remains cheap now and awkward to retrofit
if multi-device sync is ever likely.

---

## 9. Reproducing the results

```bash
./gradlew :app:architectureCheck        # the two structural rules
./gradlew :app:testDebugUnitTest        # 53 JVM tests
./gradlew :app:connectedAndroidTest     # 31 assertions, needs a device
./gradlew :app:assembleRelease          # R8 full mode
./gradlew :benchmark:assemble           # benchmark module compiles
```

Size and permission checks on the release artifact:

```bash
APK=app/build/outputs/apk/release/app-release-unsigned.apk
stat -c %s "$APK"                                   # 1180041
$SDK/build-tools/36.0.0/aapt2 dump permissions "$APK"
unzip -o "$APK" classes.dex -d /tmp && \
  $SDK/build-tools/36.0.0/dexdump -f /tmp/classes.dex | grep method_ids_size
```

The 320 dp layout check:

```bash
adb shell wm density 360     # 720 / (360/160) = 320 dp
adb shell wm density reset
```

---

## 10. Corrections to earlier reporting

The commit message on `2e9de85` and an earlier progress summary both state
**56** JVM unit tests. The verified count is **53** (`MoneyTest` 27,
`PeriodTest` 18, `NameKeyTest` 8). The README has been corrected; the commit
message is left as written rather than rewriting history over a miscount. No
other reported figure changed on re-verification.

---

## 11. The completion pass

The sections above describe the **scaffold**. An audit of that scaffold against
`02-SRS.md` found that five of the ten FR-EXP requirements were unimplemented,
so M1 as `01` §8 defines it had not actually been reached. This section records
closing that gap.

### 11.1 What was missing

Before this pass you could not change an expense's date, add a note, reach a
category outside the first six chips, edit anything, filter, or search. Tapping
a ledger row **deleted** it — the only interaction the screen offered was
destructive. Nothing outside `core/` had a single test.

| Requirement | Was | Now |
|---|---|---|
| FR-EXP-02 date overridable | date fixed to today | date picker, capped at today |
| FR-EXP-03 MRU categories | six chips, no way past them | `More…` opens the full picker |
| FR-EXP-04 leaves only | enforced by trigger only | roots render as non-selectable group headers |
| FR-EXP-05 note and method | neither reachable | note field; method picker replacing a six-tap cycle |
| FR-EXP-07 edit and delete | neither | tap to edit, swipe to delete |
| FR-EXP-08 filter and search | neither | date range, root, leaf, method; note substring or exact amount |

### 11.2 Decisions taken

**Future dates are refused.** FR-EXP-02 says the date must be user-overridable
and the SRS is silent on whether a future one is legal. It matters: a
future-dated row posts straight into the period rollup, inflating this month's
spend and deflating safe-to-spend with money that has not left the user's hand.
The schema already has the right mechanism for money that has not happened —
`status = pending` — and that arrives with recurring rules at P1.

**Editing reuses the entry sheet.** One component means one set of validation
rules, one keypad, and one surface to keep accessible, rather than a second
screen that drifts from the first.

**Bengali is not shipped.** Every user-facing string was extracted to
`strings.xml`, including the content descriptions TalkBack speaks — but `"bn"`
was *removed* from `localeFilters` rather than left declaring a locale with no
resources behind it. Financial vocabulary is the wrong place to guess at
register, and the extraction is the part that needed doing.

### 11.3 Defects found and fixed

Nine, all in code the previous pass had already reported as working.

| Defect | Consequence |
|---|---|
| The entry sheet's ViewModel resolves to the **Activity** store | Type ৳250, dismiss, reopen — ৳250 was still there |
| Undo used `SnackbarDuration.Short` ≈ 4 s | NFR-USE-03 requires "at least 5 seconds", which the surrounding comment claimed |
| Screen transitions used a raw `tween` | Animator scale 0 still animated — the one place a reduced-motion user notices most |
| Three bare `LocalDate.now()` | Bypassed the injected `Clock`; two also re-ran on every recomposition |
| `AppContainer.clock` was a hardcoded field | Its KDoc promised tests could pin "today"; nothing could |
| The last-used category was validated against a tree that had not loaded | Silently fell back to the first chip on a cold open |
| `filesDir` resolved during `Application.onCreate` | **30 ms of main-thread disk** on the tightest budget in the app |
| `LocalDate.EPOCH` as a default | API 34+; this app ships to API 26, so `NoSuchFieldError` on every device below Android 14 |
| Locale read non-observably in a composable | Grouping and the spoken form would keep the old locale after a language change |

The last two were found by Android lint, which this pass enabled. That is the
argument for enabling it: the `EPOCH` constant would have crashed on the entire
API 26–33 range, which no device in this project's test fleet covers.

### 11.4 StrictMode had to learn to discriminate

NFR-PERF-09 asks for zero main-thread database access "enforced by StrictMode in
debug builds", and the scaffold armed `penaltyDeath` to do it. On the Xiaomi test
device that killed the app on launch — for a disk read inside MediaTek's
game-detection heuristic, which stats the APK during activity start and is
attributed to this process because StrictMode charges Binder-inbound work to the
callee.

The policy now inspects each violation's stack: anything containing app frames
is fatal, anything originating entirely in vendor or framework code is logged.
The guarantee is exactly as strong for the code this project controls, which is
what the requirement is about. It immediately caught a real one — the `filesDir`
violation above.

### 11.5 Tests

From 53 JVM + 31 instrumented to **53 JVM + 94 instrumented**. The repository and
ViewModel layers had no coverage at all, including `insert()`, which every
expense in the product goes through.

| Suite | Tests | Covers |
|---|---|---|
| `SchemaAssertionsTest` | 29 | the 19 assertions of `03` §10.1, plus the six added triggers and two query plans |
| `SchemaValidationTest` | 2 | Room accepts the canonical schema across a close/reopen |
| `ExpenseRepositoryTest` | 16 | the write path, refunds, cross-period re-file, filters, keyset paging |
| `CategoryRepositoryTest` | 11 | FR-CAT rules mapped to typed errors; archiving cascades |
| `QuickAddViewModelTest` | 15 | keypad, defaults, date clamp, save, edit, reset |
| `LedgerViewModelTest` | 11 | day grouping, filters, search, undo, paging, live refresh |
| `AccessibilityTest` | 10 | money as words, named controls, 48 dp targets, font scale 0.85×/1.3× |

Three things were learned writing them, each of which changed production code:

- **ViewModels needed an injectable dispatcher.** With a hardcoded
  `Dispatchers.IO`, cancelling a ViewModel in teardown raced the query it was
  still running, and the resulting crash was attributed to whichever test ran
  next — a suite that failed differently every run.
- **`runTest` is the wrong tool here.** Room dispatches onto its own executor, so
  these are integration tests over real threads; `runTest`'s virtual clock
  expires every timeout instantly while the database is still answering.
- **The entry state needed a `seeded` flag.** The category tree and the chip row
  settle from their own flow and can arrive *before* the form is seeded, so
  there was a window showing a chip selection but the default payment method
  rather than the last-used one.

### 11.6 Test-environment finding

The Compose UI tests **cannot run on API 37**. Espresso 3.7.0 — the newest
release — calls `android.hardware.input.InputManager.getInstance()`, which no
longer exists, and every Compose test fails in `Espresso.onIdle()` before
reaching an assertion. The repository and ViewModel suites are unaffected because
they never touch Espresso.

The suite therefore runs on an **API 35 `google_apis` emulator**, created for
this purpose. That image is also rootable, which is what finally made Baseline
Profile generation possible — the Play Store images this project started with
cannot be rooted, and §8 recorded that as a blocker.

### 11.7 The release build, finally run

The scaffold shipped a release APK that had **never been installed**. R8 full
mode is exactly where Room's generated code and kotlinx.serialization break at
runtime rather than at compile time, so an unrun release build is an untested
one.

It was signed with a throwaway key, installed, and driven through the full M1
loop — add an expense, read it back from the ledger. No crash, no missing class,
no `-keep` rule needed beyond those already in `proguard-rules.pro`.

It also surfaced a defect the debug build had hidden: **every ledger row was
drawn over a red band**. `SwipeToDismissBox` composes its `backgroundContent`
unconditionally, and the Khata ledger row is deliberately transparent — the rule
is its structure, not a card — so the delete ground showed through at rest. The
background is now painted only while a swipe is under way, and the row is opaque
so the ground slides out from under it rather than through it.

Two notes for whoever ships this for real:

- `signingConfigs` reads a gitignored `keystore.properties`; with none present,
  `assembleRelease` still succeeds and produces an unsigned APK, so a fresh
  clone can run the size and shrinking checks. Supply a real key before
  distributing.
- Baseline Profile generation is now *possible* — the `android-35 google_apis`
  AVD is rootable, and the benchmark module's runner was wrong
  (`AndroidBenchmarkRunner` ships with the microbenchmark artifact, not
  Macrobenchmark, so it failed with `ClassNotFoundException` before any test
  ran). That is fixed, but the generation run was not completed in this session.
  `./gradlew :app:generateBaselineProfile` against that AVD is the command.

### 11.8 Still not done

Unchanged from §8: **every NFR-PERF target remains unmeasured.** A physical
Xiaomi Redmi 13C (8 × 1.8 GHz, 6 GB, Android 15) was connected during this pass
and is far closer to the reference device than any emulator — but MIUI blocks
installing the instrumentation APK over USB (`INSTALL_FAILED_USER_RESTRICTED`),
which needs "Install via USB" enabled in developer options before the benchmark
suite can run there. The app APK itself installs and runs on it fine.

Also outstanding: Bengali translations (every string is extracted and `bn` is
deliberately not declared), the category manager, and M2–M5.

---

## 12. M2 — category tree, budgets, alerts

**Date:** 15 August 2026

`01` §8 defines the milestone in one line:

> `| M2 | Category tree, budgets, alerts | Budgets reconcile against ledger |`

The exit criterion is a reconciliation claim, not a feature list, so §12.7 is
where this milestone is actually judged.

### 12.1 M1 had already built most of the machinery

`BudgetDao`, `BudgetStatus`, `BudgetBar`, `RollupDao.observeBudgetBars` and the
entire `CategoryRepository` CRUD surface all existed, were correct, and were
already covered by tests — **but not one of them had a caller in `main/`.** M2
was therefore mostly UI and one missing repository, over machinery that was
already proven.

| Asset | State at the start of M2 |
|---|---|
| `RollupDao.observeBudgetBars` | correct; needed `parent_id` added to the projection |
| `BudgetDao` | correct; no upsert, which `BudgetRepository` now supplies |
| `BudgetStatus.of()` | correct, thresholds already matching FR-BUD-06; **no test** |
| `BudgetBar` | correct; needed the ticked variant for FR-BUD-07 |
| `CategoryRepository` | complete and tested; **no UI at all** |
| `Period.prev/next/daysRemainingInclusive` | complete; needed a display label |
| `AppMetaRepository.lastViewedPeriod` | existed, never called |
| `ChevronLeft` / `ChevronRight` | drawn in M1 for this screen, unused until now |
| `NumericKeypad` | reused verbatim for limit entry |

### 12.2 Scope resolved from the documents' own precedence

`02` §2: *"`MUST` denotes a release blocker; `SHOULD` denotes a P1 deferral
candidate."* Two requirements sit on the boundary and that rule decides both:

- **FR-BUD-04 copy-budgets is in.** `01` §7 lists it under P1, but `01` §6.2
  lists it as P0 and FR-BUD-04 is a `MUST`. The `MUST` wins.
- **FR-CAT-11 reorder is out.** It is the one `SHOULD` in FR-CAT and `01` §7
  puts it in P1. `sort_order`, its index and every `ORDER BY` already exist, so
  adding it later is a write path and a drag handle, nothing more.

### 12.3 Four decisions the documents leave undefined

| Question | Decision | Reason |
|---|---|---|
| Where alerts surface, since the dashboard is M4 | A **needs-attention block** at the top of the Budget screen, over-budget first, **absent entirely when empty** | `05` §5.4: "An empty state here would train the user to ignore the region." The M4 dashboard will call the same `BudgetAlerts.from`. |
| What the alert line says | `Grocery` · `৳280 over`, and for approaching, `৳150 left · 18 days to go` | `05` §5.4's dashboard line is `Grocery  104%  ৳280 over ▲` — it carries the percentage, which this one drops in favour of the days left. ৳150 with eighteen days is a different situation from ৳150 with one, and the percentage is already on the row below on this screen. **When the M4 dashboard renders this block it should follow §5.4**, where the row below does not exist. |
| What "visually distinguished" means for `unpredictable` (FR-BUD-07) | A **ticked track** instead of a solid fill, and text reading `৳2,400 of ৳5,000` — never `left` | `01` §6.2: "a buffer, not a plan. Under-spending it is a win, not an unused allocation." |
| A limit of ৳0, which FR-BUD-08 permits | **Rejected in the UI**, with `Clear limit` as the way to have none | The bar query reads a missing row as `IFNULL(limit_minor, 0)`, so a stored zero and no budget at all are indistinguishable downstream — and the percentage would divide by zero. |
| FR-BUD-04 colliding with limits already set | **Copy only the gaps**, never overwrite; snackbar with Undo | Satisfies "not overwritten without confirmation" by never overwriting, which also removes the confirmation dialog `05` §8 argues against — and makes Undo a pure removal rather than a restore. |

### 12.4 One departure worth recording: nothing is ever deletable

FR-CAT-10 forbids deleting a category *referenced by any expense or budget row*,
which implies an unreferenced one may be deletable. **The implementation offers
no delete anywhere** — `CategoryRepository` has no delete method, so no screen
can offer one, and `CategoryManagerViewModelTest` asserts that absence by
reflection so it cannot be reintroduced by accident.

The trade-off is real but small: a mistyped category cannot be removed, only
renamed or archived. Rename fixes the typo and archive removes it from every
picker, so the residual cost is one row in the Archived section. Against that,
the schema's `ON DELETE RESTRICT` and the UI agree completely, and there is no
path where a delete is offered and then refused.

### 12.5 Defects found and fixed during the pass

Eight, five of them in code written earlier in this same milestone.

1. **The empty state hid the entire product.** `BudgetUiState.isEmpty` was
   `groups.all { it.isUnbudgeted }`, which is exactly the state a first-run user
   is in — so the screen showed "No limits set for this month" instead of the
   thirteen rows that each carry a `Set one` action. The list *is* the empty
   state's call to action. Found by installing the build and looking at it;
   `isEmpty` now means "there is nothing to budget at all".
2. **The budget rows had no text in the semantics tree.** Every child `Text` in
   `LimitRow` and `AlertRow` carried `clearAndSetSemantics {}`, which strips the
   words entirely rather than merely suppressing them from the announcement.
   That costs Select-to-Speak and every other service that reads text rather
   than descriptions. `LedgerRow` never did this. Both now merge descendants and
   set `contentDescription`, which already wins for TalkBack; the one child
   still cleared is `MoneyText`, whose own description would otherwise be
   announced twice. Found by `BudgetScreenTest` timing out.
3. **Announced figures were currency strings.** The spoken description read
   `Grocery, three hundred taka, ৳700 left` — half words, half glyphs, against
   `05` §10. Both halves are now built from the same string resources, with
   `spokenForm` for the announced one and `format` for the visible one.
4. **`AlertRow` hard-coded "over" and "left" in Kotlin** rather than reading
   `strings.xml`, which would have survived into a Bengali build untranslated.
5. **Quantified copy was hand-pluralised.** `Copied %1$d limits` and
   `%1$d filters` produce "Copied 1 limits"; `left_with_days` had a second
   string, `left_with_one_day`, doing the singular by hand. All three are now
   `<plurals>`, and the hand-written singular is deleted.
6. **The copy-from-last-month handler was duplicated** across two branches of
   the layout — and one of the two was missed during an edit, which is how it
   was found. It is now declared once and passed to both.
7. **`LocalContext.current.resources` is not configuration-aware.** Reading the
   plural through it would hold a stale `Resources` across a locale or
   font-scale change. `LocalResources.current` is the configuration-aware
   equivalent; lint's `LocalContextResourcesRead` caught this one.
8. **The budget rows had no leader dots.** `05` §6 lists them in the ledger-row
   specification and the budget row is that row's 72 dp with-bar variant, so a
   plain gap there is a design-system deviation — the ledger and the budget
   screen would have drawn the same relationship two different ways. The dashed
   line is now a shared `LeaderDots` composable rather than a second copy.

   The needs-attention rows deliberately do **not** get them: leader dots
   connect a label to a *figure*, and `৳900 left · 6 days to go` is a sentence.

Three of the eight were in code M1 shipped: the hand-pluralised
`filters_active`, and four strings lint reported as unused (`budget_title`,
`category_name`, `system_group`, and `coming_budget` — the placeholder this
milestone replaced).

### 12.6 What was built

| Layer | Files | Notes |
|---|---|---|
| `data/repo/BudgetRepository.kt` | new | upsert preserving `uuid` and `created_at`, leaf-only and zero rejection as typed errors, copy-gaps-only in one transaction |
| `domain/usecase/` | new package | `BudgetSummary` folds bars + tree into groups; `BudgetAlerts` derives the needs-attention list. Pure Kotlin, no Android, JVM-tested — NFR-MAIN-01 |
| `ui/common/PeriodSwitcher.kt` | new | 48 dp targets around 24 dp chevrons; `05` §10 names "period arrows" explicitly |
| `ui/feature/budget/` | new | `BudgetViewModel`, `BudgetScreen`, `LimitSheet` |
| `ui/feature/category/` | new | `CategoryManagerViewModel`, `CategoryManagerScreen`, `CategoryEditorSheet` |
| `RollupDao.BudgetBarRow` | changed | gained `parentId`, so grouping leaves under their roots needs no second query |
| `BudgetStatus` | changed | gained `percentConsumed`, **unclamped** so 104% renders — a bar pinned at 100% while the text says "over" is two signals disagreeing |
| `ui/AppNav.kt` | changed | the viewed period is hoisted above the `NavHost` and persisted through `app_meta` |
| `ui/common/LedgerRow.kt` | changed | leader dots extracted as `LeaderDots`, shared with the budget rows |

The period is hoisted because Budget, Income (M3) and Dashboard (M4) must agree
on it: a user who steps back to July on one screen has not asked to be on August
everywhere else. `rememberSaveable` covers rotation and process death;
`AppMetaRepository.setLastViewedPeriod` covers relaunch, which is the "period"
half of FR-APP-03 and the reason `KEY_LAST_PERIOD` was written in M1. The read
goes through `runCatching` — `Period`'s `init` throws on a corrupt or
future-schema value, and remembering a month is not worth taking the app down on
launch for.

### 12.7 The exit criterion, asserted

Every figure the budget screen shows comes from `rollup_expense_month`, which is
trigger-maintained and never recomputed. That is what keeps the screen's cost
bounded by the leaf count rather than by how much history exists, and the price
of it is that the same fact is stored twice. NFR-REL-02 requires the two copies
to agree.

`BudgetReconciliationTest` asserts the agreement directly: for every leaf the
screen renders, `status.spent` must equal `SUM(amount_minor)` taken straight off
the `expense` table for that (category, period) — not the rollup read a second
way, the ledger itself. It runs through `BudgetViewModel` rather than the DAO so
that the query, the grouping fold and the root sums are all inside the
assertion. The reconciliation that matters is between what a user *sees* and
what they logged.

Eight scenarios, each a way the two copies could drift apart:

| Scenario | What it would catch |
|---|---|
| A month of ordinary spending | the baseline |
| Refunds — negative amounts (FR-EXP-06) | a screen that summed absolute values |
| An expense re-filed into another category **and** another month (FR-EXP-07) | a phantom figure left behind in the old month |
| A deleted expense | residue in the rollup after the ledger row is gone |
| A pending entry, `status <> 0` | recurring rules (M5) double-counting on confirmation |
| Limits set, revised, copied and cleared | a join in the bar query dropping or duplicating rows |
| An archived category carrying spend | FR-CAT-08's `OR r.total_minor IS NOT NULL` clause getting lost — the leaf vanishes while its money stays in the ledger |
| Every mutation, then `REBUILD_ROLLUPS`, then compare | the trigger set and the rebuild query disagreeing |

The last is assertion 19 of `03` §10.1 restated at the level the user reads.

### 12.8 Test inventory

From **53 JVM + 94 instrumented** to **80 JVM + 174 instrumented**.

The instrumented suite reached a clean **174 / 174** once, and the four source
changes made after that run — `<plurals>`, `LocalResources`, `LeaderDots`, and
the `show()` settle in `BudgetScreenTest` — have not been re-run end to end. The
last complete run was **173 / 174**: the single failure was
`the_categories_action_is_present_and_reachable` closing the in-memory database
under a query still in flight, because it is one of the few tests that seeds
nothing and so tears down almost immediately. `show()` now waits for a group
header before returning, which settles the screen; **that fix is applied but not
verified.** See §12.10.

#### JVM — 80, all passing

| Suite | Tests | Covers |
|---|---|---|
| `MoneyTest` | 27 | unchanged from M1 |
| `PeriodTest` | 18 | unchanged from M1 |
| `BudgetStatusTest` | 12 | **new** — thresholds at exactly 80% and 100%, FR-BUD-06's literal acceptance figures (৳5,600 / ৳7,000 → NEAR; ৳7,000 / ৳7,000 → OVER), the unclamped percentage |
| `BudgetAlertsTest` | 9 | **new** — ordering, unplanned suppression, unbudgeted exclusion |
| `NameKeyTest` | 8 | unchanged from M1 |
| `BudgetSummaryTest` | 6 | **new** — root sums, group ordering, empty-group dropping |

#### Instrumented — 174

| Suite | Tests | Covers |
|---|---|---|
| `SchemaAssertionsTest` | 31 | the 19 assertions of `03` §10.1, the six added triggers, and **the real `observeBudgetBars` SQL** under `EXPLAIN QUERY PLAN` |
| `BudgetScreenTest` | 22 | **new** — every budget state stated in words, 48 dp period arrows, figures announced as words, font scale 0.85×/1.3×, the limit sheet end to end |
| `ExpenseRepositoryTest` | 16 | unchanged from M1 |
| `BudgetRepositoryTest` | 16 | **new** — upsert idempotence, uuid preservation, leaf-only and zero rejection, copy-gaps-only, undo |
| `QuickAddViewModelTest` | 15 | unchanged from M1 |
| `BudgetViewModelTest` | 15 | **new** — grouping and ordering, live bar movement, alert thresholds, period switching, the editor, FR-BUD-04 with undo |
| `CategoryManagerViewModelTest` | 13 | **new** — nature inheritance, depth, duplicate names, archive cascade with undo, no-delete-anywhere by reflection |
| `LedgerViewModelTest` | 11 | unchanged from M1 |
| `CategoryRepositoryTest` | 11 | unchanged from M1 |
| `AccessibilityTest` | 10 | unchanged from M1 |
| `BudgetReconciliationTest` | 8 | **new** — the M2 exit criterion (§12.7) |
| `SchemaValidationTest` | 2 | unchanged from M1 |

Three things were learned writing them:

- **`awaitState` predicates must be total.** A lookup that throws when the leaf
  is absent fails the test with "no element matching the predicate" instead of
  waiting, because `StateFlow.first(predicate)` evaluates against the *initial*
  state — where the query has not answered yet. Four tests failed this way
  before the helpers were made null-safe.
- **`= runBlocking { … }` silently gives a test a non-`void` return type** when
  its last expression is an assertion that returns something. JUnit then rejects
  the whole class with `InvalidTestClassError`, so twenty-two tests reported as
  a single `initializationError` and none of them ran. `runBlocking<Unit>` fixes
  it.
- **The query-plan assertion now embeds the real SQL**, parameter substituted,
  rather than an approximation of it. The M1 version had already drifted — it
  predated `parent_id` in the projection — which is exactly the failure mode a
  hand-copied assertion has.

### 12.9 Measured results

| Constraint | Target | M1 | M2 |
|---|---|---|---|
| NFR-SIZE-01 APK download size | ≤ 6 MB | 1.41 MB | **1.46 MB (1,530,577 bytes)** |
| NFR-SIZE-03 dex methods, single dex | ≤ 40,000 | 16,628 | **16,948 in 3,205 classes** |
| FR-APP-01 no `INTERNET` permission | none declared | confirmed | **confirmed on the merged release manifest** |
| Android lint, release | no errors | 0 errors, 7 warnings | **0 errors, 6 warnings** |
| JVM unit tests | — | 53 passing | **80 passing** |
| Instrumented tests | — | 94 passing | **174 written; 173 green on the last complete run** (§12.8) |

**Source size:** 60 main source files (9,339 lines), 19 test
files (4,510 lines).

M2 cost 52 KB of APK and 320 methods — two screens, three sheets, a repository
and two use cases — with **no new dependency**. `navigation-compose` and
Material 3 were already paid for at M1.

**Still not measured:** every NFR-PERF target, for the reasons in §8 and §11.8.
Nothing in M2 changes that position. NFR-PERF-04 — the budget screen inside
300 ms with five years of data — is now a target with a screen behind it rather
than a hypothetical, and the query it depends on is asserted never to read the
`expense` table, which is the property that makes the target reachable. Whether
it is *reached* still needs the reference device.

### 12.10 Still not done

- **One instrumented re-run is outstanding.** The `Khata_API35` AVD stopped
  staying up: with the Gradle daemon at `-Xmx4096m`, a Kotlin daemon and the
  emulator on a 16 GB host, three consecutive runs died with
  `DeviceException: No connected devices!` rather than with a test failure. The
  fix for the one real failure (§12.8) is in place and the suite has been green
  at 174 / 174 on earlier code; confirming it needs either a host with more
  headroom or `./gradlew --stop` before `connectedAndroidTest`, which is the
  cheaper of the two.
- **No greyscale capture this pass.** NFR-USE-05's text signal is asserted by
  `BudgetScreenTest` — every state puts its condition into words — but the
  colour-and-fill half is a visual check and still wants a run with
  `settings put secure accessibility_display_daltonizer 0` (monochromacy) on a
  device.
- **The release APK has not been installed and driven for M2.** `assembleRelease`
  succeeds and the size and method figures in §12.9 come from it, but R8 full
  mode is exactly where the two new ViewModels would fail at runtime rather than
  at compile time. M1's §11.7 found a real defect this way.
- **Dashboard and Income remain placeholders** (M4 and M3).
- **Category reorder, FR-CAT-11**, deferred per §12.2.
- **Bengali translations.** Every string is extracted and quantified copy now
  uses `<plurals>`, so `values-bn` is a translation job rather than a
  refactoring one. `bn` is still deliberately not declared.
- **The rollup drift check has no Settings action.** `Schema.REBUILD_ROLLUPS`
  exists and `BudgetReconciliationTest` now exercises it, but `03` §6's
  user-invocable rebuild has no screen to live on yet.
- **Export/import and recurring rules** (M5, P1).

---

## 13. The M1 + M2 audit

M1 and M2 were both reported complete. This pass re-read the implementation
against `01`–`05` requirement by requirement rather than feature by feature,
looking for the two things a feature-shaped review does not find: a `MUST` that
the code satisfies in spirit but not in fact, and a state the code can reach
that nobody designed.

Six defects. One is a specification violation, four are silent data or copy
faults, and one is a safety net that could not fail.

### 13.1 An active leaf could survive under an archived root

**FR-CAT-08/09.** The manager listed every archived category individually,
children of archived roots included, each with its own Restore. Restoring one
produced a leaf that was active while its group was not — and
`observeSelectableLeaves` tested only the leaf's own `is_archived`, so **the
leaf reappeared in the Quick Add picker with no group anywhere in the manager**.
`createSubcategory` reached the same state from the other direction: nothing
stopped a new child being added under an archived root.

Fixed in three places, because one of them is a query the rest of the app trusts:

- `CategoryDao.observeSelectableLeaves` now excludes a leaf whose parent is
  archived. It no longer depends on the invariant holding above it.
- `CategoryRepository` refuses to restore a child while its root is archived,
  and refuses to add one to an archived root — both as
  `EntryError.CATEGORY_ARCHIVED`.
- The archived section offers no Restore on such a child at all. Absent, not
  rejected, which is the rule the rest of that screen already follows.

### 13.2 Undo restored more than the archive had removed

Archiving a root cascaded to its children; the snackbar's Undo cascaded back
**unconditionally**. So: archive Dining Out, archive its root a month later,
tap Undo — and Dining Out is back, un-archived, without the user asking. An
undo that reverses more than the action it follows is a silent data change.

The first fix attempted was a stamp: identify the cascaded group by the
`updated_at` the cascade wrote. It was discarded before it shipped. It is a
heuristic — two operations inside one millisecond are indistinguishable — and
under the pinned `Clock` every test fixture uses, it is not merely unlikely to
discriminate but *guaranteed* not to.

What replaced it is exact. `CategoryRepository.archive` returns every id it
actually changed — the category plus, for a root, the children that were still
active — and `restoreAll(ids)` reverses precisely that list. A child archived
deliberately beforehand is not in it and is not touched. This is the same shape
as the budget copy's Undo, which already carried the ids it had added.

Restore from the archived section is deliberately **not** the cascade run
backwards: a root comes back alone, and its children become individually
restorable now that they have a group. After archiving everything, wanting two
of five back is the ordinary case, and a root with some children archived is a
state the app already handles everywhere.

### 13.3 FR-BUD-05 was not met: the limit was not on the row

> "For each budgeted category the system MUST display spent, limit, remaining,
> and percentage consumed for the selected period."

The row showed spent, remaining and percentage. The limit appeared only in the
group header — which is `SUM(limit_minor)` across every leaf under that root,
and therefore not the limit of any leaf on any row. For a root with five
children, a leaf's own limit was on the screen nowhere.

The percentage now carries it: `104% of ৳9,000`. That is the cheapest place for
it on 288 dp of content, and it repairs the percentage too — *104% of what* is
the question a bare figure invites. Unpredictable rows are unchanged: they
already read `৳2,400 of ৳5,000`, so adding it there would print one figure
twice. The spoken description carries the limit as well; FR-BUD-05 is not met
for a TalkBack user by a figure that exists only visually.

### 13.4 Spending exactly the limit read "৳0 over"

FR-BUD-06 puts the over-budget state at ≥ 100%, so a leaf spent exactly to its
limit is `OVER` — correctly. But its overspend is zero, and the general copy
therefore printed `৳0 over` on the row and in the needs-attention block. The
calculation was right; the sentence built on it was not.

`limit_reached` — "Limit reached" — is the one case that needed its own words.
The state, the colour and the bar are unchanged. `BudgetStatusTest` pins both
halves so the copy fix cannot drift from the arithmetic that motivates it.

### 13.5 Copy-from-last-month wrote limits onto archived categories

`copyableFromPreviousPeriod` read the `budget` table and nothing else. A leaf
archived since last month got a fresh limit for a category the user can no
longer spend into — and because the budget screen renders an archived leaf only
when it carries spend, the row never appeared, so **nothing on any screen could
clear it again**. A write with no read and no undo.

`BudgetDao.forPeriodActive` joins the category and excludes archived leaves and
leaves under archived roots. Both the copy and the count that drives the chip's
disabled state read it.

### 13.6 The drift check could not detect the failure it exists for

`assertRollupsReconcile` runs on every debug launch and is the only runtime
guard that NFR-REL-02 still holds. It joined outward from `rollup_expense_month`
to the ledger, so it compared the buckets that exist. **A bucket that is missing
entirely — which is precisely what a trigger that failed to fire produces — was
invisible to it.** It also checked only the expense rollups, leaving
`rollup_income_month` unverified: the table with no screen reading it until M3,
and the one whose three triggers were missing from the published SQL in the
first place.

Both tables are now compared symmetrically: the rollup summed positively and
the ledger negatively over the union of their keys, so any surviving non-zero
residual is drift in either direction. `RollupDriftCheckTest` corrupts the
rollups six ways — wrong total, wrong count, missing bucket, orphan bucket, and
an income-side fault — and asserts the check notices each. A self-check that
cannot fail is worse than none, because it gets read as evidence.

### 13.7 Considered and deliberately not changed

- **`spent == limit` is `OVER`, not `NEAR`.** FR-BUD-06 says ≥ 100%. The copy
  was the bug, not the threshold.
- **A limit of ৳0 stays refused** despite FR-BUD-08 permitting it. The reason
  is recorded at §12.3 and is unchanged: `IFNULL(limit_minor, 0)` makes a
  stored zero indistinguishable from no budget, and the percentage would divide
  by it.
- **`ExpenseRepository` still does not reject a future date**, though
  `EntryError.FUTURE_DATE` exists for it. `QuickAddViewModel.setDate` is the
  only way to set one and it refuses; duplicating the guard below would be
  defence in depth, but the entry point is closed and pending status — the real
  mechanism for future money — arrives with recurring rules at P1.
- **Category reorder, FR-CAT-11**, remains deferred per §12.2.

### 13.8 Verification

`architectureCheck`, `testDebugUnitTest`, `compileDebugAndroidTestKotlin`,
`lintRelease` and `assembleRelease` all pass. **81 JVM tests**, 0 failures.
Lint release: 0 errors, 6 warnings.

The budgets are unmoved by the fixes: **1,530,649 bytes (1.46 MB)** against the
6 MB ceiling, and **16,968 methods in 3,212 classes** in a single dex against
40,000 — 20 methods and 72 bytes more than §12.9 measured.

The instrumented suite gained **16 tests** (190 total) and none of them have
been run: the emulator work was called off during the M2 pass and has not been
resumed, so every instrumented figure in this section is *written and compiled*,
not *executed*. §12.10's outstanding run now covers a larger suite. That is the
honest state of it.

---

## 14. M3 — the income module

`01-PRD.md` §8: *"M3 · Income module · Yearly totals match manual calculation."*

Income is the reason this product exists. PRD §1 names the failure it is built
against — standard apps assume a fixed monthly salary, so their averages are
"meaningless when five months earn nothing and the sixth earns a year's worth" —
and every decision below follows from that one sentence.

### 14.1 M1 had already built the foundation, and nothing had ever read it

Both tables, their four indexes, `IncomeDao`, `IncomeKind`, `NameKey` and the
seeded Salary source were correct and had **no caller in `main/`**. So were the
three `rollup_income_month` triggers — the ones §5 records as *missing from the
published `schema_v1.sql` and added* — without which every income figure reads
৳0 while the ledger underneath stays perfect.

That is not a hypothetical. It is the exact failure this milestone's exit
criterion exists to rule out, and until M3 nothing in the app would have noticed
it.

### 14.2 Four decisions the documents leave open

| Question | Choice |
|---|---|
| How income entry is reached, since NFR-USE-01 keeps the FAB on expense entry from *every* primary screen and 05 §6 allows exactly one FAB | An **"Add income" header action**, mirroring "Categories" on the budget screen. The FAB contract is untouched. |
| FR-IS-05/06 require deleting a zero-entry source, and a *disabled* delete with a message otherwise — against M2's "nothing deletable, constraints absent not disabled" | **Follow the SRS exactly.** Both are `MUST`s with explicit acceptance criteria. §14.4 has the reasoning. |
| The income screen defaults to a year while every other screen is monthly, and the period is hoisted above the `NavHost` | Income owns a **scope** (Year / Month / Range); year and month steps **write back to the one shared period**. Stepping to 2025 puts the whole app on the same month of 2025. |
| FR-IE-04/05 need an arbitrary date range, but 03 §5.3 says ranges come "from the reports screen", which does not exist | **Built on the income screen now**, as a third scope. Deferring would leave two `MUST`s with no milestone owning them. |

### 14.3 Scope resolved from the documents' own precedence

- **FR-IS-07 source reorder is out** — the one `SHOULD` in FR-IS, and the exact
  mirror of FR-CAT-11's deferral at M2. `sort_order` and its index already exist.
- **The trend chart follows the scope.** FR-IE-07 asks for "a 12-month income
  trend ending at the currently selected period"; 05 §5.7's mock labels the bars
  `J F M A M J J A S O N D`. Both hold if the bars are the twelve periods ending
  at the selection — in Year scope that is January to December, which is the
  mock; in Month scope it is the trailing twelve, which is the requirement's
  wording. One rule, both readings.

### 14.4 The departure: Khata now has a delete

Every other removal in this app is an archive, and §12.4 records why at length —
"a deleted category silently rewrites history; an archived one preserves it",
which is also why every foreign key is `ON DELETE RESTRICT`.

FR-IS-06 is a `MUST`: *"The system MUST permit deletion of a source with zero
entries."* FR-IS-05's acceptance criterion goes further and specifies the
opposite of the category manager's interaction rule: *"Delete action on such a
source is **disabled** with an explanatory message offering Archive instead."*
The category manager's rule is that an unavailable action is **absent**, because
"a screen that offers an action and then explains why it failed satisfies
neither".

The SRS is normative, so the delete exists and the disabled form is what the
manager shows. The distinction also holds on its own terms: a category can never
be deleted at all, so an absent action teaches a true rule; a source *can* be
deleted, just not this one, and hiding the action would teach a false one. The
row shows its entry count beside the control, so the reason is visible rather
than inferred, and it is in the spoken description too — a disabled control with
no announced cause is worse than an absent one.

Deleting is still undoable for five seconds (NFR-USE-03). With no entries
pointing at it, restoring a source is one re-insert, uuid included.

### 14.5 One query answers the whole screen

`IncomeDao.observeCellsInPeriods` returns one row per (period, source) — sixty
rows for a year with five sources — and the hero total, the twelve bars, every
breakdown row and the stable subtotal are all folded from it by
`IncomeBreakdown.build`. Four figures that must agree, read once so they cannot
disagree, and one read for the exit criterion to reconcile.

03 §5.3 decides the other half: whole months come from `rollup_income_month`,
and a range that does not align to month boundaries falls back to the ledger on
`ix_income_entry_date`. `IncomeScope` is the type that makes that choice, and
`SchemaAssertionsTest` now carries an `EXPLAIN QUERY PLAN` assertion for both
(NFR-MAIN-03): the rollup path must never touch `income_entry`, and the ledger
path must walk the date index rather than scan.

Two of `IncomeDao`'s existing reads were repointed at the rollup as part of this
— they summed `income_entry` directly, against 03 §1 making the rollup the
source of every aggregate. That change is only safe because of the audit: §13.6
made the debug drift check symmetric and extended it to `rollup_income_month`,
and `IncomeReconciliationTest` asserts the equality the change assumes.

### 14.6 FR-IE-06 does not survive the obvious implementation

> "Percentages sum to 100 ± 0.1 after rounding."

Rounding each share independently does not do that. Three equal sources each
round to 33 and the column reads 99; six equal ones read 96. Near-equal sources
are an ordinary shape for this user's income, so this is the common case rather
than a corner one — and a breakdown whose percentages visibly fail to add up
undermines every other figure on the screen.

`IncomeBreakdown` apportions by **largest remainder**: every source gets its
floor, and the leftover points go to the largest fractional parts. The column
always totals exactly 100 and no source is ever more than a point from its exact
share. Ties break by amount and then by id, so the same data always renders the
same column.

**This is the second place the HTML mock's arithmetic does not hold.** 05 §5.7
prints 62 / 25 / 14 against ৳3,60,000 / ৳1,44,000 / ৳80,000 — which sums to
**101**. The exact shares are 61.64, 24.66 and 13.70, so the correct
apportionment is 61 / 25 / 14. Salary ends up 0.64 from exact instead of 0.36,
and in return the column adds up, which is the criterion and the thing a reader
can actually check. §12.5 found the first such discrepancy: §5.4's "18k" against
§4.3's "never abbreviate to 1.2k".

### 14.7 Stable coverage returns null rather than a number

FR-AN-06 is a dashboard requirement, but 05 §5.7 puts the line on the income
screen and calls it "the insight that matters", so `StableCoverage` is written
now and M4 reuses it unchanged.

Zero spending is not 100% coverage and it is not an error — it is a ratio with
no denominator. The function returns null and the screen omits the line, which
is 05 §5.4's rule ("sections that have nothing to say are absent, not empty")
rather than a special case invented for it. Zero *stable income* against real
spending returns 0: that is a real answer and an alarming one, and suppressing
it would hide exactly the situation the metric exists to surface. Coverage above
100% is not clamped, for the same reason `percentConsumed` is not clamped above
a budget limit.

### 14.8 One defect found before it shipped

**The filtered empty state had no way out.** Narrowing to a source with no
income in the window empties the list — and the filter control lives inside the
populated branch, so the user was left looking at *"Nothing recorded here yet.
Add what you earned"* with no way to widen the filter that produced it. That is
the same shape as the budget screen's first-run empty state (§13, and §12.5
before it): an empty state that reports a situation without carrying the control
that resolves it.

The empty state now switches copy and offers **Clear** whenever a filter is
active. Worth recording that this class of bug has appeared three times in this
codebase, each time in a different screen's empty branch.

### 14.9 The exit criterion, asserted

> "Yearly totals match manual calculation."

`IncomeReconciliationTest` performs the manual sum. Driving `IncomeViewModel`,
it asserts that **every figure the screen renders** — the hero total, each of
the twelve bars, every breakdown row, the percentages, and the stable subtotal —
equals a direct `SUM(amount_minor)` over `income_entry`, never the rollup read a
second way. FR-IE-04's own criterion uses the same words: "verified against a
manual sum".

Ten scenarios, chosen to be the ones that move a bucket without a plain insert:
several entries for one source in one month (FR-IE-02) and a source spanning
eleven months; a month scope; **an entry edited across a year boundary**, which
must leave both years right; a deletion; a delete-then-undo; a pending row
(`status = 1`), which must appear in neither the rollup nor the direct sum; an
archived source with history, which must still appear (FR-IS-04); a source
subset, where the percentages must still total 100 over what is left; a custom
range served by the ledger fallback; and a full `Schema.REBUILD_ROLLUPS`
comparison — the rebuild must reproduce the trigger-maintained state exactly.

### 14.10 Test inventory

| Suite | Where | Tests |
|---|---|---|
| `IncomeBreakdownTest` | JVM | 15 |
| `IncomeScopeTest` | JVM | 10 |
| `StableCoverageTest` | JVM | 6 |
| `IncomeRepositoryTest` | instrumented | 20 |
| `IncomeViewModelTest` | instrumented | 17 |
| `SourceManagerViewModelTest` | instrumented | 9 |
| `IncomeScreenTest` | instrumented | 16 |
| **`IncomeReconciliationTest`** | instrumented | 10 |
| `SchemaAssertionsTest` (query plans) | instrumented | +2 |

### 14.11 Measured

| | Target | M2 | M3 |
|---|---|---|---|
| Release APK | ≤ 6 MB (NFR-SIZE-01) | 1.46 MB | **1.51 MB** |
| Dex methods, single dex | ≤ 40,000 (NFR-SIZE-03) | 16,968 | **17,280** in 3,297 classes |
| Lint release | no errors | 0 errors, 6 warnings | **0 errors, 6 warnings** |
| JVM tests | — | 81 | **112, all passing** |
| Instrumented tests | — | 190 | **271, written** |
| Main sources | — | 60 files / 9,339 lines | **71 files / 12,951 lines** |

`architectureCheck`, `testDebugUnitTest`, `compileDebugAndroidTestKotlin`,
`lintRelease` and `assembleRelease` all pass. The income module cost **312
methods and 52 KB** — the whole of it, including the twelve-bar chart, which is
a `Canvas` and thirty draw calls rather than a charting library.

### 14.12 Still not done

- **Nothing instrumented has been run since M2.** The emulator work was called
  off during that pass and has not resumed, so every instrumented figure in §13
  and §14 is *written and compiled*, not *executed*. The suite was last green at
  174 / 174 on pre-audit code. This is now the largest outstanding item in the
  project, and it grows with every milestone.
- **The greyscale capture** for NFR-USE-05 is still owed, and M3 adds to it: the
  filled-versus-hollow source dot is a shape difference precisely so it survives
  monochromacy, and that has been reasoned about but not looked at.
- **The release APK has still not been installed and driven.** M1's §11.7 found
  a real R8-only defect that way.
- **Dashboard remains a placeholder** (M4). Reports, Settings, export/import and
  recurring rules are untouched.
- **Source reorder, FR-IS-07**, deferred per §14.3.

---

## 15. The M3 audit

A second reading of the income module against `01`–`05`, requirement by
requirement, in the shape §13 used for M1 and M2. Twelve defects, two of them
structural.

### 15.1 The two structural defects were already pinned by tests that had never run

`IncomeReconciliationTest` — the M3 exit criterion — asserts the correct
behaviour in both cases:

| Defect | The assertion that would have caught it |
|---|---|
| 15.2, the trend | `a_month_scope_reconciles_against_that_month_alone`, which compares every bar to a direct period sum |
| 15.3, zeroed buckets | `a_rebuild_reproduces_exactly_what_the_triggers_maintained`, whose scenario deletes a source's only entry |

Both were written during M3, both compile, and neither has ever been executed,
because the instrumented suite has not run since M2 (§12.10, §13.8, §14.11).
That is the second piece of direct evidence that the untested surface is where
the defects live, and it is worth more than the note it has been getting: the
tests were right, the code was wrong, and nothing in the pipeline could tell
the difference.

### 15.2 The twelve bars were eleven zeros in two of three scopes

`IncomeViewModel` read cells over `scope.window` and folded them into
`scope.trendPeriods()`. Those two agree **only in Year scope**, where the window
happens to be January–December and the trend is the same twelve months.

- **Month scope**: the window is one period and the trend is the trailing
  twelve, so eleven bars were structurally zero. A user stepping to August saw a
  chart claiming they had earned nothing for eleven months — on the one screen
  that exists because "five months earn nothing and the sixth earns a year's
  worth" (PRD §1) and the shape of the year *is* the information.
- **Range scope**: only the months the range touched could draw, and they drew
  the range's slice rather than the month.

FR-IE-07 asks for "a 12-month income trend ending at the currently selected
period", and the screen rendered a false one. The defect was invisible in the
default scope, which is why it survived the build.

The trend now has its own window. `IncomeScope.trendWindow` is always twelve
whole periods and therefore always rollup-backed, even when the scope is a range
03 §5.3 sends to the ledger; `IncomeBreakdown.build` takes the trend cells
separately from the window cells, filtered by the same source subset so the bars
narrow exactly as the total does. The second subscription is opened **only when
the two windows differ**, so the default scope still issues one aggregate query.

What this makes true, and should stay true: in Month scope the bars sum to the
trailing twelve while the hero figure shows the month. The chart is context for
the figure, not a second rendering of it.

### 15.3 A zeroed rollup bucket was a permanent ৳0 breakdown row

`trg_rollup_inc_del` decrements `total_minor` and `entry_count` and never
removes the row, so deleting a source's last entry in a month left
`(period, source, 0, 0)` behind. `observeCellsInPeriods` had no filter, so three
things followed:

- the breakdown rendered `Consulting  ৳0  0%  ○` for ever, and nothing the user
  could do would clear it;
- `IncomeSummary.isEmpty` is "no breakdown rows", so deleting every income entry
  in a year never returned the screen to its empty state — a hero total of ৳0
  above a list of ৳0 sources, which is not an empty state but a wrong one;
- Range scope, served by `GROUP BY` over the ledger, produces no such row, so
  **the same data yielded a different breakdown depending on the scope**.

Fixed at the read — `AND r.entry_count > 0`, in `observeCellsInPeriods` and in
`RollupDao.observeIncomeBySource`, which is 03 §5.2's query and M4's to inherit.

**The trigger is left alone deliberately, and this is the divergence to know
about.** `REBUILD_ROLLUPS` regenerates by `GROUP BY` and so never produces a
zero row; the triggers do. Three reasons not to close that at the trigger:
`SchemaAssertionsTest.income_rollups_are_maintained_by_trigger_on_all_three_mutations`
asserts the zeroed row survives the delete and is an M1 assertion about the
schema, not about income; the expense rollup has the identical shape and would
have to move with it; and triggers are installed only in
`CanonicalSchema.onCreate`, so a trigger change reaches no database that already
exists, and there is no migration framework until M5. The query fix reaches
every install immediately, including those already holding zero buckets.

Worth being precise about what the divergence is and is not. It is not drift:
the debug check in `AppContainer` compares the two representations over the
union of their keys, and a bucket with no total and no count contributes zero to
both deltas, so it is correctly not flagged. It is a residue, and the read is
the right place to ignore it.

### 15.4 05 §9's zero-income month line did not exist

The UI guide specifies it in its sample-strings table and then singles it out in
prose:

> | Zero income month | `Nothing recorded in August. Your year is at ৳5,84,000` |
>
> "The zero-income line is worth noting: it refuses to render an empty month as
> a failure, and immediately reframes to the unit that is meaningful for this
> user."

An empty month fell through to `empty_income` — "Nothing recorded here yet. Add
what you earned and the months fill in." That is the generic first-run
invitation, and it renders an empty month as exactly the failure the guide says
not to render. The screen had implemented one half of the accommodation, the
year-first default, and not the other.

`empty_income_month` now carries the reframe, over a year figure from
`RollupDao.observeIncomeTotalInPeriods` that is subscribed **only in Month
scope**, because nothing else on the screen wants it. A zero year total falls
back to the invitation: there is nothing to reframe to, and "Your year is at ৳0"
is a worse sentence than the one it would replace.

### 15.5 In Range scope the arrows did nothing, and something

`onStep` always wrote back to the shared period. A range is absolute, so the
label and every figure on the income screen held perfectly still while Budget
and Dashboard silently moved to another month — a control that looked dead and
acted somewhere the user could not see. It also announced "Previous month",
which was untrue in two different ways at once.

The arrows now branch on scope. In Range they shift the range by its own span
and do **not** write back, which is the approved decision that Range is
transient, and which makes like-for-like comparison one tap. Clamped at today,
because the range picker refuses a future date and the arrows must not produce a
window it would have rejected. `previous_range` / `next_range` say so aloud.

### 15.6 An archived source was filterable everywhere except in the filter

The filter sheet's chips came from `observeActiveSources()`. FR-IS-04 excludes
archived sources from **entry pickers**; a filter is not one, and the same
requirement's second half puts archived sources squarely in historical reports.
Two consequences, and the second is the worse one:

- FR-IE-05's own acceptance example — "Selecting {Salary, Farming} for
  2026-01-01 to 2026-12-31" — became unperformable the moment Farming was
  archived, while its rows sat visible in the breakdown above.
- Tapping an archived source's breakdown row set the filter, and the sheet then
  showed **no chip selected and "Any source" unselected too**. It could not
  represent the filter that was actually in force, let alone clear it.

`IncomeSummary.presentSources` now reports every source with income in the
window, taken **before** the subset filter — deliberately, because deriving the
chip list from the breakdown would mean narrowing to one source deletes the
others from the control that widens it again. `IncomeUiState.filterSources`
merges that with the active list. The **entry** sheet still binds to
`observeActiveSources()`, which is what FR-IS-04 actually governs.

### 15.7 Seven smaller ones

| | Defect | Fix |
|---|---|---|
| D6 | The filter sheet headed its source section with the entry sheet's question, `income_source_hint` = "Where did it come from?" | `filter_sources` |
| D7 | `RangeDatePicker` called `LocalDate.now()` — the system clock, in an app that threads an injected one everywhere and pins it in every test | `today` on the UI state, from the ViewModel's clock |
| D8 | Five source-domain refusals returned `CATEGORY_NOT_FOUND`, which `ErrorCopy` prints as "Pick a category" on a screen with no categories on it; and `toIncomeError` mapped a `FOREIGN KEY` failure to `SOURCE_HAS_ENTRIES`, which on the insert path means the opposite of what happened | `EntryError.SOURCE_NOT_FOUND` and its copy |
| D9 | `YearBars` spoke its total through `spokenForm()`, defaulting to `Locale.getDefault()`, while every other figure on the screen used the configuration-aware `rememberJavaLocale()` — so one figure would announce on a different numbering scale from the rest | `locale` as a parameter |
| D10 | `Modifier.semantics { contentDescription = "" }` on the scope-chip row, which adds a semantics node carrying nothing rather than clearing anything | Removed |
| D11 | `stable_coverage` dropped 05 §5.7's "this year", so in Month and Range scope the sentence named no window at all | One string per scope |
| D12 | The source editor wrote a rename and a kind change as two transactions, so the second could fail behind the first and leave a source renamed but still classified wrongly — and the kind is the one field the coverage figure depends on | `IncomeRepository.updateSource`, one transaction |

### 15.8 Measured after the audit

| | Target | M3 | after |
|---|---|---|---|
| APK | ≤ 6 MB | 1.51 MB | **1.51 MB** (1,583,329 bytes, +624) |
| Methods, single dex | ≤ 40,000 | 17,280 | **17,298** |
| Classes | — | 3,297 | **3,300** |
| Lint release | no errors | 0 errors, 6 warnings | **0 errors, 6 warnings** |
| JVM tests | — | 112 | **120, 0 failures** |
| Instrumented `@Test` written | — | 271 | **291** |

### 15.9 Still not done

Unchanged from §14.11, and the first item is now the more urgent for having been
proved twice over:

- **Nothing instrumented has been run since M2.** The suite was last green at
  174 / 174, on pre-audit M2 code; 291 written tests now sit behind that. Two of
  the twelve defects above were already asserted against by tests in that
  backlog. Every fix in this section is likewise verified by compilation and by
  reasoning, not by execution.
- **The greyscale capture** for NFR-USE-05 is still owed.
- **The release APK has still not been installed and driven.**
- **Dashboard remains a placeholder** (M4); Reports, Settings, export/import and
  recurring rules are untouched; source reorder (FR-IS-07) stays deferred.

---

## 16. M4 — the dashboard

`01-PRD.md` §8: *"M4 · Dashboard analytics · Dashboard renders in ≤ 300 ms with
5 years seeded data."*

The last screen in the app that had never been built. 05 §5.4 calls it "one
screen answering the questions that change behaviour", and it is the only screen
in the documents whose mock comes with three paragraphs explaining why it is
arranged the way it is — so unlike M3, almost nothing here was an open decision.

### 16.1 This milestone's exit criterion is about cost, not correctness

M2's and M3's were reconciliation claims. This one is a wall-clock number, and
the interesting thing about it is that the number is a *consequence*. 03 §5.1
states the actual claim:

> "Row count is bounded by the number of leaf categories — dozens, not thousands
> — independent of transaction history size. **This is what holds NFR-PERF-04 at
> 300 ms as the ledger grows to five years.**"

That is testable without a stopwatch, and testing it turned out to be most of
the work. `DashboardScaleTest` seeds sixty periods and asserts that the
dashboard's reads return the same number of rows over five years as over one;
`SchemaAssertionsTest` gained four `EXPLAIN QUERY PLAN` assertions holding each
read to the rollups. The stopwatch half is `DashboardBenchmark`, and it needs
the reference device.

### 16.2 Nine reads, eight of which never touch the ledger

`DashboardRepository` exists so that every window is derived from one `Period`
in one place: the screen needs this period, its days, its trailing three, its
trailing six and its trailing twelve, and computing those at nine call sites is
how two figures on one screen end up describing different months.

Only two queries were new — `observeCategoryCells` for FR-AN-05's deltas and
`observeTotalLimitsInPeriods` for FR-AN-09's reference line. Both read only
rollups, `budget` and `category`. The nature split needed no query at all: it
folds out of `observeBudgetBars`, which already carries `nature` and
`spentMinor` per leaf.

**The period's expense total comes from the same read as the budget rows**, not
from a separate scalar. It is the same discipline M3's `observeCellsInPeriods`
established — figures that must agree are read once, so they cannot disagree —
and here it means the net line and the group totals are literally the same
numbers added up two ways.

The ninth read is `ExpenseDao.observeLargest`, and it is the exception on
purpose. FR-AN-08 asks for the five largest *transactions*, which no rollup can
answer: a bucket has no largest row. It is bounded instead — an indexed equality
on the period, then `LIMIT 5` — and the query plan asserts it searches
`ix_expense_period` rather than scanning.

### 16.3 FR-AN-09 is not a dashboard requirement

FR-AN-01 through FR-AN-08 each begin "**The dashboard** MUST display". FR-AN-09
begins "**The system** MUST render a 6-period expense trend line with a budget
reference line, **and a 12-period income series**."

That distinction is not decorative, and reading it carefully removed a
duplicate: the 12-period income series already ships — it is `YearBars` on the
income screen, built at M3 (§14). So M4 owes the expense trend with its
reference, and drawing the income bars here as well would have been a second
copy of a chart the mock does not ask for on a screen that is already long.

The reference line is **per period rather than one flat line**, because limits
are set per period (FR-BUD-01) and a single line would become a fiction the
moment the user changed one. It draws flat when they do not.

### 16.4 What the mock's three notes turned out to mean in code

> **"The hero is a decision, not a balance."**

FR-AN-01's numerator is **signed**. An overspent Grocery eats into what is left
in Transport, because that is what happened to the money — and the requirement's
own instruction ("when the numerator is negative the value MUST render as zero
with an over-budget indicator") is only reachable if the arithmetic works that
way. Clamping each leaf at zero first would have made half the requirement
unreachable and the figure a lie at the shop counter.

The other half of that sentence is a line the mock does not draw: a bare zero
reads as "nothing left", not "past the limit". `safe_to_spend_over` says by how
much.

> **"Sections that have nothing to say are absent, not empty."**

Six sections obey it — needs attention, on pace to overspend, biggest changes,
where it goes, largest expenses, and the trend. So does the coverage line, and
so does the savings rate. This is the rule that most shapes the screen's code:
there is no fixed layout, and every block is a conditional.

Two consequences worth stating, because both look like omissions:

- A month with no income shows the net position **without** a savings rate
  (FR-AN-03's "suppressed when income is zero"), while a month with no *stable*
  income against real spending does show 0% coverage. The two look similar and
  are not: one is a ratio with no denominator, the other is an answer, and an
  alarming one.
- A finished period has no per-day figure at all. `SafeToSpend.perDay` is null
  rather than zero, and the caption changes from "SAFE TO SPEND TODAY" to "LEFT
  IN THIS MONTH" — a past month has a balance, not a daily allowance, and
  dividing by a day that does not exist is not the way to say so.

> **"Fixed expenses sit below variable ones."**

Already true: `BudgetSummary.GROUP_ORDER` encoded it at M2, and `SpendMix` now
orders its three slices the same way for the same reason.

### 16.5 A burn projection is a prediction, so it excludes what has already happened

FR-AN-04's justification is one clause of PRD §6.4 — it "warns on day 12, not
day 30" — and everything about the implementation follows from taking that
literally.

- **Unpredictable leaves are never projected.** FR-BUD-07 forbids under-spend
  nagging and PRD §6.2 says "Unpredictable Expenses is a buffer, not a plan". A
  single medical bill on day three projects to ten times itself by month end:
  alarming, arithmetically correct, and meaningless.
- **A leaf already over its limit is computed but flagged**, and the screen
  filters those out — they are two sections above in "needs attention", and a
  dashboard that says the same thing twice is one the user starts skimming. The
  calculation keeps them because FR-AN-04 asks for every over-pace leaf and one
  already over is trivially one.
- **Zero days elapsed returns nothing**, rather than dividing by zero or
  inventing a pace from no data.
- It multiplies before dividing. Rounding a daily rate to whole paisa first
  loses up to a month's worth of the fraction.

### 16.6 The apportionment was needed twice, so it moved

FR-AN-07's three shares have to total 100 for the same reason FR-IE-06's do, and
`IncomeBreakdown` already had the largest-remainder implementation. It is now
`LargestRemainder.percentages`, called by both. `IncomeBreakdownTest` was
written against the inlined version and passes unchanged against the extracted
one, which is what makes it a regression guard rather than a rewrite.

One behavioural addition: a negative weight is treated as zero. Expenses can be
negative — FR-EXP-06's refunds — and a share of a total is not a place where a
refund should invert the arithmetic.

### 16.7 The alert block now has one implementation and two homes

05 §5.4 puts "needs attention" on the dashboard; `BudgetAlerts`' own doc records
that the budget screen was its temporary home "until the dashboard exists at
M4", and that "this function is what both surfaces call".

It stays on both, and the row itself moved to `ui/common/AlertRow.kt`. The
dashboard is where a problem is noticed; the budget screen is where it is fixed,
because every row there taps into the limit editor. Rendering them from one
component is what keeps the two surfaces saying the same sentence — including
the spoken one, which is built from the same string resources as the visible
text so the two cannot drift.

### 16.8 The settings gear is not drawn

05 §5.4's mock has a ⚙ in the header. Settings is M5's — it is the screen export
and import live on — and a gear that opens nothing tells the user the app is
unfinished every time they look at it. Deferred and recorded, the way FR-IS-07
and FR-CAT-11 were, rather than stubbed.

### 16.9 Seeding five years, without putting a seeder in the app

The exit criterion names its workload as precisely as its target, and an empty
dashboard renders fast for reasons that say nothing about a real one. So
`SeedFiveYears` generates sixty periods of realistic data — about nine thousand
expenses and four hundred income entries, matching 03 §9's sizing — and a
`SeedReceiver` makes it `adb shell am broadcast`-able.

**Both live in `app/src/debug/`.** Not guarded by `BuildConfig.DEBUG`, not
stripped by R8 — absent from the release build, because a debug source set is
not compiled into it. That is a build-time guarantee rather than a runtime one,
and it was verified rather than assumed: the release dex contains zero
references to `SeedFiveYears`, `SeedReceiver` or `com.app.finance.dev`, and the
only `<receiver>` in the merged release manifest is
`androidx.profileinstaller.ProfileInstallReceiver`. The debug APK carries both.

The convenient part is that `androidTestDebug` compiles against the debug
variant, so `DashboardScaleTest` uses the same generator rather than keeping a
second copy of the same data in step with it.

Two properties of the generated data are load-bearing rather than cosmetic. It
is **deterministic** — a fixed seed, so a benchmark comparing yesterday's number
with today's is comparing the same workload. And it is **lumpy**: farming income
arrives in four months of the year and not the other eight, because PRD §1's
"five months earn nothing and the sixth earns a year's worth" is the shape this
app exists for, and a uniform generator would leave every delta at zero, every
trend flat and every alert silent — the states in which none of these metrics
can be wrong.

### 16.10 Test inventory

| Suite | Where | Tests |
|---|---|---|
| `LargestRemainderTest` | JVM | 14 |
| `SafeToSpendTest` | JVM | 10 |
| `NetPositionTest` | JVM | 8 |
| `BurnRateTest` | JVM | 10 |
| `CategoryDeltasTest` | JVM | 12 |
| `SpendMixTest` | JVM | 8 |
| `ExpenseTrendTest` | JVM | 12 |
| `DashboardViewModelTest` | instrumented | 24 |
| `DashboardScreenTest` | instrumented | 23 |
| **`DashboardScaleTest`** | instrumented | 8 |
| `SchemaAssertionsTest` (query plans) | instrumented | +4 |
| `DashboardBenchmark` | benchmark | 2 |

### 16.11 Measured

| | Target | after M3 audit | after M4 |
|---|---|---|---|
| APK | ≤ 6 MB | 1.51 MB | **1.53 MB** (1,601,537 bytes) |
| Methods, single dex | ≤ 40,000 | 17,298 | **17,432** |
| Classes | — | 3,300 | **3,331** |
| Lint release | no errors | 0 errors, 6 warnings | **0 errors, 6 warnings** |
| JVM tests | — | 120 | **194, 0 failures** |
| Instrumented `@Test` written | — | 291 | **350** |
| Main source | — | 71 files | **82 files, 14,893 lines** |

The whole dashboard — nine reads, seven pure calculations, a new chart primitive
and the screen — cost **134 methods and 18 KB**. The trend line is a `Canvas`
and about twenty draw calls, which is what PRD §6.4 meant by "drawn with the
platform canvas — no charting library".

### 16.12 Still not done

- **NFR-PERF-04's 300 ms has not been measured.** `DashboardBenchmark` is
  written and `SeedFiveYears` makes it runnable, but the number belongs to a
  1.4 GHz Cortex-A53 with 2 GB of RAM, and the SRS is explicit that "targets
  measured on a flagship device are not evidence of compliance". What *is*
  asserted is the structural claim underneath it — that the reads are bounded by
  the category tree rather than by history.
- **Nothing instrumented has been run since M2**, when the suite was last green
  at 174 / 174. There are now 350 written tests behind that, 59 of them added
  here. §15.1 recorded what that backlog cost last time: two of the M3 audit's
  twelve defects were already asserted against by tests inside it.
- **The greyscale capture** for NFR-USE-05 is still owed, and M4 adds to it: the
  trend's over-budget points are filled where the others are hollow, precisely
  so the state survives monochromacy, and that has been reasoned about but not
  looked at.
- **The release APK has still not been installed and driven.**
- **M5** — export/import, recurring rules, Settings (and with it the mock's ⚙),
  and the reports screen 03 §5.3 refers to. Source reorder (FR-IS-07) and
  category reorder (FR-CAT-11) remain the two deferred `SHOULD`s.

---

## 17. M5 — export, import, recurring rules, Settings

`01-PRD.md` §8: *"M5 · Export/import, recurring, polish · Round-trip
export→wipe→import loses nothing."*

The last milestone, and the one whose headline feature is argued for on grounds
of trust rather than utility. PRD §6.6:

> "Users do not trust an app with their financial history until they have proof
> they can extract it. It is also the only backup mechanism in a no-server
> product."

### 17.1 What M1 had already decided

The `recurring_rule` table, its XOR `CHECK`, `ix_rule_due`, `EntryStatus`,
`Frequency` and `RuleTarget` were all built at M1 and had no caller. So was the
mechanism the whole feature rests on: every rollup trigger is guarded on
`status = 0`, so a pending entry cannot move a figure — a property
`IncomeReconciliationTest.a_pending_entry_appears_in_neither` has been asserting
since M3 without a single pending row ever having been generated by the app.

`RecurringRuleEntity`'s own KDoc named both of the hard requirements before
there was any code to satisfy them: *"generation proceeds only when
next_due_day > last_run_day (FR-REC-03)"* and *"clamps at generation time for
short months (FR-REC-05)"*.

kotlinx.serialization has been a dependency since M1 too, added for this.

### 17.2 Three decisions

| Question | Choice |
|---|---|
| Where a pending entry is confirmed | **A section atop the ledger.** A pending row is a transaction that has not happened yet, and the ledger is where transactions live. |
| When rules are evaluated | **On launch, no WorkManager.** See §17.6. |
| The Reports screen (04 §7) | **Deferred.** PRD §7 lists it at no priority tier and no `FR-*` requires it. Its three parts already exist: the ledger has date-range filters and search, the dashboard has the fixed/variable split and the top five, and M3 built arbitrary-range income totals. Recorded beside FR-CAT-11 and FR-IS-07. |

### 17.3 The file format, and the one line in the documents that governs it

DR-01 does not stop at the database:

> "Money MUST be stored as `INTEGER` minor units. Floating-point representation
> of money is prohibited anywhere in the system, **including export files**."

Every amount in both formats is a `Long` of paisa. `ExportFormatTest` asserts it
twice — once by scanning the output for a decimal point or an exponent in any
money field, and once by round-tripping 2^53 + 1, which a serialiser that went
through `Double` would hand back as an even number.

Two more properties are load-bearing:

- **The DTOs mirror the entities rather than reusing them.** An entity is a
  storage detail that may be renamed; a file format is a promise to a user who
  exported last year. Keeping them apart means a refactor produces a compile
  error in one mapper instead of silently changing what is on disk.
- **The rollups are not in the file.** 03 §6 makes triggers their only writer
  and `REBUILD_ROLLUPS` their regeneration path; exporting derived rows would
  hand the importer two sources of truth and no rule for which wins. The
  importer rebuilds them instead, inside the same transaction.

`ignoreUnknownKeys` is on. FR-DAT-05 refuses a newer *schema version*; a
same-version file carrying a column this build has never seen is still a file it
can read, and refusing it would mean every release broke the backups taken by
the one before it.

### 17.4 The defect merge is built to prevent

An expense in the file says `category_id = 7`. On the exporting phone that was
Grocery. On this one it may be House Rent, or nothing at all.

Trusting that integer would silently re-file a year of groceries under rent —
**no error, no crash, and nothing for the user to see**. It is the worst class of
bug this app could have, because every figure would still add up.

So merge resolves every foreign key through the UUID and never through the
exported id. DR-06 exists for exactly this — every entity carries a UUID "to
support import deduplication across devices" — and the sequence is: write the
two parent tables first, read back `uuid → local id`, remap every child through
it, and throw `DanglingReference` if a reference resolves to nothing. Categories
take two passes because the table references itself; the two-level cap makes
two enough, where a third level would need a topological sort.

A new row is inserted with `id = 0` so SQLite assigns a local key. The file's
integer belongs to another device, and reusing it is how two rows end up
fighting over one id — `a_merged_insert_gets_a_local_id_rather_than_the_files_one`
is that assertion, and it is the kind that passes for the wrong reason if nobody
writes it.

FR-DAT-03's third count is what makes a merge of a file into the database it
came from a **no-op**: a row whose UUID is present and whose contents are
identical apart from the key is *skipped*, not rewritten.

### 17.5 A failed import changes nothing, and that is asserted rather than claimed

NFR-REL-04: *"A failed import MUST leave the existing database unmodified — the
operation is transactional."*

Validation happens before the first `DELETE`; everything else is inside one
`db.withTransaction`. `ImportValidationTest` takes a fingerprint of six tables
and both rollups before each attempt and asserts it is unchanged after — for a
newer schema version, a file that is not an export, a truncated file, a dangling
reference, and a row that violates a `CHECK` halfway through a batch. A
half-applied import of five years of financial history is the worst outcome
available here, worse than refusing outright.

### 17.6 Recurring rules, and a documented departure from 04

04's stack table lists WorkManager "only for recurring-rule evaluation", and §6
anticipates introducing it at P1 with on-demand initialisation. **It is not
used, and the reasoning is worth stating rather than leaving as a silent
omission.**

FR-REC-04 already requires missed dates to be generated "on next launch". 05 §12
rules out notifications, and §8 says why: "an app that scolds you about spending
gets uninstalled". So a background job would generate rows nobody could see
until the app was opened — which is precisely what launch evaluation does,
without a `ContentProvider` on the startup path 04 §6 spent real effort keeping
clear. Evaluation joins the `LaunchedEffect` that already runs `verifyDatabase`
beside the first frame, so it costs no frames either.

Three properties, each a requirement:

- **FR-REC-03 (idempotence)** holds two ways over. The loop advances
  `next_due_day` past today before exiting, so a second launch the same day
  finds nothing due; and each generation checks the ledger for a row with the
  same target, day and amount, so even a rule whose bookkeeping was corrupted —
  or imported from another device — cannot produce a duplicate.
  `a_rule_whose_bookkeeping_was_reset_still_does_not_duplicate` winds
  `next_due_day` back by hand to prove the second guard alone is enough.
- **FR-REC-04 (catch-up)** is a `while`, not an `if`. An app unopened since May
  produces four rows, each separately confirmable, guarded on strict advancement
  so a rule with a broken schedule stops instead of filling the ledger.
- **FR-REC-05 (the clamp)** is why `RecurrenceSchedule` is a calculation rather
  than a stored date, and the part the obvious implementation gets wrong is not
  the clamp — it is the recovery. An anchor of 31 falls on 28 February and then
  on **31 March**, not the 28th. Storing the clamped date and advancing from it
  would walk a rent reminder backwards a few days every short month, for ever,
  and take two months to notice on a device.

**Auto-post is off by default**, because PRD §6.5 is blunt about the reason:
"silently generated transactions that didn't actually happen destroy trust in
the ledger faster than any other bug." The switch carries that sentence on the
screen rather than leaving the user to discover it.

### 17.7 Dismissing a pending entry is a delete, so it takes an undo

Caught while clearing lint's unused-resource warnings, which is a poor way to
find a requirement gap but a real one.

NFR-USE-03 makes **every** destructive action undoable for five seconds, and
dismissing is a delete. It is also the one delete in the app with no natural
second chance: the rule has already advanced past that due date and will never
generate it again, so a mis-tap would lose the entry for good. `dismissExpense`
now returns the row and the ledger offers the same five-second snackbar
everything else does.

### 17.8 Delete-all has to leave a fresh install, not a broken one

FR-DAT-06's confirmation shape is fixed by 05 §8, which names it as the single
exception to the app's no-dialogs rule: *"the exception is 'delete all data,'
which requires typed confirmation, because there is no undo for it."* The word
is matched exactly and deliberately **not translated** — a confirmation phrase
that changes with the device language is one somebody gets wrong at the moment
they most need to get it right.

The part that is not in any requirement: the wipe re-runs `Schema.SEED`. An
expense must reference a leaf (FR-EXP-04, by trigger), so a wipe that left the
category table empty would leave an app that cannot record anything.
`an_expense_can_be_recorded_immediately_after_deleting_everything` is that
assertion.

### 17.9 The exit criterion, asserted

> "Round-trip export→wipe→import loses nothing."

FR-DAT-04's acceptance is exact, and it is **two** claims:

> "Row counts and checksums for every entity match pre-export values; **every
> report renders identical figures**."

The second does not follow from the first. The rollups are not in the file —
they are rebuilt from the ledger — so a restored database could carry every row
correctly and still render a different dashboard if the rebuild and the triggers
disagreed. So `ExportImportRoundTripTest` seeds `SeedFiveYears`' sixty periods,
takes a per-entity row count and checksum, exports, wipes, imports, and then
**drives `DashboardViewModel` and `IncomeViewModel`** and compares twenty-one
rendered figures with what they showed before.

Also covered: pending rows survive (the rollups exclude them, so an aggregate
fingerprint alone would not notice them going missing); merging a file into its
own database reports nothing inserted and everything skipped; and the CSV
archive carries one file per entity with a header and a line per row.

### 17.10 Test inventory

| Suite | Where | Tests |
|---|---|---|
| `RecurrenceScheduleTest` | JVM | 14 |
| `CsvWriterTest` | JVM | 10 |
| `ExportFormatTest` | JVM | 8 |
| `RecurringRepositoryTest` | instrumented | 18 |
| `SettingsViewModelTest` | instrumented | 15 |
| `ImportValidationTest` | instrumented | 10 |
| `LedgerPendingTest` | instrumented | 9 |
| **`ExportImportRoundTripTest`** | instrumented | 6 |

### 17.11 Measured

| | Target | after M4 | after M5 |
|---|---|---|---|
| APK | ≤ 6 MB | 1.53 MB | **1.64 MB** (1,720,741 bytes) |
| Methods, single dex | ≤ 40,000 | 17,432 | **18,815** |
| Classes | — | 3,331 | **3,599** |
| Lint release | no errors | 0 errors, 6 warnings | **0 errors, 6 warnings** |
| JVM tests | — | 194 | **226, 0 failures** |
| Instrumented `@Test` written | — | 350 | **408** |
| Main source | — | 82 files | **96 files, 18,154 lines** |

**FR-APP-01 re-verified against the merged release manifest**, because M5 is the
milestone that added file I/O: the only `<uses-permission>` is the app-scoped
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` androidx.core contributes. No
INTERNET, and no storage permission either — `ACTION_CREATE_DOCUMENT` and
`ACTION_OPEN_DOCUMENT` need none, which is what makes NFR-SEC-01's "no data
leaves the device except by explicit user-initiated export" structural rather
than a promise. M4's debug seeder is still absent from the release dex.

The whole milestone — two file formats, an importer with UUID remapping, five
recurring requirements, two screens and a theme — cost **1,383 methods and
116 KB**.

### 17.12 What the five milestones did not deliver

- **The instrumented suite has not been run since M2**, when it was last green
  at 174 / 174. There are now **408 written tests** behind that. §15.1 recorded
  what that backlog already cost once: two of the M3 audit's twelve defects were
  sitting inside it, asserted against by tests nobody had executed. This is the
  single largest outstanding item in the project and it has grown every
  milestone.
- **No performance target has been measured.** NFR-PERF-01's 800 ms cold start,
  NFR-PERF-04's 300 ms dashboard, NFR-PERF-07's 3 s export — all need the
  reference Cortex-A53, and the SRS is explicit that a flagship measurement is
  not evidence. `DashboardBenchmark` and `StartupBenchmark` are written and
  `SeedFiveYears` makes them runnable.
- **The greyscale capture** for NFR-USE-05 is owed across four screens now.
- **The release APK has never been installed and driven.** M1's §11.7 found a
  real R8-only defect that way.
- **Deferred by decision, each recorded where it was taken**: the Reports screen
  (§17.2), category reorder FR-CAT-11 (§12), source reorder FR-IS-07 (§14.3) —
  and P1's app PIN lock, which no milestone claimed.
- **`bn` is still undeclared.** Every string is extracted and every plural is a
  `<plurals>`, so the translation is a file rather than a refactor.

---

## 18. The M4 + M5 audit

M1 and M2 gave up six defects (§13); M3 gave up twelve (§15). M4 and M5 had
never been read this way, and that gap was the first item on §17.12's list.
Twelve more, one of which makes a `MUST` feature fail in the case it exists for.

### 18.1 Merge could not import a file from another phone

`Importer.plan` deduped on `uuid` alone. Every seeded row's uuid comes from
`randomblob` inside `Schema.SEED`, so **two installs have different UUIDs for
"Grocery"**. Merging a backup from one phone into another therefore went:

1. no local row with that uuid → **insert**
2. the insert hits `ux_category_parent_key` on `(IFNULL(parent_id,-1), name_key)`
3. `OnConflictStrategy.ABORT` → the transaction rolls back → `REJECTED`

The user is told the file could not be read, and nothing else is wrong, so there
is nothing to go on. The same wall stood behind `ux_income_source_key` and
`ux_budget_cat_period`.

That is not an edge case; it is the case. DR-06 says the uuid exists "to support
import deduplication **across devices**", and FR-DAT-03 requires merge to
"deduplicate on a **stable natural key**" — which for a seeded category the uuid
is not. Merging a file into the database it came from worked, which is why
`ExportImportRoundTripTest` passed and the defect survived: the only merge
anyone had tested was the one that never leaves a phone.

**The natural key is whatever the unique index enforces**, and where the schema
has no unique index there is none:

| Entity | Key | Index |
|---|---|---|
| `category` | uuid, then `(parent_id, name_key)` | `ux_category_parent_key` |
| `income_source` | uuid, then `name_key` | `ux_income_source_key` |
| `budget` | uuid, then `(category_id, period_ym)` | `ux_budget_cat_period`, which is FR-BUD-02 |
| `expense`, `income_entry`, `recurring_rule` | uuid only | none — and none is possible, because FR-IE-02 makes two identical entries on one day legitimate |

`ExportRow.naturalKey` returns null for the last three, so "there is no natural
key here" is a property of the type rather than a comment somebody has to find.

The two lookups mean different things and are ordered accordingly. A **uuid
match** is *the same row, possibly newer* — identical means skip, different
means update. A **natural-key match** is *the same thing on another phone*: the
local row is left alone and counted as skipped, because its `sort_order`, `icon`
and `is_system` are facts about this phone and `is_system` in particular is not
something a file should be able to set on a seeded root. What matters after the
match is not the row at all — it is that `resolve` then maps that file id to the
**local** id, so every imported expense follows this phone's Grocery instead of
the other phone's integer.

Categories resolve twice, because a child's natural key contains its *remapped*
parent id and can only be computed once the parents are in.

### 18.2 Safe-to-spend was offering money that could not be spent

`observeBudgetBars` includes an archived leaf while it carries spend in the
period — FR-CAT-08 requires exactly that, "so history never silently loses
rows". But `BudgetLeaf` carried no archived flag, so the two forward-looking
figures could not tell the difference.

Archive Grocery on the 14th with ৳18,000 budgeted and ৳2,000 spent, and
safe-to-spend went on offering ৳16,000 of it — on a category the entry picker
no longer lists. `BurnRate` would also announce "Grocery, on pace to overspend"
about one the user had just retired, which is the nagging FR-BUD-07 forbids
arriving by a different route.

The same class of defect §13.5 fixed when copy-from-last-month was writing
limits onto archived leaves.

`is_archived` now travels from the query through `LeafSpend` to `BudgetLeaf`,
and `SafeToSpend.of` and `BurnRate.over` exclude it. **Nothing else changes, and
that is the point:** the leaf still renders (FR-CAT-08), still counts in its
group total, and still appears in the spend mix, because the money really was
spent. `BudgetAlerts` still reports it over its limit, because that happened
too. Only the two figures about what happens *next* drop it.

### 18.3 A rule outlived the category it posted to

`RecurringViewModel` offers only active targets when a rule is made, and nothing
revisited that afterwards: `dueOnOrBefore` selected on `is_active` and
`next_due_day` alone. Archiving Grocery left its rule generating pending
groceries every month — entries the user could not have created by hand
(FR-CAT-08, FR-IS-04).

The query now joins both target tables and skips a rule whose target is
archived. **Skipped, not cancelled**: the rule is untouched, so un-archiving the
category resumes it. And because a rule that looks live and does nothing is
worse than one that says why, `observeRules` returns `targetArchived` and the
manager row reads "Archived — nothing is being added".

### 18.4 Deleting a rule had no undo

NFR-USE-03: "**Every** destructive action is undoable for at least 5 seconds via
snackbar." Every other one in the app already was — expense delete, income
delete, category archive, source delete, and dismissing a pending entry, which
§17.7 caught the same way. `RecurringScreen` showed a snackbar with no action.

`deleteRule` returns the row and `restoreRule` puts it back verbatim — uuid,
anchor and `next_due_day` included, so an undone delete resumes on the schedule
it had rather than starting over.

### 18.5 The dashboard's first screenful was thirteen zeroes

`state.groups.forEach { items(group.leaves) }` rendered every leaf, so a fresh
install opened on thirteen rows of `৳0 · No limit set` with FR-AN-04 through
FR-AN-09 below the fold — on the screen 05 §5.4 calls "one screen answering the
questions that change behaviour".

The mock shows three variable leaves and one fixed, and its own note is the
rule: "sections that have nothing to say are absent, not empty." That applies to
rows as readily as to sections. The dashboard now shows leaves with spend or a
limit, and a group left with none disappears with its header.

The budget screen is deliberately unchanged. Every row there carries "Set one",
so the full list *is* that screen's call to action (§12) — the same list means
different things on the two screens, which is why one filters and one does not.

### 18.6 A bad month read better than it was

`NetPosition.savingsRate` used `toInt()`, which truncates **toward zero**: a rate
of −60.5% reported as −60%. `Money.divideBy` documents that direction as the
wrong one — "telling the user they may spend one paisa more than they actually
may is the wrong direction to err in a budgeting app" — and it applies with more
force here, because the month this flatters is the month that went wrong.

Floored. Positive rates are unchanged: 34.9% was already reported as 34%.

### 18.7 Six smaller ones

| | Defect | Fix |
|---|---|---|
| A7 | The rule editor headed its frequency chips with `choose_scope` = *"Choose a period"*, the income filter sheet's string. **Third occurrence of borrowed copy** — §15.7's D6 was the second | `choose_frequency` = "How often" |
| A8 | A replace import wrote the file's `app_meta`, so restoring a v0 backup left a v1 database claiming to be v0 | `schema_version` re-stamped inside the import transaction |
| A9 | The nine-table wipe order was duplicated in `Importer` and `SettingsRepository` — a table added to one would be silently missed by the other | One `Schema.WIPE_ORDER`, beside `DROP_TABLES`, which already encodes the same ordering |
| A10 | `Importer`'s comment claimed clearing rollups first "spares nine thousand trigger firings". It does not: the triggers still fire, they just find nothing to decrement | Corrected to what is true |
| A11 | `Exporter` wrote with the platform default charset. UTF-8 on Android, so latent — but a file format is a promise and should not rest on a default | Explicit `Charsets.UTF_8` |
| A12 | `ImportOutcome.Failure.REJECTED` discarded the exception, leaving a failed restore of five years of data with nothing to diagnose | `Log.w` the cause |

### 18.8 Two patterns worth naming

**Borrowed copy is now at three.** §15.7 caught the income filter sheet wearing
the entry sheet's question; this pass caught the rule editor wearing the filter
sheet's. Each time the string was *nearly* right and each time it named the
wrong concept. Reusing a string is not reuse — a string resource is a sentence,
not a token.

**Empty branches are at four**, counting the budget screen's first run (§12.5),
its repeat (§13), the income screen's filtered state (§14.8) and now the
dashboard's rows. The shape is always the same: a branch that reports a
situation the user did not ask to be in, without the thing that resolves it.

### 18.9 Test inventory

| Suite | Where | Before | After |
|---|---|---|---|
| `SafeToSpendTest` | JVM | 10 | **12** |
| `BurnRateTest` | JVM | 10 | **12** |
| `SpendMixTest` | JVM | 8 | **9** |
| `NetPositionTest` | JVM | 8 | **11** |
| `ExportFormatTest` | JVM | 8 | **13** |
| `ImportValidationTest` | instrumented | 10 | **17** |
| `RecurringRepositoryTest` | instrumented | 18 | **24** |
| `DashboardViewModelTest` | instrumented | 24 | **27** |
| `DashboardScreenTest` | instrumented | 23 | **26** |

Six of the seven new `ImportValidationTest` cases are 18.1's, and they are the
ones that matter: a backup from another phone merging cleanly, a name-matched
row counted as skipped and left alone, another phone's expense landing on *this*
phone's category, the same for income, a budget for a period this phone already
has not colliding, and two identical same-day expenses staying two rows.

### 18.10 Measured

| | Target | after M5 | after the audit |
|---|---|---|---|
| APK | ≤ 6 MB | 1.64 MB | **1.64 MB** (1,720,901 bytes, +160) |
| Methods, single dex | ≤ 40,000 | 18,815 | **18,836** |
| Classes | — | 3,599 | **3,603** |
| Lint release | no errors | 0 errors, 6 warnings | **0 errors, 6 warnings** |
| JVM tests | — | 226 | **239, 0 failures** |
| Instrumented `@Test` written | — | 408 | **427** |

FR-APP-01 re-verified: no `INTERNET` in the merged release manifest. The debug
seeder is still absent from the release dex.

### 18.11 Still not done

- **Nothing instrumented has been run since M2.** 427 written tests now sit
  behind a suite last green at 174 / 174, and **18.1's fix is exercised entirely
  by instrumented tests** — so the defect that most needed proving is the one
  this pass could only reason about. Every audit so far has found defects in
  that backlog; there is no reason to think this one is different.
- **No performance target has been measured.** Cold start, dashboard render and
  export time all need the reference Cortex-A53.
- **The greyscale capture** for NFR-USE-05, now across five screens.
- **The release APK has never been installed and driven.**
- **NFR-MAIN-02's 80% coverage** has no tooling configured at all — neither
  JaCoCo nor Kover. It is the one remaining item that could be started without a
  device.
- Deferred by decision and recorded where taken: the Reports screen (§17.2),
  FR-CAT-11, FR-IS-07, and P1's FR-APP-04 app lock with NFR-SEC-04's
  `FLAG_SECURE`.

---

## 19. The whole-application audit

Every milestone had by now been audited on its own — M1 + M2 (§13), M3 (§15),
M4 + M5 (§18) — for thirty defects across five passes. This one is different in
kind, and the difference is the point.

A per-milestone read follows one feature down through its layers. It cannot see
**what one milestone did to another**, and it cannot tell whether an invariant
the documents state once actually holds in all eleven places it should. So this
pass swept the app-wide rules instead of the features: undo coverage, the
archived-entity paths, locale, the clock, process death, the startup contract,
and the guards meant to enforce the rest.

Nine findings. The first is exactly what the pass was for.

### 19.1 An M5 change had quietly disabled an M1 safety net

`MainActivity` collected `settingsRepo.observeTheme()` **above** `KhataTheme`,
so it ran before and independently of `verifyDatabase()`. That is a Room query
on `app_meta`, and the one case that matters is the database being the thing
that is broken — which is precisely when `RecoveryScreen` has to appear. The
flow would throw, the exception would leave the collecting coroutine, and the
app would die on launch.

04 §8:

> "Migration failure | Release builds never fall back to destructive migration.
> Failure surfaces a recovery screen offering export of the raw database file."

The recovery screen is the *other half* of the decision not to fall back to
destructive migration. A user whose migration failed would have got a crash
instead of the only route by which five years of data leaves the phone.

`AppNav` was written defensively for exactly this — `runCatching` around both
`lastViewedPeriod()` and `setLastViewedPeriod()`. The theme read added at M5 was
not, and no M5-shaped review would have gone looking at M1's crash path.

`.catch { emit(SYSTEM) }` now costs a user with an unreadable database their
theme preference and nothing else.

**And the fix introduced a second defect, which the project's own lint gate
caught before it shipped.** A flow operator applied in composition rebuilds the
flow on every recomposition and resets the collection under it —
`FlowOperatorInvokedInComposition`, an error rather than a warning, so
`lintRelease` refused to build. `remember` around it. Worth recording plainly:
the gate earned its keep on the very change that was fixing something else.

### 19.2 Deleting a source ignored the rule pointing at it

`deleteSource` gated on `countEntriesForSource` alone. A source with no entries
but an active recurring rule therefore reached SQLite, was refused by
`recurring_rule.source_id`'s `ON DELETE RESTRICT`, and came back through
`toIncomeError`'s `FOREIGN KEY` branch as `SOURCE_NOT_FOUND` —

> "That source no longer exists. Pick another."

— about a source sitting on the screen. Two failures in one: FR-IS-05 asks for a
**disabled control with an explanatory message**, and the manager's
`entryCount == 0` gate did not know rules existed; and the error, when it fired,
said the opposite of what happened.

M3 built the source manager. M5 built rules. Neither audit had both in view.

`SourceWithCount` now carries a rule count from the same subquery shape it
already used for entries, `deleteSource` checks it, and the new
`EntryError.SOURCE_HAS_RULES` gets its own sentence — because "this source has
entries" would send the user hunting for entries that are not there. Entries are
reported ahead of rules when both hold it: that is the one the user is likelier
to be able to act on.

### 19.3 The one door that bypassed the repository

03 §4.3, about the column every monthly figure in the app is keyed on:

> "`period_ym` is derived from `earned_on` by the application and kept
> consistent by **an assertion in the repository layer plus a debug-build
> integrity check**."

Every write path derived it. The importer did not — it took the file's value. A
hand-edited or third-party backup could therefore file August's rent under June
while the row still read 3 August, and **nothing in the app could see it**: the
rollups are built from the stated period, and `assertRollupsReconcile` compares
the rollups against that same period, so a wrong one is wrong on both sides and
cancels out. Every figure would have been internally consistent and wrong.

And the debug-build check that sentence names had never been written.

Both halves now exist. `Importer` derives `periodYm` from each row's own date,
so the file supplies dates and the application supplies the derivation.
`AppContainer.assertPeriodsDerived()` sits beside `assertRollupsReconcile()`
with the same shape and the same debug-only call site, counting rows where
`period_ym` disagrees with `strftime('%Y%m', day * 86400, 'unixepoch')` — a full
scan, which is exactly why it is a check and not a query, and exactly why 03 §1
denormalised the column in the first place.

### 19.4 The ledger filter could not reach an archived category

FR-EXP-08: "The ledger MUST be filterable by date range, **root, leaf**, and
payment method." `LedgerFilterSheet` built its leaf chips from `activeChildren`.

Archive Grocery and a year of grocery expenses stay in the ledger with "Grocery"
printed on every row — and become unfilterable.

**Third occurrence of this class**, after §15.6 on the income filter sheet and
§18.2 on the dashboard's figures. The rule is now clear enough to state once and
stop rediscovering: **an archived thing leaves the entry pickers and stays
everywhere else.** FR-CAT-08 and FR-IS-04 both say so in one clause each, and
the app has now got it wrong in three different filters.

The sheet lists active leaves plus any leaf the loaded rows reference — the same
"active ∪ present" shape `IncomeUiState.filterSources` uses, so both filters
answer the question the same way.

### 19.5 Clearing a budget limit had no undo

NFR-USE-03: "**Every** destructive action is undoable for at least 5 seconds via
snackbar." Clearing destroys a figure the user typed, and the app gives an undo
to *archiving a category*, which is less destructive than this.

**Third undo gap**, after dismissing a pending entry (§17.7) and deleting a rule
(§18.4), and the three have a shared cause worth naming: every one of them is on
a screen written after M2, and the M2-era screens share an `offerUndo` helper
that the later ones each re-derived or skipped. The gap is not carelessness
about the requirement; it is a helper that never became the obvious thing to
reach for. `BudgetScreen` already had `offerUndo` two hundred lines above the
call that did not use it.

### 19.6 Four smaller ones

| | Finding | Fix |
|---|---|---|
| C6 | Eight `String.format(template, name)` calls across three screens used the platform default locale rather than the composition's. Harmless for `%s` today, wrong the moment a template gains a `%d`, and inconsistent with every other formatted string in the app | `String.format(locale, …)` |
| C7 | `LedgerRow` set no `Role.Button` when clickable, so the same component announced itself differently on the ledger, the dashboard, the income screen and the largest-expenses list. Compose exposes the click action either way, so TalkBack worked — the defect is that one component had two voices | `role = Role.Button` when `onClick != null` |
| C8 | `architectureCheck` scanned `src/main/java` only, so `src/debug`'s five-year seeder — which handles paisa — sat outside both of its rules. A guard with a blind spot over a source set stops being one the moment code moves into it | The money rule now covers `src/debug/java` |
| C9 | NFR-MAIN-02 wants ≥80% line coverage and there was **no coverage tool configured at all** | See below |

### 19.7 What the sweep found clean

Recording this matters as much as the findings, because it is what a sweep is
for and what makes the next one cheaper:

- **No `Locale.getDefault()` and no `LocalDate.now()` anywhere outside `core/`.**
  §15.7's D7 and D9 fixed the two that existed and nothing has regressed.
- **StrictMode is installed** in debug per NFR-PERF-09, with the Binder caveat
  documented.
- **`architectureCheck` genuinely enforces both of its rules** — it is not a
  task that passes because it checks nothing. Verified by reading it, not by
  watching it succeed.
- **Every entry picker uses `activeChildren`** per FR-CAT-08 — Quick Add, the
  category picker, the rule editor's targets.
- **No read sums the ledger where a rollup exists.** The four that sum `expense`
  are 03 §5.3's documented day-range fallback and the ribbon's per-day grain,
  where no rollup exists to read.
- **FR-APP-03's "last-viewed screen and period"** both survive process death —
  `rememberSaveable` for the period, `rememberNavController`'s own saver for the
  back stack.

### 19.8 NFR-MAIN-02, and why it is JaCoCo

The requirement wanted a number; the project had no tool.

**Kover was the right choice and does not work here.** It is the Kotlin-aware
option and would count inline functions and `when` branches more honestly than
JaCoCo can. But 0.9.1 does not recognise AGP 9's variants: it applied its JVM
behaviour, found no `test` task, reported "no sources" — **and `koverVerify`
passed.** A gate that measures nothing and says yes is worse than no gate, and
it would have gone into the log as a satisfied requirement.

So the report is wired by hand over `testDebugUnitTest`, where every input is
visible: AGP's exec data, and the class files from
`intermediates/built_in_kotlinc/debug` — which is where AGP 9's bundled compiler
writes, the same move the root build file's comment is about.

**The figure is partial and the log says so rather than claiming otherwise.** It
covers `domain/` and `core/` — the calculation layer, and the whole of what
NFR-MAIN-01 made pure so that it could be measured this way. The requirement
also names the repository layer, which needs the instrumented suite; counting it
here would report zero for code that is in fact well covered and turn an honest
number into a meaningless one.

> **84.7% — 488 of 576 lines.** Above NFR-MAIN-02's 80%, and the gate was
> checked by raising it to 99% and confirming it fails.

### 19.9 Test inventory

| Suite | Where | Added |
|---|---|---|
| **`RecoveryPathTest`** | instrumented | **3** — new, including a negative control that fails if the guard is removed |
| `IncomeRepositoryTest` | instrumented | +4 |
| `ImportValidationTest` | instrumented | +3 |
| `RollupDriftCheckTest` | instrumented | +4 |
| `LedgerViewModelTest` | instrumented | +3 |
| `BudgetViewModelTest` | instrumented | +3 |

`RecoveryPathTest.the_unguarded_flow_really_would_have_thrown` is the one worth
pointing at: without it the suite would pass equally well against a flow that
never fails, and would be asserting nothing at all.

### 19.10 Measured

| | Target | after §18 | after this pass |
|---|---|---|---|
| APK | ≤ 6 MB | 1.64 MB | **1.66 MB** (1,737,397 bytes) |
| Methods, single dex | ≤ 40,000 | 18,836 | **18,870** |
| Classes | — | 3,603 | **3,614** |
| Lint release | no errors | 0 errors, 6 warnings | **0 errors, 6 warnings** |
| JVM tests | — | 239 | **239, 0 failures** |
| Instrumented `@Test` written | — | 427 | **447** |
| **Line coverage, calculation layer** | ≥ 80% | *not measured* | **84.7%** |

FR-APP-01 re-verified — no `INTERNET` in the merged release manifest. The debug
seeder is still absent from the release dex.

### 19.11 Still not done

- **Nothing instrumented has been run since M2.** 447 written tests now sit
  behind a suite last green at 174 / 174, and every one of C1 through C5 is
  exercised by instrumented tests — so once again the findings that most need
  proving are the ones no pass has been able to prove. Six audits have now each
  found defects that tests in that backlog already asserted against.
- **No performance target has been measured.** Cold start, dashboard render,
  export time — all need the reference Cortex-A53, and the SRS is explicit that
  a flagship measurement is not evidence.
- **NFR-MAIN-02 is half met.** The calculation layer is at 84.7%; the repository
  layer needs the suite that has not run.
- **The greyscale capture** for NFR-USE-05, across five screens.
- **The release APK has never been installed and driven.**
- Deferred by decision, each recorded where taken: the Reports screen (§17.2),
  FR-CAT-11, FR-IS-07, and P1's FR-APP-04 app lock with NFR-SEC-04's
  `FLAG_SECURE`. `bn` remains undeclared, and every string is extracted for it.

---

## 20. Finishing the project

Two backlogs had been carried for the whole project, and they were carried for
different reasons.

One was **blocked on hardware**. The instrumented suite had not run since M2 —
last green at 174 / 174, and holding 447 tests by the time this pass started. No
performance target had ever been measured, NFR-MAIN-02 was half met, NFR-USE-05
had never been checked, and the release APK had never been installed. Six audits
had each found defects that tests already sitting in that backlog asserted
against, so the untested surface was demonstrably where the defects were, and
saying so again was not going to find any more of them.

The other was **deferred by decision** — five items, each recorded where the
call was taken, and now asked for: FR-CAT-11, FR-IS-07, NFR-SEC-04, FR-APP-04
and the Reports screen.

A Galaxy A54 unblocked the first, which is why it went first: 447 unrun tests
against five new features would have made every failure ambiguous between old
code and new.

### 20.1 The run

**Thirty failures out of 447.** Twenty-nine were defects in tests; **one was a
defect in the app, and it was the M5 exit criterion.**

That ratio is worth sitting with, because it is the opposite of what five
audits' worth of evidence predicted. The running argument through §13, §15, §18
and §19 was that the untested surface is where the defects live — and it was
true, six times, about code the tests could see. What the backlog turned out to
be hiding was mostly *the tests themselves*: assertions written against screens
that had never rendered, files hand-built in a shape the importer would never
accept, a query plan asserted by a string SQLite does not print. None of those
would have been visible to any amount of reading either, because reading them
is exactly what produced them.

#### The one app defect: `WIPE_ORDER` could not wipe

```
android.database.sqlite.SQLiteConstraintException: FOREIGN KEY constraint failed
    (code 1811 SQLITE_CONSTRAINT_TRIGGER[1811])
```

`category.parent_id` references `category(id)` `ON DELETE RESTRICT`, and
`WIPE_ORDER` listed `category` as a single entry. A single `DELETE FROM category`
walks the table in rowid order and reaches a root while its children still point
at it, so SQLite refuses — and the seed creates thirteen leaves under three
roots, so **every database that has ever existed hits it**.

Its own docstring named the hazard:

> "every reference is `ON DELETE RESTRICT`, so a parent deleted before its
> children fails"

and then got it right for every table except the one that references itself. The
rule it was reasoning about is ordering *between* tables; `category` fails it
*within* one.

Two callers were affected, which is both of them: `SettingsRepository.deleteAllData`
— FR-DAT-06's "delete all data" — and `Importer.replace`, which is half of
FR-DAT-03. **M5's exit criterion is "round-trip export→wipe→import loses
nothing", and it could not complete.** The criterion had been recorded as met on
the strength of a test that had never been executed.

The list is now complete statements rather than table names, because nothing
that reads as a list of table names can express "children first" for a
self-referencing one:

```kotlin
"DELETE FROM category WHERE parent_id IS NOT NULL",
"DELETE FROM category",
```

Two statements suffice and the depth trigger is what guarantees it: no category
may sit under a category that already has a parent.

**And a third copy of the list existed.** `ExportImportRoundTripTest` kept its
own nine-element `listOf(...)`, so after the fix the test went on failing
against a bug that was no longer there — the exact failure `WIPE_ORDER`'s
docstring warns about, two paragraphs above the code that fell for it. It reads
`Schema.WIPE_ORDER` now.

#### The twenty-nine test defects, in four groups

**Sixteen were assertions about the semantics tree that the app deliberately
does not populate.** `DashboardScreenTest` and `IncomeScreenTest` asserted
`onNodeWithText("৳500")` on figures carrying `Modifier.clearAndSetSemantics {}`
— which is there so TalkBack reads an amount once, as words, instead of twice
(05 §10). The figure is on screen and simply not in the tree as text, so the
assertion could never pass. They asserted the spoken description instead, which
checks the same number *and* the accessibility requirement with it.

Underneath that were three more:

- **Uppercase.** `SectionHeader` uppercases for presentation; the tests asserted
  the string resource. They now match case-insensitively, because what they mean
  to assert is that the section is present, not how it is cased.
- **`LazyColumn`.** A section below the fold is never composed, so it is absent
  from the tree — indistinguishable, to a test, from a section that does not
  exist. Whether one fits depends on how much the fixture put above it, which is
  why this bit some tests and not others. The helpers scroll first.
- **`show(period)` waited for "August 2026"** while two tests passed it a
  different month, so they spent five seconds waiting for a month they had
  deliberately navigated away from.

**Six were files the importer was right to refuse.** `ImportValidationTest`
pasted local category ids into hand-built JSON and declared no categories, and
the importer answered `DANGLING_REFERENCE`. It was correct to: foreign keys
resolve **through uuid, never through the file's integer id**, because the same
integer means a different category on a different phone — the silent mis-filing
the indirection exists to prevent. The fixtures now emit the real rows, with
their real uuids, and their roots: a leaf without its parent is still not a
tree.

One of those files was shared with a test that was **passing** —
`a_merged_insert_gets_a_local_id_rather_than_the_files_one`, which asserted a
property of an import that never happened.

**Three were expectations that never matched the app.** The `two_identical_expenses`
test counted `amount_minor = 34000`, and `seedSomething` inserts ৳340 — which
*is* 34,000 paisa, so it counted the fixture's own row and asserted two where a
working merge gives three. `SchemaAssertionsTest` asserted the plan contained
`"rollup_expense_month"`; SQLite names the **alias**, and the plan it actually
prints — `SEARCH r USING PRIMARY KEY` — is a stronger statement than the one
being asserted. And a ledger filter test waited on `filters` alone, which lands
before the reload it triggers.

**Four were about the seeded corpus**, and they turned up something real —
§20.6.

#### One genuine app defect the run led to indirectly

Chasing the uppercase headers found `SectionHeader` calling `text.uppercase()`
with no argument. Kotlin's no-arg form is `Locale.ROOT`, so a Turkish user gets
"I" where the language needs "İ" — the same class of defect C6 fixed for
`String.format` in §19.6, in a call that sweep did not look at because it was
hunting format strings. It takes the composition's locale now.

### 20.2 FR-CAT-11 and FR-IS-07 — reorder

Deferred as a pair since M2 and M3, and **nearly free at the data layer**:
`sort_order` had existed on `category` and `income_source` since M1, every read
already ordered by it, both indices already carried it, and — checked — it
already round-tripped through export. What was missing was a write path and a
control.

**Move up / move down, not a drag handle.** Neither requirement names a gesture.
Compose has no reorderable list, so dragging means hand-rolled gesture and
animation code that is hard to test and, more to the point, cannot be operated
by TalkBack at all — a screen reader has nothing to drag. Two buttons work by
touch, by keyboard and by screen reader without a second implementation for
each. At the end of its range a control is **absent, not disabled**, which is
FR-CAT-03's rule for the rest of that screen.

**Every sibling is rewritten on each move, not just the two that swapped.** The
seed assigns `sort_order` positionally and nothing had written it since, so a
tree can hold a whole run of rows that all say `0` — and a swap between two rows
that both say 0 does nothing at all. Normalising the run to 0..n-1 makes the
operation total instead of dependent on the state it started from. There is a
test for exactly that, because it is the case a naive implementation passes in
review and fails on a real database.

FR-CAT-11 says "within their parent", so it is offered on children and not on
roots, which have no parent to be within — which also keeps the root header from
carrying five controls on one line at 1.3× font scale. The two action rows moved
to `FlowRow` for the same reason (NFR-COMP-04).

### 20.3 NFR-SEC-04 and FR-APP-04 — the privacy pair

Both say "optional" in the requirement itself, so both are off until asked for.
A privacy control nobody switched on is a surprise, and `FLAG_SECURE` is a
surprise that breaks the user's screenshots.

**The app lock delegates to the OS and stores no secret of its own**, because
NFR-SEC-05 had already settled the question underneath it:

> "Database encryption at rest is out of scope for v1; the rationale — that it
> requires bundling a native crypto library at material size and startup cost,
> **while the device lock screen already gates access** — is recorded here
> deliberately"

The database is plaintext in app-private storage. An app-specific PIN would be a
second, weaker secret in front of data whose real protection is the device lock:
it would need a hash, a salt, an attempt limit and a forgotten-PIN path, and it
would imply an encryption that is not there. `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`
satisfies both halves of "PIN or biometric" — the device credential *is* a PIN —
and leaves Khata holding nothing it could leak.

It costs a `FragmentActivity`, which is in tension with 04 §2.2's "no
Fragments". No fragment is inflated either way; what joins the startup path is
the fragment machinery, and §2.2 exists to protect NFR-PERF-01. That was settled
with a number rather than a preference — see §20.6.

**Three traps, all avoided deliberately:**

1. **It must not become §19.1 again.** The lock setting is another `app_meta`
   read on the launch path, exactly like the theme read that turned a
   recoverable database into a crash. It has the same `remember { … catch }`,
   and it fails **open**: `RecoveryScreen` is checked *before* the lock, because
   a gate that failed shut would lock a user out of the one screen that can
   rescue five years of data. There is a test for that ordering.
2. **The unlock prompt backgrounds the app**, because a device-credential prompt
   is a separate activity. Re-locking on `ON_STOP` without guarding for it locks
   the app underneath its own prompt.
3. **So does the file picker.** Coming back from choosing an export file must not
   demand a second authentication.

One `suppressNextBackground` flag covers both, consumed by the stop it was set
for rather than left standing — and `LockControllerTest` pins all of it,
including the case where the suppression is never used.

The setting is disabled with a reason when the phone has no screen lock, which
is FR-IS-05's shape reused.

### 20.4 The Reports screen

04 §7's last unbuilt entry, and the only screen in the inventory that **no
`FR-*` requires** — PRD §7 lists it at no priority tier, which is why everything
with a requirement number was built first.

It exists to answer what the dashboard structurally cannot. The dashboard is
built on per-month rollups; these questions do not align to months — what the
wedding fortnight cost, what this year has come to, where the money went between
two dates the user picks. 03 §5.3 anticipated this exact screen when it carved
out its exception:

> "Range queries are a deliberate exception to the rollup strategy: they are
> invoked from the reports screen on explicit user action, not on every
> dashboard render, so a bounded index scan is acceptable there."

So **this is the one place in the app that is supposed to read the ledger
directly**. Everywhere else a read that scanned `expense` for a total would be a
defect; here it is the design, and the distinction is frequency rather than
size.

`SpendMix` gained an `ofTotals` overload and `of(groups)` now delegates to it,
so the report and the dashboard cannot disagree about what "40% variable" means.
Changing the range uses `flatMapLatest`, so dragging through presets drops the
queries in flight instead of racing them. Endpoints picked in the wrong order
are swapped rather than refused: a user who taps the end date first has not made
a mistake.

### 20.5 NFR-MAIN-02, finished

§19.8 wired JaCoCo by hand and reported **84.7% over `domain/` and `core/`** —
half the requirement, and said so, because the repository half needed a suite
that had never run. It runs now.

`enableAndroidTestCoverage = true`, the instrumented `.ec` merged into the same
report, and `data/repo/**` added to the class filter:

> **94.2% — 1,183 of 1,256 lines**, across both layers NFR-MAIN-02 names.

| Package | Lines |
|---|---|
| `core/time` | 35 / 35 — 100% |
| `core/text` | 7 / 7 — 100% |
| `core/money` | 94 / 96 — 97.9% |
| `domain/usecase` | 304 / 311 — 97.7% |
| `domain/model` | 126 / 130 — 96.9% |
| **`data/repo`** | **617 / 677 — 91.1%** |

The instrumented data is joined as a `fileTree` rather than a path, so a
developer who has not run the connected suite still gets a JVM-only report
instead of a build failure — and the gate then fails honestly on the
repositories rather than passing on a file that was never written.

### 20.6 The performance targets, measured

Ten iterations each, `CompilationMode.Partial()`, against 02 §3.1's corpus
seeded onto a **minified, non-debuggable** build: **22,323 expenses, 404 income
entries, 60 categories, 60 periods**.

**The device is a Galaxy A54 (SM-A546E, arm64, 8 cores at 2.0 GHz), and it is
not the reference device.** 02 §3.1 defines every target on a 1.4 GHz Cortex-A53
with 2 GB of RAM and eMMC storage, and says plainly that "targets measured on a
flagship device are not evidence of compliance". These figures are therefore
**real measurements that do not establish compliance**. They establish something
weaker and still worth having: the A54 is several times faster than the
reference, so a target *missed* here is missed there too.

| | Target | Measured (median) | |
|---|---|---|---|
| NFR-PERF-01 cold start | ≤ 800 ms | **288 ms** | inside |
| — without compilation | ≤ 800 ms | 311 ms | |
| NFR-PERF-02 warm start | ≤ 250 ms | **168 ms** fully drawn, 89 ms first frame | inside |
| NFR-PERF-03 expense committed | ≤ 100 ms | **17 ms** (worst 34) | inside |
| NFR-PERF-04 dashboard fully rendered | ≤ 300 ms | **572 ms** | **outside** |
| — before the app's baseline profile existed | ≤ 300 ms | 666 ms | |
| — of which, first frame | — | 300 ms | |
| — of which, the nine reads | — | 223 ms | |
| NFR-PERF-05 ledger scroll | no frame > 16 ms at p95 | **13.5 ms at p95**, 1.1% janky | inside |
| NFR-PERF-06 period switch | ≤ 150 ms | **5 frames per switch**, 14 ms at p95 | inside |
| NFR-PERF-07 full JSON export | ≤ 3 s | **1,197 ms** (5.1 MB) | inside |
| NFR-PERF-08 steady-state memory | ≤ 80 MB | **50.8 MB** anonymous RSS | see below |
| NFR-PERF-09 main-thread database | zero | StrictMode, debug | enforced |

**All nine are measured now.** Seven are inside their targets, NFR-PERF-04 is
outside, and NFR-PERF-08 needs a reading of the requirement rather than another
number. The two runs of `dashboardCold` in this pass gave 552 ms and 572 ms;
both are recorded rather than the friendlier one.

**NFR-PERF-04 does not meet its target, and this is the first time anyone could
know that.** M4 recorded the criterion as met on the strength of
`DashboardScaleTest`, which proves the *structural* claim — that the dashboard's
reads are bounded by the category tree rather than by history — and that claim
still holds. What it never measured was the wall clock, and the wall clock is
666 ms from launch to a dashboard with figures on it.

Measuring it at all took a change: `StartupTimingMetric` reports
time-to-*initial*-display, which on this screen is the skeleton (05 §8 draws one
deliberately), so the number that looked like the criterion — 295 ms — is the
frame *before* the figures arrive. `ReportDrawnWhen { !state.initialLoad }` in
`DashboardScreen` is the platform's own way of saying when the content is
actually there, and it turns the criterion into `timeToFullDisplayMs`.

Where the 666 ms goes, so far as this pass can attribute it: 295 ms to the first
frame, and 223 ms of the remainder is the reads themselves — measured separately
by `PerformanceProbeTest`, which times the eight dashboard flows against the same
corpus. The rest is composition.

**The app had never had a baseline profile of its own — and now it does.**
04 §2.2 makes them mandatory rather than optional: "on low-end hardware these
routinely cut Compose cold start by 20–30%". But `BaselineProfileGenerator`
needs a rootable device, so it had always been skipped, and the 3,345-line
profile shipping in the release APK came entirely from library-supplied rules
(navigation, compose) with **no Khata code in it at all**. The numbers showed it:
314 ms without compilation against 305 ms with, a 3% difference where the
architecture assumed 20–30%.

Generated on the `Khata_API35` AVD, which is `google_apis` and therefore
rootable. The release profile went from 3,345 rules and **zero** app references
to 24,795 rules and **2,738** of them Khata's own.

Its effect on NFR-PERF-04, measured the same way on the same corpus:

> **666 ms → 552 ms. A 17% improvement**, in the neighbourhood 04 §2.2 predicted
> — and still 252 ms outside the target.

Two notes on the wiring, because both cost a build to find. The plugin injects
its output only into variants it manages, so the hand-rolled `benchmark` build
type reads `src/main/generated/baselineProfiles` explicitly or it measures
library rules only. And the generated file is what `release` ships: verified by
building with and without it, `assets/dexopt/baseline.prof` grows from 8,737 to
9,433 bytes.

**Three of those took a change to measure at all**, and the changes are more
interesting than the numbers.

**NFR-PERF-02 had no benchmark.** `StartupBenchmark` measured cold start twice
and warm start never, though the two are different numbers about different
things — warm start is what happens every time a user comes back from another
app, which is most of the time. `StartupMode.WARM` and it is 168 ms fully drawn.

**NFR-PERF-05 and -06 were measured with the wrong metric.** `FrameTimingMetric`
returned `frameCount` and nothing else on this device — 599 frames for the
scroll, 30 for the period switch — and a frame count cannot answer a question
posed in milliseconds. The first version of this section recorded that as
"unmeasured", which was true but incurious: `FrameTimingGfxInfoMetric` reads
`dumpsys gfxinfo` rather than the frame timeline and reports percentiles
directly, which is the exact shape NFR-PERF-05 is written in — "no frame > 16 ms
at **p95**". The scroll comes in at **13.5 ms at p95** over a 22,000-row ledger,
with 1.1% of frames janky.

NFR-PERF-06 is the one figure here that is derived rather than read off: the
benchmark performs six switches per iteration and records 30 frames, so five
frames per switch, which at the measured 6 ms median and 14 ms p95 is somewhere
between 30 and 70 ms. Inside 150 ms with room, but it is arithmetic on a frame
count, and it is labelled that way rather than presented as a stopwatch reading.

**NFR-PERF-08 is measured and the requirement is ambiguous.** "Steady-state
resident memory ≤ 80 MB" does not say which resident memory, and the two honest
readings disagree:

| | |
|---|---|
| Java heap | 8.8 MB |
| **Anonymous RSS** — what the app itself allocated | **50.8 MB** |
| File-backed RSS — mapped framework, fonts, the APK | 93.6 MB |
| GPU | 44.0 MB |

On the reading that matters for a device with 2 GB of RAM — the memory this app
is responsible for — it is **50.8 MB against 80 MB**. On the literal reading,
total RSS is about 145 MB, and would be for any Compose app, because file-backed
pages are shared framework mappings that exist whether Khata runs or not.
Recording both rather than picking the flattering one: the requirement should
say which it means, and until it does this is not a clean pass.

### 20.7 NFR-USE-05, looked at

"State is never conveyed by colour alone" had been asserted about since M2 and
never *checked*, because checking it means looking at the screens with the
colour taken out. `GreyscaleCaptureTest` renders five surfaces, desaturates each
by Rec. 709 luminance and writes a PNG, with the fixture deliberately holding
every state the requirement is about: a leaf over its limit, one under, one
unbudgeted, a refund, and a pending row.

Rec. 709 rather than a channel average, because indigo and vermilion average to
similar values and differ in perceived lightness — averaging would hide exactly
the failure being looked for.

**It passes, and the budget screen is the strongest case.** Three states, three
bar treatments that survive desaturation *and* three sentences: over-budget is a
doubled rule with "113% of ৳8,000" and "৳1,050 over"; under is a partial fill
with "30% of ৳4,000" and "৳2,800 left"; unbudgeted is a dashed outline with "No
limit set" and "Set one". The ledger's refunds read `−৳450` with a true U+2212,
the pending section is headed "WAITING TO CONFIRM" with a count and explicit
Confirm / Dismiss, and the dashboard's alerts say "over" in words.

#### And it found a defect in the one number the app exists to show

`SAFE TO SPEND TODAY` rendered as `৳` on one line and `97.22` on the next. It
was not a width problem — the container was 1080 px wide and `৳0`, two
characters, wrapped as well.

Measured directly, with node bounds:

| | width | height |
|---|---|---|
| `Text("৳12,250")`, one style | 504 px | 173 px — one line |
| `MoneyText`, symbol span at 0.7× | 480 px | **290 px — two lines** |
| `MoneyText`, no symbol | 427 px | 157 px — one line |

**A `SpanStyle` that changes `fontSize` mid-string makes Compose mis-measure the
line.** The same characters in a single style fit; the moment the ৳ carried its
own size, the line broke. The symbol had been styled that way since M1 — 05 §4.3
sets it smaller than the figure — and the physical API 36 device happened not to
show it, which is why five audits and every screen test missed it.

Two fixes were tried and one was rejected, which is worth recording. Auto-sizing
the hero to fit (`maxLines = 1` with `TextAutoSize`) stopped the wrap and started
**dropping the last digit** — `৳12,25` — because the symbol's absolute size did
not shrink with the rest, so the width never converged. A ledger that loses a
digit is worse than one that wraps: 05 §4.3 is that "in a ledger, precision is
the product". That was backed out.

**And fixing it exposed a thirtieth test defect**, which is the same one four
dashboard tests had. `BudgetScreenTest` asserted the group order by comparing
two `indexOf` results in a printed semantics tree — but the screen is a
`LazyColumn`, so `FIXED EXPENSES` is not composed until it is scrolled to, and
the comparison had been quietly running against a `-1`. It passed for as long as
the section happened to fit above the fold, and broke the moment money figures
got a few pixels taller. The rule itself is proven where it lives — in
`BudgetViewModelTest` and `BudgetSummaryTest` — so the screen test now scrolls
to the section and compares positions, which is what a screen test can actually
establish.

What shipped is the smaller fix: the symbol keeps its own **colour** and loses
its own **size**. It still reads as subordinate — `inkSoft` against the figure's
`ink` — and that is one signal rather than two, which NFR-USE-05 permits here
because the symbol carries no state: it is identical on every figure in the app,
so nothing is being distinguished by it. The hero now measures 504 × 173, byte
for byte the same layout as a plain single-style `Text`.

### 20.8 The release build, installed and driven

Never done before this pass, and M1 §11.7 is the reason it is on the list: an
R8-only defect that no debug build could have shown.

`assembleRelease` produced an **unsigned** APK, which cannot be installed —
`signingConfigs` reads a gitignored `keystore.properties` that did not exist. A
local test-signing key was generated for the purpose; it is gitignored,
throwaway, and no substitute for a real signing key at publication.

Installed on API 35 and driven by hand: launch, Quick Add, ৳350 to Grocery,
save. The snackbar reads "Expense saved", the dashboard fills in behind it —
Variable Expenses, Biggest Changes, Where It Goes, Largest Expenses, the
six-month trend — and logcat holds no `FATAL`. **No R8-only defect this time.**

| | after §19 | now |
|---|---|---|
| APK | 1,737,397 B (1.66 MB) | **2,218,600 B (2.12 MB)** |
| Dex files | 1 | **1** |
| Dex methods | 18,870 | **22,261** |
| Classes | 3,614 | **3,952** |
| Seeder in release dex | absent | **absent** |
| `INTERNET` in merged manifest | absent | **absent** |

The 0.46 MB is `androidx.biometric`, the Reports screen and the reorder
controls. NFR-SIZE-01's ceiling is 6 MB.

**And the permission count went from zero to two.** `androidx.biometric`
contributes `USE_BIOMETRIC` and `USE_FINGERPRINT` to the merged manifest. The
README had claimed the app "declares no permissions at all, INTERNET included",
and half of that is no longer true — so it now says what is: **no `INTERNET`**,
which is FR-APP-01 and the only one the offline guarantee rests on, plus two
normal-protection permissions that reach no network. Worth stating rather than
letting a sentence quietly go stale, because "no permissions" was a claim
somebody could have checked and found wrong.

### 20.9 Measured

| | after §19 | now |
|---|---|---|
| JVM tests | 239 | **239** |
| Instrumented tests | 447 written, **0 run** | **485, all run, 0 failures** |
| Line coverage | 84.7%, calculation layer only | **94.2%, both layers** |
| Lint release | 0 errors, 6 warnings | **0 errors, 6 warnings** |
| APK | 1.66 MB | **2.12 MB** |
| Dex files | 1 | **1** |
| Baseline profile, app rules | **0** | **2,738** |
| NFR-PERF targets measured | 0 of 9 | **9 of 9** — 7 inside, 1 outside, 1 ambiguous |

`architectureCheck`, the JVM suite, `lintRelease`, the instrumented suite and
`coverageVerify` all pass in a single invocation. That last part took a fix of
its own: joining the instrumented `.ec` files made `coverageReport` read a
directory another task writes, and Gradle refuses to guess the order. It is
`mustRunAfter` rather than `dependsOn`, because depending on the connected suite
would make a coverage report demand a device — and the whole reason the `.ec`
files are joined as a tree is that the JVM half still works without one.

### 20.10 The requirement sweep

With everything built, the last question worth asking is whether anything was
*missed* rather than deferred. All 109 `FR-*` and `NFR-*` ids in `02-SRS.md`,
checked against the sources rather than against memory.

**Thirteen carried no reference in any source file.** None turned out to be
unbuilt, and two of them had never been measured:

| | | |
|---|---|---|
| **NFR-SIZE-02** installed footprint | ≤ 20 MB | **5.58 MB** — 2.12 APK + 3.44 oat |
| **NFR-SIZE-05** database at five years | ≤ 6 MB | **5.41 MB** — 5,000 KB main, 512 KB WAL, 32 KB shm |

NFR-SIZE-05 is the tighter of the two and the more interesting: 03 §9 estimated
**3.8 MB** and the real figure is 5.41 MB, at a corpus 11% larger than the
20,000 rows that estimate assumed. Still inside, but with a quarter of the
budget left rather than a third — and the three schema decisions that spend it
are all deliberate: `period_ym` denormalised, a UUID on every entity for
FR-DAT-04's merge, and two rollup tables that duplicate the ledger on purpose.
Worth knowing before a fourth one is added.

The rest were satisfied without needing to name themselves. FR-EXP-03's recent
categories are in `app_meta` and read by the picker; NFR-USE-02's keypad is the
app's own and takes focus on open; FR-APP-02's airplane-mode behaviour is
structural, because there is no network code to behave differently.

**One was violated as literally written**, and has since been amended — §20.12.
NFR-COMP-02 asked for "ARM 32-bit and
64-bit; **no native libraries beyond the platform-bundled SQLite**", and the
release APK ships four ABIs of `libandroidx.graphics.path.so` — a transitive
dependency of Compose UI, not a choice this project made. It is 16 KB installed
and there is no way to have Compose without it. Recorded rather than quietly
tolerated, because the requirement is a real constraint and the answer is that
it cannot be met while the UI is Compose.

The x86 and x86_64 slices of that library are dead weight for the target market
and could be dropped with `abiFilters`. They are kept because the whole test
suite runs on an x86_64 emulator, and NFR-SIZE-01 has 3.9 MB of room.

**Three cannot be settled by this project at all**, and saying so is more useful
than leaving them looking unchecked:

- **NFR-REL-03** — "migrations tested against a populated database from each
  prior released version". `Schema.VERSION` is 1. There are no prior versions,
  so there is nothing to migrate from yet; the requirement starts applying at
  version 2.
- **NFR-REL-05** — was in direct conflict with three other requirements. It has
  been amended in `02-SRS.md`; §20.11 records why and what it now says.
- **NFR-MAIN-04** — "asserted by an automated benchmark suite run on each
  release candidate". Implemented since this sweep was written: §20.13 adds the
  assertion the suite lacked and the two aggregate tasks a release check needs.
  The workflow that calls them has no remote to run on.

### 20.11 NFR-REL-05, amended

The first requirement in this project to be **changed** rather than
implemented, and the only document edit outside this log all session — `01`
through `05` have been read-only throughout, because where the code and a
document disagree the presumption is that the code is wrong.

Here the document was.

#### The contradiction

"Crash-free session rate ≥ 99.5%" is a population statistic. Having one needs
three steps: count sessions and crashes per device, aggregate across the user
base, and deliver the aggregate to whoever verifies the number. The third is
blocked three times over, and one of those is structural:

| | |
|---|---|
| FR-APP-01 | "MUST NOT declare the `INTERNET` permission" — *verified against the merged manifest*, so there is no transport at all |
| NFR-SEC-01 | "No data leaves the device except by explicit user-initiated export" |
| NFR-SEC-02 | "No analytics, telemetry, **crash reporting SDK**, or advertising identifier" — names the exact tool |

So the requirement was not hard to satisfy. It was **unverifiable by
construction**, and the construction is mandated by three other requirements,
two of which are the product's premise rather than incidental choices.

The app *could* have computed a rate for its own device — steps one and two are
possible locally. What is impossible is anyone ever learning it, and a rate from
a single install is not a "session rate" in the sense the words mean.

#### How it got there, which is the more useful part

Look at the rest of the REL block: process death (REL-01), rollup reconciliation
(REL-02), transactional import (REL-04) — each written for *this* app, each with
a clear verification path. REL-05 is the odd one out. "Crash-free session rate"
is the standard Crashlytics and Play Console KPI, and it presumes a fleet and a
backend; it reads like an item imported from a generic non-functional checklist
that nobody re-examined against an offline, single-user app.

That is worth naming beyond this one row, because **NFR-PERF-08 has the same
smell** — "steady-state resident memory ≤ 80 MB" never says *which* resident
memory, and §20.6 had to record two readings that disagree by a factor of three.
Both are cases of a number borrowed from a context where it was well defined.

#### What was rejected

Three alternatives, and why not:

- **Delete it.** Free and honest, but it would leave the SRS with no reliability
  requirement at all — nothing anywhere saying the app should not crash. The
  intent is legitimate; only the instrument was wrong.
- **Compute the rate on-device** and show it in Settings. This satisfies the
  *letter* — the rate exists, nothing is transmitted. Rejected because it puts a
  database write on every launch, which is exactly the path 04 §6 spends real
  effort keeping clear for NFR-PERF-01, and buys a statistic with n = 1 that
  nobody would read. Satisfying the words while betraying the point.
- **A "share crash log" action.** Permitted — NFR-SEC-01 explicitly allows
  user-initiated export — and probably worth having on its own merits. But it
  yields no rate, so it resolves nothing here. Noted as a separate idea rather
  than folded in to make this look finished.

#### What it says now

> A release candidate must exhibit **no unhandled exception**. *Accept:* the
> instrumented suite completes with zero crashes; no StrictMode violation
> attributable to app code; the crash log is empty after the dogfooding period.

Two of the three clauses are **already machine-checked by machinery that
exists**, which is the test of whether a restatement is real or cosmetic:

- The instrumented suite is 486 tests and fails the build on any crash.
- StrictMode is not merely logged. `FinanceApp` installs a listener that
  **throws** `AssertionError("Main-thread disk access in app code")` when a
  violation is attributable to app code, and logs only the vendor and framework
  ones it cannot own. So "no violation attributable to app code" is enforced on
  every debug run, the 486-test suite included.

Only the dogfooding clause is manual, and it is not invented for the occasion:
`01-PRD.md` §8 already uses "author uses it daily for one week" as M1's exit
criterion, so the window is an established idea in this project.

#### Why restating beats leaving it

A requirement that can never be marked met trains people to stop reading the
column it lives in. That is not hypothetical here: **NFR-PERF-04 sat recorded as
satisfied across two milestones** on the strength of a structural test, and
nobody looked at the wall clock until §20.6. A compliance table with a permanent
false negative in it is a table nobody audits.

The amendment is written into `02-SRS.md` in NFR-SEC-05's style — the existing
house precedent for a requirement that records a deliberate decision and its
reasoning in place, rather than leaving the reasoning somewhere it will be lost.

### 20.12 NFR-COMP-02, amended

The second requirement changed rather than implemented, and the reasoning was
the same shape as §20.11's — but only after checking, because "the framework
forces it on us" is the kind of claim that is comfortable to make and easy to
get wrong.

#### What was actually checked

`libandroidx.graphics.path.so` ships in four ABIs. Three questions, in order:

1. **Where does it come from?** `androidx.graphics:graphics-path:1.0.1`, pulled
   in transitively by Compose UI. Not a dependency this project declares.
2. **Is it live, or is it dead weight R8 already stripped the Java side of?**
   Live. The release dex — *after* shrinking — still carries two references to
   `androidx/graphics/path` and eight to `PathIterator`. Had it been dead, a
   `jniLibs` exclusion would have removed it and the requirement would have been
   met outright, which is why this was worth checking rather than assuming.
3. **Is it needed at this minSdk?** Yes. It supplies `PathIterator`; the
   platform gained `Path.getPathIterator()` only at API 34, and NFR-COMP-01 sets
   the floor at 26. Excluding it would be an `UnsatisfiedLinkError` on API 26–33
   — precisely the low-end devices 01 §3 describes as the target.

So the requirement cannot hold while the UI is Compose. That is a real
conflict between NFR-COMP-02 and the architecture 04 §2.2 chose, not an
oversight in the build.

#### What was measured and left alone

| | |
|---|---|
| `arm64-v8a` | 10,096 B |
| `armeabi-v7a` | 7,252 B |
| `x86` | 9,284 B |
| `x86_64` | 10,760 B |
| Installed cost | **16 KB** — only the matching ABI is extracted |

The x86 slices are 20 KB of dead weight for the target market and could be
dropped with a release-only `abiFilters`. **Deliberately not done.** Twenty
kilobytes against NFR-SIZE-01's remaining 3.9 MB does not pay for a release
artifact that cannot run on the x86 emulator the entire test suite uses — and
shipping x86 *as well* does not breach a clause that asks for ARM support;
supporting a superset is not a violation.

#### What the amendment preserves

The clause was protecting something real, and it is worth separating from the
wording that failed. Native code is how a small app becomes a large one — each
ABI is another copy, R8 cannot shrink any of it, and none of it can be read the
way Kotlin can. That argument is exactly why **NFR-SEC-05 ruled out database
encryption**: SQLCipher would be precisely this kind of dependency, at megabytes
rather than kilobytes.

So the restatement keeps the prohibition on native code *this project chooses*
and admits transitive AndroidX native code under review — which is not a new
mechanism but the one **NFR-SIZE-04 already established** for dependencies:
"justified individually in review". One library, named, sized, and explained.

The distinction is doing real work rather than excusing a failure: under the new
wording, adding SQLCipher would still be refused, and so would any second
transitive native library that arrived without someone writing down why.

### 20.13 NFR-MAIN-04, implemented

The one item on the outstanding list with real engineering in it rather than a
wording problem. "Performance targets are asserted by an automated benchmark
suite run on each release candidate" — two halves, and the project had neither.

#### Macrobenchmark reports; it does not assert

`measureRepeated` writes a JSON file and returns. A target can be missed by 90%
and the build stays green, which is not a hypothetical failure mode here:
**NFR-PERF-04 was recorded as satisfied across two milestones** on the strength
of a structural test, and nobody read the wall clock until §20.6. The suite was
a measuring instrument with nobody obliged to look at the dial.

`:benchmark:verifyPerformance` reads the results and fails on three things:

1. a metric over its budget in `benchmark/performance-budget.txt`;
2. an **exempted** metric that has got worse than its recorded ceiling;
3. a budgeted benchmark **absent from the results**.

The third is what makes the other two worth anything. Macrobenchmark skips
routinely — `BaselineProfileGenerator` needs root and is skipped on every device
that lacks it — so a gate that only checks the numbers it finds would pass a run
in which nothing ran at all.

**Both failure paths were verified rather than assumed.** Tightening the ledger
scroll budget from 16 ms to 5 produced `over its budget of 5.0` and a failed
build; adding a budget for a benchmark that does not exist produced `was never
measured — the benchmark did not run or was skipped`. A gate that has only ever
been seen to pass is not known to be a gate.

#### The exemption, and why it is not a lowered threshold

NFR-PERF-04 misses. The temptation is to set its budget to what the app
currently does, which converts a gate into a ratchet that certifies the
regression. The budget file keeps the real 300 ms and records an exemption
beside it:

```
dashboardCold.timeToFullDisplayMs  <= 300   # NFR-PERF-04
exempt dashboardCold.timeToFullDisplayMs ceiling 700
```

so the run reports `EXEMPT ... = 571.5 (budget 300.0, exempt below 700.0)` and
still fails if it gets worse. The pattern is the project's own: `lint-baseline.xml`
already records what exists today so that new issues fail the build without a
flag day. Remove the exemption line, not the budget, when it is fixed.

Current state, against 02 §3.1's budgets unchanged:

| | Measured | Budget | |
|---|---|---|---|
| NFR-PERF-01 cold start | 288.4 ms | 800 | ok |
| NFR-PERF-02 warm start | 168.5 ms | 250 | ok |
| NFR-PERF-04 dashboard | 571.5 ms | 300 | **exempt below 700** |
| NFR-PERF-05 scroll p95 | 13.5 ms | 16 | ok |
| NFR-PERF-06 per switch | 70.0 ms | 150 | ok, derived |
| NFR-PERF-08 anon RSS | 51,980 KB | 81,920 | ok |

NFR-PERF-06 is the only derived figure — frames per switch times the p95 frame
time — and the metric is named `derivedMsPerSwitch` precisely so the budget file
cannot pass it off as a stopwatch reading. NFR-PERF-03 and -07 are asserted
already, by `PerformanceProbeTest` on the device.

#### "Run on each release candidate"

Two aggregate tasks, not one, because the halves need different hardware:

- **`./gradlew releaseCandidateCheck`** — architecture rules, JVM suite, lint,
  instrumented suite, coverage gate, release build. Meaningful on any device.
- **`./gradlew performanceCheck`** — the benchmark run and the budget assertion.
  Needs a real ARM device with the corpus seeded.

`.github/workflows/release-candidate.yml` runs the first on a hosted runner and
**deliberately omits the second**. That omission is the substance of the change
rather than a gap in it: 02 §3.1 says a figure from the wrong device "is not
evidence of compliance", and `DashboardBenchmark`'s header is blunter — an
x86_64 emulator "will beat it by an order of magnitude while saying nothing at
all about the phone this app is for". A hosted runner would produce green
numbers that mean nothing, and a passing check nobody can trust is worse than no
check, because it teaches people to stop reading the column.

`releaseCandidateCheck` was then run end to end: **486 instrumented tests, zero
failures, BUILD SUCCESSFUL in 11m 12s** on an API 35 emulator, covering all six
constituent tasks in one invocation.

Getting there took six attempts, and the causes are worth recording because none
of them was the code:

- A **different AVD claimed port 5554** — API 37 — and 84 tests failed in the
  five Compose classes with a single shared signature. That is the Espresso
  incompatibility the README already documents, not a regression; every
  non-Compose test passed.
- **Stale Windows file locks** on `androidTest-results`, traced to five orphaned
  JVMs left by killed builds, one of them running for over an hour and holding
  3,200 CPU-seconds. Clearing them fixed the locks *and* the emulator
  instability that had been dogging the whole session — the orphans were eating
  the memory the emulator needed.

One run that was interrupted mid-flight reported a single failure at test 280.
It did not reproduce in the clean run and no results file survived to identify
it, so it is recorded here as an observation rather than chased: a failure seen
once, in a process being torn down, with no repro.

The **workflow** has a remote now — `git@github.com:abubakar1010/expenses.git`,
which authenticates and is empty — and one adjustment came out of knowing it.
It had triggered on pull requests and `v*` tags only. This project's history is
direct commits to `master`, so a gate configured that way would have fired
approximately never; it now also runs on pushes to the default branch. Nothing
has been pushed: that is the author's call, not this pass's.

### 20.14 NFR-PERF-08, disambiguated

The third requirement amended rather than implemented, and the last of the
three §20.11 predicted: a number borrowed from a context where it was well
defined, dropped into one where it is not.

"Steady-state resident memory ≤ 80 MB" never said *which* resident memory, and
the readings disagree by a factor of three:

| | |
|---|---|
| Java heap | 8.8 MB |
| **Anonymous RSS** — pages this app allocated | **50.8 MB** |
| File-backed RSS — framework, fonts, the APK | 93.6 MB |
| GPU | 44.0 MB |

On one reading the app passes with a third of the budget spare. On the other it
fails, and **so would every Compose application ever written**, because
file-backed pages are shared mappings that exist whether Khata is running or
not. A budget that no member of its category can meet is not measuring the thing
it was written to measure.

Anonymous RSS is the honest denominator: it is what the app actually allocated,
it is what the system reclaims against this process, and — the part that decides
it — it is what `MemoryUsageMetric` reports, so `verifyPerformance` can gate on
it. A budget nothing can assert is a budget nobody checks, which §20.13 has
already been through once.

So the requirement now names the metric and the moment: `memoryRssAnonLastKb`,
once the dashboard has settled over five years of data. **50,980 KB against a
budget of 81,920**, and the gate fails if that changes.

Three requirements have now been amended in this pass — NFR-REL-05, NFR-COMP-02
and this one — and it is worth saying plainly that all three failed the same
way. Each was a plausible-sounding number or prohibition imported from general
practice: a crash-free session rate that presumes a backend, a native-code ban
that presumes you control your dependency graph, a memory ceiling that presumes
an agreed denominator. None of them was wrong about what it wanted. All three
were wrong about what could be observed, and none was caught until something
tried to measure it.

That is the argument for measuring early rather than at the end, and this
project made it the hard way: **six audits and five milestones passed before
anything tried.**

### 20.15 What is left

- **NFR-PERF-04 misses its target on the A54** — 552 and 572 ms across two runs
  against 300 ms, with the baseline profile in place. §20.6 has the
  decomposition: ~290 ms to the first frame, 223 ms for the reads, the rest
  composition. 04 §2.2 names the fallback
  if the reference device misses its budget after profiles, single-Activity and
  R8 full mode — XML views for the entry and ledger screens — and that decision
  needs the reference device, not this one.
- **NFR-PERF-08 has been disambiguated** — §20.14. It now names anonymous RSS
  and the moment it is taken, and `verifyPerformance` gates on it.
- **Every figure here is off the reference device.** 02 §3.1's targets are about
  a 1.4 GHz Cortex-A53, and nothing else can settle them.
- **NFR-REL-05's third clause is a release step, not a build step.** Two of the
  three are machine-checked and green — 486 instrumented tests with no crash,
  and no StrictMode violation attributable to app code, which `FinanceApp`
  enforces by throwing. The dogfooding clause is by definition manual and comes
  due at the release candidate.
- **`bn` is undeclared, and no requirement asks for it.** FR-APP-05 wants the
  Taka symbol and locale-appropriate grouping, both of which are implemented;
  a Bengali translation was groundwork, never a requirement. Every string is
  extracted and every plural is a `<plurals>`, so it is a file rather than a
  refactor — but the terminology needs somebody who reads Bengali, and a ledger
  that names things wrongly is worse than one in English.
- **NFR-COMP-02 has been amended** rather than left failing — §20.12. The one
  transitive native library is named, sized and justified in the requirement
  itself, under the review mechanism NFR-SIZE-04 already established.
- **NFR-MAIN-04's workflow has not run yet.** The remote exists now
  (`git@github.com:abubakar1010/expenses.git`, authenticating and empty) and the
  workflow triggers on pushes to the default branch as well as tags; it runs the
  first time anything is pushed. The gate it calls is green end to end — 486
  tests in one invocation — and its failure paths are verified.

Nothing else. Every `FR-*` and every `NFR-*` in `02-SRS.md` is implemented, and
the list above is what remains of their *verification* — one failing target, one
constraint the framework makes impossible, one manual release step, one
ambiguous wording, and a language nobody has asked for.

---

## 21. Backup: making the artifact happen without being remembered

**Date:** 22 August 2026

`01-PRD.md` §6.6 has said the same thing since the first draft:

> "Users do not trust an app with their financial history until they have proof
> they can extract it. It is also the only backup mechanism in a no-server
> product."

M5 built that mechanism and §17 records it working: streaming JSON, a
transactional import, UUID-then-natural-key dedup, a schema gate, and
`ExportImportRoundTripTest` proving twenty-one rendered figures survive a wipe.

None of it runs unless somebody remembers to run it.

That is the whole of this section. The file format was not the problem, the
importer was not the problem, and neither needed changing. What was missing sat
entirely around them: a backup happened only on request, went to a location
re-chosen every time, left no record that it had happened, was plaintext, and —
the part that matters most — a user who reinstalled landed on an empty dashboard
with nothing to suggest their five years were recoverable at all.

### 21.1 What was actually built

Six pieces, and five of them are thin:

| | |
|---|---|
| `data/export/BackupCodec.kt` | magic number, gzip, optional AES-256-GCM in authenticated blocks |
| `data/backup/BackupStore.kt` | the folder, as an interface |
| `data/backup/SafBackupStore.kt` | that interface over `DocumentsContract` |
| `data/repo/BackupRepository.kt` | whether, where, what to delete, what a restore must not lose |
| `ui/feature/backup/` | the screen |
| `ui/WelcomeScreen.kt` | the first launch after an install |

`Exporter` and `Importer` are untouched. `decode` hands the importer the same
plain JSON it has always read, which is why all twenty of
`ImportValidationTest`'s cases still pass without being looked at. `Exporter`
gained one line — a filter, §21.4.

### 21.2 NFR-SEC-01, amended

The second requirement in this project to be **changed** rather than
implemented, and it follows §20.11's procedure exactly.

#### The contradiction

> NFR-SEC-01: "No data leaves the device except by explicit user-initiated
> export."

FR-DAT-08 backs up on launch. Nobody initiates that. The two cannot both stand,
and pretending the schedule counts as "user-initiated" because the user once
switched it on would be reinterpreting a requirement quietly — which §20.11 is
explicitly the precedent against.

#### How it got there

The wording was written when export was the only way data moved, and in that
world "the user initiates it" and "one file at a time, chosen each time" were
the same sentence. They are not the same guarantee. The first is about *who
decides*; the second is about *how often they are asked*. Only the first is what
anybody wants from this requirement.

#### What was rejected

- **Leaving the automatic backup out.** It is the feature. An export nobody runs
  is a backup nobody has, and §6.6's claim is about trust, which a mechanism
  that depends on memory does not earn.
- **Calling the schedule "user-initiated" and moving on.** The requirement would
  then mean whatever the implementation needed it to mean, which is how a
  specification stops being normative.
- **Asking each time.** That is the manual export, which already exists.

#### What it says now

> "Data leaves the device only by a transfer the user asked for, to a
> destination the user chose. The app holds no network transport, and MUST NOT
> select or infer a destination of its own."

Every protection the old wording gave is intact, and one is now stated that was
only implied — the app may not pick a destination. The grant comes from
`ACTION_OPEN_DOCUMENT_TREE`, needs no manifest permission, and can be withdrawn
in system settings. **FR-APP-01 is untouched**, still gated in CI against the
merged release manifest, so there is no transport off the device even in
principle.

#### Why restating beats leaving it

The same reason as §20.11: the requirement was protecting something real, and
the instrument had stopped matching it. A requirement that is quietly violated
by shipping code teaches people to stop reading the document.

### 21.3 The passphrase problem, and where the key lives

FR-DAT-11 makes encryption optional. FR-DAT-08 runs on launch. Together they ask
for a file to be encrypted with nobody present to type anything.

Deriving on the spot is not available either: 210,000 rounds of PBKDF2-HMAC-
SHA256 is a second or two on a Cortex-A53, and NFR-PERF-01 budgets the entire
cold start at 800 ms.

So the passphrase is turned into a key once, when the user sets it, and **the
key is stored in `app_meta` beside the ledger**. That reads badly at first and
is in fact free: anyone who can read the app's private storage can read
`khata.db`, which NFR-SEC-05 deliberately leaves unencrypted on the reasoning
that "the device lock screen already gates access". Wrapping this key would be
defending a door standing open beside it.

What backup encryption is *for* is the file after it leaves — a shared folder, a
cloud client's sync directory, a forwarded chat message. Against that, a key
that never leaves the phone is exactly the protection asked for.

The salt and iteration count travel in every file, so the passphrase alone
rebuilds the key on a phone that has never held it. Without that a stored key
would produce backups only one device could read, which is the opposite of the
point. `a_key_kept_from_an_earlier_derivation_opens_the_same_as_the_passphrase`
asserts it.

**NFR-SEC-06 rather than an amendment to NFR-SEC-05.** SEC-05's stated rationale
is the size and startup cost of a **native** crypto library — SQLCipher, which
NFR-COMP-02 refuses again. Neither reaches `AES/GCM/NoPadding` and
`PBKDF2WithHmacSHA256`, which the platform has shipped since API 26 and which
add nothing to the APK. SEC-05 is about the database at rest and still stands.

### 21.4 Three defects the tests found

#### A truncated backup would have read as a shorter ledger

`CipherInputStream` under GCM swallows `AEADBadTagException` at end of stream on
several Android versions and returns -1 instead of throwing. A tampered backup
would decode partway and stop, and `Importer` would be handed a document it had
no way to know was incomplete.

So the ciphertext is framed: each block carries its own tag through
`Cipher.doFinal`, its index and a last-block marker go into the AAD, and the
final block is sealed even when it is empty. A file cut at a block boundary is
therefore a well-formed prefix that never presents a last block — detected,
where otherwise it would simply have ended.
`a_backup_truncated_at_a_block_boundary_is_refused` is the test that would have
caught this, and it is the reason the framing exists.

#### A quiet week would have deleted a real backup

The first `runIfDue` checked only the interval. A phone opened every morning
therefore wrote a byte-identical copy each day and rotated a genuine backup out
of the folder to make room for it. After `keep` quiet days the oldest real
backup is gone and every remaining file is the same week — retention actively
destroying history in the name of keeping it.

`BackupDao.ledgerRevision()` is the fix, and `app_meta` is deliberately not in
its union: `ExpenseRepository` writes `last_category_id` on every save, so
including it would report a change when nothing about the ledger changed, and
change again when the backup recorded its own result.

#### A restored phone came back unprotected

Found by writing `BackupRoundTripTest`, and the most consequential of the three.

The file carries the user's schedule — `backup_interval` is not transient — but
it cannot carry the folder grant, which names a permission the *old* phone held.
So a reinstall restored five years of ledger onto a device with nowhere to back
it up, behind a dashboard that looked entirely healthy. A one-shot recovery:
whole again and unprotected, with no way to find out until the next time it
mattered.

`WelcomeScreen` now asks for a folder the moment a restore lands without one.
Not a nag under 05 §12 — somebody who has this second restored from a backup has
said as clearly as anyone can that they want one.

### 21.5 What the file does not carry

`app_meta` is exported wholesale and restored wholesale, and that is deliberate:
a restore that brought back the ledger but not the theme, the lock or the
last-used category would not be "as it was before", which is the claim FR-DAT-04
makes.

`AppMetaDao.TRANSIENT_KEYS` is the exception, and each member earns it:

| | |
|---|---|
| `backup_tree_uri` | names a grant this install holds and a restored phone does not |
| `backup_last_at` / `_count` / `_revision` | record what *this* phone has written; importing them tells a fresh install it has just backed up |
| `backup_key` / `_salt` / `_rounds` | would ride inside the files they protect — and in a plain backup, in the clear |

`backup_interval` and `backup_keep` are **not** here. They are choices the user
made and would otherwise have to make again.

Same shape as the `schema_version` re-stamp §18.7 A8 introduced: the file
describes a ledger, not a device.

### 21.6 A documented departure from 04

`04 §2` lists WorkManager for background work, and `04 §5.3` puts export at
"`Dispatchers.IO`, foreground with progress". The automatic backup is neither
WorkManager nor, strictly, initiated in the foreground.

It runs in `MainActivity`'s existing `LaunchedEffect`, immediately after
`recurringRepo.evaluate()` — and the comment already written there for rule
evaluation applies unchanged: §6 keeps `ContentProvider` initialisers off the
startup path, which rules out WorkManager's default initialisation, and 05 §12
has no notification through which a background run could report anything.
NFR-COMP-05 settles it: "no background work is required for core function", and
a backup that only happened under Doze's good graces would put core data safety
behind exactly the OEM battery policies that requirement refuses to trust.

The "with progress" half is kept rather than dropped: the shell draws an
indeterminate bar over the top edge for as long as a backup is in flight.
Success is silent (05 §12), the Backup screen carries the record, and only a
failure gets a sentence.

**The cost is stated rather than hidden.** A phone left in a drawer is not
backed up until it is next opened. WhatsApp can do better because it has a
foreground service and a network permission; this app has neither and will not
acquire them. The Backup screen says "Backups are written when you open Khata,
not while it is closed" for that reason — 05 §9 asks for the fact and then the
action, and a user who believes they are covered and is not would be worse off
than one with no backup at all.

### 21.7 Every document picker in the app was crashing

Not a defect in this feature. A defect **in shipped code**, found by tapping a
button this feature happened to add beside the ones that already had it.

`FR-DAT-01` is P0. PRD §6.6 rests the product's entire trust argument on it —
"users do not trust an app with their financial history until they have proof
they can extract it". Tapping *Save everything as JSON* on a device killed the
process:

```
java.lang.IllegalArgumentException: Can only use lower 16 bits for requestCode
  at androidx.fragment.app.FragmentActivity.checkForValidRequestCode(FragmentActivity.java:714)
  at androidx.fragment.app.FragmentActivity.startActivityForResult(FragmentActivity.java:672)
  at androidx.core.app.ActivityCompat.startActivityForResult(ActivityCompat.java:248)
  at androidx.activity.ComponentActivity$activityResultRegistry$1.onLaunch(ComponentActivity.kt:226)
```

#### The mechanism

`androidx.biometric:1.1.0` resolves `androidx.fragment` to **1.2.5**. That
version of `FragmentActivity` still implements the old fragment-result scheme:
it packs a fragment index into the upper 16 bits of the request code and
therefore rejects any code that does not fit in the lower 16.

`ActivityResultRegistry` allocates request codes from `0x10000` upwards — every
one of them fails that check. So **every** `rememberLauncherForActivityResult`
launch from this activity threw, without exception:

| | |
|---|---|
| FR-DAT-01 | Save everything as JSON |
| FR-DAT-02 | Save everything as CSV |
| FR-DAT-03 | Restore from a backup |
| 04 §8 | The recovery screen's raw database copy — *the last resort when the ledger will not open* |

#### How it survived

`MainActivity` became a `FragmentActivity` in §20.3, for FR-APP-04's
`BiometricPrompt`, and §20.6 records the base class being chosen by measuring
its cold-start cost. The measurement was the right question. It was not the only
one, and nothing afterwards tapped a picker.

The test layers each missed it for a different reason, and the pattern is worth
recording:

- **`SettingsViewModelTest`** drives `exportJson { stream }` with a
  `ByteArrayOutputStream`. That is deliberate — `SettingsViewModel` has no
  Android types precisely so it can be tested without a picker — and it means the
  eighteen tests over the export path never touch the launcher.
- **The Compose suites** (`DashboardScreenTest`, `BudgetScreenTest`,
  `IncomeScreenTest`) cover the screens with figures on them. There is no
  `SettingsScreenTest`, because until now Settings was rows that called a
  ViewModel.
- **`RecoveryPathTest`** asserts the screen appears when the database is
  unusable. It does not press the button on it.
- **§20.8 installed and drove the release build**, and the walkthrough recorded
  there is the dashboard, the ledger, entry and the lock. Not export.

Every one of those is a reasonable decision on its own. Together they left the
single most important button in the app — the one the trust argument rests on —
with no coverage at the only layer where it was broken.

#### The fix

`androidx.fragment` pinned to **1.8.9** in `libs.versions.toml`, with the
reasoning in the version comment. Fragment 1.3.0 removed the
`startActivityForResult` override when it migrated to the ActivityResult API;
anything from there on is fine, and the current stable line is what the rest of
the AndroidX surface here is already on.

No new dependency: `androidx.fragment` was already on the classpath through
`androidx.biometric`. This changes which version resolves, and NFR-SIZE-04's
review threshold is not engaged by a version bump of something already present.

#### What it says about the suite

An instrumented test that presses this button would be the honest fix, and it is
listed in §21.11 as not done: `ActivityResultRegistry` cannot be driven to the
system document picker from an instrumented test without UI Automator against a
provider that varies by ROM. What *can* be asserted is narrower and still worth
having — that launching produces no exception — and that is the shape the gap
needs filling in.

### 21.8 Driven on a device, and what that found

`SafBackupStore` has no automated test and cannot have one — a document tree
cannot be granted without a human tapping a picker. So the provider layer was
walked by hand on an API 35 emulator, and the walk earned its keep twice.

The full scenario, in order:

| | |
|---|---|
| Clean install, first launch | `WelcomeScreen` appears. "Start fresh" dismisses it. |
| Settings → Automatic backup | Renders; "Never backed up"; the folder row offers a choice |
| Choose a folder | `ACTION_OPEN_DOCUMENT_TREE` opens; **"Allow Khata to access files in Documents?"**; the row then reads "Documents / Change folder" |
| Back up now | `khata-backup-2026-08-22-1317.khata` appears in `/sdcard/Documents`, 1,067 bytes, **no `.part` left behind** |
| The file, pulled and decoded | magic `KHATA1
`, mode 0, `schema_version` 1, 16 categories, 1 source — and **no `backup_tree_uri` in its meta**, which is `TRANSIENT_KEYS` working on a real provider rather than a fake one |
| An expense of ৳1,234, then a second backup | Two generations in the folder, both kept (retention is 5) |
| **`adb uninstall`** | **Both backups still there.** The property the whole design rests on: a document created through SAF belongs to the user, not the app |
| Reinstall, launch | `WelcomeScreen` again, on a genuinely fresh install |
| Restore → pick the file → Restore everything | ৳1,234 Grocery on 22 Aug, and every dashboard figure identical to before the uninstall |

#### What it found

**The follow-up folder question never appeared.** §21.4's third defect was
supposed to be fixed by `WelcomeScreen` asking for a folder after a restore that
landed without one. On the device it went straight to the dashboard.

The cause was two decisions that were each right and wrong together.
`observeNeedsWelcome` was a live flow, and `onboarded` was not transient — so the
restore imported the *old* phone's `onboarded = 1`, the flow re-evaluated, and
the gate closed underneath the screen that was still mid-conversation. The
question died with it.

Two changes, and the first is the interesting one:

- **The gate is latched at launch.** Whether to offer a restore is a property of
  how the app *started*, not of what the ledger holds a moment later — and a
  restore changes both. A live flow was the wrong shape for the question.
- **`onboarded` joins `TRANSIENT_KEYS`.** It describes this install, exactly like
  the folder grant does. A restored file has no business answering it.

Also fixed on the way through: the folder row showed "Choose a folder" as both
its title and its hint, the restore sheet said "Restore from a backup" as both
its title and its button, and the post-restore prompt used a hint string as a
button label. All three are 05 §9's "a control says what happens" — small, and
exactly the kind of thing only a screenshot shows.

### 21.9 The audit, and ten things it found

Everything above had been built, the suite was green at 517, and the feature had
been driven end to end on a device. Then it was read again, line by line, on the
assumption that green and working are not the same claim.

Ten defects — seven in the feature, one in a screen's wiring, one in the audit's
own new test, and one that had been sitting in a category suite since long
before any of this. None of the first eight would have failed a test that
existed, which is the part worth sitting with: the suite was not wrong, it was
aimed elsewhere.

#### A: the progress bar never went away

`BackupUiState.busy` was one flag fed by two owners:

```kotlin
backups.running.collect { on -> _state.update { it.copy(busy = it.busy || on) } }
```

The screen's own actions clear `busy` when they finish. The launch-time backup
cannot — it belongs to no screen. So the first automatic backup drove the flag
up, and `busy || false` left it there: **a permanent progress bar and every
control on the Backup screen disabled**, until the ViewModel happened to be
recreated.

The fix is structural rather than careful. `working` and `autoRunning` are
separate, each cleared by whoever set it, and `busy` is derived:

```kotlin
val busy: Boolean get() = working || autoRunning
```

A derived value cannot disagree with what is happening. A stored one can, and
did.

There was no `BackupViewModelTest` at all, which is why nothing noticed. There
is now, and the test that pins this needs a gate: a `StateFlow` conflates, so a
fast backup finishes before any collector observes it running and the assertion
passes without testing anything. `GatedStore` holds the backup open until the
test has seen the flag go up.

#### B: a failed backup left the document open

```kotlin
val sink = store.write(partial.id) ?: throw IOException(...)
exporter.writeJson(BackupCodec.encode(sink, settings.backupSecret()), at)
```

`writeJson` closes what it is given, and that closes the chain down to `sink` —
but only once `encode` has *returned*. Deriving the key or writing the header
can throw first, and then nothing closes it: a StrictMode
`detectLeakedClosableObjects` violation, and on some providers a document that
stays locked until the process dies. `sink.use { }` is the belt; closing twice
is a no-op on every stream in the chain.

#### C: a backup nobody could find was reported as a success

If `rename` failed, the code shrugged and carried on with the `.part` file:

```kotlin
val done = store.rename(partial.id, name) ?: partial
```

The content is written and valid — and invisible. `isBackupName` does not match
it, so rotation ignores it, `list()` never shows it, and "Send a copy" cannot
find it. The user is told they have a backup that nothing in the app can reach,
and the next rotation counts one generation fewer than it thinks. A backup that
cannot be named is not a backup; it is deleted and retried next launch.

#### D: a failed backup had nothing to diagnose

`catch (e: Exception) { store.delete(...); return WRITE_FAILED }` — the cause
discarded. This is §18.7 A12 exactly, reintroduced: *"`ImportOutcome.Failure.REJECTED`
discarded the exception, leaving a failed restore of five years of data with
nothing to diagnose."* A failed backup is the same shape. Logged now, as
`Importer` already does.

#### E: the sink stayed open when sealing failed

`FramedGcmOutputStream.close()` sealed the final block and then closed the sink.
A card pulled during that last write — which is exactly when sealing throws —
skipped the close. `try`/`finally`, because the file is lost either way and a
leaked handle on top of it helps nobody.

#### F: a truncated backup was called a stranger

Past the magic number the file is *ours*. A file that ended before its mode byte
threw `NotABackup`, which reads to the user as "That file isn't a Khata backup"
— sending somebody holding a truncated backup off to look for a different file.
It is `CorruptBackup` now, and `NotABackup` is gone: nothing threw it any more,
and a dead exception class is a trap for whoever catches it next.

While there: a mode byte this build does not recognise is not damage either. It
is a backup from a **later release**, and FR-DAT-05 already extends exactly that
courtesy to a newer schema — "update, then import again". `NewerFormat` says so.

#### G: the distinction never reached the user anyway

The one that justified the whole audit. The codec goes to real trouble to tell a
damaged Khata file from a file that was never one — and then `Importer` threw it
away, because `Importer` sees a read that failed and reports every one of them as
`UNREADABLE`. Damage that only shows up part-way through a file — a failed AEAD
tag, a truncated gzip stream — is thrown *while the import is reading*, so every
tampered or truncated backup produced "That file isn't a Khata backup."

Six careful error types upstream, flattened at the last step.

`DamageWatch` wraps the decoded stream and remembers the first `IOException` to
escape it. When the import fails and something escaped, the verdict is the one
the codec knew: "That backup couldn't be read to the end. Nothing was changed."
When nothing escaped, the importer's own reason stands — a dangling reference is
still a dangling reference.

The test that pins this was written first and was itself wrong: it expected
`REJECTED` from a truncated *unencrypted* backup and got `UNREADABLE`, which is
how the flattening was found at all.

#### H: choosing a folder could fail silently

```kotlin
SafBackupStore.persist(context, tree, state.settings.treeUri)?.let(vm::onFolderChosen)
```

`persist` returns null when the system refuses to make the grant persistable.
The `?.let` then did nothing at all: the user watched themselves pick a folder,
watched the screen not change, and had no idea why.

On `WelcomeScreen` it was worse, because `answered()` ran regardless — somebody
restoring onto a new phone was moved to the dashboard believing their backups
were set up, when the grant had never stuck. That is the same failure the screen
exists to prevent, one step further along.

Both say so now, and the welcome screen stays put.

#### I: the new test leaked a live database into the next one

Not a defect in the feature — a defect in the audit's own work, and the most
instructive of the nine.

`BackupViewModelTest` constructed its ViewModel directly. `BackupViewModel.init`
launches two collectors over Room flows that live as long as `viewModelScope`
does, and nothing ever cancelled it — so they woke on a database that
`fx.close()` had already shut, and threw on a Room executor thread.

The instrumentation attributes that to whichever test runs **next**.
`CategoryManagerViewModelTest`, four classes later, is what failed: two
assertions about a category tree that had nothing to do with backup, in a suite
that passed 16 out of 16 in isolation every time it was asked.

CLAUDE.md warns about exactly this shape, and the other ViewModel suites in this
project all build through a `ViewModelStore` and clear it. This one now does
too, in the order that matters: release anything parked, cancel the collectors,
*then* drain and close.

Worth recording because of how it presented. A green suite went to two failures
in a file the change never touched, and the honest first hypothesis — "the new
five-year restore probe is starving the emulator" — was wrong, and was
disproved by running the probe and the category suite together and watching all
21 pass.

#### J: a category test was asserting a race, and had been all along

Not this feature's, and not new — but found by the same pass and worth the same
honesty.

`the_same_leaf_name_under_two_different_roots_is_accepted` awaited the tree
containing the second "Misc" and then asserted the editor sheet had closed. Those
are **two independent updates**: the tree arrives on a Room flow, the editor
closes in the save coroutine. A state that has one need not yet have the other,
so the assertion was reading a moment that happened to be settled most of the
time.

It failed under `connectedDebugAndroidTest` and passed under `am instrument`,
which is the tell: JaCoCo instrumentation slows everything by roughly a factor of
three and widened a window that was always there. Nothing about the behaviour
changed.

The predicate now waits for a state that has settled, and settles on *either*
outcome so a genuine failure is still reported as one rather than as a timeout.

A test that fails one run in ten is worse than no test, because it teaches people
that red does not mean anything.

**And it was not alone.** Running the gate again surfaced two more of exactly the
same shape, one of them in this audit's own new suite:

| | The signal it awaited | The one it then asserted |
|---|---|---|
| `IncomeViewModelTest.deleting_an_entry_can_be_undone` | the total, from the rollup flow | `lastDeleted`, from the delete coroutine |
| `BackupViewModelTest.a_good_passphrase_arms_encryption…` | `settings.encrypted`, from a Room flow | the message, from the save coroutine |

Three tests, three authors, one mistake: **await one signal, assert another.**
It is the default failure of a codebase where state arrives from two places —
Room flows and the coroutine that triggered them — and neither ordering is
guaranteed. All three passed under `am instrument` and failed under
`connectedDebugAndroidTest`, because JaCoCo is the slower harness and slower is
all it takes.

Worth naming as a pattern rather than three fixes, because the next test written
here will have the chance to make it again:

> **Await the state you are about to assert on.** If an assertion reads a field
> the predicate did not mention, the test is asserting a race.

#### What the shape of these says

Six of the ten are error and lifecycle paths — what happens when the card is
pulled, the provider refuses, the file is damaged. The happy path was tested
thoroughly enough to be driven on a device twice. The unhappy ones were reasoned
about in comments and never executed.

That is not an accident of this feature. It is what a backup subsystem *is*: a
thing whose entire value is in the paths nobody takes until the day they have
to.

### 21.10 Test inventory

| Suite | Where | Tests |
|---|---|---|
| `BackupCodecTest` | `test/data/export` | 24 |
| `BackupRepositoryTest` | `androidTest/data/repo` | 29 |
| `BackupRoundTripTest` | `androidTest/data/backup` | 5 |
| `BackupViewModelTest` | `androidTest/ui/feature/backup` | 9 |
| `ActivityResultContractTest` | `androidTest/ui` | 3 |
| `PerformanceProbeTest` | `androidTest` | +1 (NFR-PERF-10) |

The last two did not exist until §21.9, and their absence is most of why that
section is as long as it is. A screen with no suite hid a stuck progress bar
through two full green runs and a device walkthrough; a picker with no guard hid
a crash in shipped code through an entire milestone.

`BackupRoundTripTest` is the exit criterion and is not `ExportImportRoundTripTest`
through a different door. Two things differ, and both have been real defects:

1. **The database is re-seeded before the restore, not merely emptied.**
   `deleteAllData` is what a fresh install looks like, and `Schema.SEED` builds
   its UUIDs from `randomblob` — so the categories waiting for the backup have
   *different* UUIDs from the ones inside it. That is §18.1's case, and every
   reinstall is it: "the only merge anyone had tested was the one that never
   leaves a phone."
2. **The file is compressed, encrypted, and travels through the folder.** If the
   framing, the gzip or the key handling were wrong, five years would decode to
   something the importer refuses — and the user would find out at the one
   moment it cannot be fixed.

Both halves of FR-DAT-04's acceptance are asserted: row counts and checksums,
and every rendered `DashboardViewModel` / `IncomeViewModel` figure, because the
rollups are rebuilt from the ledger rather than carried in the file.

### 21.11 Measured

| | target | before (§20.9) | after |
|---|---|---|---|
| Release APK | ≤ 6 MB (NFR-SIZE-01) | 2,218,600 B (2.12 MB) | **2,255,564 B (2.15 MB)** |
| Dex methods | ≤ 40,000 (NFR-SIZE-03) | — | **22,579**, single dex |
| Dex classes | — | — | 4,029 |
| Instrumented tests | — | 486 | **533**, all passing under both harnesses |
| JVM tests | — | — | **+22** |
| Line coverage, `domain/` + `core/` + `data/repo/` | ≥ 80% (NFR-MAIN-02) | — | **94.2%** (`data/repo` 91.7%, `BackupRepository` 96.3%) |
| Merged release permissions | no `INTERNET` (FR-APP-01) | 3 | **3, unchanged** |
| `<provider>` elements in the merged manifest | 0 (04 §6) | 0 | **0** |
| Lint (`lintRelease`, `abortOnError`) | clean | clean | **clean, no new baseline entries** |

**The whole feature costs 37 KB of APK** — 2.12 MB to 2.15 MB. That is what
"platform primitives only" buys: no `androidx.documentfile`, no
`androidx.security-crypto`, no WorkManager, no crypto library. NFR-SIZE-04's
300 KB review threshold was never approached because nothing was added to
review.

The permission count and the provider count are both unchanged, and both are
load-bearing: SAF needs no permission (which is what keeps FR-APP-01 and
NFR-SEC-01 structural), and a `ContentProvider` would sit on the cold-start path
04 §6 spends real effort keeping clear.

**The instrumented suite has now been run in full.** `CLAUDE.md` and §18.11
recorded that it had not been since the M2 pass and that nothing should be
assumed about its green-ness. 517 tests in one invocation, zero failures.

### 21.12 Still not done

- **NFR-PERF-10 is unmeasured on the reference device.** The 10 s restore budget
  is new and there was no import budget at all before it — only NFR-PERF-07 for
  export. Like every other NFR-PERF figure it means nothing off a 1.4 GHz
  Cortex-A53 (02 §3.1), and `PerformanceProbeTest` does not cover it yet.
- **The PBKDF2 cost is unmeasured on the reference device.** 210,000 rounds is
  paid once, when a passphrase is set, in the foreground with a progress bar. On
  an A53 it may be several seconds. If it is bad enough to feel broken the
  iteration count is in the file header precisely so it can be changed without
  orphaning any backup already taken.
- **`SafBackupStore` has no automated test, and cannot have one.** A document
  tree cannot be granted without a human tapping a picker. Everything above it
  is covered against `FakeBackupStore`; the provider layer itself was walked by
  hand (§21.8), and the fake imitates the two provider behaviours that have
  caused bugs elsewhere — a deduped display name and a write that fails
  part-way. Every future change to that class needs the same walk.
- **NFR-PERF-01 has not been re-measured.** `MainActivity` gained one more
  `app_meta` read on the launch gate, and `runIfDue` adds two reads plus a
  `MAX(updated_at)` scan on days a backup is due. Both are off the main thread
  and behind the first frame, and neither is free. 02 §3.1 is explicit that a
  figure from the wrong device "is not evidence of compliance", so this needs
  the reference hardware and the five-year corpus — not the emulator §21.8 ran
  on.
- **One provider, one ROM.** §21.8 ran against `ExternalStorageProvider` on an
  API 35 emulator. Name mangling is the specific worry: some providers append an
  extension derived from the MIME type. `create` and `rename` read the resulting
  name back rather than assuming, and `octet-stream` is the type providers leave
  alone — but a Xiaomi, an SD card, and a cloud provider's tree are all
  untested.
- **Three of §21.8's steps were left to the instrumented suite.** The device
  walk covered choosing a folder, backing up, uninstalling, reinstalling and
  restoring. It did **not** walk the encrypted path, rotation past the retention
  limit, or a folder deleted out from under the app —
  `an_encrypted_backup_is_not_readable_without_the_passphrase`,
  `rotation_keeps_the_newest_and_deletes_the_rest` and
  `an_unreachable_folder_reports_and_keeps_the_setting` cover all three against
  a fake folder, and the fake is not a provider.
- **No test presses a document picker, and one now guards the reason it broke.**
  `ActivityResultRegistry` still cannot be driven to the system picker without UI
  Automator against a provider that varies by ROM. What `ActivityResultContractTest`
  asserts instead is exactly what failed: that nothing between `MainActivity` and
  `Activity` declares `checkForValidRequestCode`, and that the folder intent still
  asks for a grant that outlives the process. A dependency bump that undoes §21.7
  fails there rather than on somebody's phone. The end-to-end tap remains manual.
- **Cloud backup remains P2.** `04 §11` still records what it would cost, and
  the estimate is smaller than it was: the artifact now exists, is compressed,
  is optionally encrypted, and is already written on a schedule. What is missing
  is the upload and the `INTERNET` permission that FR-APP-01 forbids.
