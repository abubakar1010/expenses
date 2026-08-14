# Khata

A personal finance ledger for Android. Offline-only, single user, Bangladeshi taka.

Built to the specifications in [`docs/`](docs/) — read `01-PRD.md` first, then
`04-system-architecture.md`. The documents are normative; where this code and a
document disagree, that is a bug in one of them.

Three budgets drive every decision:

| Budget | Target | Current |
|---|---|---|
| APK size (NFR-SIZE-01) | ≤ 6 MB | **1.13 MB** |
| Dex methods (NFR-SIZE-03) | ≤ 40,000, single dex | **13,687** |
| Cold start (NFR-PERF-01) | ≤ 800 ms on a Cortex-A53 | not yet measured — needs the reference device |

The app declares **no permissions at all**, INTERNET included. That is verified
against the merged release manifest, not the source one.

## State

Milestone **M1** of the five in `01-PRD.md` §8 — *schema, expense quick-add,
ledger* — complete, plus the full design system and persistence layer.

| Area | State |
|---|---|
| Build, R8 full mode, signed release, lint | done |
| Schema, 14 triggers, seed, PRAGMAs | done |
| `Money`, `Period`, `NameKey` | done |
| Khata design system, light + dark | done |
| Navigation shell, bottom bar, FAB | done |
| **Quick Add** — keypad, chips, date, method, note, full category picker | done |
| **Ledger** — paging, day groups, edit, swipe-delete + undo, filters, search | done |
| Crash log, recovery screen, debug rollup-drift check | done |
| Dashboard, Income, Budget | placeholder routes (M2–M4) |
| Category manager, Reports, Settings | not started |
| Export/import, recurring rules | not started (M5, P1) |

**53 JVM tests + 94 instrumented tests.** See `docs/06-implementation-log.md`
§11 for what the completion pass changed and why.

## Getting started

Requires JDK 17 and the Android SDK (platform 37, build-tools 36).

```bash
./gradlew :app:installDebug          # build and install
./gradlew :app:architectureCheck     # the two structural rules
./gradlew :app:testDebugUnitTest     # 53 JVM tests — domain and core
./gradlew :app:connectedAndroidTest  # 94 tests, needs a device — see below
./gradlew :app:lintRelease
./gradlew :app:assembleRelease       # R8 full mode
```

**Run the instrumented tests on API 35 or below.** Espresso 3.7.0 — the newest
release — calls `android.hardware.input.InputManager.getInstance()`, which no
longer exists on API 37, so every Compose test dies in `Espresso.onIdle()`
before reaching an assertion. The repository and ViewModel suites are
unaffected. An `android-35;google_apis` AVD works, and being rootable is also
what makes `generateBaselineProfile` possible.

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
domain/   Budget states, category tree, typed errors — no Android imports
data/     Room entities, DAOs, repositories; Schema.kt owns the real DDL
ui/       theme/ (Khata tokens), common/ (shared components), feature/
di/       AppContainer — hand-rolled, every field `by lazy`
```

### Two things worth knowing before editing

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

## Performance work

`:benchmark` holds the Macrobenchmark suite and the Baseline Profile generator,
which `04` §2.2 makes mandatory rather than optional.

```bash
./gradlew :app:generateBaselineProfile
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

Two caveats:

- Profile generation **needs root**, so it requires an AOSP or `google_apis`
  emulator image. The AVDs on this machine are all `google_apis_playstore`,
  which cannot be rooted.
- The SRS is explicit that "targets measured on a flagship device are not
  evidence of compliance". Numbers from an x86_64 emulator say nothing about a
  1.4 GHz Cortex-A53 with eMMC storage. `04` §2.2 defines the fallback if the
  real device misses 800 ms: XML views for the entry and ledger screens only.

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
