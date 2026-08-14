
# Implementation Log — M1 Scaffold
**Product:** Khata — Personal Finance Manager (Android)
**Covers:** initial scaffold through milestone M1
**Date:** 14 August 2026

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

| Area | State |
|---|---|
| Build configuration, R8 full mode, release APK | complete |
| Schema: 9 tables, 14 indices, 14 triggers, seed, PRAGMAs | complete |
| `Money`, `Period`, `NameKey` | complete |
| *Khata* design system, light and dark | complete |
| Navigation shell, bottom bar, centre FAB | complete |
| Quick Add — custom keypad, chips, inline sentence | complete |
| Ledger — keyset paging, day grouping, undo | complete |
| Dashboard, Income, Budget | placeholder routes (M2–M4) |
| Category manager, Reports, Settings | not started |
| Export/import, recurring rules | not started (M5, P1) |

**Size:** 45 main source files (5,255 lines), 7 test files (1,198 lines),
85 files committed.

---

## 3. Verified results

Every number below was measured, not estimated. Reproduce with the commands in
§9.

| Constraint | Target | Measured |
|---|---|---|
| NFR-SIZE-01 APK download size | ≤ 6 MB | **1.13 MB** (1,180,041 bytes) |
| NFR-SIZE-03 dex methods, single dex | ≤ 40,000 | **13,687** (2,539 classes) |
| FR-APP-01 no `INTERNET` permission | none declared | **confirmed on the merged release manifest** |
| Font budget (`05` §4.1) | ~12–18 KB | **5,316 bytes** |
| JVM unit tests | ≥ 90% on `core/` | **53 passing** |
| DAO / trigger assertions (`03` §10.1) | 19 ported | **31 passing** |

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

Current headroom makes this a non-issue today — 1.13 MB against a 6 MB ceiling.

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
