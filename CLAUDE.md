# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Khata — an offline-only, single-user personal finance ledger for Android (Bangladeshi taka). Single Gradle module `:app`, plus `:benchmark` for Macrobenchmark/Baseline Profile.

**`docs/` is normative.** `01-PRD.md` (requirements, `FR-*`), `02-SRS.md` (`NFR-*`), `03-database-design.md` (schema, triggers), `04-system-architecture.md` (layering, startup, testing), `05-ui-ux-guide.md` (design tokens, copy). Where code and a document disagree, one of them is a bug — do not silently pick the code. `06-implementation-log.md` records every audit and defect found so far; check it before re-litigating a decision. Comments in this codebase routinely cite requirement ids (`FR-EXP-05`, `NFR-PERF-01`) and doc sections (`03 §4.1`) — keep that convention when adding code.

## Commands

Requires JDK 17, Android SDK platform 37 / build-tools 36.

```bash
./gradlew :app:installDebug
./gradlew :app:architectureCheck        # runs automatically via preBuild
./gradlew :app:testDebugUnitTest        # JVM: domain/ + core/
./gradlew :app:connectedAndroidTest     # instrumented: DAOs, repos, ViewModels, Compose
./gradlew :app:coverageReport           # JaCoCo HTML/XML
./gradlew :app:coverageVerify           # NFR-MAIN-02 80% gate over domain/, core/, data/repo/
./gradlew :app:lintRelease
./gradlew :app:assembleRelease          # R8 full mode; unsigned without keystore.properties
```

Single test / class:

```bash
./gradlew :app:testDebugUnitTest --tests "com.app.finance.domain.usecase.SafeToSpendTest"
./gradlew :app:testDebugUnitTest --tests "*.SafeToSpendTest.dividesRemainingByDaysLeft"
./gradlew :app:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.app.finance.data.repo.ExpenseRepositoryTest
```

Benchmarks (need a rootable `google_apis` image, not `google_apis_playstore`):

