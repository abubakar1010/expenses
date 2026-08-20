package com.app.finance.di

import android.content.Context
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.repo.AppMetaRepository
import com.app.finance.data.repo.BudgetRepository
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.export.Exporter
import com.app.finance.data.export.Importer
import com.app.finance.data.repo.DashboardRepository
import com.app.finance.data.repo.ExpenseRepository
import com.app.finance.data.repo.IncomeRepository
import com.app.finance.data.repo.RecurringRepository
import com.app.finance.data.repo.ReportsRepository
import com.app.finance.data.repo.SettingsRepository
import com.app.finance.log.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock

/**
 * Hand-rolled dependency container — 04-system-architecture.md §2.3.
 *
 * Hilt is the conventional answer and the wrong one here: an annotation
 * processor, generated component classes, and a graph constructed inside
 * `Application.onCreate`, all sitting on the cold-start path this app budgets
 * at 800 ms. For roughly a dozen injectable types a plain container is smaller,
 * faster, and fully comprehensible.
 *
 * **Every field must stay `by lazy`.** That is the property doing the work: the
 * container is constructed during `onCreate` but nothing inside it is — the
 * database opens on the first query, off the main thread, after the first frame
 * has already been drawn. A single eager field here would put `SQLiteDatabase`
 * back on the startup path and quietly cost the cold-start budget.
 *
 * The honest trade-off: this scales poorly past ~50 types. The MVP has ~12.
 */
