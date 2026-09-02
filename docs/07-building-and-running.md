# Building and Running
**Product:** Personal Finance Manager (Android)
**Version:** 1.0 — MVP

---

Every command in this document was run on 2 September 2026 against a Samsung
Galaxy A54 (`SM-A546E`, `a54x`, Android 16 / API 36, arm64-v8a) on Windows 11
with Git Bash. Figures quoted are from that session. Where a command's output
matters, it is reproduced rather than described.

`CLAUDE.md` carries the same command list in terse form for the agent working in
this repository. **This document is the procedure; `CLAUDE.md` is the crib
sheet.** Where the two disagree, one of them is stale — fix both.

---

## 1. What you need

| | Version | Why this one |
|---|---|---|
| JDK | **17** | AGP 9 requires it; `17.0.20` verified |
| Android SDK platform | **37** | `compileSdk = 37`, `targetSdk = 37` |
| Build tools | **36.0.0** | Where `apksigner` and `aapt2` live |
| Gradle | fetched by the wrapper | `9.7.0`, pinned in `gradle/wrapper` |

Nothing else. Do not install Gradle system-wide — `./gradlew` downloads and
pins its own, which is what makes the build reproducible on a fresh clone.

`minSdk = 26` (Android 8.0). That floor is not arbitrary: it is what makes
`java.time` available without desugaring, and NFR-COMP-01 names it.

---

## 2. The four application ids

This project builds three variants of the app, and they are designed to sit on
one phone **at the same time**. Verified — all four packages coexisted on the
A54 during this session:

| Variant | Application id | Debuggable | What it is for |
|---|---|---|---|
| `debug` | `com.app.finance.debug` | yes | Daily development. Carries the `SEED` receiver and JaCoCo instrumentation |
| `release` | `com.app.finance` | no | What ships. R8 full mode, resource shrinking |
| `benchmark` | `com.app.finance.bench` | no | Macrobenchmark only — non-debuggable *and* seeded, which neither of the others is |
| (test APK) | `com.app.finance.debug.test` | — | The instrumented suite's own package |

The suffixes exist so a release build can be used daily without a debug install
uninstalling it out from under you — 04's exit criterion is "the author uses it
daily for a week", and that is not possible if the two collide.

`.bench` and not `.benchmark`: the `:benchmark` module's own namespace is
`com.app.finance.benchmark`, and that suffix collides with the measuring
harness's own APK. The platform treats them as one package and the install fails
with `INSTALL_FAILED_VERSION_DOWNGRADE`.

---

## 3. Building

### 3.1 Debug

```bash
./gradlew :app:assembleDebug
```

→ `app/build/outputs/apk/debug/app-debug.apk` — **19 MB**, signed with the
auto-generated debug key.

Large because it is not shrunk and carries coverage instrumentation. That is the
correct size for a debug build; do not chase it.

### 3.2 Release

```bash
./gradlew :app:assembleRelease
```

→ `app/build/outputs/apk/release/app-release.apk` — **2.3 MB**, in **7m 11s**.

The 19 MB → 2.3 MB difference is R8 full mode plus `isShrinkResources`. NFR
budget is 6 MB, so there is comfortable headroom, and the number is worth
re-checking whenever a dependency is added.

`architectureCheck` runs automatically as part of `preBuild` on every one of
these and **fails the build** — no `android.*` imports under `domain/` or
`core/`, and no money typed as a float. It is not optional and not skippable.

### 3.3 Benchmark

```bash
./gradlew :app:assembleBenchmark
```

Only needed for Macrobenchmark and Baseline Profile work. See `CLAUDE.md` for
the emulator image it requires — it needs a rootable `google_apis` image, not
`google_apis_playstore`.

---

## 4. Signing

Release signing is read from **`keystore.properties` at the repository root**,
which is gitignored. Four keys:

```
storeFile
storePassword
keyAlias
keyPassword
```

