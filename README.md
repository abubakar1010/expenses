# Khata

A personal finance ledger for Android. Offline-only, single user, Bangladeshi taka.

Built to the specifications in [`docs/`](docs/) — read `01-PRD.md` first, then
`04-system-architecture.md`. The documents are normative; where this code and a
document disagree, that is a bug in one of them.

Three budgets drive every decision:

| Budget | Target | Current |
|---|---|---|
| APK size (NFR-SIZE-01) | ≤ 6 MB | **1.66 MB** |
| Dex methods (NFR-SIZE-03) | ≤ 40,000, single dex | **18,870** |
| Line coverage, calculation layer (NFR-MAIN-02) | ≥ 80% | **84.7%** |
| Cold start (NFR-PERF-01) | ≤ 800 ms on a Cortex-A53 | not yet measured — needs the reference device |

The app declares **no permissions at all**, INTERNET included. That is verified
against the merged release manifest, not the source one.

## State

**All five milestones in `01-PRD.md` §8 are complete** — M1 (*schema, expense
quick-add, ledger*), M2 (*category tree, budgets, alerts*), M3 (*income
module*), M4 (*dashboard analytics*) and M5 (*export/import, recurring,
polish*).

| Area | State |
|---|---|
| Build, R8 full mode, signed release, lint | done |
| Schema, 14 triggers, seed, PRAGMAs | done |
| `Money`, `Period`, `NameKey` | done |
| Khata design system, light + dark | done |
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
| Category reorder (FR-CAT-11) and source reorder (FR-IS-07) — the two `SHOULD`s | deferred to P1 |
| **Settings** — export, import, rebuild totals, theme, delete all data | done (M5) |
| **Repeating entries** — rules, launch evaluation, one-tap confirm on the ledger | done (M5) |
| **Export / import** — JSON, CSV archive, replace or merge, transactional | done (M5) |
| Reports screen (04 §7's inventory; no `FR-*` requires it, PRD §7 lists it at no tier) | deferred |

**239 JVM tests + 447 instrumented tests.** Each milestone's exit criterion is
a reconciliation test that asserts every figure a screen renders equals a direct
`SUM(amount_minor)` over the ledger itself — never the rollup read a second way.
`BudgetReconciliationTest` for M2 (*"budgets reconcile against ledger"*) and
`IncomeReconciliationTest` for M3 (*"yearly totals match manual calculation"*),
across refunds, re-filed entries, deletions, pending rows, archived categories
and sources, and a full rollup rebuild. See `docs/06-implementation-log.md`
§12 and §14.

Every milestone is then re-audited against `01`–`05` requirement by
requirement — M1 and M2 gave up six defects (§13), M3 twelve (§15), M4 and M5
twelve more (§18) — and then the application was audited **as a whole** (§19),
which found nine things no per-milestone read could see. The worst of those:
a theme lookup added at M5 sat above the database check on the launch path, so
a corrupt database would have crashed the app instead of showing the recovery
screen M1 built for exactly that moment. Neither milestone's own review had
both halves in view. The worst
of them was in export: **merge deduplicated on UUID alone, so a backup from a
second phone was rejected outright**, because two installs seed "Grocery" with
different UUIDs and the unique index refused the second one. Merging a file into
the database it came from worked, which is exactly why it survived — the only
merge anyone had tested was the one that never leaves a phone.

**Both of those were already asserted against by `IncomeReconciliationTest`,
which has never been run.** That is the shape of the one real gap in this
project: **the instrumented suite has not been executed since the M2 pass**,
when its last complete run was 173/174. The 253 tests added since are written
and compiled, not executed, and every audit so far has found defects that tests
in that backlog already asserted against. §18.11 has the full account of what is
left.

M5's is `ExportImportRoundTripTest` (*"round-trip export→wipe→import loses
nothing"*), which reads FR-DAT-04's acceptance as the two claims it actually
makes: row counts and checksums per entity, **and** every figure the dashboard
and income screens render, identical before and after. The second does not
follow from the first — the rollups are rebuilt from the ledger rather than
restored from the file.

M4's exit criterion is a measurement rather than a reconciliation — *"dashboard
renders in ≤ 300 ms with 5 years seeded data"* — and it is split in two.
`DashboardScaleTest` seeds sixty periods and asserts the claim the target rests
on: that the dashboard's reads are bounded by the category tree rather than by
history, and that every figure still reconciles at that scale. The wall-clock
number itself is `DashboardBenchmark`'s, and it needs the reference
Cortex-A53. To put five years on a device:

```bash
./gradlew :app:installDebug
adb shell am broadcast -a com.app.finance.SEED -p com.app.finance.debug
```

That receiver and its generator live in `app/src/debug/`, so they are absent
from the release build rather than disabled in it — verified against the release
dex and the merged manifest, not assumed.

## Getting started

Requires JDK 17 and the Android SDK (platform 37, build-tools 36).

```bash
./gradlew :app:installDebug          # build and install
./gradlew :app:architectureCheck     # the two structural rules
./gradlew :app:testDebugUnitTest     # 239 JVM tests — domain and core
./gradlew :app:connectedAndroidTest  # 447 tests, needs a device — see below
./gradlew :app:coverageVerify        # NFR-MAIN-02, currently 84.7%
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
ui/       theme/ (Khata tokens), common/ (shared components), feature/
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

**The viewed period is owned by `KhataApp`, above the `NavHost`.** Neither
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

Two caveats:

- Profile generation **needs root**, so it requires an AOSP or `google_apis`
  emulator image — a `google_apis_playstore` one cannot be rooted. The
  `Khata_API35` AVD created for the test suite is `google_apis` and works.
- The SRS is explicit that "targets measured on a flagship device are not
  evidence of compliance". Numbers from an x86_64 emulator say nothing about a
  1.4 GHz Cortex-A53 with eMMC storage. `04` §2.2 defines the fallback if the
  real device misses 800 ms: XML views for the entry and ledger screens only.

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
