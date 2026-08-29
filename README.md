# DayBook

A personal finance ledger for Android. Offline-only, single user, Bangladeshi taka.

Built to the specifications in [`docs/`](docs/) — read `01-PRD.md` first, then
`04-system-architecture.md`. The documents are normative; where this code and a
document disagree, that is a bug in one of them.

Three budgets drive every decision:

| Budget | Target | Current |
|---|---|---|
| APK size (NFR-SIZE-01) | ≤ 6 MB | **2.17 MB** |
| Installed footprint (NFR-SIZE-02) | ≤ 20 MB | **5.58 MB** |
| Database at 5 years (NFR-SIZE-05) | ≤ 6 MB | **5.41 MB** |
| Dex methods (NFR-SIZE-03) | ≤ 40,000, single dex | **22,708 in one dex** |
| Line coverage, calculation **and repository** layers (NFR-MAIN-02) | ≥ 80% | **94.5%**, and no source file below 50% |
| Cold start (NFR-PERF-01) | ≤ 800 ms on a Cortex-A53 | **314 ms** on a Galaxy A54 — not the reference device |

**The app declares no `INTERNET` permission** (FR-APP-01), verified against the
merged release manifest rather than the source one.

It is no longer true that it declares *no permissions at all*, and that changed
in §20.3: FR-APP-04's app lock is `androidx.biometric`, which contributes
`USE_BIOMETRIC` and `USE_FINGERPRINT` to the merged manifest. Both are
normal-protection and neither reaches the network, but the count went from zero
to two and the claim had to go with it. The gate that matters — no `INTERNET`,
so the offline guarantee is structural rather than promised — is unchanged.

## State

**All five milestones in `01-PRD.md` §8 are complete**, and so is everything
that was deferred behind them — M1 (*schema, expense quick-add, ledger*), M2
(*category tree, budgets, alerts*), M3 (*income module*), M4 (*dashboard
analytics*) and M5 (*export/import, recurring, polish*), plus the five items
that had been carried as deferred since M2 (§20).

| Area | State |
|---|---|
| Build, R8 full mode, signed release, lint | done |
| Schema, 14 triggers, seed, PRAGMAs | done |
| `Money`, `Period`, `NameKey` | done |
| DayBook design system, light + dark | done |
| Navigation shell, bottom bar, FAB | done |
| **Quick Add** — keypad, chips, date, method, note, full category picker | done |
| **Ledger** — paging, day groups, edit, swipe-delete + undo, filters, search | done |
| **Budget** — limits per leaf, computed root totals, alerts, copy last month | done (M2) |
| **Category manager** — create, rename, archive with undo, nature inheritance | done (M2) |
| Shared period switcher, persisted across launches | done (M2) |
| Crash log, recovery screen, debug rollup-drift check | done |
| **Income** — year-first view, 12-month trend, per-source breakdown, stable coverage | done (M3) |
| **Income sources** — create, rename, set kind, archive, delete when unused | done (M3) |
| **Dashboard** — safe-to-spend, month ribbon, net strip, alerts, burn rate, deltas, spend mix, top 5, six-month trend | done (M4) |
| **Settings** — export, import, rebuild totals, theme, delete all data | done (M5) |
| **Repeating entries** — rules, launch evaluation, one-tap confirm on the ledger | done (M5) |
| **Export / import** — JSON, CSV archive, replace or merge, transactional | done (M5) |
| **Category reorder** (FR-CAT-11) and **source reorder** (FR-IS-07) | done (§20.2) |
| **Hide from screenshots** — `FLAG_SECURE`, optional (NFR-SEC-04) | done (§20.3) |
| **App lock** — the device's own PIN or biometric, no secret stored (FR-APP-04) | done (§20.3) |
| **Reports** — custom date range, fixed/variable split, top expenses (04 §7) | done (§20.4) |
| Bengali (`values-bn`) | not attempted — see below |