**Absent the file, `assembleRelease` still succeeds and produces an unsigned
APK.** That is deliberate: a fresh clone, or CI without secrets, can still run
the shrinking and size checks. Only the install step needs a signature.

The `keystore.properties` currently in this working tree says of itself, and it
is correct:

> A **LOCAL TEST-SIGNING KEY**, not a release key. It exists so the release
> build can be installed on a development device — NFR-PERF's numbers are
> defined against the release variant, and an unsigned APK cannot be installed
> to measure. Gitignored, throwaway, and **no substitute for a real signing key
> at publication.**

Verify what a build was actually signed with:

```bash
"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/apksigner.bat" \
  verify --print-certs app/build/outputs/apk/release/app-release.apk
```

```
Signer #1 certificate DN: CN=Khata Local Test, OU=Dev, O=Khata, L=Dhaka, C=BD
Signer #1 certificate SHA-256 digest: 89d420df0afe952f189e4b187de1882cb...
```

Seeing `Khata Local Test` on anything intended for distribution means the wrong
key was used. Generating and safeguarding a real upload key is a publication
step this document does not cover, and it must not be committed.

---

## 5. Running on a USB-connected phone

### 5.1 One-time device setup

1. **Settings → About phone → Software information** → tap **Build number**
   seven times.
2. **Settings → Developer options** → enable **USB debugging**.
3. Connect the cable, then on the phone tap **Allow** on *"Allow USB
   debugging?"*, ticking *Always allow from this computer*.

That is all a Samsung needs. Two manufacturers need more:

- **Xiaomi / MIUI** additionally needs **Developer options → Install via USB**,
  or every install is refused with `INSTALL_FAILED_USER_RESTRICTED`.
- Some ROMs gate `adb install` behind a per-install confirmation dialog that
  must be accepted on the handset.

If the device does not appear at all, try another cable before debugging
anything else — a large proportion of USB-C cables are charge-only and carry no
data lines.

### 5.2 Put `adb` on the PATH

`adb` is not on the PATH by default on this machine. For one shell:

```bash
export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
```

Permanently, add that line to `~/.bashrc`. In PowerShell instead:

```powershell
$env:PATH += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
```

### 5.3 Confirm the phone is visible

```bash
adb devices -l
```

```
RRCW804KEGB   device  product:a54xnsxx model:SM_A546E device:a54x
```

`device` is the state you want. `unauthorized` means step 3 above has not been
accepted on the handset. An empty list means the cable, the driver, or USB
debugging.

**With more than one device attached, every `adb` command needs `-s`:**

```bash
adb -s RRCW804KEGB shell ...
```

Without it, `adb` fails with `more than one device/emulator`. This bites the
moment an emulator is running alongside a phone.

### 5.4 Build, install and launch

One step:

```bash
./gradlew :app:installDebug
```

```
Installing APK 'app-debug.apk' on 'SM-A546E - 16' for :app:debug
Installed on 1 device.
```

Then launch it:

```bash
adb shell monkey -p com.app.finance.debug -c android.intent.category.LAUNCHER 1
```

Two steps, when the APK already exists or came from elsewhere:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` reinstalls over an existing copy and keeps its data. `-t` is needed only
for a test-only APK, such as the instrumented test package.

To run the release build on the phone instead:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

It installs alongside the debug one rather than replacing it — see §2.

### 5.5 Watching it run

```bash
adb logcat --pid=$(adb shell pidof -s com.app.finance.debug)
```

Confirm what is actually in the foreground:

```bash
adb shell dumpsys activity activities | grep topResumedActivity
```

**`FLAG_SECURE` is an opt-in setting, not the default.** NFR-SEC-04 says it is
"applied *optionally*", and `SettingsRepository` stores it off — *Settings →
Privacy → Hide from screenshots*. A fresh install screenshots normally.

**Even with it on, `adb screencap` may still capture.** The flag blocks the
system screenshot and other apps; `adb exec-out screencap` runs as the shell
user, which on some ROMs is permitted to capture secure layers. Measured on the
Galaxy A54 (Android 16): with the setting on, `screencap` returned a normal
image. So a black frame proves the flag is on, but a captured one **does not
prove it is off**. Read the window flag instead — `0x2000` is `FLAG_SECURE`:

```bash
adb shell dumpsys window windows | grep -A6 'com.app.finance/.*MainActivity' | grep 'fl='
```

```
fl=81812100    # setting on  -> 0x2000 present
fl=81810100    # setting off -> 0x2000 absent
```

To read the UI as text, which works regardless of the flag:

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```

