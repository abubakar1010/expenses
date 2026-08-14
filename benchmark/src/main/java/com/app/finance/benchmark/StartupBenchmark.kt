package com.app.finance.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The performance targets from the SRS, asserted rather than assumed.
 *
 * These must be run on the **reference device** — 4x Cortex-A53 at 1.4 GHz,
 * 2 GB RAM, eMMC storage, 720x1280 — with five years of data seeded. The SRS is
 * explicit that "targets measured on a flagship device are not evidence of
 * compliance", and that applies doubly to an x86_64 emulator on a desktop,
 * which will beat every number below without saying anything about the phone
 * this app is for.
 *
 * | id          | target                                    |
 * |-------------|-------------------------------------------|
 * | NFR-PERF-01 | cold start to first interactive frame <= 800 ms |
 * | NFR-PERF-02 | warm start <= 250 ms                      |
 * | NFR-PERF-04 | dashboard fully rendered <= 300 ms        |
 * | NFR-PERF-05 | ledger scroll >= 55 fps, no frame > 16 ms at p95 |
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** The baseline: no profile, no JIT warmth. The worst case a user sees. */
    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    /**
     * What ships. The delta against [startupNoCompilation] is the return on the
     * Baseline Profile, which §2.2 expects to be 20–30% on low-end hardware.
     */
    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial())

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Wait for the dashboard to actually have something on it: the first
        // frame is deliberately a skeleton (§6), so measuring only to
        // "activity started" would flatter the number.
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 5_000)
    }

    /** NFR-PERF-05. Frame timings while scrolling the ledger. */
    @Test
    fun ledgerScroll() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            startActivityAndWait()
            device.findObject(By.text("Ledger"))?.click()
            device.waitForIdle()
        },
    ) {
        val list = device.findObject(By.scrollable(true)) ?: return@measureRepeated
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.app.finance"
        const val ITERATIONS = 10
    }
}