**299 JVM tests + 596 instrumented tests, all green on a device.** Each
milestone's exit criterion is a reconciliation test that asserts every figure a
screen renders equals a direct `SUM(amount_minor)` over the ledger itself —
never the rollup read a second way. `BudgetReconciliationTest` for M2,
`IncomeReconciliationTest` for M3, `ExportImportRoundTripTest` for M5.

Every milestone was then re-audited against `01`–`05` requirement by
requirement — M1 and M2 gave up six defects (§13), M3 twelve (§15), M4 and M5
twelve more (§18) — and then the application was audited **as a whole** (§19),
which found nine things no per-milestone read could see.

### And then the tests were run

The instrumented suite had not executed since M2. §20 ran it, and the result is
worth stating plainly because it is not what six audits predicted:

> **Thirty failures out of 447. Twenty-nine were defects in the tests. One was a
> defect in the app — and it was M5's exit criterion.**

`Schema.WIPE_ORDER` could not wipe the `category` table. `parent_id` references
`category(id)` `ON DELETE RESTRICT`, so a single `DELETE FROM category` reaches
a root while its children still point at it. Both callers were affected:
"delete all data" (FR-DAT-06) and REPLACE import. Since the seed creates
thirteen leaves under three roots, it failed on **every database that has ever
existed**, and the criterion had been recorded as met on the strength of a test
that had never run.

The other twenty-nine were assertions written against screens that had never
rendered, import fixtures in a shape the importer is right to refuse, and a
query plan asserted by a string SQLite does not print. Reading them again would
not have found them, because reading them is what produced them.

## Getting started

Requires JDK 17 and the Android SDK (platform 37, build-tools 36).

```bash
./gradlew :app:installDebug          # build and install
./gradlew :app:architectureCheck     # the two structural rules
./gradlew :app:testDebugUnitTest     # 299 JVM tests — domain, core, and three gates
./gradlew :app:connectedAndroidTest  # 596 tests, needs a device — see below
./gradlew :app:coverageVerify        # NFR-MAIN-02's 80%, plus a per-class floor
./gradlew :app:lintRelease
./gradlew :app:assembleRelease       # R8 full mode
```

**Run the instrumented tests on API 35 or below.** Espresso 3.7.0 — the newest
release — calls `android.hardware.input.InputManager.getInstance()`, which no
longer exists on API 37, so every Compose test dies in `Espresso.onIdle()`
before reaching an assertion. The repository and ViewModel suites are
unaffected. An `android-35;google_apis` AVD works, and being rootable is also
what makes `generateBaselineProfile` possible.

**Run `./gradlew --stop` before `connectedAndroidTest` on a 16 GB machine.** The
daemon holds `-Xmx4096m` (R8 needs it), and with a Kotlin daemon alongside it
the emulator gets squeezed out mid-run — which surfaces as
`DeviceException: No connected devices!` rather than as anything test-shaped.

Layouts are designed against **288 dp of content on a 320 dp phone**. To check
that on a 720 px emulator:

```bash
adb shell wm density 360             # 720 / (360/160) = 320 dp
adb shell wm density reset
```

## Layout

Package structure follows `04-system-architecture.md` §3.1 exactly. Dependencies
point strictly downward and `domain/` and `core/` contain no Android imports —
enforced by `./gradlew :app:architectureCheck`, which also fails the build if
money is ever typed as `Double` or `Float`.

```
core/     Money (Long paisa), Period (YYYYMM), NameKey — pure Kotlin, JVM-tested
domain/   model/   budget states, category tree, income scope, typed errors
          usecase/ budget summary, alerts, income breakdown, stable coverage
                   — pure functions, JVM-tested
data/     Room entities, DAOs, repositories; Schema.kt owns the real DDL
ui/       theme/ (DayBook tokens), common/ (shared components), feature/
di/       AppContainer — hand-rolled, every field `by lazy`
```

### Four things worth knowing before editing