This is how the hand-walks in `06-implementation-log.md` §25.7 and §25.8 were
driven, and `uiautomator` crosses app boundaries where instrumentation cannot —
so it can drive the SAF folder picker that instrumented tests are unable to
reach.

**A debug build can die instantly rather than crash visibly.** StrictMode runs
with `penaltyDeath` in debug, so a main-thread disk access kills the process.
Read logcat for the violation before assuming a logic bug.

### 5.6 Removing it

```bash
adb uninstall com.app.finance.debug
```

---

## 6. Verifying a build

### 6.1 Lint

```bash
./gradlew :app:lintRelease
```

`abortOnError = true` with a `lint-baseline.xml`, so **new** issues fail the
build while the recorded ones do not.

### 6.2 The network check — FR-APP-01

The app declares no permission in any source manifest, and `INTERNET` must never
appear in the merged one. Check the **built release APK**, not the source
manifest:

```bash
"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe" \
  dump permissions app/build/outputs/apk/release/app-release.apk
```

```
package: com.app.finance
uses-permission: name='android.permission.USE_BIOMETRIC'
uses-permission: name='android.permission.USE_FINGERPRINT'
permission: com.app.finance.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='com.app.finance.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

Exactly three, all library-contributed: two from `androidx.biometric` and one
signature-level permission from `androidx.core`. **None reaches a network.**
Anything else in that list — `INTERNET` above all — is a defect.

SAF needs no permission, which is why every file path in the app goes through
it.

### 6.3 Tests

```bash
./gradlew :app:testDebugUnitTest      # JVM: domain/ + core/
./gradlew :app:connectedAndroidTest   # instrumented: DAOs, repos, ViewModels, Compose
./gradlew :app:coverageVerify         # NFR-MAIN-02's 80%, plus a 50% floor per file
```

The instrumented suite has real environment constraints — emulator API level,
daemon memory, foreground focus on a physical device, and cold-boot sensitivity
in the performance probes. Those are recorded in **`CLAUDE.md` → "Environment
traps that cost real time"** and are not repeated here, because they change with
measurement and one copy is enough.

One caution specific to the device in §5: it runs **API 36**. `CLAUDE.md`
records the instrumented suite as needing API 35 or below, attributing it to an
Espresso call removed in API 37 — so whether API 36 is affected is *untested*
either way. Run the Compose suites on the API 35 emulator until somebody
establishes it.

---

## 7. Two things that will cost an afternoon

**Never build with an emulator running.** The Gradle daemon holds `-Xmx4096m`
for R8. On a 16 GB machine with an emulator up, an `assembleDebug` has been
measured at **58 minutes producing nothing**; alone it takes under four. Build
first with nothing else running, then `./gradlew --stop`, then boot the
emulator. This applies to a physical device far less — the phone is its own
hardware — but the daemon is still the reason a build slows to a crawl.

**A stale emulator invalidates its own measurements.** An AVD that has already
run the full suite is several times slower than a freshly booted one, and the
performance probes assert wall-clock budgets. During the §29 audit the restore
probe measured 3.6 s cold and **20.3 s** on the same AVD after two full passes —
the same probe, the same code. A failing probe on a long-lived emulator is a
measurement of the emulator. Reboot and re-measure before believing it.
