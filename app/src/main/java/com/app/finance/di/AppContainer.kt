package com.app.finance.di

import android.content.Context
import com.app.finance.data.db.AppDatabase
import com.app.finance.data.repo.AppMetaRepository
import com.app.finance.data.repo.CategoryRepository
import com.app.finance.data.repo.ExpenseRepository
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
     */
    suspend fun assertRollupsReconcile(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val sql = """
                SELECT COUNT(*) FROM (
                    SELECT r.period_ym, r.category_id, r.total_minor, r.txn_count,
                           IFNULL(l.total, 0) AS live_total, IFNULL(l.n, 0) AS live_n
                      FROM rollup_expense_month r
                      LEFT JOIN (
                            SELECT period_ym, category_id,
                                   SUM(amount_minor) AS total, COUNT(*) AS n
                              FROM expense WHERE status = 0
                             GROUP BY period_ym, category_id
                      ) l ON l.period_ym = r.period_ym AND l.category_id = r.category_id
                     WHERE r.total_minor <> IFNULL(l.total, 0)
                        OR r.txn_count   <> IFNULL(l.n, 0)
                )
            """.trimIndent()
            db.openHelper.readableDatabase.query(sql).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) == 0
            }
        }.getOrDefault(false)
    }
}
