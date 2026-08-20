package com.app.finance

import androidx.test.core.app.ApplicationProvider
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.repo.AppMetaRepository
import com.app.finance.data.repo.BudgetRepository
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.export.Exporter
import com.app.finance.data.export.Importer
import com.app.finance.data.repo.DashboardRepository
import com.app.finance.data.repo.ExpenseRepository
import com.app.finance.data.repo.IncomeRepository
import com.app.finance.data.repo.ReportsRepository
import com.app.finance.data.repo.RecurringRepository
import com.app.finance.data.repo.SettingsRepository
import com.app.finance.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * An in-memory database carrying the real canonical schema, with the real
 * repositories on top and a **pinned clock**.
 *
 * Repositories are tested against real SQLite rather than mocks on purpose: the
 * behaviour that matters here — trigger-maintained rollups, `CHECK`
 * constraints, cross-period re-filing — lives in the database, and a mock would
 * assert only that the code calls the methods it was written to call.
 *
 * The clock is fixed so that "today" is a constant. Without it, tests of the
 * date default and the future-date clamp pass or fail depending on the hour
 * they run at, and a suite that fails at midnight is worse than no suite.
 */
class TestFixture(
    val today: LocalDate = LocalDate.of(2026, 8, 14),
) {
    val clock: Clock = Clock.fixed(
        today.atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant(),
        ZoneId.systemDefault(),
    )

    val db: AppDatabase =
        AppDatabase.inMemory(ApplicationProvider.getApplicationContext())

    val expenses = ExpenseRepository(db, clock)
    val categories = CategoryRepository(db, clock)
    val budgets = BudgetRepository(db, clock)
    val income = IncomeRepository(db, clock)
    val reports = ReportsRepository(db)
    val dashboard = DashboardRepository(db)
    val recurring = RecurringRepository(db, clock)
    val settings = SettingsRepository(db, clock)
    val exporter = Exporter(db)
    val importer = Importer(db)
    val meta = AppMetaRepository(db, clock)

    /** For Compose tests, which drive the real screens through the real graph. */
    val container = AppContainer(
        context = ApplicationProvider.getApplicationContext(),
        clock = clock,
        databaseOverride = db,
    )

    fun close() = db.close()

    /**
     * For tests that run ViewModels.
     *
     * Coroutine cancellation is cooperative, and a Room query already in flight
     * runs to completion regardless — so `ViewModelStore.clear()` returns before
     * the last query does. Closing the pool underneath it throws on a Room
     * executor thread, and the instrumentation runner attributes that crash to
     * whichever test happens to be running *next*, which is how a green suite
     * turns into a differently-failing one on every run.
     *
     * A short bounded drain lets those finish first.
     */
    fun closeAfterDraining() {
        runBlocking { delay(DRAIN_MILLIS) }
        db.close()
    }

    private companion object {
        const val DRAIN_MILLIS = 200L
    }

    /** Advances the pinned clock, for tests that need a second instant. */
    fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneId.systemDefault())

    // --- convenience lookups against the seeded tree ------------------------

    suspend fun leafId(name: String): Long =
        db.categoryDao().roots()
            .flatMap { db.categoryDao().children(it.id) }
            .first { it.name == name }
            .id

    suspend fun rootId(name: String): Long =
        db.categoryDao().roots().first { it.name == name }.id
}

/**
 * Waits for a ViewModel's state to satisfy [predicate].
 *
 * Necessary because Room's generated suspend DAOs dispatch onto Room's own
 * query executor — a real thread pool — regardless of which dispatcher the
 * caller is on. So a `UnconfinedTestDispatcher` makes the ViewModel's coroutine
 * *start* synchronously but not *finish* synchronously, and reading
 * `state.value` straight after an action reads it before the database has
 * answered.
 *
 * Forcing Room onto a direct executor would make the tests synchronous, but
 * `withTransaction` deadlocks under one — and the write path depends on
 * transactions. Waiting is the honest option.
 */
suspend fun <T> StateFlow<T>.awaitState(
    timeoutMillis: Long = 5_000,
    predicate: (T) -> Boolean,
): T = withTimeout(timeoutMillis) { first(predicate) }
