# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

DayBook — an offline-only, single-user personal finance ledger for Android (Bangladeshi taka). Single Gradle module `:app`, plus `:benchmark` for Macrobenchmark/Baseline Profile.

**`docs/` is normative.** `01-PRD.md` (requirements, `FR-*`), `02-SRS.md` (`NFR-*`), `03-database-design.md` (schema, triggers), `04-system-architecture.md` (layering, startup, testing), `05-ui-ux-guide.md` (design tokens, copy). Where code and a document disagree, one of them is a bug — do not silently pick the code. `06-implementation-log.md` records every audit and defect found so far; check it before re-litigating a decision. `07-building-and-running.md` is the human-facing procedure for the commands below — variants and application ids, signing, driving a USB-connected device, and how to verify a build; the traps section here is the crib sheet for the same ground, so fix both when one changes. Comments in this codebase routinely cite requirement ids (`FR-EXP-05`, `NFR-PERF-01`) and doc sections (`03 §4.1`) — keep that convention when adding code.

## Commands

Requires JDK 17, Android SDK platform 37 / build-tools 36.

```bash
./gradlew :app:installDebug
./gradlew :app:architectureCheck        # runs automatically via preBuild
./gradlew :app:testDebugUnitTest        # JVM: domain/ + core/
./gradlew :app:connectedAndroidTest     # instrumented: DAOs, repos, ViewModels, Compose
./gradlew :app:coverageReport           # JaCoCo HTML/XML
./gradlew :app:coverageVerify           # NFR-MAIN-02's 80% over domain/, core/, data/repo/ — plus a 50% floor per source file
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
- **Stopping the daemon is not enough — sequence the two.** `connectedAndroidTest` *builds and then runs*, so the emulator is up during the expensive half and both lose. Build first with nothing else running, then boot, then drive the device directly:

  ```bash
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest   # emulator OFF
  ./gradlew --stop
  # boot the emulator, then:
  adb install -r -t app/build/outputs/apk/debug/app-debug.apk
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w com.app.finance.debug.test/androidx.test.runner.AndroidJUnitRunner
  ```

  With both running, an `assembleDebug` took **58 minutes and produced nothing**; alone it takes under four. Add `-e class <fqcn>` to `am instrument` for a single suite.
- **`am instrument` produces no JaCoCo `.ec`, so `coverageVerify` will fail at ~0.34** — it only sees the JVM half and reports the repositories as uncovered. For the NFR-MAIN-02 gate you do need Gradle's `connectedDebugAndroidTest`; run it once everything is already compiled, with `-Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m" --no-parallel` so the daemon leaves the emulator room.
- **The debug `SEED` broadcast did not fire on an API 35 emulator (22 Aug 2026).** `am broadcast` reported `result=0`, ActivityManager logged the broadcast as enqueued, and `SeedReceiver` never logged anything — no rows were written. `src/debug/AndroidManifest.xml` states the opposite ("`am broadcast` from the shell reaches an unexported receiver in a debuggable package"), so one of the two is stale; the cause was not chased down. If seeding matters for what you are doing, verify it landed rather than assuming, or drive `SeedFiveYears.into` from an instrumented test.
- Layouts target 288 dp of content on a 320 dp phone: `adb shell wm density 360` on a 720 px emulator, `reset` after. **The other end of NFR-COMP-03 is `wm density 240`**, which gives exactly 480 dp on the same panel — Compose's `ForcedSize` can shrink a composition but never widen it past the real window, so the wide end has to come from the device. Set the density, run the Compose suites, reset; do not change it mid-suite, since every activity is recreated.
- Xiaomi/MIUI physical devices need **Install via USB** enabled or the instrumentation APK is refused with `INSTALL_FAILED_USER_RESTRICTED`.
- **A physical device runs the instrumented suite only while nothing else holds the foreground.** `MainActivityTest` and every Compose test wait for the activity to become resumed *and focused*; another app in front means that never happens, so they **hang rather than fail** — `logcat -s TestRunner:I` shows a `started:` with no matching `finished:`, and the run sits there until something kills it, which reads exactly like a slow test. Measured 28 Aug 2026 on a Redmi 13C (`gale`, HyperOS 2.0, API 35): an unrelated debug app left in the foreground with the IME open stalled the suite at **0 of 596**, and an earlier attempt died the same way after two tests when that app took focus mid-run. The emulator never shows this because nothing else runs on it. Press HOME first and leave the device alone for the whole run — one notification tap is enough. Two things make it survivable:

  ```bash
  adb logcat -G 16M   # MIUI's own MiEvent/misight spam wraps the default buffer and
  adb logcat -c       # erases the TestRunner history you need to tell a hang from a crash
  # detached, so a host-side disconnect cannot orphan the run and no client timeout can kill it:
  adb shell "nohup am instrument -w com.app.finance.debug.test/androidx.test.runner.AndroidJUnitRunner \
    > /sdcard/daybook-instr.txt 2>&1 &"
  ```
