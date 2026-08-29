package com.app.finance.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.app.finance.core.time.Period
import com.app.finance.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * `adb shell am broadcast -a com.app.finance.SEED -p com.app.finance.debug`
 *
 * The only way to put five years of data on a device without typing it. M4's
 * exit criterion is a measurement — "dashboard renders in ≤ 300 ms with 5 years
 * seeded data" — and a measurement nobody can set up is a measurement nobody
 * takes.
 *
 * **Declared in `src/debug/AndroidManifest.xml`, so it is not in the release
 * manifest and [SeedFiveYears] is not in the release dex.** Not disabled in
 * release, not guarded by `BuildConfig.DEBUG` — absent. That is the difference
 * between a build-time guarantee and a runtime one, and it is why this lives
 * here rather than behind a flag in `main`.
 *
 * `exported=false` regardless: `am broadcast` from the shell reaches an
 * unexported receiver in a debuggable package, and nothing else needs to.
 */
class SeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = AppContainer(context.applicationContext)

        // A receiver's own scope dies with `finish()`, so the work runs on one
        // that outlives it and reports through logcat, which is where whoever
        // typed the command is already looking.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // `--es scale benchmark` for 02 §3.1's corpus — 20,000
                // expenses and 60 categories, which is the database every
                // NFR-PERF number is defined against. Without it the seed is
                // what a real install holds, which is a fifth of that and the
                // wrong thing to measure.
                val scale = when (intent.getStringExtra(EXTRA_SCALE)) {
                    "benchmark" -> SeedFiveYears.Scale.BENCHMARK
                    else -> SeedFiveYears.Scale.INSTALL
                }
                val counts = SeedFiveYears.into(
                    db = container.db,
                    endingAt = Period.now(container.clock),
                    scale = scale,
                )
                Log.i(
                    TAG,
                    "seeded ${counts.periods} periods at $scale: " +
                        "${counts.expenses} expenses, ${counts.income} income entries",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "seeding failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DayBookSeed"
        const val EXTRA_SCALE = "scale"
    }
}
