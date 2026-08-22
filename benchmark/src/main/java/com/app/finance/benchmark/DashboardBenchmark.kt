package com.app.finance.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry

/**
 * **The M4 exit criterion.**
 *
 * > `| M4 | Dashboard analytics | Dashboard renders in ≤ 300 ms with 5 years
 * > seeded data |`
 *
 * ### Seeding first, or this measures nothing
 *
 * The criterion names the workload as precisely as it names the target, and an
 * empty dashboard renders fast for reasons that say nothing about a real one.
 * Put five years on the device before running this:
 *
 * ```
 * ./gradlew :app:installDebug
 * adb shell am broadcast -a com.app.finance.SEED -p com.app.finance.debug
 * adb logcat -s KhataSeed:I -m 1
 * ```
 *
 * `SeedFiveYears` lives in the app's **debug** source set, so the receiver
 * exists only in the debug package — which is also the only package the shell
 * can broadcast into without root. Point this benchmark at that package by
 * overriding [TARGET_PACKAGE] when running against seeded data; the default is
 * the release-variant package the rest of the module measures.
 *
 * ### And on the right device
 *
 * The SRS is explicit that "targets measured on a flagship device are not
 * evidence of compliance". NFR-PERF-04's 300 ms is a number about a 1.4 GHz
 * Cortex-A53 with 2 GB of RAM and eMMC storage. An x86_64 emulator on a desktop
 * will beat it by an order of magnitude while saying nothing at all about the
 * phone this app is for.
 *
 * ### What is measured
 *
 * [dashboardCold] is the criterion itself: launch to a dashboard with figures
 * on it. The screen deliberately renders a skeleton first (05 §8), so waiting
 * for the activity would flatter the number — it waits for the hero figure's
 * caption, which only appears once all nine flows have landed.
 *
 * [dashboardPeriodSwitch] is NFR-PERF-06's 150 ms, which is the same nine reads
 * again without the process start, and the one a user performs repeatedly.
 */
@RunWith(AndroidJUnit4::class)
class DashboardBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** NFR-PERF-04 — "dashboard fully rendered ≤ 300 ms", over five years of data. */
    @Test
    fun dashboardCold() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Not `waitForIdle`: the first frame is a skeleton by design, and the
        // criterion is about the frame that answers the user's question.
        device.wait(Until.hasObject(By.textContains("SAFE TO SPEND")), 5_000)
    }

    /**
     * NFR-PERF-06 — "period switch on dashboard ≤ 150 ms".
     *
     * `FrameTimingGfxInfoMetric` alongside the frame timeline, for the reason
     * `StartupBenchmark.ledgerScroll` gives: on this device the timeline metric
     * yields a frame *count* and no durations, and a count cannot answer a
     * question posed in milliseconds.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun dashboardPeriodSwitch() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric(), FrameTimingGfxInfoMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            startActivityAndWait()
            device.wait(Until.hasObject(By.textContains("SAFE TO SPEND")), 5_000)
        },
    ) {
        repeat(SWITCHES) {
            device.findObject(By.desc("Previous month"))?.click()
            device.waitForIdle()
        }
        repeat(SWITCHES) {
            device.findObject(By.desc("Next month"))?.click()
            device.waitForIdle()
        }
    }

    private companion object {
        /**
         * The package under measurement.
         *
         * Overridable, because the corpus and the measurable build live in
         * different APKs: `release` deliberately contains no seeder, so the
         * five-year database is on the `benchmark` variant
         * (`com.app.finance.benchmark`) instead. Macrobenchmark measures any
         * installed package by name, so pointing it there costs one argument:
         *
         *     -Pandroid.testInstrumentationRunnerArguments.targetPackage=com.app.finance.benchmark
         */
        val TARGET_PACKAGE: String =
            InstrumentationRegistry.getArguments().getString("targetPackage")
                ?: "com.app.finance"
        const val ITERATIONS = 10
        const val SWITCHES = 3
    }
}