- **Cold-boot the emulator before trusting `PerformanceProbeTest`.** An AVD that has already run the full suite is several times slower than a fresh one, and the probes assert wall-clock budgets. Measured on one session: NFR-PERF-10's restore took 3,643 ms on a cold-booted AVD and 20,905 ms on the same AVD after a full suite run; NFR-PERF-07's export, which nothing had touched, went 571 ms → 1,907 ms alongside it. The whole suite runs in about 12 minutes cold and 25 aged, which is the cheaper signal that it is time to reboot. Nothing in the app had changed. A failing probe on a long-lived emulator is a measurement, not a regression — reboot and re-measure before believing it.

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
- Release builds never fall back to destructive migration (03 §8). A migration failure must surface `RecoveryScreen`, not wipe the ledger. **Corruption is a different path and used to wipe it**: androidx.sqlite's default `onCorruption` deletes the file and Room opens an empty one in its place, so `verifyDatabase()` succeeded and the user got onboarding instead of the recovery screen. `AppDatabase.preserveOnCorruption()` replaces that one callback — do not drop it (06 §26.3).
- Never write rollup tables from Kotlin. Add or fix a trigger instead.
- Timestamps (`created_at`, `updated_at`) are epoch **milliseconds**; dates (`spent_on`, `earned_on`, `next_due_day`, `last_run_day`) are epoch **days**. Never interchangeable.
- `period_ym` is derived by the repository on every write path (`Period.from(date).ym`), never by SQL and never by the caller.
- `status`: 0 = posted, 1 = pending confirmation. Pending rows are excluded from every rollup trigger and every aggregate read.

### Core types

`Money` (`Long` paisa), `Period` (`YYYYMM` `Int`), `NameKey` — all inline value classes in `core/`, JVM-tested. Period arithmetic goes through `YearMonth`, never integer math on the packed value (`202612 + 1` is not `202701`).

### DI and clock injection

`AppContainer` is hand-rolled (04 §2.3 — no Hilt, to keep the 800 ms cold-start budget). **Every field must stay `by lazy`**: one eager field puts `SQLiteDatabase` back on the startup path. Its two seams are `clock: Clock` (tests pin "today") and `databaseOverride` (Compose tests drive real screens against in-memory Room). ViewModels are built via the `viewModelFactory { }` helper in `di/ViewModelFactory.kt`.

`AppContainer` also owns the debug integrity checks: `assertRollupsReconcile()` (symmetric drift over both rollup tables — symmetric because a one-sided join is blind to a *missing* bucket) and `assertPeriodsDerived()`.

### The viewed period is owned by `DayBookApp`, above the `NavHost`

Not by Budget, Income, or Dashboard. It is `rememberSaveable` for process death and persisted through `app_meta` for relaunch (FR-APP-03). Screens receive a `period` / `onPeriodChange` pair — **do not introduce a second source of period state.** Income is the exception that proves it: it owns a *scope* (year-first, 05 §5.7) and stepping the year moves the shared period by twelve months so the rest of the app follows.

Quick Add is sheet state (a `Long`: closed / new / expense id), not a route, so back doesn't unwind twice.

### Backup is a wrapper, not a second format

`data/export/BackupCodec.kt` wraps the JSON `Exporter` already writes — magic
number, gzip, optional AES-256-GCM — and `decode` hands `Importer` the same
plain JSON it has always read. **Do not teach `Importer` about the container**;
that separation is what keeps `ImportValidationTest` meaningful.

- The GCM ciphertext is **framed**, and must stay framed. `CipherInputStream`
  swallows `AEADBadTagException` at end of stream on several Android versions, so
  a tampered backup would decode partially. Each block goes through `doFinal`,
  and a last-block marker in the AAD is what makes truncation at a block boundary
  detectable.
- The encryption key is **derived once and stored** in `app_meta`. A launch-time
  backup has nobody to type a passphrase, and 210,000 PBKDF2 rounds do not fit an
  800 ms cold start. Storing it costs nothing that NFR-SEC-05 has not already
  spent — the database beside it is plaintext.
- `AppMetaDao.TRANSIENT_KEYS` must not travel in a backup. The folder grant names
  a permission a restored phone does not hold, and the key would ride inside the
  files it protects.
- `BackupRepository` takes a `(String) -> BackupStore` lambda, not a `Context`.
  That is what lets rotation and scheduling be tested against `FakeBackupStore`;
  a document tree cannot be granted without a human tapping a picker.