class AppContainer(
    context: Context,
    /**
     * A constructor parameter, not a hardcoded field: this is the seam that
     * lets a test pin "today". Every date-sensitive figure in the app —
     * safe-to-spend's divisor, the "Today" label, the default entry date —
     * derives from it, so without the seam none of them are testable.
     */
    val clock: Clock = Clock.systemDefaultZone(),
    /**
     * The second seam, and the same argument as [clock]: Compose tests need to
     * drive the real screens against an in-memory database rather than the
     * user's file. Null in every production path.
     */
    private val databaseOverride: AppDatabase? = null,
) {
    private val appContext: Context = context.applicationContext

    val db: AppDatabase by lazy { databaseOverride ?: AppDatabase.build(appContext) }

    val expenseRepo: ExpenseRepository by lazy { ExpenseRepository(db, clock) }
    val categoryRepo: CategoryRepository by lazy { CategoryRepository(db, clock) }
    val budgetRepo: BudgetRepository by lazy { BudgetRepository(db, clock) }
    val incomeRepo: IncomeRepository by lazy { IncomeRepository(db, clock) }
    val dashboardRepo: DashboardRepository by lazy { DashboardRepository(db) }
    val reportsRepo: ReportsRepository by lazy { ReportsRepository(db) }
    val recurringRepo: RecurringRepository by lazy { RecurringRepository(db, clock) }
    val settingsRepo: SettingsRepository by lazy { SettingsRepository(db, clock) }
    val exporter: Exporter by lazy { Exporter(db) }
    val importer: Importer by lazy { Importer(db) }
    val appMetaRepo: AppMetaRepository by lazy { AppMetaRepository(db, clock) }

    /**
     * The directory is passed as a lambda so constructing this touches no
     * storage. `Context.filesDir` stats the filesystem, and resolving it during
     * `Application.onCreate` cost 30 ms of main-thread disk (NFR-PERF-09).
     */
    val crashLog: CrashLog = CrashLog { File(appContext.filesDir, "crash") }

    /**
     * Opens the database and confirms it is usable. Call off the main thread.
     *
     * 03 §8 disables destructive migration in release, so a migration failure
     * throws here instead of silently wiping the ledger — and this is what
     * turns that throw into a recovery screen rather than a crash on launch
     * with no message (04 §8).
     *
     * It does not block the first frame: the UI renders its skeleton and swaps
     * to recovery only if this fails.
     */
    suspend fun verifyDatabase(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // A trivial read that forces the open, the migration path and the
            // schema validation, without depending on any seeded row existing.
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { it.moveToFirst() }
            Unit
        }
    }

    /**
     * NFR-REL-02: "Every displayed aggregate MUST reconcile exactly with a
     * direct sum over the underlying ledger. A nightly self-check in debug
     * builds asserts rollup consistency."
     *
     * Debug only, and off the main thread. Triggers are the sole writer of the
     * rollup tables, so a mismatch means either a trigger is wrong or something
     * has written around them — both silent failures that would otherwise
     * surface as quietly wrong numbers months later.
     *
     * The comparison is **symmetric**, and that is the whole point. Joining
     * from the rollup out to the ledger only finds buckets whose total is
     * wrong; it is structurally blind to a bucket that is *missing*, which is
     * exactly what a trigger that failed to fire produces — the most likely
     * failure this check exists to catch. Summing the rollup positively and the
     * ledger negatively over the union of their keys catches drift in either
     * direction: a surviving non-zero residual is a bucket present on one side
     * and not the other, or present on both and disagreeing.
     *
     * Both rollup tables are checked. `rollup_income_month` is the one with no
     * screen reading it until M3, so a fault there would go unnoticed for
     * longest — and its three triggers were the ones missing from the published
     * SQL, which is not a reassuring history.
     */
    suspend fun assertRollupsReconcile(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            countDrift(EXPENSE_DRIFT) == 0 && countDrift(INCOME_DRIFT) == 0
        }.getOrDefault(false)
    }

    /**
     * 03 §4.3's second half, which had never been built.
     *
     * > "`period_ym` is derived from `earned_on` by the application and kept
     * > consistent by an assertion in the repository layer **plus a debug-build
     * > integrity check**."
     *
     * The repository half is real — every write path derives the column — and
     * [com.app.finance.data.export.Importer] now does too. This is the check
     * that would notice if one of them stopped. A row whose `period_ym`
     * disagrees with its own date is filed in the wrong month by every figure
     * in the app while showing the right day on screen, and nothing else can
     * see it: [assertRollupsReconcile] compares the rollups against the
     * ledger's `period_ym`, so a wrong one is wrong on both sides and cancels.
     *
     * A full scan with `strftime` on every row — which is exactly why this is a
     * debug check rather than a query, and exactly why 03 §1 denormalised the
     * column in the first place.
     */
    suspend fun assertPeriodsDerived(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            countDrift(EXPENSE_PERIODS) == 0 && countDrift(INCOME_PERIODS) == 0
        }.getOrDefault(false)
    }

    private fun countDrift(sql: String): Int =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else -1
        }

    private companion object {
        /**
         * Rows whose `period_ym` disagrees with their own date.
         *
         * `spent_on` is an epoch day, so `* 86400` makes it epoch seconds
         * and `'unixepoch'` lets `strftime` read it. Zero is the only
         * acceptable answer, in either table.
         */
        val EXPENSE_PERIODS = """
            SELECT COUNT(*) FROM expense
             WHERE period_ym <> CAST(strftime('%Y%m', spent_on * 86400, 'unixepoch') AS INTEGER)
        """.trimIndent()

        val INCOME_PERIODS = """
            SELECT COUNT(*) FROM income_entry
             WHERE period_ym <> CAST(strftime('%Y%m', earned_on * 86400, 'unixepoch') AS INTEGER)
        """.trimIndent()

        /**
         * One row per (period, category) where the trigger-maintained rollup and
         * a direct sum over the ledger disagree — in either direction. Zero is
         * the only acceptable answer.
         */
        val EXPENSE_DRIFT = """
            SELECT COUNT(*) FROM (
                SELECT period_ym, category_id,
                       SUM(total) AS total_delta, SUM(n) AS count_delta
                  FROM (
                        SELECT period_ym, category_id, total_minor AS total, txn_count AS n
                          FROM rollup_expense_month
                        UNION ALL
                        SELECT period_ym, category_id, -amount_minor, -1
                          FROM expense WHERE status = 0
                  )
                 GROUP BY period_ym, category_id
                HAVING total_delta <> 0 OR count_delta <> 0
            )
        """.trimIndent()

        val INCOME_DRIFT = """
            SELECT COUNT(*) FROM (
                SELECT period_ym, source_id,
                       SUM(total) AS total_delta, SUM(n) AS count_delta
                  FROM (
                        SELECT period_ym, source_id, total_minor AS total, entry_count AS n
                          FROM rollup_income_month
                        UNION ALL
                        SELECT period_ym, source_id, -amount_minor, -1
                          FROM income_entry WHERE status = 0
                  )
                 GROUP BY period_ym, source_id
                HAVING total_delta <> 0 OR count_delta <> 0
            )
        """.trimIndent()
    }
}