```bash
./gradlew :app:generateBaselineProfile
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Seed five years of data (debug/benchmark variants only — the receiver lives in `src/debug/`):

```bash
adb shell am broadcast -a com.app.finance.SEED -p com.app.finance.debug
```

### Environment traps that cost real time

- **Run instrumented tests on API 35 or below.** Espresso 3.7.0 calls `InputManager.getInstance()`, removed in API 37 — every Compose test dies in `Espresso.onIdle()`. Repository and ViewModel suites are unaffected.
- **`./gradlew --stop` before `connectedAndroidTest` on a 16 GB machine.** The daemon holds `-Xmx4096m` for R8; with a Kotlin daemon alongside, the emulator gets squeezed out and it surfaces as `DeviceException: No connected devices!`.
- Layouts target 288 dp of content on a 320 dp phone: `adb shell wm density 360` on a 720 px emulator, `reset` after.
- Xiaomi/MIUI physical devices need **Install via USB** enabled or the instrumentation APK is refused with `INSTALL_FAILED_USER_RESTRICTED`.

## Commit Guidelines (applies to all sub-projects)

- Use conventional commits: `feat:`, `fix:`, `refactor:`, etc.
- Never use `Co-Authored-By: Claude` or similar
- Commit per feature/module/fix — never batch everything into one commit
- Always commit and push after completing work


## Enforced invariants

`:app:architectureCheck` is wired into `preBuild` and **fails the build**, per 04 §3.1 and §4.1:

1. No `android.*` / `androidx.*` imports under `domain/` or `core/` — that purity is what makes them JVM-testable and is the basis of the coverage target.
2. Money is never `Double`/`Float`. Any identifier matching `*amount|paisa|minor|money|balance|total|limit|spent|earned*` typed as a float fails. Use `Money` (inline value class over `Long` paisa). The rule also scans `src/debug/`.

Lint has `abortOnError = true` with a `lint-baseline.xml`; new issues fail the build.

## Architecture

Layers depend strictly downward: `ui/` (stateless Compose, renders from `UiState`) → ViewModels (`StateFlow<UiState>`) → `domain/` (pure functions) → `data/repo/` (transactions, invariants) → `data/db/` (Room DAOs → SQLite WAL). Threading: all DAO access on `Dispatchers.IO`; composition on main; StrictMode with `penaltyDeath` in debug catches main-thread disk.

### Schema.kt, not Room, creates the database

Room cannot express `CHECK` constraints, `WITHOUT ROWID`, the functional unique index on `category(IFNULL(parent_id,-1), name_key)`, or triggers — and 03 §1 makes triggers the **sole writer** of the rollup tables. So Room creates its tables and `AppDatabase.CanonicalSchema` immediately drops and recreates them from `Schema.kt` in the same transaction, then seeds.

Consequences when editing:

- Entity declarations must stay **column-for-column identical** to the DDL in `Schema.kt`, or Room's `TableInfo` validation fails on the *second* launch with user data already in the file. `SchemaValidationTest` opens the database twice for exactly this.
- `docs/schema_v1.sql` is generated from `Schema.kt` — regenerate it whenever the schema changes.
- Release builds never fall back to destructive migration (03 §8). A migration failure must surface `RecoveryScreen`, not wipe the ledger.
- Never write rollup tables from Kotlin. Add or fix a trigger instead.
- Timestamps (`created_at`, `updated_at`) are epoch **milliseconds**; dates (`spent_on`, `earned_on`, `next_due_day`, `last_run_day`) are epoch **days**. Never interchangeable.
- `period_ym` is derived by the repository on every write path (`Period.from(date).ym`), never by SQL and never by the caller.
- `status`: 0 = posted, 1 = pending confirmation. Pending rows are excluded from every rollup trigger and every aggregate read.

### Core types

`Money` (`Long` paisa), `Period` (`YYYYMM` `Int`), `NameKey` — all inline value classes in `core/`, JVM-tested. Period arithmetic goes through `YearMonth`, never integer math on the packed value (`202612 + 1` is not `202701`).

### DI and clock injection

`AppContainer` is hand-rolled (04 §2.3 — no Hilt, to keep the 800 ms cold-start budget). **Every field must stay `by lazy`**: one eager field puts `SQLiteDatabase` back on the startup path. Its two seams are `clock: Clock` (tests pin "today") and `databaseOverride` (Compose tests drive real screens against in-memory Room). ViewModels are built via the `viewModelFactory { }` helper in `di/ViewModelFactory.kt`.

`AppContainer` also owns the debug integrity checks: `assertRollupsReconcile()` (symmetric drift over both rollup tables — symmetric because a one-sided join is blind to a *missing* bucket) and `assertPeriodsDerived()`.

### The viewed period is owned by `KhataApp`, above the `NavHost`

Not by Budget, Income, or Dashboard. It is `rememberSaveable` for process death and persisted through `app_meta` for relaunch (FR-APP-03). Screens receive a `period` / `onPeriodChange` pair — **do not introduce a second source of period state.** Income is the exception that proves it: it owns a *scope* (year-first, 05 §5.7) and stepping the year moves the shared period by twelve months so the rest of the app follows.

Quick Add is sheet state (a `Long`: closed / new / expense id), not a route, so back doesn't unwind twice.

### Archive, don't delete

Every foreign key is `ON DELETE RESTRICT`, and `CategoryRepository` has no delete method at all — deleting a category silently rewrites history. The single delete in the app is on income sources with zero entries (FR-IS-06), guarded by a count above and the FK below.

## Testing conventions

- Repositories are tested against **real in-memory SQLite with the canonical schema**, never mocks — the behaviour that matters (triggers, `CHECK` constraints, cross-period re-filing) lives in the database.
- `TestFixture` (androidTest) builds that database, the real repositories, a pinned `Clock` (2026-08-14), and a real `AppContainer`. Use `closeAfterDraining()` for tests that run ViewModels — closing the pool under an in-flight Room query throws on an executor thread and gets attributed to whatever test runs *next*.
- Each milestone's exit criterion is a reconciliation test asserting every rendered figure equals a direct `SUM(amount_minor)` over the ledger — never the rollup read a second way (`BudgetReconciliationTest`, `IncomeReconciliationTest`, `ExportImportRoundTripTest`).
- Known gap: the instrumented suite has not been fully executed since the M2 pass. Assume nothing about its green-ness; see `06-implementation-log.md` §18.11.

## Build config notes that look arbitrary

- `android.disallowKotlinSourceSets=false` — AGP 9's built-in Kotlin rejects `kotlin.sourceSets` contributions, but KSP still registers generated sources that way (google/ksp#2729). Remove once KSP moves to `android.sourceSets`.
- No `org.jetbrains.kotlin.android` plugin: AGP 9 supplies Kotlin (9.3.1 → 2.2.10). The Compose/serialization plugin versions must match that.
- `benchmark = "1.5.0-rc01"`, not 1.4.1 stable — 1.4.1 rejects AGP 9 application modules.
- The `benchmark` build type exists because Macrobenchmark needs a non-debuggable build *and* the seeder; neither `debug` nor `release` is both. Its `applicationIdSuffix` is `.bench`, not `.benchmark`, to avoid colliding with the `:benchmark` module's own namespace.
- `app/build.gradle.kts` must have no top-level `val`s — `lintVital` analyses Gradle scripts and script-level properties crash it.
- The app declares **no permissions at all**, INTERNET included; verify against the *merged release* manifest, not the source one.
- Locale filter is `en` only. `bn` stays absent until `values-bn/` exists.