- The automatic run lives in `MainActivity`'s existing `LaunchedEffect`, beside
  `recurringRepo.evaluate()`. **Do not move it to WorkManager** — 04 §6 and
  NFR-COMP-05, reasoned out in the comment already there.

### Archive, don't delete

Every foreign key is `ON DELETE RESTRICT`, and `CategoryRepository` has no delete method at all — deleting a category silently rewrites history. The single delete in the app is on income sources with zero entries (FR-IS-06), guarded by a count above and the FK below.

## Testing conventions

- Repositories are tested against **real in-memory SQLite with the canonical schema**, never mocks — the behaviour that matters (triggers, `CHECK` constraints, cross-period re-filing) lives in the database.
- `TestFixture` (androidTest) builds that database, the real repositories, a pinned `Clock` (2026-08-14), and a real `AppContainer`. Use `closeAfterDraining()` for tests that run ViewModels — closing the pool under an in-flight Room query throws on an executor thread and gets attributed to whatever test runs *next*.
- Each milestone's exit criterion is a reconciliation test asserting every rendered figure equals a direct `SUM(amount_minor)` over the ledger — never the rollup read a second way (`BudgetReconciliationTest`, `IncomeReconciliationTest`, `ExportImportRoundTripTest`).
- **Await the state you are about to assert on.** ViewModel state arrives from
  two places — Room flows and the coroutine that triggered the write — and
  neither ordering is guaranteed. An assertion that reads a field the
  `awaitState` predicate did not mention is asserting a race. Three tests had
  this and all three passed under `am instrument` and failed under
  `connectedDebugAndroidTest`, because JaCoCo is slower and slower is all it
  takes (`06-implementation-log.md` §21.9 J). **A stale match is the same bug**:
  a `StateFlow` hands back its current value before any new one arrives, so
  `awaitState { rows.size == 1 }` after two deletions and an undo settles on the
  one-row state from *between* the deletions (§22.8).
- **`MainActivityTest` launches the real activity** against in-memory Room, via
  `FinanceApp.installContainer` (the Application-level twin of `AppContainer`'s
  `databaseOverride`). Use it only for what needs a real `Window` or real
  lifecycle events — the system bars, `FLAG_SECURE`, the lock across
  `moveToState(CREATED)`. The launch-gate *ordering* is `rootScreen`, a pure
  function with a JVM test: composing the activity cannot test it, because a
  database broken enough to reach the recovery branch also takes the lock
  setting's read down with it (§23.1).
- **A Compose test that renders a screen must scope its ViewModels.** Provide
  `LocalViewModelStoreOwner` with a store the test owns and `clear()` it before
  `closeAfterDraining()`; otherwise `viewModel()` resolves against the host
  activity's store, whose collectors outlive `@After` and throw on a Room
  executor — attributed to whichever test runs next (§21.9 I, §23.3).
- `:app:coverageVerify` has **two** rules: NFR-MAIN-02's 80% over the bundle, and
  a 50% floor per **source file**. An average hides a whole file at zero, which is
  how six launch-path functions went untested until §22 — and the floor caught
  `WriteErrors.kt` at 36% on the day it was added.
- The instrumented suite was run in full on 25 August 2026 — **596 tests, zero failures**, API 35 emulator (`06-implementation-log.md` §23). The JVM suite is 299. Before that it had been run on 22 August (§21.8), and before *that* not since M2.

## Build config notes that look arbitrary

- `android.disallowKotlinSourceSets=false` — AGP 9's built-in Kotlin rejects `kotlin.sourceSets` contributions, but KSP still registers generated sources that way (google/ksp#2729). Remove once KSP moves to `android.sourceSets`.
- No `org.jetbrains.kotlin.android` plugin: AGP 9 supplies Kotlin (9.3.1 → 2.2.10). The Compose/serialization plugin versions must match that.
- `benchmark = "1.5.0-rc01"`, not 1.4.1 stable — 1.4.1 rejects AGP 9 application modules.
- The `benchmark` build type exists because Macrobenchmark needs a non-debuggable build *and* the seeder; neither `debug` nor `release` is both. Its `applicationIdSuffix` is `.bench`, not `.benchmark`, to avoid colliding with the `:benchmark` module's own namespace.
- `app/build.gradle.kts` must have no top-level `val`s — `lintVital` analyses Gradle scripts and script-level properties crash it.
- The app declares **no permission in any source manifest**, and `INTERNET` must never appear in the merged one — CI greps for it (FR-APP-01). The merged release manifest does carry three library-contributed permissions: `USE_BIOMETRIC` and `USE_FINGERPRINT` from `androidx.biometric`, and androidx.core's signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. None reaches a network. Verify against the *merged release* manifest, not the source one. SAF needs no permission, which is why every file path in the app goes through it.
- Locale filter is `en` only. `bn` stays absent until `values-bn/` exists.
