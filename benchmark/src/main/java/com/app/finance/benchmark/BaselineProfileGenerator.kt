package com.app.finance.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Generates the Baseline Profile that ships in the release build.
 *
 * Run with:
 * ```
 * ./gradlew :app:generateBaselineProfile
 * ```
 *
 * **This needs a rooted device or an AOSP / `google_apis` emulator image.**
 * `google_apis_playstore` images cannot be rooted, so profile generation fails
 * on them — which is worth knowing before spending twenty minutes on it. The
 * AVDs currently on this machine are all Play Store images.
 *
 * The journeys below are chosen to match where cold start actually spends its
 * time: 04 §6 lists app startup plus the dashboard, ledger and entry paths, so
 * those are the composition paths that must be in the profile. Anything else
 * added here dilutes it — a profile that covers everything prioritises nothing.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 5_000)

        // The entry sheet: the most frequent action, and the one with the
        // tightest latency budget (NFR-PERF-03, 100 ms to committed).
        device.findObject(By.desc("Add expense"))?.let { fab ->
            fab.click()
            device.waitForIdle()
            device.findObject(By.text("1"))?.click()
            device.findObject(By.text("0"))?.click()
            device.pressBack()
            device.waitForIdle()
        }

        // The ledger: keyset paging and the row composition on a scroll.
        device.findObject(By.text("Ledger"))?.let {
            it.click()
            device.waitForIdle()
            device.findObject(By.scrollable(true))?.fling(
                androidx.test.uiautomator.Direction.DOWN,
            )
            device.waitForIdle()
        }

        listOf("Income", "Budget", "Dashboard").forEach { tab ->
            device.findObject(By.text(tab))?.click()
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
    }
}
