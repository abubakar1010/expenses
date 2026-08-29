package com.app.finance

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.app.finance.di.AppContainer
import java.util.concurrent.Executors

/**
 * Deliberately near-empty.
 *
 * 04-system-architecture.md §6 budgets this method at under 10 ms. It runs on
 * the cold-start critical path before any Activity exists, so the only work
 * permitted here is constructing [AppContainer] — whose every field is `by
 * lazy`, meaning the database is *not* opened, no DAO is created, and no disk
 * is touched until the first screen actually issues a query.
 *
 * Anything added to this method is paid on every launch, forever.
 */
class FinanceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        container = AppContainer(this)
        // Installing a handler is a field write and a lambda; it touches no
        // disk and opens no file until something actually crashes, so it stays
        // inside the <10 ms budget this method has.
        container.crashLog.install()
    }

    /**
     * Replaces the container before an Activity is launched.
     *
     * The Application-level counterpart of [AppContainer]'s `databaseOverride`,
     * and it exists for the same reason: without it, nothing can launch
     * `MainActivity` against anything but the user's real database, so the two
     * things that only a real `Window` and real lifecycle events can show —
     * whether the system bars follow [com.app.finance.domain.model.ThemeChoice],
     * and whether the lock re-engages on a genuine `ON_STOP` — cannot be
     * asserted at all. §22.10 listed both as open for exactly that reason.
     *
     * `internal`, so only this module's tests can reach it, and it must be
     * called before the Activity starts: the container is read once per
     * composition.
     */
    @VisibleForTesting
    internal fun installContainer(replacement: AppContainer) {
        container = replacement
    }

    /**
     * NFR-PERF-09 requires zero main-thread database access, "enforced by
     * StrictMode in debug builds". An accidental blocking query should be a
     * crash during development, not a jank report from the one user this app
     * has.
     *
     * The catch is that StrictMode attributes Binder-inbound work to *this*
     * process. On the test device — a MediaTek Xiaomi — the ROM's game-detection
     * heuristic stats the APK during activity start, and a plain `penaltyDeath`
     * killed the app for a disk read that is not ours and cannot be fixed here.
     *
     * So the policy discriminates: any violation whose stack contains our own
     * frames is fatal; anything originating entirely in vendor or framework code
     * is logged. That keeps the guarantee exactly as strong for the code this
     * project controls, which is what the requirement is about.
     */
    private fun enableStrictMode() {
        val builder = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
                val ours = violation.stackTrace.any { it.className.startsWith(OUR_PACKAGE) }
                if (ours) {
                    // Rethrown off the main thread so the stack survives intact
                    // in the crash log rather than being swallowed by a handler
                    // further up the main looper.
                    throw AssertionError("Main-thread disk access in app code", violation)
                }
                Log.w(TAG, "StrictMode violation outside app code (vendor/framework)", violation)
            }
        } else {
            // Below API 28 there is no listener to filter with. Logging beats
            // killing the app for a violation it may not own.
            builder.penaltyDeath()
        }

        StrictMode.setThreadPolicy(builder.build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }

    private companion object {
        const val OUR_PACKAGE = "com.app.finance"
        const val TAG = "DayBook"
    }
}