**`Schema.kt`, not Room, creates the database.** Room cannot express `CHECK`
constraints, `WITHOUT ROWID`, the functional index on `category`, or triggers —
and 03 §1 makes triggers the *sole* writer of the rollup tables. So Room creates
its tables and `AppDatabase.CanonicalSchema` immediately replaces them from
`Schema.kt` inside the same transaction. The entity declarations must stay
column-for-column identical to that DDL or Room's validation fails on the second
launch, with the user's data already in the file. `SchemaValidationTest` opens
the database twice specifically to catch that.

**`docs/schema_v1.sql` is generated from `Schema.kt`.** Regenerate it when the
schema changes. It carries a change log of what was added relative to its first
draft — most importantly the three `rollup_income_month` triggers, without which
every income figure in the app reads ৳0 while the ledger underneath stays
correct.

**The viewed period is owned by `DayBookApp`, above the `NavHost`.** Neither
Budget nor Income owns any of it, and Dashboard (M4) must read the same value —
a user who steps back to July on one screen has not asked to be on August
everywhere else. It is `rememberSaveable` for rotation and process death, and
persisted through `app_meta` for relaunch (FR-APP-03). Read it via the `period`
/ `onPeriodChange` pair a screen is handed; do not add a second one.

Income is the screen that makes this awkward and the reason it is worth stating:
it defaults to a **year** while everything else is monthly (05 §5.7 — "a farming
month showing ৳0 is alarming and meaningless in isolation"). It resolves that by
owning a *scope*, not a period; stepping the year moves the shared period by
twelve months, so the rest of the app follows rather than diverging.

**There is exactly one delete in the app, and it is on income sources.** Every
other removal is an archive, because deleting a category silently rewrites
history — which is why every foreign key is `ON DELETE RESTRICT` and
`CategoryRepository` has no delete method at all. FR-IS-06 requires deleting a
source with **zero** entries, so that one exists, guarded by a count above and
by `ON DELETE RESTRICT` below. It is also the one screen that shows a constraint
by *disabling* a control rather than by omitting it, because FR-IS-05's
acceptance criterion asks for that in as many words. `docs/06-implementation-log.md`
§14.4 has the argument.

## Performance work

`:benchmark` holds the Macrobenchmark suite and the Baseline Profile generator,
which `04` §2.2 makes mandatory rather than optional.

```bash
./gradlew :app:generateBaselineProfile
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Seed 02 §3.1's corpus first — 20,000 expenses, 400 income entries, 60
categories — onto the `benchmark` variant, which is minified and **not
debuggable** because a debuggable build disables optimisations wholesale:

```bash
./gradlew :app:installBenchmark
adb shell am start -n com.app.finance.bench/com.app.finance.MainActivity
adb shell am broadcast -a com.app.finance.SEED -p com.app.finance.bench   --include-stopped-packages --es scale benchmark
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest   -Pandroid.testInstrumentationRunnerArguments.targetPackage=com.app.finance.bench
```

### The release-candidate gate

```bash
./gradlew releaseCandidateCheck   # architecture, JVM, lint, instrumented, coverage, release build
./gradlew performanceCheck        # the benchmarks and their budgets — needs a real ARM device
```

`performanceCheck` asserts 02 §3.1's budgets from `benchmark/performance-budget.txt`
and fails on three things: a metric over budget, an exempted metric that has got
worse than its recorded ceiling, and **a budgeted benchmark missing from the
results** — because macrobenchmark skips routinely, and a gate that only checks
what it finds would pass a run in which nothing ran.

NFR-PERF-04 currently sits under a recorded exemption rather than a lowered
threshold: the 300 ms budget stays, the exemption states the 572 ms that is
measured today, and the build fails again if it worsens. Same idea as
`lint-baseline.xml`.

`.github/workflows/release-candidate.yml` runs the first task and deliberately
omits the second — an x86_64 hosted runner would produce green performance
numbers that mean nothing. It is a template; this project has no remote yet.

### What has been measured

On a Galaxy A54 (arm64, 8 cores at 2.0 GHz), ten iterations each, against that
corpus. **Not the reference device**, so these are real measurements that do not
establish compliance — but the A54 is several times faster than a 1.4 GHz
Cortex-A53, so a target missed here is missed there too.

| | Target | Median |
|---|---|---|
| NFR-PERF-01 cold start | ≤ 800 ms | 288 ms |
| NFR-PERF-02 warm start | ≤ 250 ms | 168 ms |
| NFR-PERF-03 expense committed | ≤ 100 ms | 17 ms |
| **NFR-PERF-04 dashboard fully rendered** | **≤ 300 ms** | **572 ms** |
| NFR-PERF-05 ledger scroll | no frame > 16 ms at p95 | 13.5 ms at p95 |
| NFR-PERF-06 period switch | ≤ 150 ms | 5 frames per switch |
| NFR-PERF-07 full JSON export | ≤ 3 s | 1,197 ms (5.1 MB) |
| NFR-PERF-08 steady-state memory | ≤ 80 MB | 50.8 MB anonymous RSS |

**NFR-PERF-04 misses**, and §20.6 has the decomposition: 281 ms to the first
frame, 223 ms for the reads, the rest composition. The mitigation `04` §2.2
requires has now been applied — the app finally has a baseline profile of its own
code, generated on the `DayBook_API35` AVD, taking the release profile from 3,345
library-only rules to 24,795 with 2,738 of them DayBook's. That moved the dashboard
from **666 ms to 552 ms**, a 17% improvement in the range the architecture
predicted, and still 252 ms outside the target. The next lever is `04` §2.2's own
fallback — XML views for the entry and ledger screens — and that call needs the
reference device.

Two caveats:

- Profile generation **needs root**, so it requires an AOSP or `google_apis`
  emulator image — a `google_apis_playstore` one cannot be rooted. The
  `DayBook_API35` AVD created for the test suite is `google_apis` and works.
- `FrameTimingMetric` returns frame *counts* and no per-frame durations on the
  A54, so the scroll and period-switch benchmarks use `FrameTimingGfxInfoMetric`
  as well — it reads `dumpsys gfxinfo` and reports percentiles, which is the
  shape NFR-PERF-05 is actually written in.
- NFR-PERF-08 is measured but the requirement is ambiguous: "resident memory" is
  50.8 MB of anonymous RSS, or ~145 MB counting shared framework mappings. §20.6
  records both rather than picking one.
- `04` §2.2 defines the fallback if the real device misses 800 ms: XML views for
  the entry and ledger screens only.

To benchmark on a physical Xiaomi/MIUI device, enable **Install via USB** in
developer options first — without it the instrumentation APK is refused with
`INSTALL_FAILED_USER_RESTRICTED`, even though the app APK installs fine.

## Toolchain

AGP 9.3.1 · Gradle 9.7 · Kotlin 2.2.10 (built into AGP 9) · Compose BOM
2026.08.00 · Room 2.8.4 · compileSdk 37 · **minSdk 26**.

minSdk 26 is what makes `java.time` available without core library desugaring,
so there is no desugar jar in the APK.

Two version notes that will look arbitrary later:

- `android.disallowKotlinSourceSets=false` in `gradle.properties` — AGP 9's
  built-in Kotlin rejects contributions to `kotlin.sourceSets`, but the KSP
  plugin still registers generated sources that way ([google/ksp#2729][ksp]).
  Remove it once KSP registers through `android.sourceSets`.
- `benchmark = "1.5.0-rc01"` rather than the 1.4.1 stable — 1.4.1 refuses AGP 9
  application modules outright.

[ksp]: https://github.com/google/ksp/issues/2729

## The font

`app/src/main/res/font/plex_mono_medium.ttf` is IBM Plex Mono Medium (SIL OFL)
subsetted to fourteen glyphs — 5.3 KB. Regenerate with `tools/fonts/subset.sh`.

The guide lists ৳ among the glyphs, but IBM Plex Mono is a Latin/Greek/Cyrillic
family and has no U+09F3. That costs nothing: §4.3 sets the symbol as a separate
0.7em `ink-soft` span, which resolves through the system Noto Sans Bengali.
