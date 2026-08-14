package com.app.finance

import android.app.Application
import android.os.StrictMode
import com.app.finance.di.AppContainer

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
    }

    /**
     * NFR-PERF-09 requires zero main-thread database access. `penaltyDeath`
     * turns an accidental blocking query into a crash during development
     * rather than a jank report from the one user this app has.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }
}
